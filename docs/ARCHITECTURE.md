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

Ошибки: `{"error": {"code": "zoom_in", "message": "..."}}`.

Действия над участником (`{action}`): `approve`, `reject`, `promote`, `demote`, `block`, `unblock`. Владельца модерировать нельзя; других админов — только владелец.

Системные сообщения (`kind: system`) несут событие в `body`: `joined`, `requested`, `left`, `blocked`, `promoted`, `renamed:<старое имя>`.

Читать открытый видимый чат можно без вступления. Закрытый — только участникам; остальные видят заголовок и кнопку заявки.

## Realtime

> **Сейчас (v0.1):** WebSocket ещё не сделан. Открытый чат опрашивает `GET /messages` и `GET /chats/{id}` каждые 3 секунды и сливает последнюю страницу — так подтягиваются и новые сообщения, и реакции, удаления, модерация. Интерфейс клиентов от этого не зависит, WebSocket можно добавить позже.

`GET /v1/ws` (WebSocket, тот же Bearer-токен). Сервер шлёт события по всем чатам, где устройство — активный участник, плюс по чату, который клиент сейчас открыл (`{"type":"watch","chat_id":...}`), — для чтения открытого чата без вступления.

```json
{"type": "message.created", "chat_id": "...", "seq": 42, "message": {...}}
```

Типы: `message.created`, `message.updated` (одобрено / удалено / реакции), `member.updated`, `chat.updated`, `chat.deleted`.

Сообщения в премодерации: автор и админы получают полное сообщение, остальные — без `body` и с `status: "pending"`.

После переподключения клиент догружает пропущенное через `GET messages?after=<последний seq>`. WebSocket — только ускоритель, источник правды — REST.

На старте хаб событий живёт в памяти процесса (один сервер). Если серверов станет несколько — Redis pub/sub или Postgres `LISTEN/NOTIFY`.

## Push

- iOS: APNs, авторизация по ключу `.p8`.
- Android: FCM HTTP v1, сервисный аккаунт.
- Отправляется, только если у участника `notify = true` и нет активного WebSocket.
- Текст уведомления: название чата и имя автора; тело сообщения — по настройке (по умолчанию без текста, ради анонимности).

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
