import SwiftUI

/// App settings and the privacy story in plain words.
struct SettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(Router.self) private var router
    @State private var confirmErase = false
    @State private var erasing = false
    @State private var eraseError: String?
    @State private var server: ServerStatus?
    @State private var host = ""
    @State private var serverURL: URL?
    @Bindable private var push = Push.shared

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
                Section {
                    LabeledContent("Notifications") {
                        switch push.status {
                        case .authorized, .provisional, .ephemeral: Text("On")
                        case .denied:
                            Button("Off · Open Settings") {
                                if let url = URL(string: UIApplication.openNotificationSettingsURLString) {
                                    UIApplication.shared.open(url)
                                }
                            }
                        default: Text("Asked when you join a chat")
                        }
                    }
                    Toggle("Show Message Text", isOn: $push.preview)
                } header: {
                    Text("Notifications")
                } footer: {
                    Text("Notifications show the chat and the author's name, and the message text if you allow it. They are delivered through Apple's servers. Turn them off for a single chat in its Chat Info.")
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
                    if !APIClient.servers.isEmpty {
                        Picker("Connect to", selection: $serverURL) {
                            ForEach(APIClient.servers, id: \.url) { server in
                                Text(server.name).tag(Optional(server.url))
                            }
                        }
                        .onChange(of: serverURL) { old, new in
                            guard let new, old != nil else { return }
                            Task { await switchServer(to: new) }
                        }
                    }
                    Button { Task { await checkServer() } } label: {
                        LabeledContent("Server") { ServerStatusText(status: server) }
                    }
                    .foregroundStyle(Color.primary)
                } footer: {
                    Text("\(host) · tap to check again")
                }
                Section {
                    LabeledContent("Version",
                                   value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "")
                }
            }
            .labelStyle(SettingsLabelStyle())
            .task { await checkServer() }
            .task { await push.refreshStatus() }
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
        host = await APIClient.shared.host
        if serverURL == nil { serverURL = await APIClient.shared.baseURL }
        server = await APIClient.shared.serverStatus()
    }

    /// Debug builds only: talk to another server with the same device key.
    private func switchServer(to url: URL) async {
        await APIClient.shared.use(url)
        await Push.shared.upload()
        // Reconnect to the new server; the socket's resync reloads the map.
        Realtime.shared.stop()
        Realtime.shared.start()
        router.path = []
        await checkServer()
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
        await Push.shared.upload() // the new anonymous device gets the token
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
