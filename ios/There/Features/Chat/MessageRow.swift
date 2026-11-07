import SwiftUI

/// One entry in the chat: a text bubble or a centered system event.
struct MessageRow: View {
    let message: Message
    let model: ChatModel
    let showAuthor: Bool
    let report: () -> Void

    @State private var showActions = false

    private var isMine: Bool { message.memberId != nil && message.memberId == model.me?.id }
    private var isLocal: Bool { message.id.hasPrefix("local-") }

    var body: some View {
        if message.kind == .system {
            SystemEventRow(message: message)
        } else {
            VStack(alignment: isMine ? .trailing : .leading, spacing: 2) {
                if showAuthor {
                    HStack(spacing: 4) {
                        Text(message.authorName).fontWeight(.semibold)
                        if let role = message.authorRole, role != .member {
                            Text("(\(role.rawValue))")
                        }
                    }
                    .font(.caption)
                    .foregroundStyle(Color.thereSecondary)
                    .padding(.horizontal, 12)
                    .padding(.top, 8)
                }
                bubble
                    .onTapGesture { if !isLocal && message.status != .deleted { showActions = true } }
                    .popover(isPresented: $showActions) {
                        MessageActions(message: message, model: model, isMine: isMine) {
                            showActions = false
                        } report: {
                            showActions = false
                            report()
                        }
                        .presentationCompactAdaptation(.popover)
                    }
                if let reactions = message.reactions, !reactions.isEmpty {
                    ReactionChips(reactions: reactions, enabled: model.chat.isMember) { emoji in
                        Task { await model.toggle(emoji, on: message) }
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: isMine ? .trailing : .leading)
        }
    }

    @ViewBuilder
    private var bubble: some View {
        let shape = RoundedRectangle(cornerRadius: 14)
        VStack(alignment: .leading, spacing: 2) {
            Group {
                switch message.status {
                case .deleted:
                    Text("Message deleted")
                        .italic()
                        .foregroundStyle(Color.thereSecondary)
                case .rejected where message.body.isEmpty:
                    Text("Message rejected by the admins").italic().foregroundStyle(Color.thereSecondary)
                case .pending where message.body.isEmpty:
                    Label("Waiting for moderation", systemImage: "hourglass")
                        .italic()
                        .foregroundStyle(Color.thereSecondary)
                default:
                    Text(message.body)
                        .foregroundStyle(message.status == .published ? Color.thereInk : Color.thereSecondary)
                        .textSelection(.enabled)
                }
            }
            .font(.body)

            HStack(spacing: 4) {
                if message.status == .pending && !isLocal {
                    Image(systemName: "hourglass")
                    Text("pending")
                } else if message.status == .rejected {
                    Image(systemName: "xmark.circle")
                    Text("rejected")
                }
                if isLocal {
                    Image(systemName: "clock")
                } else {
                    Text(message.status == .deleted ? message.deletedDate ?? message.date : message.date,
                         format: .dateTime.hour().minute())
                }
            }
            .font(.caption2)
            .foregroundStyle(Color.thereSecondary)
            .frame(maxWidth: .infinity, alignment: .trailing)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(message.status == .deleted ? Color.clear : Color(.systemBackground), in: shape)
        .overlay(shape.stroke(Color.thereSecondary.opacity(message.status == .deleted ? 0.4 : 0), lineWidth: 1))
        .opacity(isLocal ? 0.6 : 1)
        .frame(maxWidth: 300, alignment: isMine ? .trailing : .leading)
        .fixedSize(horizontal: false, vertical: true)
        .contentShape(shape)
    }
}

// MARK: - System events

private struct SystemEventRow: View {
    let message: Message

    var body: some View {
        VStack(spacing: 2) {
            Text(message.date, format: .dateTime.hour().minute())
                .foregroundStyle(Color.thereSecondary)
            text.foregroundStyle(Color.thereInk)
        }
        .font(.caption)
        .multilineTextAlignment(.center)
        .frame(maxWidth: .infinity)
        .padding(.vertical, 6)
    }

    private var text: Text {
        let name = Text(message.authorName).fontWeight(.semibold)
        switch message.body {
        case "joined": return Text("\(name) joined the conversation")
        case "requested": return Text("\(name) requested to join the conversation")
        case "left": return Text("\(name) has left the conversation")
        case "blocked": return Text("\(name) was blocked by the admins")
        case "promoted": return Text("\(name) is now an admin")
        case let body where body.hasPrefix("renamed:"):
            let old = Text(String(body.dropFirst("renamed:".count))).fontWeight(.semibold)
            return Text("\(old) is now known as \(name)")
        default: return Text("\(name): \(message.body)")
        }
    }
}

// MARK: - Reactions

private struct ReactionChips: View {
    let reactions: [Reaction]
    let enabled: Bool
    let toggle: (String) -> Void

    var body: some View {
        HStack(spacing: 4) {
            ForEach(reactions, id: \.emoji) { r in
                Button { toggle(r.emoji) } label: {
                    HStack(spacing: 3) {
                        Text(r.emoji)
                        Text("\(r.count)").font(.caption).foregroundStyle(Color.thereInk)
                    }
                    .padding(.horizontal, 6)
                    .padding(.vertical, 3)
                    .background(Color.thereReaction, in: .rect(cornerRadius: 6))
                    .overlay(RoundedRectangle(cornerRadius: 6)
                        .stroke(r.mine ? Color.accentColor : Color.thereReaction.mix(with: .orange, by: 0.4), lineWidth: 1))
                }
                .buttonStyle(.plain)
                .disabled(!enabled)
                .accessibilityLabel("\(r.emoji) \(r.count)\(r.mine ? ", including you" : "")")
            }
        }
    }
}

/// Tapping a message: quick reactions on top, actions below.
private struct MessageActions: View {
    let message: Message
    let model: ChatModel
    let isMine: Bool
    let close: () -> Void
    let report: () -> Void

    static let quick = ["👍", "❤️", "🤣", "😮", "😢", "🔥", "👏"]

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if model.chat.isMember && message.status == .published {
                HStack(spacing: 2) {
                    ForEach(Self.quick, id: \.self) { emoji in
                        let mine = message.reactions?.contains { $0.emoji == emoji && $0.mine } ?? false
                        Button {
                            close()
                            Task { await model.toggle(emoji, on: message) }
                        } label: {
                            Text(emoji)
                                .font(.title2)
                                .frame(width: 40, height: 40)
                                .background(mine ? Color.accentColor.opacity(0.2) : .clear, in: .circle)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(8)
                Divider()
            }
            VStack(alignment: .leading, spacing: 0) {
                if !message.body.isEmpty {
                    action("Copy", "doc.on.doc") { UIPasteboard.general.string = message.body }
                }
                if model.chat.isAdmin && message.status == .pending {
                    action("Publish", "checkmark.circle") { Task { await model.moderate(message, approve: true) } }
                    action("Reject", "xmark.circle", role: .destructive) { Task { await model.moderate(message, approve: false) } }
                }
                if model.canDelete(message) {
                    action("Delete", "trash", role: .destructive) { Task { await model.delete(message) } }
                }
                if !isMine {
                    action("Report", "exclamationmark.bubble", role: .destructive, closeFirst: false) { report() }
                }
            }
            .padding(.vertical, 4)
        }
        .frame(minWidth: 220)
    }

    private func action(_ title: String, _ icon: String, role: ButtonRole? = nil, closeFirst: Bool = true,
                        perform: @escaping () -> Void) -> some View {
        Button(role: role) {
            if closeFirst { close() }
            perform()
        } label: {
            Label(title, systemImage: icon)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .foregroundStyle(role == .destructive ? Color.thereDanger : Color.thereInk)
    }
}
