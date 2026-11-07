package store

import (
	"context"
	"database/sql"
	"errors"
)

type Message struct {
	ID         string `json:"id"`
	ChatID     string `json:"chat_id"`
	Seq        int64  `json:"seq"`
	MemberID   string `json:"member_id,omitempty"`
	AuthorName string `json:"author_name"`
	Kind       string `json:"kind"`
	Body       string `json:"body"`
	Status     string `json:"status"`
	CreatedAt  int64  `json:"created_at"`
	DeletedAt  *int64 `json:"deleted_at,omitempty"`
}

const messageColumns = `id, chat_id, seq, COALESCE(member_id, ''), author_name, kind, body, status, created_at, deleted_at`

func scanMessage(row interface{ Scan(...any) error }, m *Message) error {
	return row.Scan(&m.ID, &m.ChatID, &m.Seq, &m.MemberID, &m.AuthorName, &m.Kind, &m.Body, &m.Status, &m.CreatedAt, &m.DeletedAt)
}

func insertMessage(ctx context.Context, tx *sql.Tx, chatID, memberID, author, kind, body, status, clientID string, now int64) (Message, error) {
	m := Message{ID: newID(), ChatID: chatID, MemberID: memberID, AuthorName: author,
		Kind: kind, Body: body, Status: status, CreatedAt: now}
	var client any
	if clientID != "" {
		client = clientID
	}
	err := tx.QueryRowContext(ctx, `
		INSERT INTO messages (id, chat_id, seq, member_id, author_name, kind, body, status, client_id, created_at)
		VALUES (?, ?, (SELECT COALESCE(MAX(seq), 0) + 1 FROM messages WHERE chat_id = ?), ?, ?, ?, ?, ?, ?, ?)
		RETURNING seq`,
		m.ID, chatID, chatID, memberID, author, kind, body, status, client, now).Scan(&m.Seq)
	return m, err
}

// PostMessage stores a text message from an active member. In channels only
// admins may post; in premoderated chats non-admin messages start as pending.
// Repeating a request with the same clientID returns the original message.
func (s *Store) PostMessage(ctx context.Context, chat Chat, author Member, body, clientID string) (Message, error) {
	if author.Status != StatusActive {
		return Message{}, ErrForbidden
	}
	if chat.Kind == "channel" && !author.IsAdmin() {
		return Message{}, ErrForbidden
	}
	status := "published"
	if chat.Premoderated && !author.IsAdmin() {
		status = "pending"
	}
	var m Message
	err := s.tx(ctx, func(tx *sql.Tx) error {
		if clientID != "" {
			err := scanMessage(tx.QueryRowContext(ctx,
				`SELECT `+messageColumns+` FROM messages WHERE member_id = ? AND client_id = ?`,
				author.ID, clientID), &m)
			if err == nil {
				return nil
			}
			if !errors.Is(err, sql.ErrNoRows) {
				return err
			}
		}
		var err error
		m, err = insertMessage(ctx, tx, chat.ID, author.ID, author.Name, "text", body, status, clientID, s.now().Unix())
		return err
	})
	return m, err
}

// Page selects messages by seq: After > 0 returns the next messages after it
// (oldest first); otherwise Before (0 = latest) returns earlier ones.
type Page struct {
	Before, After int64
	Limit         int
}

// Messages returns a page of messages in ascending seq order, redacted for the
// viewer: pending messages keep their body only for the author and admins,
// deleted ones lose it for everyone. viewer may be the zero Member.
func (s *Store) Messages(ctx context.Context, chatID string, viewer Member, p Page) ([]Message, error) {
	var rows *sql.Rows
	var err error
	if p.After > 0 {
		rows, err = s.db.QueryContext(ctx, `SELECT `+messageColumns+` FROM messages
			WHERE chat_id = ? AND seq > ? ORDER BY seq ASC LIMIT ?`, chatID, p.After, p.Limit)
	} else {
		before := p.Before
		if before <= 0 {
			before = 1 << 62
		}
		rows, err = s.db.QueryContext(ctx, `SELECT * FROM (SELECT `+messageColumns+` FROM messages
			WHERE chat_id = ? AND seq < ? ORDER BY seq DESC LIMIT ?) ORDER BY seq ASC`, chatID, before, p.Limit)
	}
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Message{}
	for rows.Next() {
		var m Message
		if err := scanMessage(rows, &m); err != nil {
			return nil, err
		}
		switch m.Status {
		case "deleted", "rejected":
			m.Body = ""
		case "pending":
			if m.MemberID != viewer.ID && !viewer.IsAdmin() {
				m.Body = ""
			}
		}
		out = append(out, m)
	}
	return out, rows.Err()
}
