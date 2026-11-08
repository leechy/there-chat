# Архитектура There

```
there-chat/
├── docs/        спецификация, UI, архитектура
├── design/      выгрузка из There.sketch
├── server/      Go, SQLite, REST + WebSocket
├── ios/         Swift, SwiftUI, MapKit (iOS 26+)
└── android/     Kotlin, Jetpack Compose, Google Maps (Android 8+)
```

## Идентичность устройства

1. При первом запуске клиент генерирует 32 случайных байта — **device secret** — и кладёт их:
   - iOS: Keychain (`kSecAttrAccessibleAfterFirstUnlock`, синхронизация с iCloud выключена) — переживает переустановку;
   - Android: Block Store (`shouldBackupToCloud = true`) — переживает переустановку и перенос на новый телефон из резервной копии.
2. `POST /v1/devices` с секретом. Сервер хранит только SHA-256 от секрета, сам секрет не сохраняет.
3. Каждый запрос — заголовок `Authorization: Bearer <secret>`.

Сервер не может связать секрет с человеком: ни телефона, ни e-mail, ни имени. Push-токен хранится отдельно и удаляется, когда пользователь выключает уведомления.

**Анонимность между чатами.** Наружу device ID не отдаётся никогда. В каждом чате у участника свой `member_id`, поэтому по API нельзя понять, что «Hans Gruber» в одном чате и «Neo» в другом — одно и то же устройство.

## Модель данных (SQLite)

```
devices    id, token_hash UNIQUE, push_platform, push_token, created_at, last_seen_at
chats      id, kind(chat|channel), title, emoji, lat, lng,
           free_join, premoderated, visible, invite_code UNIQUE,
           created_at, deleted_at
members    id, chat_id, device_id, name, role(owner|admin|member),
           status(pending|active|left|blocked), notify, last_read_seq,
           created_at, updated_at
           UNIQUE(chat_id, device_id)
messages   id, chat_id, seq, member_id, author_name, kind(text|system),
           body, status(pending|published|rejected|deleted),
           client_id, created_at, deleted_at
           UNIQUE(chat_id, seq), UNIQUE(member_id, client_id)
reactions  message_id, member_id, emoji, created_at
           PRIMARY KEY(message_id, member_id, emoji)
reports    id, device_id, chat_id, message_id, reason, created_at
```

- `author_name` копируется в сообщение при отправке: переименование не трогает старые сообщения.
- `seq` — сквозной номер внутри чата, по нему клиенты догружают пропущенное и считают непрочитанные.
- Имя уникально среди участников со статусом `active` или `pending` (проверка в коде, без учёта регистра).
- Геопоиск: индекс по `(lat, lng)`; при росте — модуль R*Tree из SQLite или PostGIS после переезда.

## Видимость на карте

- Клиент запрашивает `GET /v1/map/chats?bbox=west,south,east,north`.
- Если область больше порога (по умолчанию 0,03° по широте, это примерно зум 15–16, «видны здания»), сервер отвечает `400 zoom_in` — чаты не отдаются вовсе. Порог проверяется на сервере, иначе клиент мог бы его обойти.
- В ответ попадают видимые чаты в области плюс скрытые, где устройство — активный участник.
- Группировку близких маркеров в один маркер со списком делает клиент.

## REST API (v1)

| Метод | Путь | Что делает |
|---|---|---|
| GET | `/healthz` | проверка живости |
| POST | `/v1/devices` | регистрация устройства |
| PUT / DELETE | `/v1/devices/me/push` | push-токен |
| GET | `/v1/names/suggest` | случайное имя киноперсонажа |
| GET | `/v1/map/chats?bbox=` | чаты в видимой области |
| GET | `/v1/me/chats` | мои чаты с количеством непрочитанных |
| POST | `/v1/chats` | создать чат или канал (создатель — owner) |
| GET / PATCH / DELETE | `/v1/chats/{id}` | инфо, настройки, удаление (admin/owner) |
| GET | `/v1/invites/{code}` | чат по ссылке-приглашению |
| POST | `/v1/chats/{id}/join` | вступить или подать заявку; `{name, invite_code?}` |
| POST | `/v1/chats/{id}/leave` | выйти |
| PATCH | `/v1/chats/{id}/me` | своё имя, уведомления, прочитано до `seq` |
| GET | `/v1/chats/{id}/members?status=` | участники |
| POST | `/v1/chats/{id}/members/{mid}/{approve\|reject\|promote\|block}` | действия админа |
| DELETE | `/v1/chats/{id}/members/{mid}/messages` | удалить все сообщения участника |
| GET | `/v1/chats/{id}/messages?before=&after=&limit=` | история по `seq` |
| POST | `/v1/chats/{id}/messages` | отправить; `{body, client_id}` — повтор с тем же `client_id` не создаёт дубль |
| POST | `/v1/messages/{id}/{approve\|reject}` | премодерация |
| DELETE | `/v1/messages/{id}` | удалить (автор или админ) |
| PUT / DELETE | `/v1/messages/{id}/reactions/{emoji}` | реакции |
| POST | `/v1/reports` | жалоба |
| GET | `/v1/ws` | WebSocket с событиями, см. Realtime |

Ошибки: `{"error": {"code": "zoom_in", "message": "..."}}`.

Действия над участником (`{action}`): `approve`, `reject`, `promote`, `demote`, `block`, `unblock`. Владельца модерировать нельзя; других админов — только владелец.

Системные сообщения (`kind: system`) несут событие в `body`: `joined`, `requested`, `left`, `blocked`, `promoted`, `renamed:<старое имя>`.

