import Combine
import SwiftUI

struct ChatView: View {
    @State private var model: ChatModel
    @State private var draft = ""
    @State private var position = ScrollPosition(edge: .bottom)
    @State private var nameSheet: NameSheet.Purpose?
    @State private var reportTarget: ReportTarget?
    @State private var confirmLeave = false
    @Environment(Router.self) private var router
    @Environment(\.dismiss) private var dismiss

    init(chat: Chat, invite: String? = nil) {
        _model = State(initialValue: ChatModel(chat: chat, invite: invite))
    }

    private var chat: Chat { model.chat }

    var body: some View {
        content
            .background(Color.thereSurfaceMuted)
            .safeAreaInset(edge: .bottom, spacing: 0) { bottomBar }
            .safeAreaInset(edge: .top, spacing: 0) { offlineBanner }
            .animation(.default, value: model.isOffline)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { toolbar }
            .task {
                model.start()
                Push.shared.openChatID = chat.id
                Push.shared.clearDelivered(chatID: chat.id)
            }
            .onDisappear {
                model.stop()
                if Push.shared.openChatID == chat.id { Push.shared.openChatID = nil }
            }
            .sheet(item: $nameSheet) { purpose in
                NameSheet(purpose: purpose, chat: chat) { name in
                    switch purpose {
                    case .join: try await model.join(as: name)
                    case .rename: try await model.rename(to: name)
                    }
                }
            }
            .sheet(item: $reportTarget) { target in
                ReportSheet { reason in
                    await model.report(target.message, reason: reason)
                }
            }
            .confirmationDialog("Leave \(chat.title)?", isPresented: $confirmLeave) {
                Button("Leave", role: .destructive) {
                    Task {
                        try? await model.leave()
                        dismiss()
                    }
                }
            } message: {
                Text("You can come back later, but you'll need to pick a name again.")
            }
            .alert("Something went wrong", isPresented: .init(
                get: { model.error != nil }, set: { if !$0 { model.error = nil } })
            ) {
                Button("OK") {}
            } message: {
                Text(model.error ?? "")
            }
    }

    // MARK: Messages

