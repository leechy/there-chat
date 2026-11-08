package store

import (
	"context"
	"database/sql"
	"errors"
	"strings"
)

type Message struct {
	ID         string     `json:"id"`
	ChatID     string     `json:"chat_id"`
	Seq        int64      `json:"seq"`
	MemberID   string     `json:"member_id,omitempty"`
	AuthorName string     `json:"author_name"`
	AuthorRole string     `json:"author_role,omitempty"` // the author's current role
	Kind       string     `json:"kind"`
	Body       string     `json:"body"`
	Status     string     `json:"status"`
	CreatedAt  int64      `json:"created_at"`
	DeletedAt  *int64     `json:"deleted_at,omitempty"`
	Reactions  []Reaction `json:"reactions,omitempty"`
	// Replayed is set when PostMessage returned an earlier message for a
	// repeated client ID, so nothing new happened.
	Replayed bool `json:"-"`
}

type Reaction struct {
	Emoji string `json:"emoji"`
	Count int    `json:"count"`
	Mine  bool   `json:"mine,omitempty"`
}

// System message bodies. Rename events carry the old name: "renamed:<old>".
const (
	EventJoined    = "joined"
	EventRequested = "requested"
	EventLeft      = "left"
	EventBlocked   = "blocked"
	EventPromoted  = "promoted"
	EventRenamed   = "renamed:"
)

const messageColumns = `msg.id, msg.chat_id, msg.seq, COALESCE(msg.member_id, ''), msg.author_name,
	COALESCE(mb.role, ''), msg.kind, msg.body, msg.status, msg.created_at, msg.deleted_at`

const messageFrom = ` FROM messages msg LEFT JOIN members mb ON mb.id = msg.member_id `

func scanMessage(row interface{ Scan(...any) error }, m *Message) error {
	return row.Scan(&m.ID, &m.ChatID, &m.Seq, &m.MemberID, &m.AuthorName, &m.AuthorRole,
		&m.Kind, &m.Body, &m.Status, &m.CreatedAt, &m.DeletedAt)
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
				`SELECT `+messageColumns+messageFrom+`WHERE msg.member_id = ? AND msg.client_id = ?`,
				author.ID, clientID), &m)
			if err == nil {
				m.Replayed = true
				return nil
			}
			if !errors.Is(err, sql.ErrNoRows) {
				return err
			}
		}
		var err error
		m, err = insertMessage(ctx, tx, chat.ID, author.ID, author.Name, "text", body, status, clientID, s.now().Unix())
		m.AuthorRole = author.Role
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
// viewer and with reactions attached. viewer may be the zero Member.
func (s *Store) Messages(ctx context.Context, chatID string, viewer Member, p Page) ([]Message, error) {
	var rows *sql.Rows
	var err error
	if p.After > 0 {
		rows, err = s.db.QueryContext(ctx, `SELECT `+messageColumns+messageFrom+`
			WHERE msg.chat_id = ? AND msg.seq > ? ORDER BY msg.seq ASC LIMIT ?`, chatID, p.After, p.Limit)
	} else {
		before := p.Before
		if before <= 0 {
			before = 1 << 62
		}
		rows, err = s.db.QueryContext(ctx, `SELECT * FROM (SELECT `+messageColumns+messageFrom+`
			WHERE msg.chat_id = ? AND msg.seq < ? ORDER BY msg.seq DESC LIMIT ?) ORDER BY 3 ASC`, chatID, before, p.Limit)
	}
	if err != nil {
		return nil, err
	}
	out := []Message{}
	for rows.Next() {
		var m Message
		if err := scanMessage(rows, &m); err != nil {
			rows.Close()
			return nil, err
		}
		out = append(out, redact(m, viewer))
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	return out, s.attachReactions(ctx, out, viewer.ID)
}

// Message returns one message redacted for the viewer, with reactions.
func (s *Store) Message(ctx context.Context, id string, viewer Member) (Message, error) {
	var m Message
	err := scanMessage(s.db.QueryRowContext(ctx, `SELECT `+messageColumns+messageFrom+`WHERE msg.id = ?`, id), &m)
	if errors.Is(err, sql.ErrNoRows) {
		return m, ErrNotFound
	}
	if err != nil {
		return m, err
	}
	out := []Message{redact(m, viewer)}
	if err := s.attachReactions(ctx, out, viewer.ID); err != nil {
		return m, err
	}
	return out[0], nil
}

// MessageChatID returns the chat a message belongs to.
func (s *Store) MessageChatID(ctx context.Context, id string) (string, error) {
	var chatID string
	err := s.db.QueryRowContext(ctx, `SELECT chat_id FROM messages WHERE id = ?`, id).Scan(&chatID)
	if errors.Is(err, sql.ErrNoRows) {
		return "", ErrNotFound
	}
	return chatID, err
}

func redact(m Message, viewer Member) Message {
	switch m.Status {
	case "deleted":
		m.Body = ""
	case "rejected":
		if m.MemberID != viewer.ID {
			m.Body = ""
		}
	case "pending":
		if m.MemberID != viewer.ID && !viewer.IsAdmin() {
			m.Body = ""
		}
	}
	return m
}

func (s *Store) attachReactions(ctx context.Context, msgs []Message, viewerID string) error {
	if len(msgs) == 0 {
		return nil
	}
	ids := make([]any, 0, len(msgs)+1)
	ids = append(ids, viewerID)
	index := map[string]int{}
	for i, m := range msgs {
		ids = append(ids, m.ID)
		index[m.ID] = i
	}
	rows, err := s.db.QueryContext(ctx, `
		SELECT message_id, emoji, COUNT(*), SUM(CASE WHEN member_id = ? THEN 1 ELSE 0 END)
		FROM reactions WHERE message_id IN (?`+strings.Repeat(", ?", len(msgs)-1)+`)
		GROUP BY message_id, emoji ORDER BY MIN(created_at)`, ids...)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var id string
		var r Reaction
		var mine int
		if err := rows.Scan(&id, &r.Emoji, &r.Count, &mine); err != nil {
			return err
		}
		r.Mine = mine > 0
		i := index[id]
		msgs[i].Reactions = append(msgs[i].Reactions, r)
	}
	return rows.Err()
}

