package api

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"

	"there-chat.com/server/internal/store"
)

type client struct {
	t     *testing.T
	srv   *httptest.Server
	token string
}

func newServer(t *testing.T) *httptest.Server {
	return newServerWith(t, Config{})
}

func newServerWith(t *testing.T, cfg Config) *httptest.Server {
	t.Helper()
	st, err := store.Open(context.Background(), ":memory:")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	srv := httptest.NewServer(New(st, cfg, slog.New(slog.NewTextHandler(io.Discard, nil))))
	t.Cleanup(srv.Close)
	return srv
}

// newDevice registers a fresh device and returns a client authenticated as it.
func newDevice(t *testing.T, srv *httptest.Server) *client {
	t.Helper()
	secret := make([]byte, 32)
	rand.Read(secret)
	c := &client{t: t, srv: srv, token: base64.RawURLEncoding.EncodeToString(secret)}
	c.do("POST", "/v1/devices", map[string]string{"secret": c.token}, http.StatusNoContent, nil)
	return c
}

func (c *client) do(method, path string, body any, wantStatus int, out any) {
	c.t.Helper()
	var r io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		r = bytes.NewReader(b)
	}
	req, _ := http.NewRequest(method, c.srv.URL+path, r)
	req.Header.Set("Authorization", "Bearer "+c.token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		c.t.Fatal(err)
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != wantStatus {
		c.t.Fatalf("%s %s: status %d, want %d: %s", method, path, resp.StatusCode, wantStatus, data)
	}
	if out != nil {
		if err := json.Unmarshal(data, out); err != nil {
			c.t.Fatalf("%s %s: decode: %v: %s", method, path, err, data)
		}
	}
}

type chatResp struct {
	store.Chat
	Me *store.Member `json:"me"`
}

func createChat(c *client, overrides map[string]any) chatResp {
	c.t.Helper()
	body := map[string]any{
		"kind": "chat", "title": "Guess who?", "emoji": "❓",
		"lat": 48.853, "lng": 2.3499, "free_join": true, "visible": true, "name": "Hans Gruber",
	}
	for k, v := range overrides {
		body[k] = v
	}
	var out chatResp
	c.do("POST", "/v1/chats", body, http.StatusCreated, &out)
	return out
}

const paris = "2.34,48.85,2.36,48.86"

func TestAuthRequired(t *testing.T) {
	srv := newServer(t)
	c := &client{t: t, srv: srv, token: "nope"}
	c.do("GET", "/v1/me/chats", nil, http.StatusUnauthorized, nil)

	c.token = base64.RawURLEncoding.EncodeToString(make([]byte, 32))
	c.do("GET", "/v1/me/chats", nil, http.StatusUnauthorized, nil)
}

func TestRegisterIsIdempotent(t *testing.T) {
	srv := newServer(t)
	c := newDevice(t, srv)
	c.do("POST", "/v1/devices", map[string]string{"secret": c.token}, http.StatusNoContent, nil)
}

func TestMapZoomThresholdAndHiddenChats(t *testing.T) {
	srv := newServer(t)
	owner, other := newDevice(t, srv), newDevice(t, srv)
	createChat(owner, nil)
	hidden := createChat(owner, map[string]any{"title": "Secret", "visible": false})

	var res struct{ Chats []store.Chat }
	other.do("GET", "/v1/map/chats?bbox="+paris, nil, http.StatusOK, &res)
	if len(res.Chats) != 1 || res.Chats[0].Title != "Guess who?" {
		t.Fatalf("outsider sees %+v, want only the visible chat", res.Chats)
	}
	if res.Chats[0].InviteCode != "" {
		t.Fatal("invite code leaked on the map")
	}

	owner.do("GET", "/v1/map/chats?bbox="+paris, nil, http.StatusOK, &res)
	if len(res.Chats) != 2 {
		t.Fatalf("owner sees %d chats, want 2", len(res.Chats))
	}

	other.do("GET", "/v1/map/chats?bbox=2.0,48.5,2.6,49.0", nil, http.StatusBadRequest, nil)

	// The hidden chat does not exist for outsiders, except through its invite.
	other.do("GET", "/v1/chats/"+hidden.ID, nil, http.StatusNotFound, nil)
	other.do("GET", "/v1/invites/"+hidden.InviteCode, nil, http.StatusOK, nil)
}

