package api

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestLimiterRefills(t *testing.T) {
	now := time.Unix(0, 0)
	l := newLimiter(Rate{PerHour: 3600, Burst: 2}) // 1/s
	l.now = func() time.Time { return now }

	for i := range 2 {
		if ok, _ := l.allow("a"); !ok {
			t.Fatalf("request %d within burst was refused", i+1)
		}
	}
	ok, wait := l.allow("a")
	if ok || wait != time.Second {
		t.Fatalf("over burst: ok=%v wait=%v, want refused with 1s", ok, wait)
	}
	if ok, _ := l.allow("b"); !ok {
		t.Fatal("another key shares the bucket")
	}
	now = now.Add(time.Second)
	if ok, _ := l.allow("a"); !ok {
		t.Fatal("token not refilled after 1s")
	}

	now = now.Add(time.Hour)
	l.allow("c") // triggers a sweep
	if _, kept := l.buckets["a"]; kept {
		t.Fatal("full bucket not swept")
	}
}

func TestClientIP(t *testing.T) {
	cases := []struct{ remote, xff, want string }{
		{"127.0.0.1:5000", "203.0.113.7", "203.0.113.7"},
		{"127.0.0.1:5000", "6.6.6.6, 203.0.113.7", "203.0.113.7"}, // client-sent part is ignored
		{"127.0.0.1:5000", "", "127.0.0.1"},
		{"198.51.100.2:5000", "203.0.113.7", "198.51.100.2"}, // not from Caddy: header ignored
		{"[::1]:5000", "2001:db8:1:2:3:4:5:6", "2001:db8:1:2::/64"},
	}
	for _, c := range cases {
		r := httptest.NewRequest("GET", "/", nil)
		r.RemoteAddr = c.remote
		if c.xff != "" {
			r.Header.Set("X-Forwarded-For", c.xff)
		}
		if got := clientIP(r); got != c.want {
			t.Errorf("remote %s xff %q: got %s, want %s", c.remote, c.xff, got, c.want)
		}
	}
}

func TestMessageRateLimit(t *testing.T) {
	srv := newServer(t)
	a, b := newDevice(t, srv), newDevice(t, srv)
	chat := createChat(a, nil)
	path := "/v1/chats/" + chat.ID + "/messages"
	for range DefaultLimits.Message.Burst {
		a.do("POST", path, map[string]string{"body": "hi"}, http.StatusCreated, nil)
	}
	a.do("POST", path, map[string]string{"body": "one too many"}, http.StatusTooManyRequests, nil)

	// Another device in the same chat is not affected.
	b.do("POST", "/v1/chats/"+chat.ID+"/join", map[string]string{"name": "Ellen Ripley"}, http.StatusOK, nil)
	b.do("POST", path, map[string]string{"body": "hi"}, http.StatusCreated, nil)
}
