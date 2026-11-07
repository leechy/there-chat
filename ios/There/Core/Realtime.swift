import Foundation
import Observation

/// A nudge from the server: something changed in a chat. Events carry no
/// content; listeners refetch through REST, which stays the source of truth.
struct RealtimeEvent: Decodable, Sendable, Equatable {
    var type: String
    var chatId: String?
    var seq: Int64?
    var messageId: String?
    var memberId: String?
    var action: String?

    /// Synthetic event sent after every (re)connect: events may have been
    /// missed while offline, so listeners should refetch everything.
    static let resync = RealtimeEvent(type: "resync")

    static func decode(_ text: String) -> RealtimeEvent? {
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return try? decoder.decode(RealtimeEvent.self, from: Data(text.utf8))
    }
}

/// The app's single WebSocket to `GET /v1/ws`.
///
/// Open while the app is in the foreground. Reconnects with exponential
/// backoff; while `isConnected` is false, screens fall back to polling.
@MainActor
@Observable
final class Realtime {
    static let shared = Realtime()

    private(set) var isConnected = false

    @ObservationIgnored private var loop: Task<Void, Never>?
    @ObservationIgnored private var socket: URLSessionWebSocketTask?
    @ObservationIgnored private var listeners: [UUID: AsyncStream<RealtimeEvent>.Continuation] = [:]
    @ObservationIgnored private let api: APIClient
    @ObservationIgnored private let session = URLSession(configuration: .default)

    init(api: APIClient = .shared) {
        self.api = api
    }

    func start() {
        guard loop == nil else { return }
        loop = Task { [weak self] in await self?.run() }
    }

    func stop() {
        loop?.cancel()
        loop = nil
        socket?.cancel(with: .goingAway, reason: nil)
        socket = nil
        isConnected = false
    }

    /// A stream of events; it ends when the consuming task is cancelled.
    func events() -> AsyncStream<RealtimeEvent> {
        let id = UUID()
        let (stream, continuation) = AsyncStream.makeStream(of: RealtimeEvent.self, bufferingPolicy: .bufferingNewest(32))
        listeners[id] = continuation
        continuation.onTermination = { [weak self] _ in
            Task { @MainActor in self?.listeners[id] = nil }
        }
        return stream
    }

    /// 1, 2, 4 … 30 seconds; the caller adds jitter.
    nonisolated static func backoff(attempt: Int) -> Double {
        min(30, pow(2, Double(min(attempt, 5))))
    }

    // MARK: Connection loop

    private func run() async {
        var attempt = 0
        while !Task.isCancelled {
            if await connectAndListen() { attempt = 0 }
            if isConnected {
                isConnected = false
            }
            guard !Task.isCancelled else { return }
            let delay = Self.backoff(attempt: attempt) * Double.random(in: 0.5...1)
            attempt += 1
            try? await Task.sleep(for: .seconds(delay))
        }
    }

    /// Returns true if the socket got as far as the server's hello.
    private func connectAndListen() async -> Bool {
        guard let request = try? await api.webSocketRequest(), !Task.isCancelled else { return false }
        let task = session.webSocketTask(with: request)
        socket = task
        task.resume()
        defer {
            task.cancel(with: .goingAway, reason: nil)
            if socket === task { socket = nil }
        }
        guard case .string(let first)? = try? await task.receive(),
              RealtimeEvent.decode(first)?.type == "hello" else { return false }
        isConnected = true
        emit(.resync)

        // The server pings us; we also ping it, so a silently dead
        // connection is noticed within a minute instead of a TCP timeout.
        let keepalive = Task { [weak task] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(30))
                guard let task, !Task.isCancelled else { return }
                // No pong in 10 s: cancelling fails the ping and ends receive().
                let timeout = Task {
                    try? await Task.sleep(for: .seconds(10))
                    if !Task.isCancelled { task.cancel(with: .goingAway, reason: nil) }
                }
                let alive = await withCheckedContinuation { (done: CheckedContinuation<Bool, Never>) in
                    task.sendPing { done.resume(returning: $0 == nil) }
                }
                timeout.cancel()
                if !alive { task.cancel(with: .goingAway, reason: nil) }
            }
        }
        defer { keepalive.cancel() }

        while !Task.isCancelled {
            guard let message = try? await task.receive() else { break }
            if case .string(let text) = message, let event = RealtimeEvent.decode(text) {
                emit(event)
            }
        }
        return true
    }

    private func emit(_ event: RealtimeEvent) {
        for continuation in listeners.values { continuation.yield(event) }
    }
}
