import SwiftUI
import UserNotifications

/// Push notifications: asks for permission when the user first joins or
/// creates a chat, sends the APNs token to the server, keeps notifications
/// for the chat on screen quiet and opens a chat when one is tapped.
@MainActor
@Observable
final class Push: NSObject {
    static let shared = Push()

    /// What the system allows; refreshed when Settings appears.
    private(set) var status: UNAuthorizationStatus = .notDetermined
    /// Whether notifications show the message text (sent to the server).
    var preview: Bool = UserDefaults.standard.object(forKey: "pushPreview") as? Bool ?? true {
        didSet {
            UserDefaults.standard.set(preview, forKey: "pushPreview")
            Task { await upload() }
        }
    }

    /// The chat on screen; its notifications are not shown.
    @ObservationIgnored var openChatID: String?
    @ObservationIgnored weak var router: Router?
    @ObservationIgnored private var token: String?

    private var center: UNUserNotificationCenter { .current() }

    /// Debug builds are signed for Apple's sandbox, the rest for production.
    private var platform: String {
        #if DEBUG
        "apns_sandbox"
        #else
        "apns"
        #endif
    }

    func setUp(router: Router) {
        self.router = router
        center.delegate = self
        Task {
            await refreshStatus()
            // Tokens can change; Apple hands out the current one on every launch.
            if status == .authorized || status == .provisional {
                UIApplication.shared.registerForRemoteNotifications()
            }
        }
    }

    func refreshStatus() async {
        status = await center.notificationSettings().authorizationStatus
    }

    /// Asks once, at the moment it makes sense: right after joining or creating a chat.
    func requestIfNeeded() async {
        await refreshStatus()
        guard status == .notDetermined else { return }
        let granted = (try? await center.requestAuthorization(options: [.alert, .badge, .sound])) ?? false
        await refreshStatus()
        if granted {
            UIApplication.shared.registerForRemoteNotifications()
        }
    }

    func didRegister(deviceToken: Data) {
        token = deviceToken.map { String(format: "%02x", $0) }.joined()
        Task { await upload() }
    }

    /// Sends the token to the current server; again after switching servers
    /// or erasing data, since the server then knows a new device.
    func upload() async {
        guard let token else { return }
        try? await APIClient.shared.setPush(platform: platform, token: token, preview: preview)
    }

    /// Keeps the app icon badge in step with unread messages.
    func setBadge(_ count: Int) {
        center.setBadgeCount(count)
    }

    /// Removes delivered notifications of a chat once it is open.
    func clearDelivered(chatID: String) {
        Task {
            let ids = await center.deliveredNotifications()
                .filter { $0.request.content.threadIdentifier == chatID }
                .map(\.request.identifier)
            center.removeDeliveredNotifications(withIdentifiers: ids)
        }
    }

    fileprivate func open(chatID: String) {
        Task {
            guard let chat = try? await APIClient.shared.chat(chatID) else { return }
            router?.showSettings = false
            router?.showNewChat = false
            router?.path = [.chat(chat)]
        }
    }
}

extension Push: UNUserNotificationCenterDelegate {
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter, willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        let chatID = notification.request.content.userInfo["chat_id"] as? String
        let open = await MainActor.run { Push.shared.openChatID }
        return chatID != nil && chatID == open ? [] : [.banner, .list, .sound]
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse
    ) async {
        guard let chatID = response.notification.request.content.userInfo["chat_id"] as? String else { return }
        await MainActor.run { Push.shared.open(chatID: chatID) }
    }
}

/// Receives the APNs device token, which only comes through the app delegate.
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        MainActor.assumeIsolated { Push.shared.didRegister(deviceToken: deviceToken) }
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        print("Push registration failed: \(error.localizedDescription)")
    }
}
