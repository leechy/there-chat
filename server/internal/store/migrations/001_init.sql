CREATE TABLE devices (
    id            TEXT PRIMARY KEY,
    token_hash    BLOB NOT NULL UNIQUE,
    push_platform TEXT,
    push_token    TEXT,
    created_at    INTEGER NOT NULL,
    last_seen_at  INTEGER NOT NULL
);

CREATE TABLE chats (
    id           TEXT PRIMARY KEY,
    kind         TEXT NOT NULL CHECK (kind IN ('chat', 'channel')),
    title        TEXT NOT NULL,
    emoji        TEXT NOT NULL,
    lat          REAL NOT NULL,
    lng          REAL NOT NULL,
    free_join    INTEGER NOT NULL,
    premoderated INTEGER NOT NULL,
    visible      INTEGER NOT NULL,
    invite_code  TEXT NOT NULL UNIQUE,
    created_at   INTEGER NOT NULL,
    deleted_at   INTEGER
);
CREATE INDEX chats_location ON chats (lat, lng) WHERE deleted_at IS NULL;

CREATE TABLE members (
    id            TEXT PRIMARY KEY,
    chat_id       TEXT NOT NULL REFERENCES chats (id),
    device_id     TEXT NOT NULL REFERENCES devices (id),
    name          TEXT NOT NULL,
    role          TEXT NOT NULL CHECK (role IN ('owner', 'admin', 'member')),
    status        TEXT NOT NULL CHECK (status IN ('pending', 'active', 'left', 'blocked')),
    notify        INTEGER NOT NULL DEFAULT 1,
    last_read_seq INTEGER NOT NULL DEFAULT 0,
    created_at    INTEGER NOT NULL,
    updated_at    INTEGER NOT NULL,
    UNIQUE (chat_id, device_id)
);
CREATE INDEX members_device ON members (device_id);

CREATE TABLE messages (
    id          TEXT PRIMARY KEY,
    chat_id     TEXT NOT NULL REFERENCES chats (id),
    seq         INTEGER NOT NULL,
    member_id   TEXT REFERENCES members (id),
    author_name TEXT NOT NULL,
    kind        TEXT NOT NULL CHECK (kind IN ('text', 'system')),
    body        TEXT NOT NULL,
    status      TEXT NOT NULL CHECK (status IN ('pending', 'published', 'rejected', 'deleted')),
    client_id   TEXT,
    created_at  INTEGER NOT NULL,
    deleted_at  INTEGER,
    UNIQUE (chat_id, seq),
    UNIQUE (member_id, client_id)
);

CREATE TABLE reactions (
    message_id TEXT NOT NULL REFERENCES messages (id),
    member_id  TEXT NOT NULL REFERENCES members (id),
    emoji      TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (message_id, member_id, emoji)
);

CREATE TABLE reports (
    id         TEXT PRIMARY KEY,
    device_id  TEXT NOT NULL REFERENCES devices (id),
    chat_id    TEXT REFERENCES chats (id),
    message_id TEXT REFERENCES messages (id),
    reason     TEXT NOT NULL,
    created_at INTEGER NOT NULL
);
