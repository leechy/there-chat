package store

import (
	"context"
	"database/sql"
	"errors"
)

// MemberByID returns a member of the given chat.
func (s *Store) MemberByID(ctx context.Context, chatID, memberID string) (Member, error) {
	var m Member
	err := s.db.QueryRowContext(ctx,
		`SELECT `+memberColumns+` FROM members m WHERE m.chat_id = ? AND m.id = ?`,
		chatID, memberID).Scan(memberDest(&m)...)
	if errors.Is(err, sql.ErrNoRows) {
		return m, ErrNotFound
	}
	return m, err
}

// Members lists chat members, optionally filtered by status; owners and
// admins first, then by join time.
func (s *Store) Members(ctx context.Context, chatID, status string) ([]Member, error) {
	q := `SELECT ` + memberColumns + ` FROM members m WHERE m.chat_id = ?`
	args := []any{chatID}
	if status != "" {
		q += ` AND m.status = ?`
		args = append(args, status)
	}
	q += ` ORDER BY CASE m.role WHEN 'owner' THEN 0 WHEN 'admin' THEN 1 ELSE 2 END,
	              CASE m.status WHEN 'pending' THEN 0 WHEN 'active' THEN 1 ELSE 2 END, m.created_at`
	rows, err := s.db.QueryContext(ctx, q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Member{}
	for rows.Next() {
		var m Member
		if err := rows.Scan(memberDest(&m)...); err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, rows.Err()
}

// MeUpdate holds optional changes to the caller's own membership.
type MeUpdate struct {
	Name        *string
	Notify      *bool
	LastReadSeq *int64
}

// UpdateMe renames the member, toggles notifications or moves the read
// marker forward. Renaming leaves old messages with the old name and posts a
// "renamed:<old name>" system message.
func (s *Store) UpdateMe(ctx context.Context, me Member, u MeUpdate) (Member, error) {
	now := s.now().Unix()
	err := s.tx(ctx, func(tx *sql.Tx) error {
		if u.Name != nil && *u.Name != me.Name {
			if me.Status != StatusActive && me.Status != StatusPending {
				return ErrForbidden
			}
			if err := nameFree(ctx, tx, me.ChatID, *u.Name, me.ID); err != nil {
				return err
			}
			if _, err := tx.ExecContext(ctx, `UPDATE members SET name = ?, updated_at = ? WHERE id = ?`,
				*u.Name, now, me.ID); err != nil {
				return err
			}
			if _, err := insertMessage(ctx, tx, me.ChatID, me.ID, *u.Name, "system",
				EventRenamed+me.Name, "published", "", now); err != nil {
				return err
			}
			me.Name = *u.Name
		}
		if u.Notify != nil {
			if _, err := tx.ExecContext(ctx, `UPDATE members SET notify = ? WHERE id = ?`, boolInt(*u.Notify), me.ID); err != nil {
				return err
			}
			me.Notify = *u.Notify
		}
		if u.LastReadSeq != nil && *u.LastReadSeq > me.LastReadSeq {
			if _, err := tx.ExecContext(ctx, `
				UPDATE members SET last_read_seq = MIN(?, (SELECT COALESCE(MAX(seq), 0) FROM messages WHERE chat_id = ?))
				WHERE id = ?`, *u.LastReadSeq, me.ChatID, me.ID); err != nil {
				return err
			}
			if err := tx.QueryRowContext(ctx, `SELECT last_read_seq FROM members WHERE id = ?`, me.ID).Scan(&me.LastReadSeq); err != nil {
				return err
			}
		}
		return nil
	})
	return me, err
}

// SetMemberStatus changes a member's status and posts event as a system
// message, unless event is empty.
func (s *Store) SetMemberStatus(ctx context.Context, m Member, status, event string) (Member, error) {
	now := s.now().Unix()
	err := s.tx(ctx, func(tx *sql.Tx) error {
		role := m.Role
		if status != StatusActive {
			role = RoleMember // leaving or being blocked drops admin rights
		}
		if _, err := tx.ExecContext(ctx, `UPDATE members SET status = ?, role = ?, updated_at = ? WHERE id = ?`,
			status, role, now, m.ID); err != nil {
			return err
		}
		m.Status, m.Role, m.UpdatedAt = status, role, now
		if event == "" {
			return nil
		}
		_, err := insertMessage(ctx, tx, m.ChatID, m.ID, m.Name, "system", event, "published", "", now)
		return err
	})
	return m, err
}

// SetMemberRole promotes a member to admin or demotes them back.
func (s *Store) SetMemberRole(ctx context.Context, m Member, role string) (Member, error) {
	now := s.now().Unix()
	err := s.tx(ctx, func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(ctx, `UPDATE members SET role = ?, updated_at = ? WHERE id = ?`, role, now, m.ID); err != nil {
			return err
		}
		m.Role, m.UpdatedAt = role, now
		if role != RoleAdmin {
			return nil
		}
		_, err := insertMessage(ctx, tx, m.ChatID, m.ID, m.Name, "system", EventPromoted, "published", "", now)
		return err
	})
	return m, err
}

// ChatUpdate holds optional changes to chat settings.
type ChatUpdate struct {
	Title        *string
	Emoji        *string
	FreeJoin     *bool
	Premoderated *bool
	Visible      *bool
}

// UpdateChat applies settings changes and returns the updated chat.
func (s *Store) UpdateChat(ctx context.Context, id string, u ChatUpdate) (Chat, error) {
	sets := []string{}
	args := []any{}
	add := func(col string, v any) { sets = append(sets, col+" = ?"); args = append(args, v) }
	if u.Title != nil {
		add("title", *u.Title)
	}
	if u.Emoji != nil {
		add("emoji", *u.Emoji)
	}
	if u.FreeJoin != nil {
		add("free_join", boolInt(*u.FreeJoin))
	}
	if u.Premoderated != nil {
		add("premoderated", boolInt(*u.Premoderated))
	}
	if u.Visible != nil {
		add("visible", boolInt(*u.Visible))
	}
	if len(sets) > 0 {
		q := "UPDATE chats SET "
		for i, set := range sets {
			if i > 0 {
				q += ", "
			}
			q += set
		}
		if _, err := s.db.ExecContext(ctx, q+" WHERE id = ?", append(args, id)...); err != nil {
			return Chat{}, err
		}
	}
	return s.Chat(ctx, id)
}

// DeleteChat hides the chat everywhere and wipes the text of its messages.
func (s *Store) DeleteChat(ctx context.Context, id string) error {
	return s.tx(ctx, func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(ctx, `UPDATE chats SET deleted_at = ? WHERE id = ?`, s.now().Unix(), id); err != nil {
			return err
		}
		_, err := tx.ExecContext(ctx, `UPDATE messages SET body = '' WHERE chat_id = ?`, id)
		return err
	})
}

// Report stores an abuse report for the operators.
func (s *Store) Report(ctx context.Context, deviceID, chatID, messageID, reason string) error {
	var msg any
	if messageID != "" {
		msg = messageID
	}
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO reports (id, device_id, chat_id, message_id, reason, created_at) VALUES (?, ?, ?, ?, ?, ?)`,
		newID(), deviceID, chatID, msg, reason, s.now().Unix())
	return err
}
