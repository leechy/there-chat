// Command there runs the There chat API server.
//
// Configuration comes from the environment:
//
//	THERE_ADDR       listen address (default 127.0.0.1:8080; Caddy proxies to it)
//	THERE_DB         SQLite file path (default there.db)
//	THERE_MAX_SPAN   largest map area in degrees that still returns chats (default 0.04)
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

	span, _ := strconv.ParseFloat(env("THERE_MAX_SPAN", "0"), 64)
	handler := api.New(st, api.Config{MaxSpanDeg: span}, log)
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
