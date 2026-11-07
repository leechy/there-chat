import SwiftUI

struct MyChatsView: View {
    var title = "My Chats"
    @Environment(Router.self) private var router
    @State private var chats: [MyChat] = []
    @State private var kind: ChatKind = .chat
    @State private var loaded = false
    @State private var error: String?

    private var shown: [MyChat] { chats.filter { $0.chat.kind == kind } }

    var body: some View {
        List {
            Section {
                ForEach(shown) { item in
                    Button { router.open(.chat(item.chat)) } label: { MyChatRow(item: item) }
                        .buttonStyle(.plain)
                }
            } header: {
                Picker("Type", selection: $kind) {
                    Text("Chats").tag(ChatKind.chat)
                    Text("Channels").tag(ChatKind.channel)
                }
                .pickerStyle(.segmented)
                .textCase(nil)
                .padding(.bottom, 8)
            }
        }
        .listStyle(.plain)
        .overlay {
            if loaded && shown.isEmpty {
                ContentUnavailableView {
                    Label(kind == .chat ? "No chats yet" : "No channels yet",
                          systemImage: kind == .chat ? "bubble.left.and.bubble.right" : "megaphone")
                } description: {
                    Text(error ?? "Join one on the map or start your own.")
                } actions: {
                    Button("New Chat") { router.showNewChat = true }
                        .buttonStyle(.glassProminent)
                }
            }
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.large)
        .refreshable { await load() }
        .task { await load() }
    }

    private func load() async {
        do {
            chats = try await APIClient.shared.myChats()
            error = nil
        } catch {
            self.error = error.localizedDescription
        }
        loaded = true
    }
}

private struct MyChatRow: View {
    let item: MyChat

    var body: some View {
        HStack(spacing: 12) {
            Text(item.chat.emoji).font(.title)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.chat.title)
                    .font(.headline)
                    .foregroundStyle(Color.thereInk)
                    .lineLimit(1)
                HStack(spacing: 8) {
                    Label("\(item.memberCount)", systemImage: "person.2")
                    Label("\(item.lastSeq)", systemImage: "bubble.left")
                    if item.chat.me?.status == .pending {
                        Text("request pending").foregroundStyle(Color.accentColor)
                    }
                }
                .font(.caption2)
                .foregroundStyle(Color.thereSecondary)
                .labelStyle(.titleAndIcon)
            }
            Spacer()
            if item.unread > 0 {
                Text("\(item.unread)")
                    .font(.caption.weight(.semibold))
                    .monospacedDigit()
                    .foregroundStyle(.white)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 2)
                    .background(item.chat.me?.notify == false ? Color.thereSecondary : Color.accentColor, in: .capsule)
            }
            Image(systemName: "chevron.right")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.tertiary)
        }
        .padding(.vertical, 4)
        .contentShape(.rect)
    }
}
