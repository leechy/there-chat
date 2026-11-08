package push

import (
	"bytes"
	"context"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

// GoogleSender talks to FCM HTTP v1 with a service account key.
type GoogleSender struct {
	projectID, email, tokenURI string
	key                        *rsa.PrivateKey
	client                     *http.Client
	now                        func() time.Time
	// SendURL is the FCM endpoint; tests point it at a fake server.
	SendURL string

	mu     sync.Mutex
	access string
	expiry time.Time
}

// NewGoogleSender reads the service account JSON downloaded from Firebase.
func NewGoogleSender(credentials []byte) (*GoogleSender, error) {
	var c struct {
		ProjectID   string `json:"project_id"`
		ClientEmail string `json:"client_email"`
		PrivateKey  string `json:"private_key"`
		TokenURI    string `json:"token_uri"`
	}
	if err := json.Unmarshal(credentials, &c); err != nil {
		return nil, fmt.Errorf("fcm: %w", err)
	}
	block, _ := pem.Decode([]byte(c.PrivateKey))
	if block == nil || c.ProjectID == "" || c.ClientEmail == "" {
		return nil, errors.New("fcm: not a service account key")
	}
	k, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("fcm: %w", err)
	}
	key, ok := k.(*rsa.PrivateKey)
	if !ok {
		return nil, errors.New("fcm: key is not RSA")
	}
	if c.TokenURI == "" {
		c.TokenURI = "https://oauth2.googleapis.com/token"
	}
	return &GoogleSender{
		projectID: c.ProjectID, email: c.ClientEmail, tokenURI: c.TokenURI, key: key,
		client:  &http.Client{Timeout: 10 * time.Second},
		now:     time.Now,
		SendURL: "https://fcm.googleapis.com/v1/projects/" + c.ProjectID + "/messages:send",
	}, nil
}

// accessToken returns an OAuth token, exchanging a signed JWT for a new one
// shortly before the old one expires.
func (g *GoogleSender) accessToken(ctx context.Context) (string, error) {
	g.mu.Lock()
	defer g.mu.Unlock()
	now := g.now()
	if g.access != "" && now.Before(g.expiry.Add(-5*time.Minute)) {
		return g.access, nil
	}
	header := b64json(map[string]string{"alg": "RS256", "typ": "JWT"})
	claims := b64json(map[string]any{
		"iss": g.email, "scope": "https://www.googleapis.com/auth/firebase.messaging",
		"aud": g.tokenURI, "iat": now.Unix(), "exp": now.Add(time.Hour).Unix(),
	})
	signing := header + "." + claims
	sum := sha256.Sum256([]byte(signing))
	sig, err := rsa.SignPKCS1v15(rand.Reader, g.key, crypto.SHA256, sum[:])
	if err != nil {
		return "", err
	}
	form := url.Values{
		"grant_type": {"urn:ietf:params:oauth:grant-type:jwt-bearer"},
		"assertion":  {signing + "." + base64.RawURLEncoding.EncodeToString(sig)},
	}
	req, _ := http.NewRequestWithContext(ctx, "POST", g.tokenURI, strings.NewReader(form.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := g.client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	var out struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int64  `json:"expires_in"`
	}
	if resp.StatusCode != http.StatusOK || json.NewDecoder(resp.Body).Decode(&out) != nil || out.AccessToken == "" {
		return "", fmt.Errorf("fcm: token exchange failed: %d", resp.StatusCode)
	}
	g.access, g.expiry = out.AccessToken, now.Add(time.Duration(out.ExpiresIn)*time.Second)
	return g.access, nil
}

// Send posts a data-only message: the app builds the notification itself,
// so it can group by chat and stay quiet while that chat is open.
func (g *GoogleSender) Send(ctx context.Context, n Notification) error {
	access, err := g.accessToken(ctx)
	if err != nil {
		return err
	}
	body, _ := json.Marshal(map[string]any{"message": map[string]any{
		"token": n.Token,
		"data": map[string]string{
			"chat_id": n.ChatID, "title": n.Title, "body": n.Body, "badge": fmt.Sprint(n.Badge),
		},
		"android": map[string]any{"priority": "HIGH", "ttl": "86400s"},
	}})
	req, _ := http.NewRequestWithContext(ctx, "POST", g.SendURL, bytes.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+access)
	req.Header.Set("Content-Type", "application/json")
	resp, err := g.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusOK {
		return nil
	}
	data, _ := io.ReadAll(io.LimitReader(resp.Body, 8192))
	text := string(data)
	if resp.StatusCode == http.StatusNotFound || strings.Contains(text, "UNREGISTERED") ||
		(resp.StatusCode == http.StatusBadRequest && strings.Contains(text, "registration token")) {
		return ErrBadToken
	}
	if resp.StatusCode == http.StatusUnauthorized {
		g.mu.Lock()
		g.access = "" // fetch a new one next time
		g.mu.Unlock()
	}
	return fmt.Errorf("fcm: %d %.200s", resp.StatusCode, text)
}
