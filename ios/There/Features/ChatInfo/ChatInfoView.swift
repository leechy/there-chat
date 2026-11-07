import CoreImage.CIFilterBuiltins
import MapKit
import SwiftUI

struct ChatInfoView: View {
    let chatID: String

    @Environment(Router.self) private var router
    @State private var chat: Chat?
    @State private var members: [Member] = []
    @State private var filter: Filter = .all
    @State private var showSettings = false
    @State private var showQR = false
    @State private var confirm: PendingAction?
    @State private var error: String?

    enum Filter: String, CaseIterable { case all = "All", pending = "Pending", active = "Active" }

    struct PendingAction: Identifiable {
        let id = UUID()
        let title: String
        let message: String
        let run: () async throws -> Void
    }

    private var shown: [Member] {
        switch filter {
        case .all: members
        case .pending: members.filter { $0.status == .pending }
        case .active: members.filter { $0.status == .active }
        }
    }

    var body: some View {
        List {
            if let chat {
                Section {
                    header(chat)
                        .listRowInsets(.init())
                }
                Section {
                    Toggle(isOn: notifyBinding(chat)) {
                        Text("Notifications")
                        Text("Get a notification for every new message in this chat.")
                    }
                }
                Section {
                    Picker("Members", selection: $filter) {
                        ForEach(Filter.allCases, id: \.self) { f in
                            let count = f == .pending ? members.filter { $0.status == .pending }.count : 0
                            Text(count > 0 ? "\(f.rawValue) (\(count))" : f.rawValue).tag(f)
                        }
                    }
                    .pickerStyle(.segmented)
                    .listRowBackground(Color.clear)
                    .listRowInsets(.init())
                    .padding(.bottom, 4)
                    ForEach(shown) { member in
                        MemberRow(member: member, isMe: member.id == chat.me?.id,
                                  hasActions: canModerate(member, in: chat) && member.status != .left) {
                            actions(for: member, in: chat)
                        }
                    }
                } header: {
                    Text("Members").font(.title2.bold()).foregroundStyle(Color.thereInk).textCase(nil)
                }
            }
        }
        .navigationTitle(chat?.title ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if chat?.isAdmin == true {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Settings", systemImage: "slider.horizontal.3") { showSettings = true }
                }
            }
        }
        .task { await load() }
        .refreshable { await load() }
        .sheet(isPresented: $showSettings) {
            if let chat {
                ChatSettingsSheet(chat: chat) { updated in
                    self.chat = updated
                } deleted: {
                    router.path.removeAll()
                }
            }
        }
        .sheet(isPresented: $showQR) {
            if let url = chat?.inviteURL { QRSheet(url: url) }
        }
        .confirmationDialog(confirm?.title ?? "", isPresented: .init(
            get: { confirm != nil }, set: { if !$0 { confirm = nil } }), titleVisibility: .visible, presenting: confirm
        ) { action in
            Button(action.title, role: .destructive) { run(action.run) }
        } message: { action in
            Text(action.message)
        }
        .alert("Something went wrong", isPresented: .init(
            get: { error != nil }, set: { if !$0 { error = nil } })
        ) {
            Button("OK") {}
        } message: {
            Text(error ?? "")
        }
    }

    private func header(_ chat: Chat) -> some View {
        Map(initialPosition: .region(.init(center: chat.coordinate, latitudinalMeters: 400, longitudinalMeters: 400)),
            interactionModes: [])
        .mapStyle(.standard(pointsOfInterest: .excludingAll))
        .frame(height: 180)
        .overlay {
            HStack(spacing: 10) {
                Text(chat.emoji).font(.largeTitle)
                Text(chat.title).font(.title2.bold()).foregroundStyle(Color.thereInk)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .glassEffect(.regular, in: .capsule)
        }
        .overlay(alignment: .bottomTrailing) {
            if chat.inviteURL != nil {
                Button("Link and QR Code", systemImage: "qrcode") { showQR = true }
                    .labelStyle(.iconOnly)
                    .font(.title2)
                    .buttonStyle(.glass)
                    .buttonBorderShape(.circle)
                    .padding(12)
            }
        }
    }

    private func canModerate(_ member: Member, in chat: Chat) -> Bool {
        chat.isAdmin && member.id != chat.me?.id && member.role != .owner
            && (member.role != .admin || chat.me?.role == .owner)
    }

    @ViewBuilder
    private func actions(for member: Member, in chat: Chat) -> some View {
        if canModerate(member, in: chat) {
            switch member.status {
            case .pending:
                Button("Approve", systemImage: "checkmark") { act(.approve, member) }
                Button("Reject", systemImage: "xmark", role: .destructive) { act(.reject, member) }
            case .active:
                if member.role == .member {
                    Button("Promote to Admin", systemImage: "star") { act(.promote, member) }
                } else {
                    Button("Remove Admin Rights", systemImage: "star.slash") { act(.demote, member) }
                }
                Button("Delete All Member Messages", systemImage: "trash", role: .destructive) {
                    confirm = .init(title: "Delete All Messages",
                                    message: "Every message \(member.name) wrote in this chat will be deleted.") {
                        try await APIClient.shared.deleteMessages(of: member.id, in: chat.id)
                    }
                }
                Button("Block This Member", systemImage: "hand.raised", role: .destructive) {
                    confirm = .init(title: "Block \(member.name)",
                                    message: "They won't be able to read or join this chat from this device.") {
                        _ = try await APIClient.shared.member(chat.id, member.id, .block)
                    }
                }
            case .blocked:
                Button("Unblock", systemImage: "hand.raised.slash") { act(.unblock, member) }
            case .left:
                EmptyView()
            }
        }
    }

    private func notifyBinding(_ chat: Chat) -> Binding<Bool> {
        Binding(
            get: { chat.me?.notify ?? false },
            set: { on in
                Task {
                    do { self.chat = try await APIClient.shared.updateMe(chat.id, .init(notify: on)) }
                    catch { self.error = error.localizedDescription }
                }
            })
    }

    private func act(_ action: APIClient.MemberAction, _ member: Member) {
        guard let chat else { return }
        run { _ = try await APIClient.shared.member(chat.id, member.id, action) }
    }

    private func run(_ work: @escaping () async throws -> Void) {
        Task {
            do {
                try await work()
                await load()
            } catch {
                self.error = error.localizedDescription
            }
        }
    }

    private func load() async {
        do {
            chat = try await APIClient.shared.chat(chatID)
            members = try await APIClient.shared.members(chatID)
        } catch {
            self.error = error.localizedDescription
        }
    }
}