Читать открытый видимый чат можно без вступления. Закрытый — только участникам; остальные видят заголовок и кнопку заявки.

## Realtime

`GET /v1/ws` — WebSocket (`github.com/coder/websocket`), тот же `Authorization: Bearer <secret>`. Первый кадр от сервера — `{"type":"hello"}`, клиент сам ничего не шлёт.

**Событие — только толчок, без содержимого.** Получив его, клиент догружает через REST: `GET messages?after=<последний seq>` для новых сообщений, последнюю страницу — для правок (реакции, удаление, модерация), `GET /chats/{id}` и `GET /me/chats` — для участников и настроек. Все правила видимости остаются в REST.

```json
{"type": "message", "chat_id": "...", "seq": 42, "message_id": "..."}
{"type": "member", "chat_id": "...", "member_id": "...", "action": "joined"}
```

| `type` | когда | поля |
|---|---|---|
| `message` | новое сообщение | `seq`, `message_id` |
| `reaction` | реакция поставлена / снята | `seq`, `message_id` |
| `message_deleted` | удалено сообщение (или все сообщения участника) | `seq`, `message_id` или `member_id` |
| `moderated` | премодерация: `action` = `approved` / `rejected` | `seq`, `message_id` |
| `member` | `action`: `joined`, `pending`, `left`, `rejected`, `blocked`, `unblocked`, `promoted`, `demoted`, `renamed` | `member_id` |
| `chat_updated`, `chat_deleted` | настройки чата, удаление | — |

**Кому.** События чата получают только его участники со статусом `active` (список берётся из `members` в момент записи). Заблокированные, вышедшие и ожидающие одобрения не получают ничего; читатели открытого чата без вступления тоже — они опрашивают. Сообщение в премодерации (`pending`) анонсируется только автору и админам; остальные узнают о нём после `moderated`.

**Хаб** живёт в памяти процесса (`server/internal/api/realtime.go`): одно соединение на устройство (новое закрывает старое), публикация из хендлеров после успешной записи. Буфер на соединение — 64 события; кто не успевает, того сервер отключает, клиент переподключится и догрузит. Сервер пингует каждые 25 с. Если серверов станет несколько — Redis pub/sub или Postgres `LISTEN/NOTIFY`.

**Клиенты** (`ios/There/Core/Realtime.swift`, `android/.../core/Realtime.kt`):
- сокет открыт, пока приложение на переднем плане (iOS — `scenePhase`, Android — `onStart`/`onStop` активности), и сам пингует сервер раз в 30 с;
- переподключение с экспоненциальной задержкой 1, 2, 4 … 30 с (со случайным разбросом); после каждого подключения — синтетическое событие `resync`, по которому экраны догружают всё, что могли пропустить;
- открытый чат, пока сокет подключён и устройство в чате, не опрашивает (только страховочный опрос раз в минуту); без сокета или при чтении без вступления — прежний опрос каждые 3 секунды;
- карта и My Chats обновляют счётчики по событиям.

## Push

- Код: `server/internal/push` (APNs и FCM без сторонних библиотек, очередь на 1000 уведомлений
  и 4 воркера) и `server/internal/api/push.go` (кому и что отправить).
- iOS: APNs по ключу `.p8`, JWT ES256 обновляется каждые 40 минут. Debug-сборки регистрируются
  как `apns_sandbox`, TestFlight и App Store — как `apns`.
- Android: FCM HTTP v1, сервисный аккаунт; сообщения только с данными (data-only), уведомление
  рисует приложение: одно на чат, с последними строками.
- Отправляется при новом опубликованном сообщении (и после одобрения премодерируемого) всем
  активным участникам с `notify = true` и push-токеном, кроме автора. Повтор запроса с тем же
  `client_id` уведомлений не шлёт.
- Пока чат открыт на экране, его уведомления приложение не показывает и убирает уже пришедшие.
- Текст: заголовок — эмодзи и название чата; тело — «Имя: текст» (до 180 символов) или
  «Имя sent a message», если в настройках выключено «Показывать текст» (`push_preview`).
- iOS-бейдж = непрочитанные во всех чатах с уведомлениями.
- Токен, который Apple или Google объявили недействительным, удаляется. Один токен принадлежит
  одному устройству: после «Erase My Data» новый анонимный пользователь забирает его себе.
- Разрешение спрашивается, когда пользователь впервые вступает в чат или создаёт его.

## Деплой

- DigitalOcean droplet (Ubuntu), один статический бинарник `there` + systemd.
- Caddy: HTTPS для `api.there-chat.com` (API) и `there-chat.com` (ссылки-приглашения); на `there-chat.com` раздаёт `/.well-known/apple-app-site-association` и `/.well-known/assetlinks.json` для открытия ссылок `https://there-chat.com/<код>` в приложении.
- Litestream: непрерывный бэкап SQLite в DigitalOcean Spaces.

## Клиенты

**iOS** (Swift 6, SwiftUI, iOS 26+, проект генерируется XcodeGen из `ios/project.yml`):
`App/` точка входа · `Core/` API-клиент, Keychain, модели · `Features/Map`, `Features/Chat`, `Features/MyChats`, `Features/ChatInfo` · `DesignSystem/` цвета.

**Android** (Kotlin, Compose, Material 3, minSdk 26):
`core/` API (Ktor client), Block Store, модели · `feature/map`, `feature/chat`, ... · `ui/theme`.
Пока один модуль `app` с пакетами; на модули разобьём, когда станет тесно.
