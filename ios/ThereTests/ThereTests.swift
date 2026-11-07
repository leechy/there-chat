import Foundation
import Testing
@testable import There

struct ThereTests {
    @Test func base64URLHasNoPaddingOrUnsafeCharacters() {
        let encoded = Data([0xFB, 0xFF, 0xBF]).base64URLEncoded()
        #expect(encoded == "-_-_")
        #expect(Data(count: 32).base64URLEncoded().count == 43)
    }

    @Test func bboxQueryOrder() {
        let box = BBox(west: 2.34, south: 48.85, east: 2.36, north: 48.86)
        #expect(box.query == "2.34,48.85,2.36,48.86")
    }

    @Test func decodesChatFromServerJSON() throws {
        let json = """
        {"id":"abc","kind":"chat","title":"Guess who?","emoji":"❓","lat":48.853,"lng":2.3499,
         "free_join":true,"premoderated":false,"visible":true,"created_at":1700000000}
        """
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        let chat = try decoder.decode(Chat.self, from: Data(json.utf8))
        #expect(chat.freeJoin)
        #expect(chat.me == nil)
    }
}

struct DecodingTests {
    private let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.keyDecodingStrategy = .convertFromSnakeCase
        return d
    }()

    @Test func myChatFlattensChatAndCounters() throws {
        let json = """
        {"id":"abc","kind":"channel","title":"News","emoji":"📰","lat":1,"lng":2,"free_join":true,
         "premoderated":false,"visible":true,"created_at":1,"last_seq":7,"unread":3,"member_count":2,
         "me":{"id":"m1","chat_id":"abc","name":"Neo","role":"owner","status":"active","notify":true,
               "last_read_seq":4,"created_at":1,"updated_at":1}}
        """
        let item = try decoder.decode(MyChat.self, from: Data(json.utf8))
        #expect(item.chat.kind == .channel)
        #expect(item.unread == 3)
        #expect(item.chat.isAdmin)
    }

    @Test func reactionMineDefaultsToFalse() throws {
        let json = """
        {"id":"x","chat_id":"c","seq":1,"member_id":"m","author_name":"A","author_role":"admin","kind":"text",
         "body":"hi","status":"published","created_at":1,"reactions":[{"emoji":"🔥","count":2},{"emoji":"👍","count":1,"mine":true}]}
        """
        let m = try decoder.decode(Message.self, from: Data(json.utf8))
        #expect(m.reactions?.map(\.mine) == [false, true])
        #expect(m.authorRole == .admin)
    }
}
