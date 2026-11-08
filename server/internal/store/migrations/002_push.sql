-- Whether push notifications show the message text (a per-device setting).
ALTER TABLE devices ADD COLUMN push_preview INTEGER NOT NULL DEFAULT 1;
CREATE INDEX devices_push_token ON devices (push_token) WHERE push_token IS NOT NULL;
