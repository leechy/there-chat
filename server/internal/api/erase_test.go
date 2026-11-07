package api

import (
	"net/http"
	"net/url"
	"testing"

	"there-chat.com/server/internal/store"
)

func TestEraseDevice(t *testing.T) {
	srv := newServer(t)
	leaving, other := newDevice(t, srv), newDevice(t, srv)
	owned := createChat(leaving, nil)
	theirs := createChat(other, map[string]any{"name": "Karl"})
	leaving.do("POST", "/v1/chats/"+theirs.ID+"/join", map[string]string{"name": "Theo"}, http.StatusOK, nil)

	var mine, kept store.Message
	leaving.do("POST", "/v1/chats/"+theirs.ID+"/messages", map[string]string{"body": "I want my phone call"}, http.StatusCreated, &mine)
	other.do("POST", "/v1/chats/"+theirs.ID+"/messages", map[string]string{"body": "Shoot the glass"}, http.StatusCreated, &kept)
	other.do("PUT", "/v1/messages/"+mine.ID+"/reactions/"+url.PathEscape("👍"), nil, http.StatusOK, nil)
	leaving.do("PUT", "/v1/messages/"+kept.ID+"/reactions/"+url.PathEscape("🔥"), nil, http.StatusOK, nil)

	leaving.do("DELETE", "/v1/devices/me", nil, http.StatusNoContent, nil)

	// The old key is retired.
	leaving.do("GET", "/v1/me/chats", nil, http.StatusUnauthorized, nil)
	// The chat it owned is gone.
	other.do("GET", "/v1/chats/"+owned.ID, nil, http.StatusNotFound, nil)

	for _, m := range messages(other, theirs.ID, "") {
		switch {
		case m.ID == mine.ID:
			if m.Status != "deleted" || m.Body != "" || m.AuthorName != "" || len(m.Reactions) != 0 {
				t.Fatalf("erased message kept data: %+v", m)
			}
		case m.ID == kept.ID:
			if len(m.Reactions) != 0 {
				t.Fatalf("erased reaction kept: %+v", m.Reactions)
			}
		case m.AuthorName == "Theo":
			t.Fatalf("message still carries the erased name: %+v", m)
		}
	}
	var members struct{ Members []store.Member }
	other.do("GET", "/v1/chats/"+theirs.ID+"/members", nil, http.StatusOK, &members)
	for _, m := range members.Members {
		if m.Name == "Theo" {
			t.Fatalf("erased member still listed: %+v", m)
		}
	}

	// Registering the same secret again starts a brand-new, empty device.
	leaving.do("POST", "/v1/devices", map[string]string{"secret": leaving.token}, http.StatusNoContent, nil)
	var res struct{ Chats []store.Chat }
	leaving.do("GET", "/v1/me/chats", nil, http.StatusOK, &res)
	if len(res.Chats) != 0 {
		t.Fatalf("new device sees old chats: %+v", res.Chats)
	}
}
