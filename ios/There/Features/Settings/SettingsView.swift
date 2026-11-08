import SwiftUI

/// App settings and the privacy story in plain words.
struct SettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(Router.self) private var router
    @State private var confirmErase = false
    @State private var erasing = false
    @State private var eraseError: String?
    @State private var server: ServerStatus?

    var body: some View {
        NavigationStack {
            List {
                Section("Privacy") {
                    Label("No account, no phone number, no e-mail.", systemImage: "person.crop.circle.badge.questionmark")
                    Label("Your name is chosen separately in every chat. Nobody can tell that two names are the same person.",
                          systemImage: "theatermasks")
                    Label("Your location is only used to show the map around you and is never stored.",
                          systemImage: "location")
                }
                Section("This Device") {
                    Label("Your chats are tied to a secret key stored on this iPhone. It survives reinstalling the app, but not moving to another phone.",
                          systemImage: "key")
                }
                Section {
                    Button(role: .destructive) {
                        confirmErase = true
                    } label: {
                        HStack {
                            Text("Erase My Data")
                            if erasing {
                                Spacer()
                                ProgressView()
                            }
                        }
                    }
                    .disabled(erasing)
                } footer: {
                    Text("Deletes your messages, reactions and names in every chat, removes you from your chats and deletes the chats you created. This can't be undone.")
                }
                Section {
                    Button { Task { await checkServer() } } label: {
                        LabeledContent("Server") { ServerStatusText(status: server) }
                    }
                    .foregroundStyle(Color.primary)
                } footer: {
                    Text("\(APIClient.shared.host) · tap to check again")
                }
                Section {
                    LabeledContent("Version",
                                   value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "")
                }
            }
            .labelStyle(SettingsLabelStyle())
            .task { await checkServer() }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .confirmationDialog("Erase all your data?", isPresented: $confirmErase, titleVisibility: .visible) {
                Button("Erase Everything", role: .destructive) { Task { await erase() } }
            } message: {
                Text("Your messages and names will be deleted from every chat, and chats you created will be deleted for everyone. You will start over as a new anonymous user.")
            }
            .alert("Couldn't erase your data", isPresented: .init(
                get: { eraseError != nil }, set: { if !$0 { eraseError = nil } })
            ) {
                Button("OK") {}
            } message: {
                Text(eraseError ?? "")
            }
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done", systemImage: "checkmark") { dismiss() }
                }
            }
        }
    }
}

extension SettingsView {
    private func checkServer() async {
        server = nil
        server = await APIClient.shared.serverStatus()
    }

    private func erase() async {
        erasing = true
        defer { erasing = false }
        do {
            try await APIClient.shared.eraseDevice()
        } catch {
            eraseError = error.localizedDescription
            return
        }
        // Reconnect as the new device; the socket's resync reloads the map.
        Realtime.shared.stop()
        Realtime.shared.start()
        router.path = []
        dismiss()
    }
}

private struct ServerStatusText: View {
    let status: ServerStatus?

    var body: some View {
        switch status {
        case nil:
            ProgressView()
        case .online(let ms):
            dot(.green, "Online · \(ms) ms")
        case .failing(let code):
            dot(.orange, "Error \(code)")
        case .unreachable:
            dot(.red, "Unreachable")
        }
    }

    private func dot(_ color: Color, _ text: String) -> some View {
        HStack(spacing: 6) {
            Circle().fill(color).frame(width: 8, height: 8)
            Text(text)
        }
    }
}

private struct SettingsLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            configuration.icon.foregroundStyle(Color.accentColor).frame(width: 24)
            configuration.title.font(.subheadline).foregroundStyle(Color.thereInk)
        }
    }
}

/// Placeholder until push notifications land: shows unread chats.
struct NotificationsView: View {
    var body: some View {
        MyChatsView(title: "Notifications")
    }
}
