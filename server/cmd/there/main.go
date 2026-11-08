// Command there runs the There chat API server.
//
// Configuration comes from the environment:
//
//	THERE_ADDR       listen address (default 127.0.0.1:8080; Caddy proxies to it)
//	THERE_DB         SQLite file path (default there.db)
//	THERE_MAX_SPAN   largest map area in degrees that still returns chats (default 0.04)
//
// Push notifications (each platform is off until configured):
//
//	THERE_APNS_KEY_FILE   Apple .p8 key; with THERE_APNS_KEY_ID, THERE_APNS_TEAM_ID, THERE_APNS_TOPIC
//	THERE_FCM_CREDENTIALS Firebase service account JSON
package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"there-chat.com/server/internal/api"
	"there-chat.com/server/internal/push"
	"there-chat.com/server/internal/store"
)

func main() {
	log := slog.New(slog.NewJSONHandler(os.Stderr, nil))
	if err := run(log); err != nil {
		log.Error("fatal", "err", err)
		os.Exit(1)
	}
}

func run(log *slog.Logger) error {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	st, err := store.Open(ctx, env("THERE_DB", "there.db"))
	if err != nil {
		return err
	}
	defer st.Close()

	pusher := newPusher(st, log)
	pusher.Start(4)
	defer pusher.Close()

	span, _ := strconv.ParseFloat(env("THERE_MAX_SPAN", "0"), 64)
	handler := api.New(st, api.Config{MaxSpanDeg: span, Push: pusher}, log)
	srv := &http.Server{
		Addr:              env("THERE_ADDR", "127.0.0.1:8080"),
		Handler:           handler,
		ReadHeaderTimeout: 10 * time.Second,
	}
	srv.RegisterOnShutdown(handler.Close) // WebSockets are hijacked; Shutdown doesn't wait for them

	errc := make(chan error, 1)
	go func() {
		log.Info("listening", "addr", srv.Addr)
		errc <- srv.ListenAndServe()
	}()
	select {
	case err := <-errc:
		return err
	case <-ctx.Done():
	}
	shutdown, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := srv.Shutdown(shutdown); err != nil && !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	log.Info("stopped")
	return nil
}

func env(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

// newPusher sets up the platforms that have credentials; a bad configuration
// is logged and leaves that platform off rather than stopping the server.
func newPusher(st *store.Store, log *slog.Logger) *push.Dispatcher {
	senders := map[string]push.Sender{}
	if file := os.Getenv("THERE_APNS_KEY_FILE"); file != "" {
		p8, err := os.ReadFile(file)
		var apple *push.AppleSender
		if err == nil {
			apple, err = push.NewAppleSender(p8, os.Getenv("THERE_APNS_KEY_ID"), os.Getenv("THERE_APNS_TEAM_ID"), os.Getenv("THERE_APNS_TOPIC"))
		}
		if err != nil {
			log.Error("apns disabled", "err", err)
		} else {
			senders[push.APNs], senders[push.APNsSandbox] = apple, apple
		}
	}
	if file := os.Getenv("THERE_FCM_CREDENTIALS"); file != "" {
		creds, err := os.ReadFile(file)
		var google *push.GoogleSender
		if err == nil {
			google, err = push.NewGoogleSender(creds)
		}
		if err != nil {
			log.Error("fcm disabled", "err", err)
		} else {
			senders[push.FCM] = google
		}
	}
	platforms := make([]string, 0, len(senders))
	for p := range senders {
		platforms = append(platforms, p)
	}
	log.Info("push", "platforms", platforms)
	return push.NewDispatcher(senders, func(token string) {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := st.DropPushToken(ctx, token); err != nil {
			log.Error("drop push token", "err", err)
		}
	}, log)
}
