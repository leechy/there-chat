package push

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"net/http"
	"sync"
	"time"
)

// AppleSender talks to APNs with a token-based (.p8) key.
type AppleSender struct {
	keyID, teamID, topic string
	key                  *ecdsa.PrivateKey
	client               *http.Client
	now                  func() time.Time
	// Hosts; tests point them at a fake server.
	ProductionURL, SandboxURL string

	mu    sync.Mutex
	jwt   string
	jwtAt time.Time
}

func NewAppleSender(p8 []byte, keyID, teamID, topic string) (*AppleSender, error) {
	block, _ := pem.Decode(p8)
	if block == nil {
		return nil, errors.New("apns: key is not PEM")
	}
	k, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("apns: %w", err)
	}
	key, ok := k.(*ecdsa.PrivateKey)
	if !ok {
		return nil, errors.New("apns: key is not an EC key")
	}
	return &AppleSender{
		keyID: keyID, teamID: teamID, topic: topic, key: key,
		client:        &http.Client{Timeout: 10 * time.Second}, // HTTP/2 over TLS by default
		now:           time.Now,
		ProductionURL: "https://api.push.apple.com",
		SandboxURL:    "https://api.sandbox.push.apple.com",
	}, nil
}

// token returns the provider JWT; Apple wants it renewed every 20–60 minutes.
func (a *AppleSender) token(renew bool) (string, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	now := a.now()
	if a.jwt != "" && !renew && now.Sub(a.jwtAt) < 40*time.Minute {
		return a.jwt, nil
	}
	header := b64json(map[string]string{"alg": "ES256", "kid": a.keyID})
	claims := b64json(map[string]any{"iss": a.teamID, "iat": now.Unix()})
	signing := header + "." + claims
	sum := sha256.Sum256([]byte(signing))
	r, s, err := ecdsa.Sign(rand.Reader, a.key, sum[:])
	if err != nil {
		return "", err
	}
	sig := make([]byte, 64)
	r.FillBytes(sig[:32])
	s.FillBytes(sig[32:])
	a.jwt, a.jwtAt = signing+"."+base64.RawURLEncoding.EncodeToString(sig), now
	return a.jwt, nil
}

type apsAlert struct {
	Title string `json:"title"`
	Body  string `json:"body"`
}

type aps struct {
	Alert    apsAlert `json:"alert"`
	Sound    string   `json:"sound"`
	Badge    int64    `json:"badge"`
	ThreadID string   `json:"thread-id"`
}

func (a *AppleSender) Send(ctx context.Context, n Notification) error {
	body, _ := json.Marshal(map[string]any{
		"aps":     aps{Alert: apsAlert{n.Title, n.Body}, Sound: "default", Badge: n.Badge, ThreadID: n.ChatID},
		"chat_id": n.ChatID,
	})
	host := a.ProductionURL
	if n.Platform == APNsSandbox {
		host = a.SandboxURL
	}
	for attempt := 0; ; attempt++ {
		jwt, err := a.token(attempt > 0)
		if err != nil {
			return err
		}
		req, _ := http.NewRequestWithContext(ctx, "POST", host+"/3/device/"+n.Token, bytes.NewReader(body))
		req.Header.Set("authorization", "bearer "+jwt)
		req.Header.Set("apns-topic", a.topic)
		req.Header.Set("apns-push-type", "alert")
		req.Header.Set("apns-priority", "10")
		req.Header.Set("apns-expiration", fmt.Sprint(a.now().Add(24*time.Hour).Unix()))
		resp, err := a.client.Do(req)
		if err != nil {
			return err
		}
		data, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		resp.Body.Close()
		if resp.StatusCode == http.StatusOK {
			return nil
		}
		var reason struct{ Reason string }
		json.Unmarshal(data, &reason)
		switch {
		case resp.StatusCode == http.StatusGone,
			reason.Reason == "BadDeviceToken", reason.Reason == "DeviceTokenNotForTopic", reason.Reason == "Unregistered":
			return ErrBadToken
		case reason.Reason == "ExpiredProviderToken" && attempt == 0:
			continue // renew the JWT once
		}
		return fmt.Errorf("apns: %d %s", resp.StatusCode, reason.Reason)
	}
}

func b64json(v any) string {
	b, _ := json.Marshal(v)
	return base64.RawURLEncoding.EncodeToString(b)
}