// SetReaction adds or removes the member's emoji reaction on a message.
func (s *Store) SetReaction(ctx context.Context, messageID string, member Member, emoji string, on bool) error {
	if member.Status != StatusActive {
		return ErrForbidden
	}
	if on {
		_, err := s.db.ExecContext(ctx, `
			INSERT INTO reactions (message_id, member_id, emoji, created_at) VALUES (?, ?, ?, ?)
			ON CONFLICT DO NOTHING`, messageID, member.ID, emoji, s.now().Unix())
		return err
	}
	_, err := s.db.ExecContext(ctx,
		`DELETE FROM reactions WHERE message_id = ? AND member_id = ? AND emoji = ?`, messageID, member.ID, emoji)
	return err
}

// DeleteMessage wipes a message's text and marks it deleted.
func (s *Store) DeleteMessage(ctx context.Context, id string) error {
	_, err := s.db.ExecContext(ctx, `
		UPDATE messages SET status = 'deleted', body = '', deleted_at = ? WHERE id = ? AND kind = 'text'`,
		s.now().Unix(), id)
	return err
}

// DeleteMemberMessages wipes every message a member wrote in the chat.
func (s *Store) DeleteMemberMessages(ctx context.Context, chatID, memberID string) error {
	_, err := s.db.ExecContext(ctx, `
		UPDATE messages SET status = 'deleted', body = '', deleted_at = ?
		WHERE chat_id = ? AND member_id = ? AND kind = 'text' AND status <> 'deleted'`,
		s.now().Unix(), chatID, memberID)
	return err
}

// ModerateMessage publishes or rejects a pending message.
func (s *Store) ModerateMessage(ctx context.Context, id string, approve bool) error {
	status := "rejected"
	if approve {
		status = "published"
	}
	res, err := s.db.ExecContext(ctx, `UPDATE messages SET status = ? WHERE id = ? AND status = 'pending'`, status, id)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrConflict
	}
	return nil
}