private struct MemberRow<Actions: View>: View {
    let member: Member
    let isMe: Bool
    let hasActions: Bool
    @ViewBuilder let actions: () -> Actions

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(member.name + (isMe ? " (you)" : ""))
                    .font(.headline)
                    .foregroundStyle(member.status == .active || member.status == .pending ? Color.thereInk : Color.thereSecondary)
                if let date = member.createdAt.map({ Date(timeIntervalSince1970: TimeInterval($0)) }) {
                    Text("\(member.status == .pending ? "pending since" : member.role == .owner ? "created on" : "joined on") \(date.formatted(date: .abbreviated, time: .shortened))")
                        .font(.caption)
                        .foregroundStyle(Color.thereSecondary)
                }
            }
            Spacer()
            Text(label)
                .font(.caption)
                .foregroundStyle(member.status == .pending ? Color.accentColor : Color.thereInk)
            if hasActions {
                Menu {
                    actions()
                } label: {
                    Image(systemName: "ellipsis.circle")
                        .font(.title3)
                        .foregroundStyle(Color.accentColor)
                }
                .menuStyle(.button)
                .buttonStyle(.plain)
                .accessibilityLabel("Actions for \(member.name)")
            }
        }
    }

    private var label: String {
        switch member.status {
        case .active: member.role.rawValue
        case .pending: "pending"
        case .left: "left"
        case .blocked: "blocked"
        }
    }
}

// MARK: - Settings

private struct ChatSettingsSheet: View {
    let chat: Chat
    let updated: (Chat) -> Void
    let deleted: () -> Void

