import CoreLocation
import Foundation

enum ChatKind: String, Codable, Sendable, CaseIterable {
    case chat, channel
}

struct Chat: Codable, Identifiable, Hashable, Sendable {
    let id: String
    var kind: ChatKind
    var title: String
    var emoji: String
    let lat: Double
    let lng: Double
    var freeJoin: Bool
    var premoderated: Bool
    var visible: Bool
    var inviteCode: String?
    let createdAt: Int64
    var me: Member?

    var coordinate: CLLocationCoordinate2D { .init(latitude: lat, longitude: lng) }

    /// The link that opens this chat; only admins get the invite code.
    var inviteURL: URL? {
        inviteCode.map { URL(string: "https://there-chat.com/\($0)")! }
    }

    var isMember: Bool { me?.status == .active }
    var isAdmin: Bool { isMember && (me?.role == .owner || me?.role == .admin) }
}

/// A row of "My Chats": the chat plus counters.
struct MyChat: Codable, Identifiable, Hashable, Sendable {
    let chat: Chat
    let lastSeq: Int64
    let unread: Int64
    let memberCount: Int64
    var id: String { chat.id }

    init(from decoder: Decoder) throws {
        chat = try Chat(from: decoder)
        let c = try decoder.container(keyedBy: CodingKeys.self)
        lastSeq = try c.decode(Int64.self, forKey: .lastSeq)
        unread = try c.decode(Int64.self, forKey: .unread)
        memberCount = try c.decode(Int64.self, forKey: .memberCount)
    }

    func encode(to encoder: Encoder) throws {
        try chat.encode(to: encoder)
    }

    private enum CodingKeys: String, CodingKey { case lastSeq, unread, memberCount }
}

struct Member: Codable, Identifiable, Hashable, Sendable {
    enum Role: String, Codable, Sendable { case owner, admin, member }
    enum Status: String, Codable, Sendable { case pending, active, left, blocked }

    let id: String
    let chatId: String
    var name: String
    var role: Role
    var status: Status
    var notify: Bool
    var lastReadSeq: Int64
    var createdAt: Int64?
    var updatedAt: Int64?
}

struct Reaction: Codable, Hashable, Sendable {
    let emoji: String
    var count: Int
    var mine: Bool

    init(emoji: String, count: Int, mine: Bool) {
        self.emoji = emoji
        self.count = count
        self.mine = mine
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        emoji = try c.decode(String.self, forKey: .emoji)
        count = try c.decode(Int.self, forKey: .count)
        mine = try c.decodeIfPresent(Bool.self, forKey: .mine) ?? false // omitted when false
    }
}

struct Message: Codable, Identifiable, Hashable, Sendable {
    enum Kind: String, Codable, Sendable { case text, system }
    enum Status: String, Codable, Sendable { case pending, published, rejected, deleted }

    let id: String
    let chatId: String
    let seq: Int64
    let memberId: String?
    let authorName: String
    var authorRole: Member.Role?
    let kind: Kind
    var body: String
    var status: Status
    let createdAt: Int64
    var deletedAt: Int64?
    var reactions: [Reaction]?

    var date: Date { Date(timeIntervalSince1970: TimeInterval(createdAt)) }
    var deletedDate: Date? { deletedAt.map { Date(timeIntervalSince1970: TimeInterval($0)) } }
}

/// A map area in degrees, as the server expects it.
struct BBox: Sendable, Equatable {
    var west, south, east, north: Double

    var query: String { "\(west),\(south),\(east),\(north)" }
}
