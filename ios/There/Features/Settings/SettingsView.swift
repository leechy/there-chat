import SwiftUI

/// App settings and the privacy story in plain words.
struct SettingsView: View {
    @Environment(\.dismiss) private var dismiss

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
                    LabeledContent("Version",
                                   value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "")
                }
            }
            .labelStyle(SettingsLabelStyle())
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done", systemImage: "checkmark") { dismiss() }
                }
            }
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
