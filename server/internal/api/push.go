package api

import (
	"context"
	"net/http"
	"time"
	"unicode/utf8"

	"there-chat.com/server/internal/push"
	"there-chat.com/server/internal/store"
)

// Pusher delivers notifications in the background (push.Dispatcher).
type Pusher interface {
	Enqueue(push.Notification)
}

func (s *Server) setPush(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Platform string `json:"platform"`
		Token    string `json:"token"`
		Preview  *bool  `json:"preview"` // show message text; default true
	}
	if !readJSON(w, r, &req) {
		return
	}
	switch req.Platform {
	case push.APNs, push.APNsSandbox, push.FCM:
	default:
		writeError(w, http.StatusBadRequest, "bad_push", "platform must be apns, apns_sandbox or fcm")
		return
	}
	if req.Token == "" || len(req.Token) > 4096 {
		writeError(w, http.StatusBadRequest, "bad_push", "token required")
		return
	}
	preview := req.Preview == nil || *req.Preview
	if err := s.store.SetPushToken(r.Context(), deviceID(r), req.Platform, req.Token, preview); err != nil {
		s.internal(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) clearPush(w http.ResponseWriter, r *http.Request) {
	if err := s.store.ClearPushToken(r.Context(), deviceID(r)); err != nil {
		s.internal(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// notifyMessage tells the other members of a chat about a newly published
// message. It runs after the response, so slow lookups never delay posting.
func (s *Server) notifyMessage(chat store.Chat, msg store.Message) {
	if s.push == nil || msg.Kind != "text" || msg.Status != "published" || msg.Replayed {
		return
	}
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		targets, err := s.store.PushTargets(ctx, chat.ID, msg.MemberID)
		if err != nil {
			s.log.Error("push targets", "chat", chat.ID, "err", err)
			return
		}
		title := chat.Title
		if chat.Emoji != "" {
			title = chat.Emoji + " " + chat.Title
		}
		for _, t := range targets {
			body := msg.AuthorName + ": " + shorten(msg.Body, 180)
			if !t.Preview {
				body = msg.AuthorName + " sent a message"
			}
			s.push.Enqueue(push.Notification{
				Platform: t.Platform, Token: t.Token, ChatID: chat.ID,
				Title: title, Body: body, Badge: t.Badge,
			})
		}
	}()
}

func shorten(s string, max int) string {
	if utf8.RuneCountInString(s) <= max {
		return s
	}
	r := []rune(s)
	return string(r[:max-1]) + "…"
}
