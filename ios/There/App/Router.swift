import SwiftUI

enum Route: Hashable {
    /// A chat; `invite` unlocks hidden chats opened from a link.
    case chat(Chat, invite: String? = nil)
    case chatInfo(String)
    case myChats
    case notifications
}

/// App-wide navigation state, shared through the environment.
@MainActor
@Observable
final class Router {
    var path: [Route] = []
    var showNewChat = false
    var showSettings = false
    var linkError: String?

    func open(_ route: Route) { path.append(route) }

    /// Handles https://there-chat.com/<code> and there://<code> links.
    func handle(_ url: URL) {
        let code: String
        if url.scheme == "there" {
            code = url.host() ?? url.lastPathComponent
        } else {
            code = url.lastPathComponent
        }
        guard !code.isEmpty, code != "/" else { return }
        Task {
            do {
                let chat = try await APIClient.shared.chat(invite: code)
                path = [.chat(chat, invite: code)]
            } catch {
                linkError = error.localizedDescription
            }
        }
    }
}
