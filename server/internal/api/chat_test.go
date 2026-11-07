package api

import (
	"net/http"
	"net/url"
	"testing"

	"there-chat.com/server/internal/store"
)

func messages(c *client, chatID, query string) []store.Message {
	c.t.Helper()
	var res struct{ Messages []store.Message }
	c.do("GET", "/v1/chats/"+chatID+"/messages"+query, nil, http.StatusOK, &res)
	return res.Messages
}

func TestReactions(t *testing.T) {
	srv := newServer(t)
	owner, guest := newDevice(t, srv), newDevice(t, srv)
	chat := createChat(owner, nil)
	guest.do("POST", "/v1/chats/"+chat.ID+"/join", map[string]string{"name": "Al Powell"}, http.StatusOK, nil)
	var msg store.Message
	owner.do("POST", "/v1/chats/"+chat.ID+"/messages", map[string]string{"body": "I am an exceptional thief"}, http.StatusCreated, &msg)

	laugh := "/v1/messages/" + msg.ID + "/reactions/" + url.PathEscape("🤣")
	guest.do("PUT", laugh, nil, http.StatusOK, nil)
	guest.do("PUT", laugh, nil, http.StatusOK, nil) // idempotent
	var updated store.Message
	owner.do("PUT", laugh, nil, http.StatusOK, &updated)
	if len(updated.Reactions) != 1 || updated.Reactions[0].Count != 2 || !updated.Reactions[0].Mine {
		t.Fatalf("reactions: %+v", updated.Reactions)
	}
	var afterRemoving store.Message // fresh: "mine" is omitted when false
	guest.do("DELETE", laugh, nil, http.StatusOK, &afterRemoving)
	if afterRemoving.Reactions[0].Count != 1 || afterRemoving.Reactions[0].Mine {
		t.Fatalf("after removing: %+v", afterRemoving.Reactions)
	}

	outsider := newDevice(t, srv)
	outsider.do("PUT", laugh, nil, http.StatusForbidden, nil)
}

func TestRenameKeepsOldMessagesAndReadMarker(t *testing.T) {
	srv := newServer(t)
	owner, guest := newDevice(t, srv), newDevice(t, srv)
	chat := createChat(owner, nil)
	guest.do("POST", "/v1/chats/"+chat.ID+"/join", map[string]string{"name": "Holly Gennero"}, http.StatusOK, nil)
	guest.do("POST", "/v1/chats/"+chat.ID+"/messages", map[string]string{"body": "hello"}, http.StatusCreated, nil)

	owner.do("PATCH", "/v1/chats/"+chat.ID+"/me", map[string]string{"name": "holly gennero"}, http.StatusConflict, nil)
	var me chatResp
	guest.do("PATCH", "/v1/chats/"+chat.ID+"/me", map[string]any{"name": "Holly McClane", "last_read_seq": 99}, http.StatusOK, &me)
	if me.Me.Name != "Holly McClane" || me.Me.LastReadSeq != 3 {
		t.Fatalf("me: %+v", me.Me)
	}

	msgs := messages(owner, chat.ID, "")
	if msgs[1].AuthorName != "Holly Gennero" {
		t.Fatalf("old message renamed: %+v", msgs[1])
	}
	last := msgs[len(msgs)-1]
	if last.Kind != "system" || last.Body != "renamed:Holly Gennero" || last.AuthorName != "Holly McClane" {
		t.Fatalf("rename event: %+v", last)
	}
}

