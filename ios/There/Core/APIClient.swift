import Foundation

struct APIError: Error, Decodable, Sendable, LocalizedError {
    let code: String
    let message: String

    var errorDescription: String? { message }
}

/// Thin async client for the There REST API.
///
/// Registers the device lazily on first use, then sends the device secret as
/// a bearer token with every request.
actor APIClient {
    static let shared = APIClient(
        baseURL: URL(string: Bundle.main.object(forInfoDictionaryKey: "ThereAPIBaseURL") as? String ?? "")
            ?? URL(string: "http://localhost:8080")!
    )

    private let baseURL: URL
    private let session: URLSession
    private var registered = false

    private let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.keyDecodingStrategy = .convertFromSnakeCase
        return d
    }()

    private let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.keyEncodingStrategy = .convertToSnakeCase
        return e
    }()

    init(baseURL: URL, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.session = session
    }

    // MARK: Map and lists

    func chatsOnMap(_ box: BBox) async throws -> [Chat] {
        struct Response: Decodable { let chats: [Chat] }
        let res: Response = try await send("GET", "/v1/map/chats", query: ["bbox": box.query])
        return res.chats
    }

    func myChats() async throws -> [MyChat] {
        struct Response: Decodable { let chats: [MyChat] }
        let res: Response = try await send("GET", "/v1/me/chats")
        return res.chats
    }

    func suggestedNames(count: Int = 5) async throws -> [String] {
        struct Response: Decodable { let names: [String] }
        let res: Response = try await send("GET", "/v1/names/suggest", query: ["count": "\(count)"])
        return res.names
    }

    // MARK: Chats

    struct NewChat: Encodable, Sendable {
        var kind: ChatKind
        var title: String
        var emoji: String
        var lat: Double
        var lng: Double
        var freeJoin: Bool
        var premoderated: Bool
        var visible: Bool
        var name: String
    }

    func createChat(_ new: NewChat) async throws -> Chat {
        try await send("POST", "/v1/chats", body: new)
    }

    func chat(_ id: String, invite: String? = nil) async throws -> Chat {
        try await send("GET", "/v1/chats/\(id)", query: invite.map { ["invite": $0] } ?? [:])
    }

    func chat(invite code: String) async throws -> Chat {
        try await send("GET", "/v1/invites/\(code)")
    }

    struct ChatSettings: Encodable, Sendable {
        var title: String?
        var emoji: String?
        var freeJoin: Bool?
        var premoderated: Bool?
        var visible: Bool?
    }

    func updateChat(_ id: String, _ settings: ChatSettings) async throws -> Chat {
        try await send("PATCH", "/v1/chats/\(id)", body: settings)
    }

    func deleteChat(_ id: String) async throws {
        try await sendEmpty("DELETE", "/v1/chats/\(id)")
    }

    func join(_ id: String, name: String, invite: String?) async throws -> Chat {
        struct Body: Encodable { let name: String; let inviteCode: String? }
        return try await send("POST", "/v1/chats/\(id)/join", body: Body(name: name, inviteCode: invite))
    }

    func leave(_ id: String) async throws -> Chat {
        try await send("POST", "/v1/chats/\(id)/leave")
    }

    struct MeUpdate: Encodable, Sendable {
        var name: String?
        var notify: Bool?
        var lastReadSeq: Int64?
    }

    func updateMe(_ chatID: String, _ update: MeUpdate) async throws -> Chat {
        try await send("PATCH", "/v1/chats/\(chatID)/me", body: update)
    }

    // MARK: Members

    func members(_ chatID: String, status: Member.Status? = nil) async throws -> [Member] {
        struct Response: Decodable { let members: [Member] }
        let res: Response = try await send("GET", "/v1/chats/\(chatID)/members",
                                           query: status.map { ["status": $0.rawValue] } ?? [:])
        return res.members
    }

    enum MemberAction: String, Sendable { case approve, reject, promote, demote, block, unblock }

    func member(_ chatID: String, _ memberID: String, _ action: MemberAction) async throws -> Member {
        try await send("POST", "/v1/chats/\(chatID)/members/\(memberID)/\(action.rawValue)")
    }

    func deleteMessages(of memberID: String, in chatID: String) async throws {
        try await sendEmpty("DELETE", "/v1/chats/\(chatID)/members/\(memberID)/messages")
    }

    // MARK: Messages

    func messages(_ chatID: String, before: Int64? = nil, after: Int64? = nil, limit: Int = 50,
                  invite: String? = nil) async throws -> [Message] {
        struct Response: Decodable { let messages: [Message] }
        var query = ["limit": "\(limit)"]
        if let before { query["before"] = "\(before)" }
        if let after { query["after"] = "\(after)" }
        if let invite { query["invite"] = invite }
        let res: Response = try await send("GET", "/v1/chats/\(chatID)/messages", query: query)
        return res.messages
    }

    func post(_ chatID: String, body: String, clientID: String) async throws -> Message {
        struct Body: Encodable { let body: String; let clientId: String }
        return try await send("POST", "/v1/chats/\(chatID)/messages", body: Body(body: body, clientId: clientID))
    }

    func deleteMessage(_ id: String) async throws {
        try await sendEmpty("DELETE", "/v1/messages/\(id)")
    }

    func moderate(_ id: String, approve: Bool) async throws -> Message {
        try await send("POST", "/v1/messages/\(id)/\(approve ? "approve" : "reject")")
    }

    func react(_ id: String, emoji: String, on: Bool) async throws -> Message {
        let escaped = emoji.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? emoji
        return try await send(on ? "PUT" : "DELETE", "/v1/messages/\(id)/reactions/\(escaped)", escapedPath: true)
    }

    func report(chatID: String, messageID: String?, reason: String) async throws {
        struct Body: Encodable { let chatId: String; let messageId: String?; let reason: String }
        try await sendEmpty("POST", "/v1/reports", body: Body(chatId: chatID, messageId: messageID, reason: reason))
    }

    // MARK: Realtime

    /// The authenticated request for the `GET /v1/ws` socket.
    func webSocketRequest() async throws -> URLRequest {
        try await ensureRegistered()
        var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false)!
        components.scheme = components.scheme == "https" ? "wss" : "ws"
        components.path = "/v1/ws"
        var request = URLRequest(url: components.url!)
        request.setValue("Bearer \(try DeviceIdentity.secret())", forHTTPHeaderField: "Authorization")
        return request
    }

    // MARK: Plumbing

    private struct Empty: Codable {}
    private struct ErrorEnvelope: Decodable { let error: APIError }

    private func ensureRegistered() async throws {
        guard !registered else { return }
        struct Body: Encodable { let secret: String }
        let _: Empty? = try await raw("POST", "/v1/devices", body: Body(secret: try DeviceIdentity.secret()), auth: false)
        registered = true
    }

    private func send<T: Decodable>(_ method: String, _ path: String, query: [String: String] = [:],
                                    body: (any Encodable)? = nil, escapedPath: Bool = false) async throws -> T {
        try await ensureRegistered()
        guard let value: T = try await raw(method, path, query: query, body: body, auth: true, escapedPath: escapedPath) else {
            throw APIError(code: "empty", message: "Empty response from the server")
        }
        return value
    }

    private func sendEmpty(_ method: String, _ path: String, body: (any Encodable)? = nil) async throws {
        try await ensureRegistered()
        let _: Empty? = try await raw(method, path, body: body, auth: true)
    }

    private func raw<T: Decodable>(_ method: String, _ path: String, query: [String: String] = [:],
                                   body: (any Encodable)?, auth: Bool, escapedPath: Bool = false) async throws -> T? {
        var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false)!
        if escapedPath {
            components.percentEncodedPath = path
        } else {
            components.path = path
        }
        if !query.isEmpty {
            components.queryItems = query.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) }
        }
        var request = URLRequest(url: components.url!)
        request.httpMethod = method
        if auth {
            request.setValue("Bearer \(try DeviceIdentity.secret())", forHTTPHeaderField: "Authorization")
        }
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try encoder.encode(body)
        }
        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            throw (try? decoder.decode(ErrorEnvelope.self, from: data))?.error
                ?? APIError(code: "http_\(status)", message: HTTPURLResponse.localizedString(forStatusCode: status))
        }
        if data.isEmpty { return nil }
        return try decoder.decode(T.self, from: data)
    }
}
