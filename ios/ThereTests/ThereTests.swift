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
