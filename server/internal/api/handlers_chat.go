package api

import (
	"net/http"
	"strings"
	"unicode/utf8"

	"there-chat.com/server/internal/store"
)

func (s *Server) chatRoutes() {
	s.mux.HandleFunc("PATCH /v1/chats/{id}", s.auth(s.updateChat))
	s.mux.HandleFunc("DELETE /v1/chats/{id}", s.auth(s.deleteChat))
	s.mux.HandleFunc("POST /v1/chats/{id}/leave", s.auth(s.leaveChat))
	s.mux.HandleFunc("PATCH /v1/chats/{id}/me", s.auth(s.updateMe))
	s.mux.HandleFunc("GET /v1/chats/{id}/members", s.auth(s.listMembers))
	s.mux.HandleFunc("POST /v1/chats/{id}/members/{mid}/{action}", s.auth(s.memberAction))
	s.mux.HandleFunc("DELETE /v1/chats/{id}/members/{mid}/messages", s.auth(s.deleteMemberMessages))
	s.mux.HandleFunc("DELETE /v1/messages/{id}", s.auth(s.deleteMessage))
	s.mux.HandleFunc("POST /v1/messages/{id}/{action}", s.auth(s.moderateMessage))
	s.mux.HandleFunc("PUT /v1/messages/{id}/reactions/{emoji}", s.auth(s.react(true)))
	s.mux.HandleFunc("DELETE /v1/messages/{id}/reactions/{emoji}", s.auth(s.react(false)))
	s.mux.HandleFunc("POST /v1/reports", s.auth(s.report))
}

// --- chat settings ---

func (s *Server) updateChat(w http.ResponseWriter, r *http.Request) {
	a, ok := s.adminAccess(w, r, r.PathValue("id"))
	if !ok {
		return
	}
	var req struct {
		Title        *string `json:"title"`
		Emoji        *string `json:"emoji"`
		FreeJoin     *bool   `json:"free_join"`
		Premoderated *bool   `json:"premoderated"`
		Visible      *bool   `json:"visible"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	if req.Title != nil {
		*req.Title = clean(*req.Title)
		if !lenOK(*req.Title, maxTitleLen) {
			writeError(w, http.StatusBadRequest, "bad_title", "title must be 1–32 characters")
			return
		}
	}
	if req.Emoji != nil {
		*req.Emoji = strings.TrimSpace(*req.Emoji)
		if *req.Emoji == "" || utf8.RuneCountInString(*req.Emoji) > 10 {
			writeError(w, http.StatusBadRequest, "bad_emoji", "emoji is required")
			return
		}
	}
	chat, err := s.store.UpdateChat(r.Context(), a.chat.ID, store.ChatUpdate{
		Title: req.Title, Emoji: req.Emoji, FreeJoin: req.FreeJoin, Premoderated: req.Premoderated, Visible: req.Visible,
	})
	if err != nil {
		s.storeError(w, r, err)
		return
	}
	a.chat = chat
	writeJSON(w, http.StatusOK, a.view())
}

func (s *Server) deleteChat(w http.ResponseWriter, r *http.Request) {
	a, ok := s.adminAccess(w, r, r.PathValue("id"))
	if !ok {
		return
	}
	if a.me.Role != store.RoleOwner {
		writeError(w, http.StatusForbidden, "owner_only", "only the owner can delete the chat")
		return
	}
	if err := s.store.DeleteChat(r.Context(), a.chat.ID); err != nil {
		s.internal(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// --- own membership ---

func (s *Server) leaveChat(w http.ResponseWriter, r *http.Request) {
	a, err := s.loadAccess(r, r.PathValue("id"), "")
	if err != nil {
		s.storeError(w, r, err)
		return
	}
	switch {
	case a.me.ID == "" || (a.me.Status != store.StatusActive && a.me.Status != store.StatusPending):
		writeError(w, http.StatusConflict, "not_member", "you are not in this chat")
		return
	case a.me.Role == store.RoleOwner:
		writeError(w, http.StatusConflict, "owner_cannot_leave", "the owner can delete the chat instead")
		return
	}
	event := store.EventLeft
	if a.me.Status == store.StatusPending {
		event = "" // withdrawing a request is not worth a message
	}
	me, err := s.store.SetMemberStatus(r.Context(), a.me, store.StatusLeft, event)
	if err != nil {
		s.storeError(w, r, err)
		return
	}
	a.me = me
	writeJSON(w, http.StatusOK, a.view())
}

func (s *Server) updateMe(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Name        *string `json:"name"`
		Notify      *bool   `json:"notify"`
		LastReadSeq *int64  `json:"last_read_seq"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	if req.Name != nil {
		*req.Name = clean(*req.Name)
		if !lenOK(*req.Name, maxNameLen) {
			writeError(w, http.StatusBadRequest, "bad_name", "name must be 1–32 characters")
			return
		}
	}
	a, err := s.loadAccess(r, r.PathValue("id"), "")
	if err != nil {
		s.storeError(w, r, err)
		return
	}
	if a.me.ID == "" {
		writeError(w, http.StatusForbidden, "join_required", "join the chat first")
		return
	}
	me, err := s.store.UpdateMe(r.Context(), a.me, store.MeUpdate{Name: req.Name, Notify: req.Notify, LastReadSeq: req.LastReadSeq})
	if err != nil {
		s.storeError(w, r, err)
		return
	}
	a.me = me
	writeJSON(w, http.StatusOK, a.view())
}

// --- members ---

func (s *Server) listMembers(w http.ResponseWriter, r *http.Request) {
	a, err := s.loadAccess(r, r.PathValue("id"), "")
	if err != nil {
		s.storeError(w, r, err)
		return
	}
	if !a.isMember {
		writeError(w, http.StatusForbidden, "join_required", "join the chat to see its members")
		return
	}
	status := r.URL.Query().Get("status")
	members, err := s.store.Members(r.Context(), a.chat.ID, status)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"members": members})
}

