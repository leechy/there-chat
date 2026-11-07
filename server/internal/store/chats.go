package store

import (
	"context"
	"database/sql"
	"errors"
	"strings"
)

type Chat struct {
	ID           string  `json:"id"`
	Kind         string  `json:"kind"`
	Title        string  `json:"title"`
	Emoji        string  `json:"emoji"`
	Lat          float64 `json:"lat"`
	Lng          float64 `json:"lng"`
	FreeJoin     bool    `json:"free_join"`
	Premoderated bool    `json:"premoderated"`
	Visible      bool    `json:"visible"`
	InviteCode   string  `json:"invite_code,omitempty"` // only shown to admins
	CreatedAt    int64   `json:"created_at"`
}

type NewChat struct {
	Kind         string
	Title        string
	Emoji        string
	Lat, Lng     float64
	FreeJoin     bool
	Premoderated bool
	Visible      bool
}

// BBox is a map area in degrees.
type BBox struct{ West, South, East, North float64 }

const chatColumns = `c.id, c.kind, c.title, c.emoji, c.lat, c.lng, c.free_join, c.premoderated, c.visible, c.invite_code, c.created_at`

func scanChat(row interface{ Scan(...any) error }, c *Chat, extra ...any) error {
	return row.Scan(append([]any{&c.ID, &c.Kind, &c.Title, &c.Emoji, &c.Lat, &c.Lng,
		&c.FreeJoin, &c.Premoderated, &c.Visible, &c.InviteCode, &c.CreatedAt}, extra...)...)
}

// CreateChat creates a chat and makes the device its owner under ownerName.
func (s *Store) CreateChat(ctx context.Context, deviceID string, n NewChat, ownerName string) (Chat, Member, error) {
	now := s.now().Unix()
	c := Chat{
		ID: newID(), Kind: n.Kind, Title: n.Title, Emoji: n.Emoji, Lat: n.Lat, Lng: n.Lng,
		FreeJoin: n.FreeJoin, Premoderated: n.Premoderated, Visible: n.Visible,
		InviteCode: newInviteCode(), CreatedAt: now,
	}
	m := Member{ID: newID(), ChatID: c.ID, Name: ownerName, Role: RoleOwner, Status: StatusActive,
		Notify: true, CreatedAt: now}
	err := s.tx(ctx, func(tx *sql.Tx) error {
		_, err := tx.ExecContext(ctx, `
			INSERT INTO chats (id, kind, title, emoji, lat, lng, free_join, premoderated, visible, invite_code, created_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
			c.ID, c.Kind, c.Title, c.Emoji, c.Lat, c.Lng,
			boolInt(c.FreeJoin), boolInt(c.Premoderated), boolInt(c.Visible), c.InviteCode, now)
		if err != nil {
			return err
		}
		_, err = tx.ExecContext(ctx, `
			INSERT INTO members (id, chat_id, device_id, name, role, status, created_at, updated_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
			m.ID, c.ID, deviceID, m.Name, m.Role, m.Status, now, now)
		return err
	})
	return c, m, err
}

// Chat returns a non-deleted chat by ID.
func (s *Store) Chat(ctx context.Context, id string) (Chat, error) {
	var c Chat
	err := scanChat(s.db.QueryRowContext(ctx,
		`SELECT `+chatColumns+` FROM chats c WHERE c.id = ? AND c.deleted_at IS NULL`, id), &c)
	if errors.Is(err, sql.ErrNoRows) {
		return c, ErrNotFound
	}
	return c, err
}

// ChatByInvite returns a non-deleted chat by its invite code.
func (s *Store) ChatByInvite(ctx context.Context, code string) (Chat, error) {
	var c Chat
	err := scanChat(s.db.QueryRowContext(ctx,
		`SELECT `+chatColumns+` FROM chats c WHERE c.invite_code = ? AND c.deleted_at IS NULL`,
		strings.ToLower(code)), &c)
	if errors.Is(err, sql.ErrNoRows) {
		return c, ErrNotFound
	}
	return c, err
}

// ChatsInBox returns visible chats inside the box, plus hidden ones the device
// is an active member of. Boxes crossing the antimeridian are not supported yet.
func (s *Store) ChatsInBox(ctx context.Context, deviceID string, b BBox, limit int) ([]Chat, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT `+chatColumns+` FROM chats c
		WHERE c.deleted_at IS NULL
		  AND c.lat BETWEEN ? AND ? AND c.lng BETWEEN ? AND ?
		  AND (c.visible = 1 OR EXISTS (
		        SELECT 1 FROM members m
		        WHERE m.chat_id = c.id AND m.device_id = ? AND m.status = 'active'))
		ORDER BY c.created_at
		LIMIT ?`,
		b.South, b.North, b.West, b.East, deviceID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	chats := []Chat{}
	for rows.Next() {
		var c Chat
		if err := scanChat(rows, &c); err != nil {
			return nil, err
		}
		chats = append(chats, c)
	}
	return chats, rows.Err()
}

type MyChat struct {
	Chat
	Me          Member `json:"me"`
	LastSeq     int64  `json:"last_seq"`
	Unread      int64  `json:"unread"`
	MemberCount int64  `json:"member_count"`
}

// MyChats lists chats where the device is an active or pending member,
// most recently active first.
func (s *Store) MyChats(ctx context.Context, deviceID string) ([]MyChat, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT `+chatColumns+`, `+memberColumns+`,
		       COALESCE((SELECT MAX(seq) FROM messages WHERE chat_id = c.id), 0) AS last_seq,
		       (SELECT COUNT(*) FROM messages
		         WHERE chat_id = c.id AND seq > m.last_read_seq AND status = 'published' AND kind = 'text'
		           AND (member_id IS NULL OR member_id <> m.id)) AS unread,
		       (SELECT COUNT(*) FROM members WHERE chat_id = c.id AND status = 'active') AS member_count
		FROM members m JOIN chats c ON c.id = m.chat_id
		WHERE m.device_id = ? AND m.status IN ('active', 'pending') AND c.deleted_at IS NULL
		ORDER BY last_seq DESC, c.created_at DESC`, deviceID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []MyChat{}
	for rows.Next() {
		var mc MyChat
		extra := append(memberDest(&mc.Me), &mc.LastSeq, &mc.Unread, &mc.MemberCount)
		if err := scanChat(rows, &mc.Chat, extra...); err != nil {
			return nil, err
		}
		if mc.Me.Role == RoleMember {
			mc.InviteCode = ""
		}
		out = append(out, mc)
	}
	return out, rows.Err()
}