    @ViewBuilder
    private var content: some View {
        if model.canRead {
            ScrollView {
                LazyVStack(spacing: 4) {
                    if model.hasOlder && !model.messages.isEmpty {
                        ProgressView()
                            .padding()
                            // Retries when the connection comes back.
                            .task(id: model.isOffline) { await model.loadOlder() }
                    }
                    ForEach(timeline) { item in
                        switch item {
                        case let .day(date):
                            Text(date, format: .dateTime.day().month(.abbreviated).year())
                                .font(.caption.weight(.medium))
                                .foregroundStyle(Color.thereInk)
                                .padding(.top, 16)
                                .padding(.bottom, 4)
                        case let .message(message, showAuthor):
                            MessageRow(message: message, model: model, showAuthor: showAuthor) {
                                reportTarget = ReportTarget(message: message)
                            }
                        }
                    }
                }
                .padding(.horizontal)
                .padding(.bottom, 8)
                .scrollTargetLayout()
            }
            .scrollPosition($position)
            .defaultScrollAnchor(.bottom)
            .scrollDismissesKeyboard(.interactively)
            .onChange(of: model.lastSeq) { _, _ in withAnimation { position.scrollTo(edge: .bottom) } }
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardDidShowNotification)) { _ in
                // Keep the latest message visible above the keyboard.
                withAnimation { position.scrollTo(edge: .bottom) }
            }
            .onChange(of: model.outbox.count) { old, new in
                if new > old { withAnimation { position.scrollTo(edge: .bottom) } }
            }
            .overlay {
                if model.messages.isEmpty && model.outbox.isEmpty {
                    ContentUnavailableView("No messages yet", systemImage: "bubble.left.and.bubble.right",
                                           description: Text(model.canPost ? "Say hi!" : "It's quiet here."))
                }
            }
        } else {
            VStack(spacing: 12) {
                Text(chat.emoji).font(.system(size: 56))
                Text(model.me?.status == .pending
                     ? "Your request is waiting for the admins."
                     : "This chat is closed for the public.\nTo join the conversation, you have to request access from the admins first.")
                    .multilineTextAlignment(.center)
                    .foregroundStyle(Color.thereInk)
            }
            .padding(32)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private enum Item: Identifiable {
        case day(Date)
        case message(Message, showAuthor: Bool)

        var id: String {
            switch self {
            case let .day(d): "day-\(d.timeIntervalSince1970)"
            case let .message(m, _): m.id
            }
        }
    }

    /// Messages with day separators; consecutive messages from the same
    /// author only show the name once.
    private var timeline: [Item] {
        var items: [Item] = []
        var lastDay: Date?
        var lastAuthor: String?
        let calendar = Calendar.current
        for message in model.messages + model.outbox {
            let day = calendar.startOfDay(for: message.date)
            if day != lastDay {
                items.append(.day(day))
                lastDay = day
                lastAuthor = nil
            }
            let author = message.kind == .text ? "\(message.memberId ?? "")|\(message.authorName)" : nil
            items.append(.message(message, showAuthor: author != lastAuthor))
            lastAuthor = author
        }
        return items
    }

    @ViewBuilder
    private var offlineBanner: some View {
        if model.isOffline {
            Label("Can't reach the server. Retrying…", systemImage: "wifi.slash")
                .font(.footnote)
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .glassEffect(.regular, in: .capsule)
                .padding(.top, 4)
                .transition(.opacity.combined(with: .move(edge: .top)))
        }
    }

    // MARK: Bottom bar

    @ViewBuilder
    private var bottomBar: some View {
        if model.canPost, let me = model.me {
            Composer(text: $draft, name: me.name, role: me.role) {
                model.send(draft)
                draft = ""
            } rename: {
                nameSheet = .rename
            }
        } else if model.me?.status == .pending {
            Button("Withdraw Request", role: .destructive) {
                Task { try? await model.leave() }
            }
            .buttonStyle(.glass)
            .controlSize(.large)
            .padding()
        } else if !chat.isMember && model.me?.status != .blocked && error == nil {
            Button {
                nameSheet = .join
            } label: {
                Text(chat.freeJoin ? (chat.kind == .channel ? "Follow This Channel" : "Join This Chat")
                                   : "Send a Request to Join")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.glassProminent)
            .controlSize(.large)
            .padding()
        }
    }

    private var error: String? { model.error }

    // MARK: Toolbar

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItem(placement: .principal) {
            Button {
                if chat.isMember { router.open(.chatInfo(chat.id)) }
            } label: {
                HStack(spacing: 8) {
                    Text(chat.emoji).font(.title3)
                    Text(chat.title).font(.headline).foregroundStyle(Color.thereInk).lineLimit(1)
                    if chat.kind == .channel {
                        Image(systemName: "megaphone.fill").font(.caption).foregroundStyle(Color.thereSecondary)
                    }
                }
            }
            .buttonStyle(.plain)
        }
        ToolbarItem(placement: .topBarTrailing) {
            Menu("More", systemImage: "ellipsis") {
                if chat.isMember {
                    Button("Chat Info", systemImage: "info.circle") { router.open(.chatInfo(chat.id)) }
                    Button("Change My Name", systemImage: "person.text.rectangle") { nameSheet = .rename }
                }
                if let url = chat.inviteURL {
                    ShareLink(item: url) { Label("Share Link", systemImage: "square.and.arrow.up") }
                }
                Button("Report Chat", systemImage: "exclamationmark.bubble") {
                    reportTarget = ReportTarget(message: nil)
                }
                if chat.isMember && chat.me?.role != .owner {
                    Divider()
                    Button("Leave Chat", systemImage: "rectangle.portrait.and.arrow.right", role: .destructive) {
                        confirmLeave = true
                    }
                }
            }
        }
    }
}

struct ReportTarget: Identifiable {
    let id = UUID()
    let message: Message?
}

// MARK: - Composer

private struct Composer: View {
    @Binding var text: String
    let name: String
    let role: Member.Role
    let send: () -> Void
    let rename: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Button(action: rename) {
                HStack(spacing: 4) {
                    Text(name).fontWeight(.semibold)
                    if role != .member { Text("(\(role.rawValue))") }
                    Image(systemName: "pencil").imageScale(.small)
                }
                .font(.caption)
                .foregroundStyle(Color.thereSecondary)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("You write as \(name). Change name")
            .padding(.leading, 12)

            HStack(alignment: .bottom, spacing: 8) {
                TextField("Message", text: $text, axis: .vertical)
                    .lineLimit(1...6)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .glassEffect(.regular.interactive(), in: .rect(cornerRadius: 20))
                Button("Send", systemImage: "arrow.up", action: send)
                    .labelStyle(.iconOnly)
                    .font(.headline)
                    .buttonStyle(.glassProminent)
                    .buttonBorderShape(.circle)
                    .controlSize(.large)
                    .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .padding(.horizontal)
        .padding(.vertical, 8)
    }
}
