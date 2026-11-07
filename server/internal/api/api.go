// Package api is the HTTP layer: routing, auth, validation and JSON.
package api

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"strings"

	"there-chat.com/server/internal/store"
)

type Config struct {
	// MaxSpanDeg is the largest map area (in degrees of latitude and of
	// longitude) for which chats are returned. Bigger areas get "zoom_in".
	MaxSpanDeg float64
}

type Server struct {
	store *store.Store
	cfg   Config
	log   *slog.Logger
	mux   *http.ServeMux
}

func New(st *store.Store, cfg Config, log *slog.Logger) *Server {
	if cfg.MaxSpanDeg == 0 {
		cfg.MaxSpanDeg = 0.04
	}
	s := &Server{store: st, cfg: cfg, log: log, mux: http.NewServeMux()}
	s.routes()
	return s
}

func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) { s.mux.ServeHTTP(w, r) }

func (s *Server) routes() {
	s.mux.HandleFunc("GET /healthz", s.health)
	s.mux.HandleFunc("POST /v1/devices", s.registerDevice)

	s.mux.HandleFunc("PUT /v1/devices/me/push", s.auth(s.setPush))
	s.mux.HandleFunc("DELETE /v1/devices/me/push", s.auth(s.clearPush))
	s.mux.HandleFunc("GET /v1/names/suggest", s.auth(s.suggestNames))
	s.mux.HandleFunc("GET /v1/map/chats", s.auth(s.mapChats))
	s.mux.HandleFunc("GET /v1/me/chats", s.auth(s.myChats))
	s.mux.HandleFunc("POST /v1/chats", s.auth(s.createChat))
	s.mux.HandleFunc("GET /v1/chats/{id}", s.auth(s.getChat))
	s.mux.HandleFunc("GET /v1/invites/{code}", s.auth(s.getInvite))
	s.mux.HandleFunc("POST /v1/chats/{id}/join", s.auth(s.joinChat))
	s.mux.HandleFunc("GET /v1/chats/{id}/messages", s.auth(s.listMessages))
	s.mux.HandleFunc("POST /v1/chats/{id}/messages", s.auth(s.postMessage))
}

// --- auth ---

type ctxKey struct{}

func deviceID(r *http.Request) string { return r.Context().Value(ctxKey{}).(string) }

func (s *Server) auth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		token, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
		secret, err := decodeSecret(token)
		if !ok || err != nil {
			writeError(w, http.StatusUnauthorized, "unauthorized", "missing or malformed device token")
			return
		}
		id, err := s.store.DeviceBySecret(r.Context(), secret)
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusUnauthorized, "unknown_device", "register the device first")
			return
		}
		if err != nil {
			s.internal(w, r, err)
			return
		}
		next(w, r.WithContext(context.WithValue(r.Context(), ctxKey{}, id)))
	}
}

// decodeSecret accepts a base64url (unpadded) 32-byte device secret.
func decodeSecret(s string) ([]byte, error) {
	b, err := base64.RawURLEncoding.DecodeString(s)
	if err != nil {
		return nil, err
	}
	if len(b) != 32 {
		return nil, errors.New("secret must be 32 bytes")
	}
	return b, nil
}

// --- JSON helpers ---

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

type apiError struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

func writeError(w http.ResponseWriter, status int, code, msg string) {
	writeJSON(w, status, map[string]apiError{"error": {Code: code, Message: msg}})
}

func (s *Server) internal(w http.ResponseWriter, r *http.Request, err error) {
	s.log.Error("request failed", "method", r.Method, "path", r.URL.Path, "err", err)
	writeError(w, http.StatusInternalServerError, "internal", "something went wrong")
}

// storeError maps well-known store errors to responses; anything else is a 500.
func (s *Server) storeError(w http.ResponseWriter, r *http.Request, err error) {
	switch {
	case errors.Is(err, store.ErrNotFound):
		writeError(w, http.StatusNotFound, "not_found", "not found")
	case errors.Is(err, store.ErrForbidden):
		writeError(w, http.StatusForbidden, "forbidden", "not allowed")
	case errors.Is(err, store.ErrNameTaken):
		writeError(w, http.StatusConflict, "name_taken", "someone in this chat already uses this name")
	default:
		s.internal(w, r, err)
	}
}

const maxBody = 64 << 10

func readJSON(w http.ResponseWriter, r *http.Request, v any) bool {
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxBody))
	if err := dec.Decode(v); err != nil {
		writeError(w, http.StatusBadRequest, "bad_json", err.Error())
		return false
	}
	return true
}
