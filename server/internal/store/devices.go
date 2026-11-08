package store

import (
	"context"
	"crypto/rand"
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

// SetPushToken stores the device's push token and whether notifications
// show message text. A token belongs to one device only: an app that was
// erased and registered again takes its token along.
func (s *Store) SetPushToken(ctx context.Context, deviceID, platform, token string, preview bool) error {
	return s.tx(ctx, func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(ctx,
			`UPDATE devices SET push_platform = NULL, push_token = NULL WHERE push_token = ? AND id <> ?`,
			token, deviceID); err != nil {
			return err
		}
		_, err := tx.ExecContext(ctx,
			`UPDATE devices SET push_platform = ?, push_token = ?, push_preview = ? WHERE id = ?`,
			platform, token, boolInt(preview), deviceID)
		return err
	})
}

// ClearPushToken forgets a device's push token.
func (s *Store) ClearPushToken(ctx context.Context, deviceID string) error {
	_, err := s.db.ExecContext(ctx,
		`UPDATE devices SET push_platform = NULL, push_token = NULL WHERE id = ?`, deviceID)
	return err
}

// DropPushToken forgets a token that Apple or Google reported as no longer valid.
func (s *Store) DropPushToken(ctx context.Context, token string) error {
	_, err := s.db.ExecContext(ctx,
		`UPDATE devices SET push_platform = NULL, push_token = NULL WHERE push_token = ?`, token)
	return err
}

// PushTarget is a device to notify about a new message.
type PushTarget struct {
	Platform, Token string
	Preview         bool
	Badge           int64 // unread messages in all its chats with notifications on
}

// PushTargets lists the devices to notify about a new message in a chat:
// active members with notifications on and a push token, except the author.
func (s *Store) PushTargets(ctx context.Context, chatID, authorMemberID string) ([]PushTarget, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT d.push_platform, d.push_token, d.push_preview,
		       (SELECT COUNT(*) FROM members mm
		          JOIN chats cc ON cc.id = mm.chat_id AND cc.deleted_at IS NULL
		          JOIN messages g ON g.chat_id = mm.chat_id
		         WHERE mm.device_id = d.id AND mm.status = 'active' AND mm.notify = 1
		           AND g.seq > mm.last_read_seq AND g.status = 'published' AND g.kind = 'text'
		           AND (g.member_id IS NULL OR g.member_id <> mm.id)) AS badge
		FROM members m JOIN devices d ON d.id = m.device_id
		WHERE m.chat_id = ? AND m.status = 'active' AND m.notify = 1 AND m.id <> ?
		  AND d.push_token IS NOT NULL`, chatID, authorMemberID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []PushTarget
	for rows.Next() {
		var t PushTarget
		if err := rows.Scan(&t.Platform, &t.Token, &t.Preview, &t.Badge); err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

// Erased tells callers which chats an erased device touched, for realtime events.
type Erased struct {
	DeletedChats []string          // chats the device owned, now deleted
	LeftChats    map[string]string // chat ID -> member ID, for chats it was in
}

// EraseDevice removes everything the device posted and unlinks the device
// row from its secret, so the old key no longer works and the remaining rows
// (kept for foreign keys and abuse reports) point at nobody.
//   - chats it owns are deleted: title, place and all message text wiped;
//   - its text messages are deleted and its system messages removed;
//   - its reactions, and reactions on its messages, are removed;
//   - its memberships lose their name and become "left" (blocks stay);
//   - the device's push token is cleared and its secret hash replaced.
func (s *Store) EraseDevice(ctx context.Context, deviceID string) (Erased, error) {
	out := Erased{LeftChats: map[string]string{}}
	now := s.now().Unix()
	err := s.tx(ctx, func(tx *sql.Tx) error {
		rows, err := tx.QueryContext(ctx, `
			SELECT m.id, m.chat_id, m.role FROM members m JOIN chats c ON c.id = m.chat_id
			WHERE m.device_id = ? AND c.deleted_at IS NULL`, deviceID)
		if err != nil {
			return err
		}
		for rows.Next() {
			var id, chat, role string
			if err := rows.Scan(&id, &chat, &role); err != nil {
				rows.Close()
				return err
			}
			if role == RoleOwner {
				out.DeletedChats = append(out.DeletedChats, chat)
			} else {
				out.LeftChats[chat] = id
			}
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return err
		}

		mine := `SELECT id FROM members WHERE device_id = ?2`
		stmts := []string{
			`UPDATE chats SET deleted_at = COALESCE(deleted_at, ?1), title = '', emoji = '', lat = 0, lng = 0
			 WHERE id IN (SELECT chat_id FROM members WHERE device_id = ?2 AND role = 'owner')`,
			`UPDATE messages SET body = '' WHERE chat_id IN
			 (SELECT chat_id FROM members WHERE device_id = ?2 AND role = 'owner')`,
			`DELETE FROM reactions WHERE member_id IN (` + mine + `)
			 OR message_id IN (SELECT id FROM messages WHERE member_id IN (` + mine + `))`,
			`DELETE FROM messages WHERE kind = 'system' AND member_id IN (` + mine + `)`,
			`UPDATE messages SET status = 'deleted', body = '', author_name = '', deleted_at = COALESCE(deleted_at, ?1)
			 WHERE member_id IN (` + mine + `)`,
			`UPDATE members SET name = '', role = CASE role WHEN 'owner' THEN 'owner' ELSE 'member' END,
			 status = CASE status WHEN 'blocked' THEN 'blocked' ELSE 'left' END, updated_at = ?1
			 WHERE device_id = ?2`,
		}
		for _, q := range stmts {
			if _, err := tx.ExecContext(ctx, q, now, deviceID); err != nil {
				return err
			}
		}
		dead := make([]byte, 32)
		rand.Read(dead)
		_, err = tx.ExecContext(ctx, `
			UPDATE devices SET token_hash = ?, push_platform = NULL, push_token = NULL WHERE id = ?`,
			dead, deviceID)
		return err
	})
	return out, err
}
