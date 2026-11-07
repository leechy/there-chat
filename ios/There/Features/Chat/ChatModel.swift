import Foundation
import Observation

/// State of one open chat: header, membership, messages and sending.
///
/// Realtime events (`Realtime`) nudge it to refetch through REST, which stays
/// the source of truth. When the socket is down, or the chat is only being
/// read without membership (no events for those), it polls every 3 seconds.
@MainActor
@Observable
final class ChatModel {
    private(set) var chat: Chat
    private(set) var messages: [Message] = []
    private(set) var hasOlder = true
    private(set) var isLoading = false
    /// Errors from things the user did (send, react, delete…), shown as an alert.
    var error: String?
    /// True while background refreshes can't reach the server. Shown as a
    /// banner; what's already loaded stays readable and polling keeps retrying.
    private(set) var isOffline = false

    /// Messages typed here that the server hasn't confirmed yet.
    private(set) var outbox: [Message] = []

    let invite: String?
    private let api: APIClient
    private var pollTask: Task<Void, Never>?
    private var eventsTask: Task<Void, Never>?
    private let realtime: Realtime
    private var lastRefresh = Date.distantPast

    /// What an event asks to refetch; several events coalesce into one pass.
    private struct Refetch: OptionSet {
        let rawValue: Int
        static let chat = Refetch(rawValue: 1)   // header, settings, my membership
        static let newer = Refetch(rawValue: 2)  // messages after lastSeq
        static let latest = Refetch(rawValue: 4) // newest page, picks up edits
        static let all: Refetch = [.chat, .latest]
    }
    private var wanted: Refetch = []
    private var refetching = false

    init(chat: Chat, invite: String? = nil, api: APIClient = .shared, realtime: Realtime = .shared) {
        self.chat = chat
        self.invite = invite
        self.api = api
        self.realtime = realtime
    }

    var me: Member? { chat.me }
    var canRead: Bool { chat.isMember || (chat.freeJoin && me?.status != .blocked) }
    var canPost: Bool { chat.isMember && (chat.kind == .chat || chat.isAdmin) }
    var lastSeq: Int64 { messages.last?.seq ?? 0 }

    // MARK: Loading

    /// True when events for this chat arrive over the socket, so polling can rest.
    private var isLive: Bool {
        realtime.isConnected && (chat.isMember || me?.status == .pending)
    }

