package api

import (
	"math"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Rate is a token bucket: Burst requests at once, refilled at PerHour.
type Rate struct {
	PerHour float64
	Burst   int
}

// Limits caps how fast one client can use the API. Requests and Register
// are counted per IP address, the rest per device.
type Limits struct {
	Requests   Rate // every request
	Register   Rate // new device identities
	Message    Rate // posted messages
	CreateChat Rate
	Join       Rate
	Report     Rate
}

// DefaultLimits are loose enough for a whole venue behind one Wi-Fi and
// tight enough to stop scripted flooding.
var DefaultLimits = Limits{
	Requests:   Rate{PerHour: 36000, Burst: 200}, // 10/s
	Register:   Rate{PerHour: 60, Burst: 30},
	Message:    Rate{PerHour: 1200, Burst: 15}, // 20/min
	CreateChat: Rate{PerHour: 20, Burst: 5},
	Join:       Rate{PerHour: 120, Burst: 20},
	Report:     Rate{PerHour: 30, Burst: 10},
}

type limiters struct {
	requests, register, message, createChat, join, report *limiter
}

func newLimiters(l Limits) limiters {
	return limiters{
		requests:   newLimiter(l.Requests),
		register:   newLimiter(l.Register),
		message:    newLimiter(l.Message),
		createChat: newLimiter(l.CreateChat),
		join:       newLimiter(l.Join),
		report:     newLimiter(l.Report),
	}
}

// limiter keeps one bucket per key in memory. Nothing is persisted, so IP
// addresses never reach the database.
type limiter struct {
	mu        sync.Mutex
	perSec    float64
	burst     float64
	buckets   map[string]*bucket
	lastSweep time.Time
	now       func() time.Time
}

type bucket struct {
	tokens float64
	last   time.Time
}

func newLimiter(r Rate) *limiter {
	return &limiter{
		perSec:  r.PerHour / 3600,
		burst:   float64(r.Burst),
		buckets: map[string]*bucket{},
		now:     time.Now,
	}
}

// allow takes one token for key. If none is left it says how long to wait.
func (l *limiter) allow(key string) (bool, time.Duration) {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	if now.Sub(l.lastSweep) > time.Minute {
		l.sweep(now)
	}
	b, ok := l.buckets[key]
	if !ok {
		b = &bucket{tokens: l.burst, last: now}
		l.buckets[key] = b
	}
	b.tokens = math.Min(l.burst, b.tokens+now.Sub(b.last).Seconds()*l.perSec)
	b.last = now
	if b.tokens >= 1 {
		b.tokens--
		return true, 0
	}
	return false, time.Duration((1 - b.tokens) / l.perSec * float64(time.Second))
}

// sweep forgets buckets that have refilled completely: they behave like new ones.
func (l *limiter) sweep(now time.Time) {
	l.lastSweep = now
	for k, b := range l.buckets {
		if b.tokens+now.Sub(b.last).Seconds()*l.perSec >= l.burst {
			delete(l.buckets, k)
		}
	}
}

func tooMany(w http.ResponseWriter, wait time.Duration) {
	w.Header().Set("Retry-After", strconv.Itoa(int(math.Ceil(wait.Seconds()))))
	writeError(w, http.StatusTooManyRequests, "rate_limited", "too many requests, try again later")
}

// limitIP wraps a handler with a per-IP limit.
func limitIP(l *limiter, next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if ok, wait := l.allow(clientIP(r)); !ok {
			tooMany(w, wait)
			return
		}
		next(w, r)
	}
}

// limitDevice wraps an authenticated handler with a per-device limit.
func limitDevice(l *limiter, next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if ok, wait := l.allow(deviceID(r)); !ok {
			tooMany(w, wait)
			return
		}
		next(w, r)
	}
}

// clientIP is the address of the client. Behind Caddy on the same machine
// the peer is loopback and the client is the last X-Forwarded-For entry,
// which Caddy itself appends; the header is ignored from anyone else, so it
// can't be forged. IPv6 clients are grouped by /64, the usual size of one
// customer's network.
func clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	ip := net.ParseIP(host)
	if ip != nil && ip.IsLoopback() {
		if xff := r.Header.Values("X-Forwarded-For"); len(xff) > 0 {
			parts := strings.Split(xff[len(xff)-1], ",")
			if fwd := net.ParseIP(strings.TrimSpace(parts[len(parts)-1])); fwd != nil {
				ip = fwd
			}
		}
	}
	if ip == nil {
		return host
	}
	if ip.To4() == nil {
		return ip.Mask(net.CIDRMask(64, 128)).String() + "/64"
	}
	return ip.String()
}