func TestJoinPostAndRead(t *testing.T) {
	srv := newServer(t)
	owner, guest := newDevice(t, srv), newDevice(t, srv)
	chat := createChat(owner, nil)

	// Open chats can be read before joining.
	guest.do("GET", "/v1/chats/"+chat.ID+"/messages", nil, http.StatusOK, nil)
	guest.do("POST", "/v1/chats/"+chat.ID+"/messages", map[string]string{"body": "hi"}, http.StatusForbidden, nil)

	guest.do("POST", "/v1/chats/"+chat.ID+"/join", map[string]string{"name": "hans gruber"}, http.StatusConflict, nil)
	var joined chatResp
	guest.do("POST", "/v1/chats/"+chat.ID+"/join", map[string]string{"name": "Holly Gennero"}, http.StatusOK, &joined)
	if joined.Me == nil || joined.Me.Status != "active" {
		t.Fatalf("join: %+v", joined.Me)
	}

	msg := map[string]string{"body": "After all your posturing…", "client_id": "c1"}
	var m1, m2 store.Message
	guest.do("POST", "/v1/chats/"+chat.ID+"/messages", msg, http.StatusCreated, &m1)
	guest.do("POST", "/v1/chats/"+chat.ID+"/messages", msg, http.StatusCreated, &m2)
	if m1.ID != m2.ID {
		t.Fatal("retry with the same client_id created a duplicate")
	}

	var res struct{ Messages []store.Message }
	owner.do("GET", "/v1/chats/"+chat.ID+"/messages", nil, http.StatusOK, &res)
	if len(res.Messages) != 2 || res.Messages[0].Body != "joined" || res.Messages[1].AuthorName != "Holly Gennero" {
		t.Fatalf("messages: %+v", res.Messages)
	}

	var mine struct {
		Chats []struct {
			ID     string
			Unread int64
		}
	}
	owner.do("GET", "/v1/me/chats", nil, http.StatusOK, &mine)
	if len(mine.Chats) != 1 || mine.Chats[0].Unread != 1 {
		t.Fatalf("my chats: %+v", mine.Chats)
	}
}

func TestPremoderationHidesBody(t *testing.T) {
	srv := newServer(t)
	owner, author, reader := newDevice(t, srv), newDevice(t, srv), newDevice(t, srv)
	chat := createChat(owner, map[string]any{"premoderated": true})
	author.do("POST", "/v1/chats/"+chat.ID+"/join", map[string]string{"name": "Al Powell"}, http.StatusOK, nil)
	reader.do("POST", "/v1/chats/"+chat.ID+"/join", map[string]string{"name": "John McClane"}, http.StatusOK, nil)
	author.do("POST", "/v1/chats/"+chat.ID+"/messages", map[string]string{"body": "secret"}, http.StatusCreated, nil)

	bodyFor := func(c *client) string {
		var res struct{ Messages []store.Message }
		c.do("GET", "/v1/chats/"+chat.ID+"/messages?after=2", nil, http.StatusOK, &res)
		if len(res.Messages) != 1 || res.Messages[0].Status != "pending" {
			t.Fatalf("messages: %+v", res.Messages)
		}
		return res.Messages[0].Body
	}
	if bodyFor(author) != "secret" || bodyFor(owner) != "secret" {
		t.Fatal("author and admins must see pending messages")
	}
	if bodyFor(reader) != "" {
		t.Fatal("pending message body leaked to a regular member")
	}
}

func TestChannelsAndClosedChats(t *testing.T) {
	srv := newServer(t)
	owner, guest := newDevice(t, srv), newDevice(t, srv)
	channel := createChat(owner, map[string]any{"kind": "channel"})
	guest.do("POST", "/v1/chats/"+channel.ID+"/join", map[string]string{"name": "Neo"}, http.StatusOK, nil)
	guest.do("POST", "/v1/chats/"+channel.ID+"/messages", map[string]string{"body": "hi"}, http.StatusForbidden, nil)
	owner.do("POST", "/v1/chats/"+channel.ID+"/messages", map[string]string{"body": "news"}, http.StatusCreated, nil)

	closed := createChat(owner, map[string]any{"free_join": false, "title": "Closed"})
	guest.do("GET", "/v1/chats/"+closed.ID+"/messages", nil, http.StatusForbidden, nil)
	var joined chatResp
	guest.do("POST", "/v1/chats/"+closed.ID+"/join", map[string]string{"name": "Neo"}, http.StatusOK, &joined)
	if joined.Me.Status != "pending" {
		t.Fatalf("closed chat join status %q, want pending", joined.Me.Status)
	}
	guest.do("GET", "/v1/chats/"+closed.ID+"/messages", nil, http.StatusForbidden, nil)
}
