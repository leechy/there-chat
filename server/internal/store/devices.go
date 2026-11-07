package store

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"errors"
)

// RegisterDevice records a device by the hash of its secret. Registering the
// same secret again is a no-op and returns the same device ID.
func (s *Store) RegisterDevice(ctx context.Context, secret []byte) (string, error) {
	hash := sha256.Sum256(secret)
	now := s.now().Unix()
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO devices (id, token_hash, created_at, last_seen_at) VALUES (?, ?, ?, ?)
		ON CONFLICT (token_hash) DO NOTHING`,
		newID(), hash[:], now, now)
	if err != nil {
		return "", err
	}
	var id string
	err = s.db.QueryRowContext(ctx, `SELECT id FROM devices WHERE token_hash = ?`, hash[:]).Scan(&id)
	return id, err
}

// DeviceBySecret resolves a bearer secret to a device ID and bumps last_seen_at.
func (s *Store) DeviceBySecret(ctx context.Context, secret []byte) (string, error) {
	hash := sha256.Sum256(secret)
	var id string
	err := s.db.QueryRowContext(ctx, `
		UPDATE devices SET last_seen_at = ? WHERE token_hash = ? RETURNING id`,
		s.now().Unix(), hash[:]).Scan(&id)
	if errors.Is(err, sql.ErrNoRows) {
		return "", ErrNotFound
	}
	return id, err
}

// SetPushToken stores the push token, or clears it when platform is empty.
func (s *Store) SetPushToken(ctx context.Context, deviceID, platform, token string) error {
	var p, t any
	if platform != "" {
		p, t = platform, token
	}
	_, err := s.db.ExecContext(ctx,
		`UPDATE devices SET push_platform = ?, push_token = ? WHERE id = ?`, p, t, deviceID)
	return err
}
