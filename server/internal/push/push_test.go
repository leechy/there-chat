package push

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"io"
	"log/slog"
	"math/big"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

func TestAppleSender(t *testing.T) {
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	der, _ := x509.MarshalPKCS8PrivateKey(key)
	p8 := pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der})

	var got struct {
		path, topic, auth string
		body              map[string]any
	}
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got.path, got.topic, got.auth = r.URL.Path, r.Header.Get("apns-topic"), r.Header.Get("authorization")
		json.NewDecoder(r.Body).Decode(&got.body)
		if strings.HasSuffix(r.URL.Path, "/gone") {
			w.WriteHeader(http.StatusGone)
			w.Write([]byte(`{"reason":"Unregistered"}`))
		}
	}))
	defer srv.Close()

	a, err := NewAppleSender(p8, "KEY123", "TEAM456", "com.there-chat.app")
	if err != nil {
		t.Fatal(err)
	}
	a.client, a.ProductionURL, a.SandboxURL = srv.Client(), srv.URL, srv.URL+"/sandbox"

	n := Notification{Platform: APNs, Token: "abc", ChatID: "c1", Title: "🥞 Блинчики", Body: "Ellen: hi", Badge: 3}
	if err := a.Send(context.Background(), n); err != nil {
		t.Fatal(err)
	}
	if got.path != "/3/device/abc" || got.topic != "com.there-chat.app" {
		t.Fatalf("path %q topic %q", got.path, got.topic)
	}
	aps := got.body["aps"].(map[string]any)
	if aps["badge"].(float64) != 3 || aps["thread-id"] != "c1" || got.body["chat_id"] != "c1" {
		t.Fatalf("payload %v", got.body)
	}
	verifyES256(t, strings.TrimPrefix(got.auth, "bearer "), &key.PublicKey)

	n.Platform = APNsSandbox
	a.Send(context.Background(), n)
	if got.path != "/sandbox/3/device/abc" {
		t.Fatalf("sandbox path %q", got.path)
	}

	n.Token = "gone"
	if err := a.Send(context.Background(), n); !errors.Is(err, ErrBadToken) {
		t.Fatalf("410: got %v, want ErrBadToken", err)
	}
}

func verifyES256(t *testing.T, jwt string, pub *ecdsa.PublicKey) {
	t.Helper()
	parts := strings.Split(jwt, ".")
	if len(parts) != 3 {
		t.Fatalf("not a JWT: %q", jwt)
	}
	header, _ := base64.RawURLEncoding.DecodeString(parts[0])
	if !strings.Contains(string(header), `"kid":"KEY123"`) {
		t.Fatalf("header %s", header)
	}
	sig, _ := base64.RawURLEncoding.DecodeString(parts[2])
	sum := sha256.Sum256([]byte(parts[0] + "." + parts[1]))
	r, s := new(big.Int).SetBytes(sig[:32]), new(big.Int).SetBytes(sig[32:])
	if !ecdsa.Verify(pub, sum[:], r, s) {
		t.Fatal("JWT signature does not verify")
	}
}

func TestGoogleSender(t *testing.T) {
	key, _ := rsa.GenerateKey(rand.Reader, 2048)
	der, _ := x509.MarshalPKCS8PrivateKey(key)
	tokens := 0
	var sent map[string]any
	var auth string
	mux := http.NewServeMux()
	mux.HandleFunc("POST /token", func(w http.ResponseWriter, r *http.Request) {
		r.ParseForm()
		if r.Form.Get("grant_type") != "urn:ietf:params:oauth:grant-type:jwt-bearer" || r.Form.Get("assertion") == "" {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		tokens++
		w.Write([]byte(`{"access_token":"ya29.test","expires_in":3600}`))
	})
	mux.HandleFunc("POST /send", func(w http.ResponseWriter, r *http.Request) {
		auth = r.Header.Get("Authorization")
		json.NewDecoder(r.Body).Decode(&sent)
		msg := sent["message"].(map[string]any)
		if msg["token"] == "stale" {
			w.WriteHeader(http.StatusNotFound)
			w.Write([]byte(`{"error":{"status":"NOT_FOUND","details":[{"errorCode":"UNREGISTERED"}]}}`))
		}
	})
	srv := httptest.NewServer(mux)
	defer srv.Close()

	creds, _ := json.Marshal(map[string]string{
		"project_id": "there-chat", "client_email": "push@there-chat.iam.gserviceaccount.com",
		"private_key": string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der})),
		"token_uri":   srv.URL + "/token",
	})
	g, err := NewGoogleSender(creds)
	if err != nil {
		t.Fatal(err)
	}
	g.SendURL = srv.URL + "/send"

	n := Notification{Platform: FCM, Token: "tok", ChatID: "c1", Title: "Блинчики", Body: "Ellen: hi", Badge: 2}
	for range 2 {
		if err := g.Send(context.Background(), n); err != nil {
			t.Fatal(err)
		}
	}
	if tokens != 1 {
		t.Fatalf("access token fetched %d times, want 1 (cached)", tokens)
	}
	if auth != "Bearer ya29.test" {
		t.Fatalf("auth %q", auth)
	}
	data := sent["message"].(map[string]any)["data"].(map[string]any)
	if data["chat_id"] != "c1" || data["body"] != "Ellen: hi" || data["badge"] != "2" {
		t.Fatalf("data %v", data)
	}
	n.Token = "stale"
	if err := g.Send(context.Background(), n); !errors.Is(err, ErrBadToken) {
		t.Fatalf("unregistered: got %v, want ErrBadToken", err)
	}
}

type fakeSender struct {
	mu   sync.Mutex
	sent []Notification
	err  error
}

func (f *fakeSender) Send(_ context.Context, n Notification) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.sent = append(f.sent, n)
	return f.err
}

func TestDispatcherDropsBadTokens(t *testing.T) {
	s := &fakeSender{err: ErrBadToken}
	var dropped []string
	d := NewDispatcher(map[string]Sender{FCM: s}, func(tok string) { dropped = append(dropped, tok) },
		slog.New(slog.NewTextHandler(io.Discard, nil)))
	d.Start(1)
	d.Enqueue(Notification{Platform: FCM, Token: "t1"})
	d.Enqueue(Notification{Platform: APNs, Token: "t2"}) // not configured: ignored
	d.Close()
	d.Enqueue(Notification{Platform: FCM, Token: "t3"}) // after Close: ignored, no panic
	if len(s.sent) != 1 || len(dropped) != 1 || dropped[0] != "t1" {
		t.Fatalf("sent %v dropped %v", s.sent, dropped)
	}
}
