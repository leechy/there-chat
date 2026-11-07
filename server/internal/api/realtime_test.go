package api

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
	"there-chat.com/server/internal/store"
)

type socket struct {
	t      *testing.T
	conn   *websocket.Conn
	events chan Event
}

// dial opens the device's realtime socket and waits for the hello frame.
func (c *client) dial() *socket {
	c.t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	url := "ws" + strings.TrimPrefix(c.srv.URL, "http") + "/v1/ws"
	conn, _, err := websocket.Dial(ctx, url, &websocket.DialOptions{
		HTTPHeader: http.Header{"Authorization": {"Bearer " + c.token}},
	})
	if err != nil {
		cancel()
		c.t.Fatal(err)
	}
	s := &socket{t: c.t, conn: conn, events: make(chan Event, 100)}
	c.t.Cleanup(func() { conn.CloseNow(); cancel() })
	go func() {
		defer close(s.events)
		for {
			_, data, err := conn.Read(ctx)
			if err != nil {
				return
			}
			var ev Event
			json.Unmarshal(data, &ev)
			s.events <- ev
		}
	}()
	if ev := s.next(); ev.Type != EvHello {
		c.t.Fatalf("first frame %+v, want hello", ev)
	}
	return s
}

func (s *socket) next() Event {
	s.t.Helper()
	select {
	case ev, ok := <-s.events:
		if !ok {
			s.t.Fatal("socket closed")
		}
		return ev
	case <-time.After(2 * time.Second):
		s.t.Fatal("no event within 2s")
	}
	return Event{}
}

// expect waits for an event of the given type, skipping others.
func (s *socket) expect(typ string) Event {
	s.t.Helper()
	for {
		if ev := s.next(); ev.Type == typ {
			return ev
		}
	}
}

// quiet asserts that nothing but the listed types arrives for a short while.
func (s *socket) quiet(allowed ...string) {
	s.t.Helper()
	deadline := time.After(300 * time.Millisecond)
	for {
		select {
		case ev, ok := <-s.events:
			if !ok {
				return
			}
			ok = false
			for _, a := range allowed {
				ok = ok || ev.Type == a
			}
			if !ok {
				s.t.Fatalf("unexpected event %+v", ev)
			}
		case <-deadline:
			return
		}
	}
}

func TestWebSocketRequiresAuth(t *testing.T) {
	srv := newServer(t)
	_, resp, err := websocket.Dial(context.Background(), "ws"+strings.TrimPrefix(srv.URL, "http")+"/v1/ws", nil)
	if err == nil || resp == nil || resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("dial without token: err=%v resp=%v", err, resp)
	}
}

func TestWebSocketMessageEvent(t *testing.T) {
	srv := newServer(t)
	owner, guest, outsider := newDevice(t, srv), newDevice(t, srv), newDevice(t, srv)
	chat := createChat(owner, nil)
	guest.do("POST", "/v1/chats/"+chat.ID+"/join", map[string]string{"name": "Holly Gennero"}, http.StatusOK, nil)

	ownerWS, outsiderWS := owner.dial(), outsider.dial()
	// The outsider only reads the open chat; that is not a subscription.
	outsider.do("GET", "/v1/chats/"+chat.ID+"/messages", nil, http.StatusOK, nil)

	var msg store.Message
	guest.do("POST", "/v1/chats/"+chat.ID+"/messages", map[string]string{"body": "hi", "client_id": "c1"}, http.StatusCreated, &msg)
	ev := ownerWS.expect(EvMessage)
	if ev.ChatID != chat.ID || ev.Seq != msg.Seq || ev.MessageID != msg.ID {
		t.Fatalf("event %+v, want chat %s seq %d", ev, chat.ID, msg.Seq)
	}

	guest.do("PUT", "/v1/messages/"+msg.ID+"/reactions/x", nil, http.StatusOK, nil)
	if ev := ownerWS.expect(EvReaction); ev.Seq != msg.Seq {
		t.Fatalf("reaction event %+v", ev)
	}
	owner.do("DELETE", "/v1/messages/"+msg.ID, nil, http.StatusNoContent, nil)
	ownerWS.expect(EvMessageDeleted)
	owner.do("PATCH", "/v1/chats/"+chat.ID, map[string]string{"title": "New title"}, http.StatusOK, nil)
	ownerWS.expect(EvChatUpdated)

	outsiderWS.quiet() // a non-member gets nothing at all
}

