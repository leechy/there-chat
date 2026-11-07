# There

Anonymous chats pinned to places on the map. No sign-up, no names — just a
device key and whatever name you pick in each chat.

- [docs/PRODUCT.md](docs/PRODUCT.md) — what we're building
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — API, data model, identity, deploy
- [docs/UI-PLATFORMS.md](docs/UI-PLATFORMS.md) — iOS 26 / Material 3 component mapping
- `design/` — screens and icons exported from `There.sketch`

## Server (Go)

```bash
cd server
go test ./...
go run ./cmd/there          # http://127.0.0.1:8080, SQLite in ./there.db
```

## iOS (Xcode 26+, iOS 26+)

```bash
cd ios
xcodegen generate           # creates There.xcodeproj from project.yml
open There.xcodeproj
```

Debug builds talk to `http://localhost:8080` (the simulator reaches the Mac's
localhost directly).

## Android (Android Studio, JDK 17)

```bash
cd android
./gradlew :app:assembleDebug
```

Put a Google Maps key into `android/secrets.properties`:

```
MAPS_API_KEY=AIza...
```

Debug builds talk to `http://10.0.2.2:8080` — the Mac's localhost as seen from
the emulator.