// memberAction: approve | reject | promote | demote | block | unblock.
func (s *Server) memberAction(w http.ResponseWriter, r *http.Request) {
	a, ok := s.adminAccess(w, r, r.PathValue("id"))
	if !ok {
		return
	}
	target, ok := s.targetMember(w, r, a)
	if !ok {
		return
	}
	var (
		m   store.Member
		err error
	)
	switch action := r.PathValue("action"); {
	case action == "approve" && target.Status == store.StatusPending:
		m, err = s.store.SetMemberStatus(r.Context(), target, store.StatusActive, store.EventJoined)
	case action == "reject" && target.Status == store.StatusPending:
		m, err = s.store.SetMemberStatus(r.Context(), target, store.StatusLeft, "")
	case action == "promote" && target.Status == store.StatusActive && target.Role == store.RoleMember:
		m, err = s.store.SetMemberRole(r.Context(), target, store.RoleAdmin)
	case action == "demote" && target.Role == store.RoleAdmin:
		m, err = s.store.SetMemberRole(r.Context(), target, store.RoleMember)
	case action == "block" && target.Status != store.StatusBlocked:
		m, err = s.store.SetMemberStatus(r.Context(), target, store.StatusBlocked, store.EventBlocked)
	case action == "unblock" && target.Status == store.StatusBlocked:
		m, err = s.store.SetMemberStatus(r.Context(), target, store.StatusLeft, "")
	case action == "approve", action == "reject", action == "promote", action == "demote", action == "block", action == "unblock":
		writeError(w, http.StatusConflict, "bad_state", "the member is not in a state that allows this")
		return
	default:
		writeError(w, http.StatusNotFound, "not_found", "unknown action")
		return
	}
	if err != nil {
		s.storeError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, m)
}