    func start() {
        guard pollTask == nil else { return }
        eventsTask = Task { [weak self, realtime] in
            for await event in realtime.events() {
                self?.handle(event)
            }
        }
        pollTask = Task { [weak self] in
            self?.refetch(.all)
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(3))
                guard let self, !Task.isCancelled else { return }
                // Fallback polling; a slow safety net even while live.
                if !self.isLive || self.lastRefresh.timeIntervalSinceNow < -60 {
                    self.refetch(.all)
                }
            }
        }
    }

    func stop() {
        pollTask?.cancel()
        pollTask = nil
        eventsTask?.cancel()
        eventsTask = nil
    }

    private func handle(_ event: RealtimeEvent) {
        if event.type == RealtimeEvent.resync.type {
            refetch(.all)
            return
        }
        guard event.chatId == chat.id else { return }
        switch event.type {
        case "message":
            if (event.seq ?? .max) > lastSeq { refetch(.newer) }
        case "reaction", "message_deleted", "moderated":
            refetch(.latest)
        default: // member, chat_updated, chat_deleted
            refetch(.all)
        }
    }

    /// Runs the requested refetches, merging requests that arrive meanwhile.
    private func refetch(_ what: Refetch) {
        wanted.formUnion(what)
        guard !refetching else { return }
        refetching = true
        Task {
            while !wanted.isEmpty {
                let now = wanted
                wanted = []
                if now.contains(.chat) { await refreshChat() }
                if now.contains(.latest) || lastSeq == 0 {
                    await loadLatest()
                } else if now.contains(.newer) {
                    await loadNewer()
                }
            }
            lastRefresh = .now
            refetching = false
        }
    }

    func refreshChat() async {
        do {
            chat = try await api.chat(chat.id, invite: invite)
            isOffline = false
        } catch let e as APIError where e.code == "not_found" || e.code == "forbidden" {
            error = e.code == "forbidden" ? "You can't access this chat anymore." : "This chat doesn't exist anymore."
            stop()
        } catch {
            isOffline = true
        }
    }

    /// Fetches the newest page and merges it, which also picks up edits to
    /// recent messages (reactions, deletions, moderation).
    func loadLatest() async {
        guard canRead else { return }
        do {
            let latest = try await api.messages(chat.id, limit: 50, invite: invite)
            if let first = latest.first, first.seq > lastSeq + 1, !messages.isEmpty {
                // We fell behind by more than a page: fetch the gap.
                let gap = try await api.messages(chat.id, after: lastSeq, limit: 100, invite: invite)
                merge(gap)
            }
            if messages.isEmpty { hasOlder = latest.count == 50 }
            merge(latest)
            isOffline = false
            await markRead()
        } catch {
            isOffline = true
        }
    }

    /// Fetches everything after the last known message.
    func loadNewer() async {
        guard canRead else { return }
        do {
            var page: [Message]
            repeat {
                page = try await api.messages(chat.id, after: lastSeq, limit: 100, invite: invite)
                merge(page)
            } while page.count == 100
            isOffline = false
            await markRead()
        } catch {
            isOffline = true
        }
    }

    func loadOlder() async {
        guard hasOlder, !isLoading, let first = messages.first else { return }
        isLoading = true
        defer { isLoading = false }
        do {
            let older = try await api.messages(chat.id, before: first.seq, limit: 50, invite: invite)
            hasOlder = older.count == 50
            merge(older)
            isOffline = false
        } catch {
            isOffline = true
        }
    }

    private func merge(_ incoming: [Message]) {
        guard !incoming.isEmpty else { return }
        var byID = Dictionary(uniqueKeysWithValues: messages.map { ($0.id, $0) })
        for m in incoming { byID[m.id] = m }
        messages = byID.values.sorted { $0.seq < $1.seq }
    }

    private func markRead() async {
        guard let me, chat.isMember, lastSeq > me.lastReadSeq else { return }
        if let updated = try? await api.updateMe(chat.id, .init(lastReadSeq: lastSeq)) {
            chat.me = updated.me
        }
    }

    // MARK: Membership

    func join(as name: String) async throws {
        chat = try await api.join(chat.id, name: name, invite: invite)
        await loadLatest()
    }

    func leave() async throws {
        chat = try await api.leave(chat.id)
    }

    func rename(to name: String) async throws {
        chat = try await api.updateMe(chat.id, .init(name: name))
        await loadLatest()
    }

    // MARK: Messages

    func send(_ text: String) {
        let body = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !body.isEmpty, let me else { return }
        let clientID = UUID().uuidString
        let draft = Message(id: "local-\(clientID)", chatId: chat.id, seq: Int64.max, memberId: me.id,
                            authorName: me.name, authorRole: me.role, kind: .text, body: body,
                            status: .pending, createdAt: Int64(Date().timeIntervalSince1970))
        outbox.append(draft)
        Task {
            // Retries reuse the client ID, so the server never stores duplicates.
            for attempt in 0..<5 {
                do {
                    let sent = try await api.post(chat.id, body: body, clientID: clientID)
                    outbox.removeAll { $0.id == draft.id }
                    merge([sent])
                    return
                } catch let e as APIError {
                    outbox.removeAll { $0.id == draft.id }
                    error = e.message
                    return
                } catch {
                    try? await Task.sleep(for: .seconds(1 << attempt))
                }
            }
            outbox.removeAll { $0.id == draft.id }
            error = "Couldn't send the message. Check your connection."
        }
    }

    func toggle(_ emoji: String, on message: Message) async {
        let mine = message.reactions?.contains { $0.emoji == emoji && $0.mine } ?? false
        do {
            merge([try await api.react(message.id, emoji: emoji, on: !mine)])
        } catch {
            self.error = error.localizedDescription
        }
    }

    func delete(_ message: Message) async {
        do {
            try await api.deleteMessage(message.id)
            await loadLatest()
        } catch {
            self.error = error.localizedDescription
        }
    }

    func moderate(_ message: Message, approve: Bool) async {
        do {
            merge([try await api.moderate(message.id, approve: approve)])
        } catch {
            self.error = error.localizedDescription
        }
    }

    func report(_ message: Message?, reason: String) async {
        do {
            try await api.report(chatID: chat.id, messageID: message?.id, reason: reason)
        } catch {
            self.error = error.localizedDescription
        }
    }

    func canDelete(_ message: Message) -> Bool {
        message.kind == .text && message.status != .deleted
            && (chat.isAdmin || (chat.isMember && message.memberId == me?.id))
    }
}
