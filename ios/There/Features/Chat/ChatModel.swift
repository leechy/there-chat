import Foundation
import Observation

/// State of one open chat: header, membership, messages and sending.
///
/// Updates come from polling the latest page every few seconds while the chat
/// is on screen. REST stays the source of truth; a WebSocket can replace the
/// polling later without changing the view.
@MainActor
@Observable
final class ChatModel {
    private(set) var chat: Chat
    private(set) var messages: [Message] = []
    private(set) var hasOlder = true
    private(set) var isLoading = false
    var error: String?

    /// Messages typed here that the server hasn't confirmed yet.
    private(set) var outbox: [Message] = []

    let invite: String?
    private let api: APIClient
    private var pollTask: Task<Void, Never>?

    init(chat: Chat, invite: String? = nil, api: APIClient = .shared) {
        self.chat = chat
        self.invite = invite
        self.api = api
    }

    var me: Member? { chat.me }
    var canRead: Bool { chat.isMember || (chat.freeJoin && me?.status != .blocked) }
    var canPost: Bool { chat.isMember && (chat.kind == .chat || chat.isAdmin) }
    var lastSeq: Int64 { messages.last?.seq ?? 0 }

    // MARK: Loading

    func start() {
        guard pollTask == nil else { return }
        pollTask = Task { [weak self] in
            await self?.refreshChat()
            await self?.loadLatest()
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(3))
                guard let self, !Task.isCancelled else { return }
                await self.refreshChat()
                await self.loadLatest()
            }
        }
    }

    func stop() {
        pollTask?.cancel()
        pollTask = nil
    }

    func refreshChat() async {
        do {
            chat = try await api.chat(chat.id, invite: invite)
        } catch let e as APIError where e.code == "not_found" || e.code == "forbidden" {
            error = e.code == "forbidden" ? "You can't access this chat anymore." : "This chat doesn't exist anymore."
            stop()
        } catch {}
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
            await markRead()
        } catch {
            self.error = error.localizedDescription
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
        } catch {
            self.error = error.localizedDescription
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
