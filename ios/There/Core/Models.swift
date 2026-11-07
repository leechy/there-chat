import CoreLocation
import Foundation

enum ChatKind: String, Codable, Sendable {
    case chat, channel
}

struct Chat: Codable, Identifiable, Hashable, Sendable {
    let id: String
    let kind: ChatKind
    let title: String
    let emoji: String
    let lat: Double
    let lng: Double
    let freeJoin: Bool
    let premoderated: Bool
    let visible: Bool
    let inviteCode: String?
    let createdAt: Int64
    let me: Member?

    var coordinate: CLLocationCoordinate2D { .init(latitude: lat, longitude: lng) }
}

struct Member: Codable, Identifiable, Hashable, Sendable {
    enum Role: String, Codable, Sendable { case owner, admin, member }
    enum Status: String, Codable, Sendable { case pending, active, left, blocked }

    let id: String
    let chatId: String
    let name: String
    let role: Role
    let status: Status
    let notify: Bool
    let lastReadSeq: Int64
}

struct Message: Codable, Identifiable, Hashable, Sendable {
    enum Kind: String, Codable, Sendable { case text, system }
    enum Status: String, Codable, Sendable { case pending, published, rejected, deleted }

    let id: String
    let chatId: String
    let seq: Int64
    let memberId: String?
    let authorName: String
    let kind: Kind
    let body: String
    let status: Status
    let createdAt: Int64
}

/// A map area in degrees, as the server expects it.
struct BBox: Sendable, Equatable {
    var west, south, east, north: Double

    var query: String { "\(west),\(south),\(east),\(north)" }
}
