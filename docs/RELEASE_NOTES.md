# Release notes

Newest first. The block under each version is ready to paste into
Play Console / App Store Connect ("What's new", 500 characters max).

## 0.2.0 — Android versionCode 2 (8 Nov 2026)

```
<en-US>
• Notifications: get notified about new messages in your chats. Tap one to jump straight into the chat. Mute a single chat in its Chat Info, or hide message text in Settings.
• While you scroll back through older messages, new ones no longer pull you down: a "new messages" button shows how many arrived.
• Settings now show whether the server is reachable.
• New "Child safety" reason when reporting a message.
</en-US>
```

Details:

- Push notifications through Firebase Cloud Messaging (Android) and APNs (iOS).
  Permission is asked the first time you join or create a chat. One
  notification per chat with the last few lines; nothing is shown for the
  chat you have open.
- "↓ N new messages" button in chats instead of jumping to the bottom.
- Server status row in Settings; debug builds can switch between the local
  and production servers.
- Server: rate limiting per IP and per device.

## 0.1.0 — Android versionCode 1

First internal test build: map of chats, creating and joining chats, names
per chat, reactions, reports, moderation, Erase My Data.
