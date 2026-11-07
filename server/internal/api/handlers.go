package api

import (
	"errors"
	"net/http"
	"strconv"
	"strings"
	"unicode/utf8"

	"there-chat.com/server/internal/names"
	"there-chat.com/server/internal/store"
)

const (
	maxTitleLen = 32
	maxNameLen  = 32
	maxBodyLen  = 4000
)

func (s *Server) health(w http.ResponseWriter, r *http.Request) {
	if err := s.store.Ping(r.Context()); err != nil {
		s.internal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

// --- devices ---

func (s *Server) registerDevice(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Secret string `json:"secret"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	secret, err := decodeSecret(req.Secret)
	if err != nil {
		writeError(w, http.StatusBadRequest, "bad_secret", "secret must be 32 bytes, base64url without padding")
		return
	}
	if _, err := s.store.RegisterDevice(r.Context(), secret); err != nil {
		s.internal(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) setPush(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Platform string `json:"platform"`
		Token    string `json:"token"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	if (req.Platform != "apns" && req.Platform != "fcm") || req.Token == "" || len(req.Token) > 4096 {
		writeError(w, http.StatusBadRequest, "bad_push", "platform must be apns or fcm, token required")
		return
	}
	if err := s.store.SetPushToken(r.Context(), deviceID(r), req.Platform, req.Token); err != nil {
		s.internal(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) clearPush(w http.ResponseWriter, r *http.Request) {
	if err := s.store.SetPushToken(r.Context(), deviceID(r), "", ""); err != nil {
		s.internal(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) suggestNames(w http.ResponseWriter, r *http.Request) {
	n, _ := strconv.Atoi(r.URL.Query().Get("count"))
	n = min(max(n, 1), 10)
	writeJSON(w, http.StatusOK, map[string][]string{"names": names.Random(n)})
}

// --- map ---

func (s *Server) mapChats(w http.ResponseWriter, r *http.Request) {
	box, ok := parseBBox(r.URL.Query().Get("bbox"))
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_bbox", "bbox must be west,south,east,north in degrees")
		return
	}
	if box.North-box.South > s.cfg.MaxSpanDeg || box.East-box.West > s.cfg.MaxSpanDeg {
		writeError(w, http.StatusBadRequest, "zoom_in", "the area is too large, zoom in to see chats")
		return
	}
	chats, err := s.store.ChatsInBox(r.Context(), deviceID(r), box, 500)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	for i := range chats {
		chats[i].InviteCode = ""
	}
	writeJSON(w, http.StatusOK, map[string]any{"chats": chats})
}

func parseBBox(v string) (store.BBox, bool) {
	parts := strings.Split(v, ",")
	if len(parts) != 4 {
		return store.BBox{}, false
	}
	var f [4]float64
	for i, p := range parts {
		x, err := strconv.ParseFloat(strings.TrimSpace(p), 64)
		if err != nil {
			return store.BBox{}, false
		}
		f[i] = x
	}
	b := store.BBox{West: f[0], South: f[1], East: f[2], North: f[3]}
	ok := b.South >= -90 && b.North <= 90 && b.South < b.North &&
		b.West >= -180 && b.East <= 180 && b.West < b.East
	return b, ok
}

// --- chats ---

func (s *Server) myChats(w http.ResponseWriter, r *http.Request) {
	chats, err := s.store.MyChats(r.Context(), deviceID(r))
	if err != nil {
		s.internal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"chats": chats})
}

func (s *Server) createChat(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Kind         string  `json:"kind"`
		Title        string  `json:"title"`
		Emoji        string  `json:"emoji"`
		Lat          float64 `json:"lat"`
		Lng          float64 `json:"lng"`
		FreeJoin     bool    `json:"free_join"`
		Premoderated bool    `json:"premoderated"`
		Visible      bool    `json:"visible"`
		Name         string  `json:"name"` // the creator's name in this chat
	}
	if !readJSON(w, r, &req) {
		return
	}
	req.Title, req.Name, req.Emoji = clean(req.Title), clean(req.Name), strings.TrimSpace(req.Emoji)
	switch {
	case req.Kind != "chat" && req.Kind != "channel":
		writeError(w, http.StatusBadRequest, "bad_kind", "kind must be chat or channel")
	case !lenOK(req.Title, maxTitleLen):
		writeError(w, http.StatusBadRequest, "bad_title", "title must be 1–32 characters")
	case req.Emoji == "" || utf8.RuneCountInString(req.Emoji) > 10:
		writeError(w, http.StatusBadRequest, "bad_emoji", "emoji is required")
	case req.Lat < -90 || req.Lat > 90 || req.Lng < -180 || req.Lng > 180:
		writeError(w, http.StatusBadRequest, "bad_location", "lat/lng out of range")
	case !lenOK(req.Name, maxNameLen):
		writeError(w, http.StatusBadRequest, "bad_name", "name must be 1–32 characters")
	default:
		chat, me, err := s.store.CreateChat(r.Context(), deviceID(r), store.NewChat{
			Kind: req.Kind, Title: req.Title, Emoji: req.Emoji, Lat: req.Lat, Lng: req.Lng,
			FreeJoin: req.FreeJoin, Premoderated: req.Premoderated, Visible: req.Visible,
		}, req.Name)
		if err != nil {
			s.internal(w, r, err)
			return
		}
		writeJSON(w, http.StatusCreated, chatView{Chat: chat, Me: &me})
	}
}

type chatView struct {
	store.Chat
	Me *store.Member `json:"me,omitempty"`
}

// access loads a chat and the caller's membership and decides what the caller
// may see. invite is an optional invite code that unlocks hidden chats.
type access struct {
	chat     store.Chat
	me       store.Member // zero if not a member
	canSee   bool         // title, settings
	canRead  bool         // messages
	isMember bool         // active
}

func (s *Server) loadAccess(r *http.Request, chatID, invite string) (access, error) {
	var a access
	chat, err := s.store.Chat(r.Context(), chatID)
	if err != nil {
		return a, err
	}
	a.chat = chat
	me, err := s.store.MemberOf(r.Context(), chatID, deviceID(r))
	switch {
	case err == nil:
		a.me = me
	case !errors.Is(err, store.ErrNotFound):
		return a, err
	}
	if a.me.Status == store.StatusBlocked {
		return a, store.ErrForbidden
	}
	a.isMember = a.me.Status == store.StatusActive
	invited := invite != "" && strings.EqualFold(invite, chat.InviteCode)
	a.canSee = chat.Visible || invited || a.me.ID != ""
	a.canRead = a.isMember || (a.canSee && chat.FreeJoin)
	if !a.canSee {
		return a, store.ErrNotFound // hidden chats don't exist for outsiders
	}
	if !a.me.IsAdmin() {
		a.chat.InviteCode = ""
	}
	return a, nil
}

func (a access) view() chatView {
	v := chatView{Chat: a.chat}
	if a.me.ID != "" {
		me := a.me
		v.Me = &me
	}
	return v
}

func (s *Server) getChat(w http.ResponseWriter, r *http.Request) {
	a, err := s.loadAccess(r, r.PathValue("id"), r.URL.Query().Get("invite"))
	if err != nil {
		s.storeError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, a.view())
}

func (s *Server) getInvite(w http.ResponseWriter, r *http.Request) {
	chat, err := s.store.ChatByInvite(r.Context(), r.PathValue("code"))
	if err != nil {
		s.storeError(w, r, err)
		return
	}
	a, err := s.loadAccess(r, chat.ID, chat.InviteCode)
	if err != nil {
		s.storeError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, a.view())
}

func (s *Server) joinChat(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Name       string `json:"name"`
		InviteCode string `json:"invite_code"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	req.Name = clean(req.Name)
	if !lenOK(req.Name, maxNameLen) {
		writeError(w, http.StatusBadRequest, "bad_name", "name must be 1–32 characters")
		return
	}
	a, err := s.loadAccess(r, r.PathValue("id"), req.InviteCode)
	if err != nil {
		s.storeError(w, r, err)
		return
	}
	me, err := s.store.Join(r.Context(), a.chat, deviceID(r), req.Name)
	if err != nil {
		s.storeError(w, r, err)
		return
	}
	a.me = me
	action := MemberJoined
	if me.Status == store.StatusPending {
		action = MemberPending
	}
	s.publish(r.Context(), Event{Type: EvMember, ChatID: a.chat.ID, MemberID: me.ID, Action: action}, "")
	writeJSON(w, http.StatusOK, a.view())
}

// --- messages ---

func (s *Server) listMessages(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	a, err := s.loadAccess(r, r.PathValue("id"), q.Get("invite"))
	if err != nil {
		s.storeError(w, r, err)
		return
	}
	if !a.canRead {
		writeError(w, http.StatusForbidden, "join_required", "join the chat to read messages")
		return
	}
	before, _ := strconv.ParseInt(q.Get("before"), 10, 64)
	after, _ := strconv.ParseInt(q.Get("after"), 10, 64)
	limit, _ := strconv.Atoi(q.Get("limit"))
	limit = min(max(limit, 1), 100)
	if q.Get("limit") == "" {
		limit = 50
	}
	msgs, err := s.store.Messages(r.Context(), a.chat.ID, a.me, store.Page{Before: before, After: after, Limit: limit})
	if err != nil {
		s.internal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"messages": msgs})
}

func (s *Server) postMessage(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Body     string `json:"body"`
		ClientID string `json:"client_id"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	req.Body = strings.TrimSpace(req.Body)
	if !lenOK(req.Body, maxBodyLen) {
		writeError(w, http.StatusBadRequest, "bad_body", "message must be 1–4000 characters")
		return
	}
	if len(req.ClientID) > 64 {
		writeError(w, http.StatusBadRequest, "bad_client_id", "client_id is too long")
		return
	}
	a, err := s.loadAccess(r, r.PathValue("id"), "")
	if err != nil {
		s.storeError(w, r, err)
		return
	}
	if !a.isMember {
		writeError(w, http.StatusForbidden, "join_required", "join the chat to post")
		return
	}
	msg, err := s.store.PostMessage(r.Context(), a.chat, a.me, req.Body, req.ClientID)
	if err != nil {
		s.storeError(w, r, err)
		return
	}
	privateTo := ""
	if msg.Status == "pending" {
		privateTo = msg.MemberID // premoderated: only the author and admins know yet
	}
	s.publish(r.Context(), Event{Type: EvMessage, ChatID: a.chat.ID, Seq: msg.Seq, MessageID: msg.ID}, privateTo)
	writeJSON(w, http.StatusCreated, msg)
}

// --- validation ---

// clean trims and collapses whitespace in single-line fields.
func clean(s string) string { return strings.Join(strings.Fields(s), " ") }

func lenOK(s string, max int) bool {
	n := utf8.RuneCountInString(s)
	return n >= 1 && n <= max
}