    @State private var title: String
    @State private var emoji: String
    @State private var freeJoin: Bool
    @State private var premoderated: Bool
    @State private var visible: Bool
    @State private var confirmDelete = false
    @State private var error: String?
    @Environment(\.dismiss) private var dismiss

    init(chat: Chat, updated: @escaping (Chat) -> Void, deleted: @escaping () -> Void) {
        self.chat = chat
        self.updated = updated
        self.deleted = deleted
        _title = State(initialValue: chat.title)
        _emoji = State(initialValue: chat.emoji)
        _freeJoin = State(initialValue: chat.freeJoin)
        _premoderated = State(initialValue: chat.premoderated)
        _visible = State(initialValue: chat.visible)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack(spacing: 12) {
                        EmojiField(emoji: $emoji)
                            .frame(width: 52, height: 52)
                            .background(Color.thereSurfaceMuted, in: .rect(cornerRadius: 10))
                        TextField("Name", text: $title)
                            .font(.title3)
                            .padding(.horizontal, 12)
                            .frame(height: 52)
                            .background(Color.thereSurfaceMuted, in: .rect(cornerRadius: 10))
                    }
                    .listRowBackground(Color.clear)
                    .listRowInsets(.init())
                }
                Section {
                    Toggle(isOn: $freeJoin) {
                        Text("Free to Join")
                        Text("Anyone who can see the chat or has a link can join and read all messages.")
                    }
                    if chat.kind == .chat {
                        Toggle(isOn: $premoderated) {
                            Text("Premoderated")
                            Text("New messages are hidden until an admin publishes them.")
                        }
                    }
                    Toggle(isOn: $visible) {
                        Text("Visible to Everyone")
                        Text("Shown on the map when zoomed in to building level.")
                    }
                } footer: {
                    if let error { Text(error).foregroundStyle(Color.thereDanger) }
                }
                if chat.me?.role == .owner {
                    Section {
                        Button("Delete This Chat", role: .destructive) { confirmDelete = true }
                            .frame(maxWidth: .infinity)
                    }
                }
            }
            .navigationTitle("Chat Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", systemImage: "xmark") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Update", systemImage: "checkmark", action: save)
                        .disabled(title.trimmingCharacters(in: .whitespaces).isEmpty || emoji.isEmpty)
                }
            }
            .confirmationDialog("Delete \(chat.title)?", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("Delete for Everyone", role: .destructive) {
                    Task {
                        do {
                            try await APIClient.shared.deleteChat(chat.id)
                            dismiss()
                            deleted()
                        } catch {
                            self.error = error.localizedDescription
                        }
                    }
                }
            } message: {
                Text("All messages will be erased. This can't be undone.")
            }
        }
    }

    private func save() {
        Task {
            do {
                let chat = try await APIClient.shared.updateChat(chat.id, .init(
                    title: title, emoji: emoji, freeJoin: freeJoin, premoderated: premoderated, visible: visible))
                updated(chat)
                dismiss()
            } catch {
                self.error = error.localizedDescription
            }
        }
    }
}

// MARK: - QR

struct QRSheet: View {
    let url: URL
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Text("This link lets anyone join the chat without finding it on the map — even if it's hidden.")
                    .font(.subheadline)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(Color.thereInk)
                    .fixedSize(horizontal: false, vertical: true)
                if let image = Self.qr(url.absoluteString) {
                    Image(uiImage: image)
                        .interpolation(.none)
                        .resizable()
                        .frame(width: 220, height: 220)
                        .padding(12)
                        .background(.white, in: .rect(cornerRadius: 12))
                }
                Text(url.absoluteString)
                    .font(.footnote.monospaced())
                    .textSelection(.enabled)
                ShareLink(item: url) {
                    Label("Share Link", systemImage: "square.and.arrow.up")
                }
                .buttonStyle(.glassProminent)
                .controlSize(.large)
            }
            .padding()
            .navigationTitle("Link to Chat")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done", systemImage: "checkmark") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    static func qr(_ text: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage,
              let cg = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}
