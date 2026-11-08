package api

import (
	"net/http"
	"testing"
	"time"

	"there-chat.com/server/internal/push"
)

type recorder chan push.Notification

func (r recorder) Enqueue(n push.Notification) { r <- n }

// next returns the next notification, or fails if none comes.
func (r recorder) next(t *testing.T) push.Notification {
	t.Helper()
	select {
	case n := <-r:
		return n
	case <-time.After(2 * time.Second):
		t.Fatal("no notification")
		return push.Notification{}
	}
}

func (r recorder) none(t *testing.T) {
	t.Helper()
	select {
	case n := <-r:
		t.Fatalf("unexpected notification %+v", n)
	case <-time.After(200 * time.Millisecond):
	}
}

func TestPushOnNewMessage(t *testing.T) {
	rec := make(recorder, 10)
	srv := newServerWith(t, Config{Push: rec})
	owner, ellen, quiet := newDevice(t, srv), newDevice(t, srv), newDevice(t, srv)
	chat := createChat(owner, map[string]any{"title": "Блинчики", "emoji": "🥞"})
	path := "/v1/chats/" + chat.ID
	ellen.do("POST", path+"/join", map[string]string{"name": "Ellen Ripley"}, http.StatusOK, nil)
	quiet.do("POST", path+"/join", map[string]string{"name": "Bob"}, http.StatusOK, nil)

	owner.do("PUT", "/v1/devices/me/push", map[string]any{"platform": "apns_sandbox", "token": "owner-tok"}, http.StatusNoContent, nil)
	ellen.do("PUT", "/v1/devices/me/push", map[string]any{"platform": "fcm", "token": "ellen-tok", "preview": false}, http.StatusNoContent, nil)
	// quiet has a token but turned notifications off for this chat.
	quiet.do("PUT", "/v1/devices/me/push", map[string]any{"platform": "fcm", "token": "quiet-tok"}, http.StatusNoContent, nil)
	quiet.do("PATCH", path+"/me", map[string]any{"notify": false}, http.StatusOK, nil)

	ellen.do("POST", path+"/messages", map[string]string{"body": "Are there any pancakes left?", "client_id": "m1"}, http.StatusCreated, nil)
	n := rec.next(t)
	if n.Token != "owner-tok" || n.Platform != push.APNsSandbox || n.ChatID != chat.ID {
		t.Fatalf("notification %+v", n)
	}
	if n.Title != "🥞 Блинчики" || n.Body != "Ellen Ripley: Are there any pancakes left?" || n.Badge != 1 {
		t.Fatalf("title %q body %q badge %d", n.Title, n.Body, n.Badge)
	}
	rec.none(t) // not to Ellen herself, not to quiet

	// A retried request with the same client ID notifies nobody again.
	ellen.do("POST", path+"/messages", map[string]string{"body": "Are there any pancakes left?", "client_id": "m1"}, http.StatusCreated, nil)
	rec.none(t)

	owner.do("POST", path+"/messages", map[string]string{"body": "Plenty!"}, http.StatusCreated, nil)
	n = rec.next(t)
	if n.Token != "ellen-tok" || n.Body != "Hans Gruber sent a message" {
		t.Fatalf("no-preview notification %+v", n)
	}

	// Turning notifications off on the device clears its token.
	ellen.do("DELETE", "/v1/devices/me/push", nil, http.StatusNoContent, nil)
	owner.do("POST", path+"/messages", map[string]string{"body": "Anyone?"}, http.StatusCreated, nil)
	rec.none(t)
}

func TestPushAfterApproval(t *testing.T) {
	rec := make(recorder, 10)
	srv := newServerWith(t, Config{Push: rec})
	owner, member := newDevice(t, srv), newDevice(t, srv)
	chat := createChat(owner, map[string]any{"premoderated": true})
	member.do("POST", "/v1/chats/"+chat.ID+"/join", map[string]string{"name": "Ellen Ripley"}, http.StatusOK, nil)
	owner.do("PUT", "/v1/devices/me/push", map[string]any{"platform": "apns", "token": "owner-tok"}, http.StatusNoContent, nil)

	var msg struct{ ID string }
	member.do("POST", "/v1/chats/"+chat.ID+"/messages", map[string]string{"body": "hi"}, http.StatusCreated, &msg)
	rec.none(t) // pending: nobody but admins can see it yet
	owner.do("POST", "/v1/messages/"+msg.ID+"/approve", nil, http.StatusOK, nil)
	if n := rec.next(t); n.Token != "owner-tok" {
		// the owner gets it too: it's a new visible message from someone else
		t.Fatalf("after approval %+v", n)
	}
}
