import SwiftUI

@main
struct ThereApp: App {
    @State private var router = Router()

    var body: some Scene {
        WindowGroup {
            NavigationStack(path: $router.path) {
                MapHomeView()
                    .navigationDestination(for: Route.self) { route in
                        switch route {
                        case let .chat(chat, invite):
                            ChatView(chat: chat, invite: invite)
                        case let .chatInfo(id):
                            ChatInfoView(chatID: id)
                        case .myChats:
                            MyChatsView()
                        case .notifications:
                            NotificationsView()
                        }
                    }
            }
            .environment(router)
            .tint(Color.accentColor)
            .onOpenURL { router.handle($0) }
            .fullScreenCover(isPresented: $router.showNewChat) {
                NewChatView()
                    .environment(router)
            }
            .sheet(isPresented: $router.showSettings) {
                SettingsView()
            }
            .alert("Can't open the link", isPresented: .init(
                get: { router.linkError != nil }, set: { if !$0 { router.linkError = nil } })
            ) {
                Button("OK") {}
            } message: {
                Text(router.linkError ?? "")
            }
        }
    }
}