func TestAdminModeration(t *testing.T) {
	srv := newServer(t)
	owner, admin, troll := newDevice(t, srv), newDevice(t, srv), newDevice(t, srv)
	chat := createChat(owner, map[string]any{"free_join": false, "premoderated": true})

	var adminMe, trollMe chatResp
	admin.do("POST", "/v1/chats/"+chat.ID+"/join", map[string]string{"name": "Al Powell"}, http.StatusOK, &adminMe)
	troll.do("POST", "/v1/chats/"+chat.ID+"/join", map[string]string{"name": "Karl"}, http.StatusOK, &trollMe)

	var pending struct{ Members []store.Member }
	owner.do("GET", "/v1/chats/"+chat.ID+"/members?status=pending", nil, http.StatusOK, &pending)
	if len(pending.Members) != 2 {
		t.Fatalf("pending: %+v", pending.Members)
	}
	members := "/v1/chats/" + chat.ID + "/members/"
	owner.do("POST", members+adminMe.Me.ID+"/approve", nil, http.StatusOK, nil)
	owner.do("POST", members+adminMe.Me.ID+"/promote", nil, http.StatusOK, nil)
	admin.do("POST", members+trollMe.Me.ID+"/approve", nil, http.StatusOK, nil)

	// Admins can't touch the owner; members can't moderate at all.
	var ownerMember chatResp
	owner.do("GET", "/v1/chats/"+chat.ID, nil, http.StatusOK, &ownerMember)
	admin.do("POST", members+ownerMember.Me.ID+"/block", nil, http.StatusForbidden, nil)
	troll.do("POST", members+adminMe.Me.ID+"/block", nil, http.StatusForbidden, nil)

	var spam store.Message
	troll.do("POST", "/v1/chats/"+chat.ID+"/messages", map[string]string{"body": "spam"}, http.StatusCreated, &spam)
	if spam.Status != "pending" {
		t.Fatalf("premoderated message status %q", spam.Status)
	}
	admin.do("POST", "/v1/messages/"+spam.ID+"/reject", nil, http.StatusOK, nil)
	admin.do("POST", "/v1/messages/"+spam.ID+"/reject", nil, http.StatusConflict, nil)

	var ok store.Message
	troll.do("POST", "/v1/chats/"+chat.ID+"/messages", map[string]string{"body": "fine"}, http.StatusCreated, &ok)
	admin.do("POST", "/v1/messages/"+ok.ID+"/approve", nil, http.StatusOK, nil)

	admin.do("DELETE", members+trollMe.Me.ID+"/messages", nil, http.StatusNoContent, nil)
	admin.do("POST", members+trollMe.Me.ID+"/block", nil, http.StatusOK, nil)
	for _, m := range messages(owner, chat.ID, "") {
		if m.MemberID == trollMe.Me.ID && m.Kind == "text" && (m.Body != "" || m.Status != "deleted") {
			t.Fatalf("troll message survived: %+v", m)
		}
	}
	troll.do("GET", "/v1/chats/"+chat.ID+"/messages", nil, http.StatusForbidden, nil)
	troll.do("POST", "/v1/chats/"+chat.ID+"/join", map[string]string{"name": "Karl 2"}, http.StatusForbidden, nil)
}

func TestSettingsLeaveAndDelete(t *testing.T) {
	srv := newServer(t)
	owner, guest := newDevice(t, srv), newDevice(t, srv)
	chat := createChat(owner, nil)
	guest.do("POST", "/v1/chats/"+chat.ID+"/join", map[string]string{"name": "Neo"}, http.StatusOK, nil)

	guest.do("PATCH", "/v1/chats/"+chat.ID, map[string]any{"title": "Mine now"}, http.StatusForbidden, nil)
	var updated chatResp
	owner.do("PATCH", "/v1/chats/"+chat.ID, map[string]any{"title": "Renamed", "visible": false}, http.StatusOK, &updated)
	if updated.Title != "Renamed" || updated.Visible {
		t.Fatalf("settings: %+v", updated.Chat)
	}

	owner.do("POST", "/v1/chats/"+chat.ID+"/leave", nil, http.StatusConflict, nil)
	guest.do("POST", "/v1/chats/"+chat.ID+"/leave", nil, http.StatusOK, nil)
	var mine struct{ Chats []store.Chat }
	guest.do("GET", "/v1/me/chats", nil, http.StatusOK, &mine)
	if len(mine.Chats) != 0 {
		t.Fatalf("left chat still listed: %+v", mine.Chats)
	}

	guest.do("POST", "/v1/reports", map[string]string{"chat_id": chat.ID, "reason": "rude"}, http.StatusNoContent, nil)
	owner.do("DELETE", "/v1/chats/"+chat.ID, nil, http.StatusNoContent, nil)
	owner.do("GET", "/v1/chats/"+chat.ID, nil, http.StatusNotFound, nil)
}