func TestWebSocketPendingMessageVisibility(t *testing.T) {
	srv := newServer(t)
	owner, author, reader := newDevice(t, srv), newDevice(t, srv), newDevice(t, srv)
	chat := createChat(owner, map[string]any{"premoderated": true})
	author.do("POST", "/v1/chats/"+chat.ID+"/join", map[string]string{"name": "Al Powell"}, http.StatusOK, nil)
	reader.do("POST", "/v1/chats/"+chat.ID+"/join", map[string]string{"name": "John McClane"}, http.StatusOK, nil)
	ownerWS, authorWS, readerWS := owner.dial(), author.dial(), reader.dial()

	var msg store.Message
	author.do("POST", "/v1/chats/"+chat.ID+"/messages", map[string]string{"body": "secret"}, http.StatusCreated, &msg)
	if msg.Status != "pending" {
		t.Fatalf("status %q, want pending", msg.Status)
	}
	ownerWS.expect(EvMessage)
	authorWS.expect(EvMessage)
	readerWS.quiet()

	owner.do("POST", "/v1/messages/"+msg.ID+"/approve", nil, http.StatusOK, nil)
	if ev := readerWS.expect(EvModerated); ev.MessageID != msg.ID || ev.Action != "approved" {
		t.Fatalf("moderated event %+v", ev)
	}
}

func TestWebSocketMembersAndBlocking(t *testing.T) {
	srv := newServer(t)
	owner, guest := newDevice(t, srv), newDevice(t, srv)
	chat := createChat(owner, map[string]any{"free_join": false})
	ownerWS, guestWS := owner.dial(), guest.dial()

	var joined chatResp
	guest.do("POST", "/v1/chats/"+chat.ID+"/join", map[string]string{"name": "Neo"}, http.StatusOK, &joined)
	if ev := ownerWS.expect(EvMember); ev.Action != MemberPending || ev.MemberID != joined.Me.ID {
		t.Fatalf("member event %+v", ev)
	}
	owner.do("POST", "/v1/chats/"+chat.ID+"/members/"+joined.Me.ID+"/approve", nil, http.StatusOK, nil)
	if ev := guestWS.expect(EvMember); ev.Action != MemberJoined {
		t.Fatalf("approved member event %+v", ev)
	}

	owner.do("POST", "/v1/chats/"+chat.ID+"/members/"+joined.Me.ID+"/block", nil, http.StatusOK, nil)
	ownerWS.expect(EvMember)
	guestWS.quiet(EvMember) // drain anything sent before the block
	owner.do("POST", "/v1/chats/"+chat.ID+"/messages", map[string]string{"body": "bye"}, http.StatusCreated, nil)
	ownerWS.expect(EvMessage)
	guestWS.quiet() // blocked members hear nothing
}

func TestWebSocketNewConnectionReplacesOld(t *testing.T) {
	srv := newServer(t)
	owner := newDevice(t, srv)
	chat := createChat(owner, nil)
	first := owner.dial()
	second := owner.dial()
	select {
	case _, ok := <-first.events:
		if ok {
			t.Fatal("old socket got an event instead of closing")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("old socket was not closed")
	}
	owner.do("POST", "/v1/chats/"+chat.ID+"/messages", map[string]string{"body": "hi"}, http.StatusCreated, nil)
	second.expect(EvMessage)
}

func TestHubDropsSlowClients(t *testing.T) {
	h := newHub()
	killed := false
	c := &wsConn{send: make(chan []byte, 1), kill: func() { killed = true }}
	h.register("d", c)
	h.deliver([]string{"d"}, []byte("1"))
	h.deliver([]string{"d"}, []byte("2")) // buffer full
	if !killed || h.online("d") {
		t.Fatal("slow client was not dropped")
	}
}
