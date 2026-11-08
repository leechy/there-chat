// Package push sends notifications through Apple (APNs) and Google (FCM)
// from a small in-memory queue, so requests never wait for them.
package push

import (
	"context"
	"errors"
	"log/slog"
	"sync"
	"time"
)

// Platforms a device can register with. Debug iOS builds get tokens for
// Apple's sandbox, TestFlight and App Store builds for production.
const (
	APNs        = "apns"
	APNsSandbox = "apns_sandbox"
	FCM         = "fcm"
)

// Notification is one message for one device.
type Notification struct {
	Platform, Token string
	ChatID          string
	Title, Body     string
	Badge           int64
}

// ErrBadToken means the token will never work again and should be forgotten.
var ErrBadToken = errors.New("push token is no longer valid")

type Sender interface {
	Send(ctx context.Context, n Notification) error
}

// Dispatcher delivers notifications in the background. If the queue is full
// (Apple or Google down for a while) new notifications are dropped: they are
// a hint, the messages themselves are always on the server.
type Dispatcher struct {
	senders    map[string]Sender
	queue      chan Notification
	onBadToken func(token string)
	log        *slog.Logger
	wg         sync.WaitGroup
	mu         sync.RWMutex // guards closed against Enqueue racing Close
	closed     bool
}

func NewDispatcher(senders map[string]Sender, onBadToken func(string), log *slog.Logger) *Dispatcher {
	return &Dispatcher{senders: senders, queue: make(chan Notification, 1000), onBadToken: onBadToken, log: log}
}

// Supports reports whether notifications for platform can be sent at all.
func (d *Dispatcher) Supports(platform string) bool { return d.senders[platform] != nil }

// Start runs workers until Close.
func (d *Dispatcher) Start(workers int) {
	for range workers {
		d.wg.Add(1)
		go func() {
			defer d.wg.Done()
			for n := range d.queue {
				d.deliver(n)
			}
		}()
	}
}

// Close stops accepting notifications and waits for the queued ones.
func (d *Dispatcher) Close() {
	d.mu.Lock()
	d.closed = true
	close(d.queue)
	d.mu.Unlock()
	d.wg.Wait()
}

func (d *Dispatcher) Enqueue(n Notification) {
	if !d.Supports(n.Platform) {
		return
	}
	d.mu.RLock()
	defer d.mu.RUnlock()
	if d.closed {
		return
	}
	select {
	case d.queue <- n:
	default:
		d.log.Warn("push queue full, dropping notification", "platform", n.Platform)
	}
}

func (d *Dispatcher) deliver(n Notification) {
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	err := d.senders[n.Platform].Send(ctx, n)
	switch {
	case err == nil:
	case errors.Is(err, ErrBadToken):
		d.onBadToken(n.Token)
	default:
		d.log.Warn("push failed", "platform", n.Platform, "err", err)
	}
}