func (s *Server) deleteMemberMessages(w http.ResponseWriter, r *http.Request) {
	a, ok := s.adminAccess(w, r, r.PathValue("id"))
	if !ok {
		return
	}
	target, ok := s.targetMember(w, r, a)
	if !ok {
		return
	}
	if err := s.store.DeleteMemberMessages(r.Context(), a.chat.ID, target.ID); err != nil {
		s.internal(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// targetMember loads {mid} and checks the caller may act on them: not on
// themselves, never on the owner, and only the owner acts on other admins.
func (s *Server) targetMember(w http.ResponseWriter, r *http.Request, a access) (store.Member, bool) {
	target, err := s.store.MemberByID(r.Context(), a.chat.ID, r.PathValue("mid"))
	if err != nil {
		s.storeError(w, r, err)
		return target, false
	}
	switch {
	case target.ID == a.me.ID:
		writeError(w, http.StatusConflict, "self", "you can't do this to yourself")
	case target.Role == store.RoleOwner:
		writeError(w, http.StatusForbidden, "owner", "the owner can't be moderated")
	case target.Role == store.RoleAdmin && a.me.Role != store.RoleOwner:
		writeError(w, http.StatusForbidden, "owner_only", "only the owner can moderate admins")
	default:
		return target, true
	}
	return target, false
}

// adminAccess loads the chat and requires the caller to be an active admin.
func (s *Server) adminAccess(w http.ResponseWriter, r *http.Request, chatID string) (access, bool) {
	a, err := s.loadAccess(r, chatID, "")
	if err != nil {
		s.storeError(w, r, err)
		return a, false
	}
	if !a.me.IsAdmin() {
		writeError(w, http.StatusForbidden, "admin_only", "only admins can do this")
		return a, false
	}
	return a, true
}

// --- messages ---

// messageAccess loads a message's chat and the caller's membership.
func (s *Server) messageAccess(w http.ResponseWriter, r *http.Request) (access, store.Message, bool) {
	var msg store.Message
	chatID, err := s.store.MessageChatID(r.Context(), r.PathValue("id"))
	if err != nil {
		s.storeError(w, r, err)
		return access{}, msg, false
	}
	a, err := s.loadAccess(r, chatID, "")
	if err != nil {
		s.storeError(w, r, err)
		return a, msg, false
	}
	msg, err = s.store.Message(r.Context(), r.PathValue("id"), a.me)
	if err != nil {
		s.storeError(w, r, err)
		return a, msg, false
	}
	return a, msg, true
}

func (s *Server) deleteMessage(w http.ResponseWriter, r *http.Request) {
	a, msg, ok := s.messageAccess(w, r)
	if !ok {
		return
	}
	if !(a.isMember && msg.MemberID == a.me.ID) && !a.me.IsAdmin() {
		writeError(w, http.StatusForbidden, "forbidden", "only the author or an admin can delete a message")
		return
	}
	if err := s.store.DeleteMessage(r.Context(), msg.ID); err != nil {
		s.internal(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) moderateMessage(w http.ResponseWriter, r *http.Request) {
	action := r.PathValue("action")
	if action != "approve" && action != "reject" {
		writeError(w, http.StatusNotFound, "not_found", "unknown action")
		return
	}
	a, msg, ok := s.messageAccess(w, r)
	if !ok {
		return
	}
	if !a.me.IsAdmin() {
		writeError(w, http.StatusForbidden, "admin_only", "only admins can moderate messages")
		return
	}
	if err := s.store.ModerateMessage(r.Context(), msg.ID, action == "approve"); err != nil {
		if err == store.ErrConflict {
			writeError(w, http.StatusConflict, "not_pending", "the message is not waiting for moderation")
			return
		}
		s.internal(w, r, err)
		return
	}
	msg, err := s.store.Message(r.Context(), msg.ID, a.me)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, msg)
}

func (s *Server) react(on bool) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		emoji := r.PathValue("emoji")
		if emoji == "" || utf8.RuneCountInString(emoji) > 10 {
			writeError(w, http.StatusBadRequest, "bad_emoji", "one emoji please")
			return
		}
		a, msg, ok := s.messageAccess(w, r)
		if !ok {
			return
		}
		if !a.isMember {
			writeError(w, http.StatusForbidden, "join_required", "join the chat to react")
			return
		}
		if msg.Kind != "text" || msg.Status != "published" {
			writeError(w, http.StatusConflict, "not_reactable", "you can only react to published messages")
			return
		}
		if err := s.store.SetReaction(r.Context(), msg.ID, a.me, emoji, on); err != nil {
			s.storeError(w, r, err)
			return
		}
		msg, err := s.store.Message(r.Context(), msg.ID, a.me)
		if err != nil {
			s.internal(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, msg)
	}
}

func (s *Server) report(w http.ResponseWriter, r *http.Request) {
	var req struct {
		ChatID    string `json:"chat_id"`
		MessageID string `json:"message_id"`
		Reason    string `json:"reason"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	req.Reason = strings.TrimSpace(req.Reason)
	if req.Reason == "" || utf8.RuneCountInString(req.Reason) > 1000 {
		writeError(w, http.StatusBadRequest, "bad_reason", "reason must be 1–1000 characters")
		return
	}
	a, err := s.loadAccess(r, req.ChatID, "")
	if err != nil {
		s.storeError(w, r, err)
		return
	}
	if req.MessageID != "" {
		if chatID, err := s.store.MessageChatID(r.Context(), req.MessageID); err != nil || chatID != a.chat.ID {
			writeError(w, http.StatusBadRequest, "bad_message", "message is not in this chat")
			return
		}
	}
	if err := s.store.Report(r.Context(), deviceID(r), a.chat.ID, req.MessageID, req.Reason); err != nil {
		s.internal(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
