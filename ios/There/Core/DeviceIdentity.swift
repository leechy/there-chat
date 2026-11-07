import Foundation
import Security

/// The device's anonymous identity: 32 random bytes kept in the Keychain.
///
/// Keychain items survive app reinstalls (unlike `identifierForVendor`), and
/// with `ThisDeviceOnly` they are never synced to iCloud, so the identity
/// stays on this phone. The server only ever stores a hash of it.
enum DeviceIdentity {
    private static let service = "com.there-chat.device"
    private static let account = "secret"

    /// The base64url-encoded secret, created on first use.
    static func secret() throws -> String {
        if let existing = try read() {
            return existing.base64URLEncoded()
        }
        var bytes = Data(count: 32)
        let status = bytes.withUnsafeMutableBytes {
            SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!)
        }
        guard status == errSecSuccess else { throw KeychainError(status: status) }
        try write(bytes)
        return bytes.base64URLEncoded()
    }

    private static func read() throws -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        switch status {
        case errSecSuccess: return result as? Data
        case errSecItemNotFound: return nil
        default: throw KeychainError(status: status)
        }
    }

    private static func write(_ data: Data) throws {
        let item: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            kSecValueData as String: data,
        ]
        let status = SecItemAdd(item as CFDictionary, nil)
        guard status == errSecSuccess else { throw KeychainError(status: status) }
    }
}

struct KeychainError: Error {
    let status: OSStatus
}

extension Data {
    func base64URLEncoded() -> String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
