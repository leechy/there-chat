import Foundation

struct APIError: Error, Decodable, Sendable {
    let code: String
    let message: String
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

    // MARK: Endpoints

    func chatsOnMap(_ box: BBox) async throws -> [Chat] {
        struct Response: Decodable { let chats: [Chat] }
        let res: Response = try await send("GET", "/v1/map/chats", query: ["bbox": box.query])
        return res.chats
    }

    func suggestedNames(count: Int = 5) async throws -> [String] {
        struct Response: Decodable { let names: [String] }
        let res: Response = try await send("GET", "/v1/names/suggest", query: ["count": "\(count)"])
        return res.names
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

    private func send<T: Decodable>(_ method: String, _ path: String,
                                    query: [String: String] = [:], body: (any Encodable)? = nil) async throws -> T {
        try await ensureRegistered()
        guard let value: T = try await raw(method, path, query: query, body: body, auth: true) else {
            throw APIError(code: "empty", message: "empty response")
        }
        return value
    }

    private func raw<T: Decodable>(_ method: String, _ path: String, query: [String: String] = [:],
                                   body: (any Encodable)?, auth: Bool) async throws -> T? {
        var components = URLComponents(url: baseURL.appending(path: path), resolvingAgainstBaseURL: false)!
        if !query.isEmpty {
            components.queryItems = query.map { URLQueryItem(name: $0.key, value: $0.value) }
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
