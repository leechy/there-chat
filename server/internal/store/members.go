package store

import (
	"context"
	"database/sql"
	"errors"
	"strings"
)

const (
	RoleOwner  = "owner"
	RoleAdmin  = "admin"
	RoleMember = "member"

	StatusPending = "pending"
	StatusActive  = "active"
	StatusLeft    = "left"
	StatusBlocked = "blocked"
)

var ErrNameTaken = errors.New("name taken")

type Member struct {
	ID          string `json:"id"`
	ChatID      string `json:"chat_id"`
	Name        string `json:"name"`
	Role        string `json:"role"`
	Status      string `json:"status"`
	Notify      bool   `json:"notify"`
	LastReadSeq int64  `json:"last_read_seq"`
	CreatedAt   int64  `json:"created_at"`
	UpdatedAt   int64  `json:"updated_at"`
}

func (m Member) IsAdmin() bool {
	return m.Status == StatusActive && (m.Role == RoleOwner || m.Role == RoleAdmin)
}

const memberColumns = `m.id, m.chat_id, m.name, m.role, m.status, m.notify, m.last_read_seq, m.created_at, m.updated_at`

func memberDest(m *Member) []any {
	return []any{&m.ID, &m.ChatID, &m.Name, &m.Role, &m.Status, &m.Notify, &m.LastReadSeq, &m.CreatedAt, &m.UpdatedAt}
}

// MemberOf returns the device's membership in a chat, in any status.
func (s *Store) MemberOf(ctx context.Context, chatID, deviceID string) (Member, error) {
	var m Member
	err := s.db.QueryRowContext(ctx,
		`SELECT `+memberColumns+` FROM members m WHERE m.chat_id = ? AND m.device_id = ?`,
		chatID, deviceID).Scan(memberDest(&m)...)
	if errors.Is(err, sql.ErrNoRows) {
		return m, ErrNotFound
	}
	return m, err
}

// Join adds the device to the chat as an active member, or as pending when the
// chat requires approval. Joining again after leaving is allowed; a blocked
// device gets ErrForbidden. A system message records the event.
func (s *Store) Join(ctx context.Context, chat Chat, deviceID, name string) (Member, error) {
	now := s.now().Unix()
	status := StatusActive
	if !chat.FreeJoin {
		status = StatusPending
	}
	var m Member
	err := s.tx(ctx, func(tx *sql.Tx) error {
		err := tx.QueryRowContext(ctx,
			`SELECT `+memberColumns+` FROM members m WHERE m.chat_id = ? AND m.device_id = ?`,
			chat.ID, deviceID).Scan(memberDest(&m)...)
		switch {
		case err == nil:
			if m.Status == StatusBlocked {
				return ErrForbidden
			}
			if m.Status == StatusActive || m.Status == StatusPending {
				return nil // already in; nothing to do
			}
		case !errors.Is(err, sql.ErrNoRows):
			return err
		}
		if err := nameFree(ctx, tx, chat.ID, name, m.ID); err != nil {
			return err
		}
		if m.ID == "" {
			m = Member{ID: newID(), ChatID: chat.ID, Role: RoleMember, Notify: true, CreatedAt: now}
			_, err = tx.ExecContext(ctx, `
				INSERT INTO members (id, chat_id, device_id, name, role, status, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
				m.ID, chat.ID, deviceID, name, m.Role, status, now, now)
		} else {
			_, err = tx.ExecContext(ctx,
				`UPDATE members SET name = ?, status = ?, role = ?, updated_at = ? WHERE id = ?`,
				name, status, RoleMember, now, m.ID)
			m.Role = RoleMember
		}
		if err != nil {
			return err
		}
		m.Name, m.Status, m.UpdatedAt = name, status, now
		event := "joined"
		if status == StatusPending {
			event = "requested"
		}
		_, err = insertMessage(ctx, tx, chat.ID, m.ID, name, "system", event, "published", "", now)
		return err
	})
	return m, err
}

// nameFree checks that no other active or pending member uses the name
// (case-insensitive). exceptMemberID allows a member to keep their own name.
func nameFree(ctx context.Context, tx *sql.Tx, chatID, name, exceptMemberID string) error {
	var n int
	err := tx.QueryRowContext(ctx, `
		SELECT COUNT(*) FROM members
		WHERE chat_id = ? AND LOWER(name) = ? AND status IN ('active', 'pending') AND id <> ?`,
		chatID, strings.ToLower(name), exceptMemberID).Scan(&n)
	if err != nil {
		return err
	}
	if n > 0 {
		return ErrNameTaken
	}
	return nil
}
