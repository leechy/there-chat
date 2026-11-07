package api

import (
	"context"
	"encoding/json"
	"net/http"
	"sync"
	"time"

	"github.com/coder/websocket"
)

// Realtime: GET /v1/ws keeps one WebSocket per device and pushes small
// events ("something changed in chat X") for every chat where the device is
// an active member. Events carry no message text: clients refetch through
// REST, which stays the source of truth and applies all visibility rules.

// Event types sent over the socket.
const (
	EvHello          = "hello" // first frame after the socket is accepted
	EvMessage        = "message"
	EvReaction       = "reaction"
	EvMessageDeleted = "message_deleted"
	EvModerated      = "moderated"
	EvMember         = "member"
	EvChatUpdated    = "chat_updated"
	EvChatDeleted    = "chat_deleted"
)

// Member event actions.
const (
	MemberJoined    = "joined"
	MemberPending   = "pending"
	MemberLeft      = "left"
	MemberRejected  = "rejected"
	MemberBlocked   = "blocked"
	MemberUnblocked = "unblocked"
	MemberPromoted  = "promoted"
	MemberDemoted   = "demoted"
	MemberRenamed   = "renamed"
)

type Event struct {
	Type      string `json:"type"`
	ChatID    string `json:"chat_id,omitempty"`
	Seq       int64  `json:"seq,omitempty"`
	MessageID string `json:"message_id,omitempty"`
	MemberID  string `json:"member_id,omitempty"`
	Action    string `json:"action,omitempty"` // member events: joined, left, ...; moderated: approved, rejected
}

const (
	defaultPingInterval = 25 * time.Second
	writeTimeout        = 10 * time.Second
	// sendBuffer is how many events may queue for one connection. A client
	// that falls this far behind is disconnected and resyncs over REST.
	sendBuffer = 64
)

type wsConn struct {
	send chan []byte
	kill context.CancelFunc
}

type hub struct {
	mu    sync.Mutex
	conns map[string]*wsConn // by device ID
}

func newHub() *hub { return &hub{conns: map[string]*wsConn{}} }

// register makes c the device's connection, closing any older one.
func (h *hub) register(device string, c *wsConn) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if old := h.conns[device]; old != nil {
		old.kill()
	}
	h.conns[device] = c
}

func (h *hub) unregister(device string, c *wsConn) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.conns[device] == c {
		delete(h.conns, device)
	}
}

func (h *hub) empty() bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	return len(h.conns) == 0
}

// Online reports whether the device has a live socket (used to skip pushes).
func (h *hub) online(device string) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.conns[device] != nil
}

// deliver queues msg for each device without blocking; slow clients are dropped.
func (h *hub) deliver(devices []string, msg []byte) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for _, d := range devices {
		c := h.conns[d]
		if c == nil {
			continue
		}
		select {
		case c.send <- msg:
		default:
			c.kill()
			delete(h.conns, d)
		}
	}
}

func (h *hub) closeAll() {
	h.mu.Lock()
	defer h.mu.Unlock()
	for d, c := range h.conns {
		c.kill()
		delete(h.conns, d)
	}
}

// Close disconnects all WebSocket clients; call it on server shutdown.
func (s *Server) Close() { s.hub.closeAll() }

// publish fans ev out to the chat's active members. If privateTo is set (a
// premoderated pending message), only that member and the admins hear it.
func (s *Server) publish(ctx context.Context, ev Event, privateTo string) {
	if s.hub.empty() {
		return
	}
	ctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
	defer cancel()
	listeners, err := s.store.Listeners(ctx, ev.ChatID)
	if err != nil {
		s.log.Error("realtime: listeners", "chat", ev.ChatID, "err", err)
		return
	}
	devices := make([]string, 0, len(listeners))
	for _, l := range listeners {
		if privateTo == "" || l.Admin || l.MemberID == privateTo {
			devices = append(devices, l.DeviceID)
		}
	}
	msg, _ := json.Marshal(ev)
	s.hub.deliver(devices, msg)
}

func (s *Server) websocket(w http.ResponseWriter, r *http.Request) {
	c, err := websocket.Accept(w, r, nil)
	if err != nil {
		return // Accept has already replied
	}
	defer c.CloseNow()
	device := deviceID(r)

	// Clients send nothing; CloseRead discards input, answers pings and
	// cancels ctx once the peer goes away.
	ctx := c.CloseRead(r.Context())
	ctx, kill := context.WithCancel(ctx)
	defer kill()
	conn := &wsConn{send: make(chan []byte, sendBuffer), kill: kill}
	s.hub.register(device, conn)
	defer s.hub.unregister(device, conn)

	write := func(msg []byte) error {
		wctx, cancel := context.WithTimeout(ctx, writeTimeout)
		defer cancel()
		return c.Write(wctx, websocket.MessageText, msg)
	}
	hello, _ := json.Marshal(Event{Type: EvHello})
	if write(hello) != nil {
		return
	}

	ping := time.NewTicker(s.cfg.PingInterval)
	defer ping.Stop()
	for {
		select {
		case <-ctx.Done():
			c.Close(websocket.StatusGoingAway, "")
			return
		case msg := <-conn.send:
			if write(msg) != nil {
				return
			}
		case <-ping.C:
			pctx, cancel := context.WithTimeout(ctx, writeTimeout)
			err := c.Ping(pctx)
			cancel()
			if err != nil {
				return
			}
		}
	}
}
