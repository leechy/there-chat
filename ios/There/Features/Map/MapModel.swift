import MapKit
import Observation

/// Chats close enough on screen to overlap are shown as one marker.
struct ChatCluster: Identifiable, Hashable {
    let chats: [Chat]
    var id: String { chats.map(\.id).joined(separator: ",") }
    var coordinate: CLLocationCoordinate2D {
        let n = Double(chats.count)
        return .init(latitude: chats.map(\.lat).reduce(0, +) / n,
                     longitude: chats.map(\.lng).reduce(0, +) / n)
    }

    static func == (a: Self, b: Self) -> Bool { a.id == b.id }
    func hash(into h: inout Hasher) { h.combine(id) }
}

@MainActor
@Observable
final class MapModel {
    /// Same limit as the server (THERE_MAX_SPAN): beyond it chats are hidden.
    static let maxSpan: CLLocationDegrees = 0.04

    private(set) var clusters: [ChatCluster] = []
    private(set) var tooFar = false
    private(set) var error: String?
    private(set) var region: MKCoordinateRegion?
    private(set) var unread = 0
    private(set) var myChatCount = 0

    /// How far into the "chats visible" zoom range we are: 0 = just at the
    /// threshold, 1 = street level. Negative means chats are hidden.
    var zoomProgress: Double {
        guard let span = region?.span else { return -1 }
        let worst = max(span.latitudeDelta, span.longitudeDelta)
        return 1 - log2(worst / 0.002) / log2(Self.maxSpan / 0.002)
    }

    private var chats: [Chat] = []
    private var loadTask: Task<Void, Never>?
    private let api: APIClient

    init(api: APIClient = .shared) {
        self.api = api
    }

    /// Reloads chats in view and the "My Chats" counters, e.g. when coming
    /// back to the map after creating or leaving a chat.
    func refresh() {
        if let region { regionChanged(region) }
        Task { await refreshCounters() }
    }

    private func refreshCounters() async {
        guard let mine = try? await api.myChats() else { return }
        myChatCount = mine.count
        unread = Int(mine.filter { $0.chat.me?.notify != false }.map(\.unread).reduce(0, +))
        Push.shared.setBadge(unread)
    }

    /// Keeps the My Chats counters (and the map, when chats change) current
    /// from realtime events. Runs until the calling task is cancelled.
    func listen(to realtime: Realtime = .shared) async {
        for await event in realtime.events() {
            switch event.type {
            case "resync", "chat_updated", "chat_deleted":
                refresh()
            default:
                await refreshCounters()
            }
        }
    }

    func regionChanged(_ region: MKCoordinateRegion) {
        self.region = region
        let span = region.span
        tooFar = span.latitudeDelta > Self.maxSpan || span.longitudeDelta > Self.maxSpan
        loadTask?.cancel()
        guard !tooFar else {
            chats = []
            clusters = []
            return
        }
        let box = BBox(west: region.center.longitude - span.longitudeDelta / 2,
                       south: region.center.latitude - span.latitudeDelta / 2,
                       east: region.center.longitude + span.longitudeDelta / 2,
                       north: region.center.latitude + span.latitudeDelta / 2)
        loadTask = Task {
            do {
                let loaded = try await api.chatsOnMap(box)
                guard !Task.isCancelled else { return }
                chats = loaded
                error = nil
                recluster()
            } catch is CancellationError {
            } catch {
                guard !Task.isCancelled else { return }
                self.error = (error as? APIError)?.message ?? error.localizedDescription
            }
        }
        recluster()
    }

    /// Groups chats into grid cells of ~1/12 of the visible width, so markers
    /// never sit on top of each other at the current zoom.
    private func recluster() {
        guard let span = region?.span else { return }
        let cell = max(span.longitudeDelta, 1e-6) / 12
        let groups = Dictionary(grouping: chats) { chat in
            "\(Int((chat.lat / cell).rounded(.down))):\(Int((chat.lng / cell).rounded(.down)))"
        }
        clusters = groups.values
            .map { ChatCluster(chats: $0.sorted { $0.createdAt < $1.createdAt }) }
            .sorted { $0.id < $1.id }
    }
}
