# Деплой There

План выкатки сервера, ссылок-приглашений, push и приложений в магазины.
Архитектура сервера описана в [ARCHITECTURE.md](ARCHITECTURE.md).

## Статус

| Что | Состояние |
|---|---|
| Домен `there-chat.com` | куплен, DNS у регистратора |
| Google Play Console | аккаунт есть |
| Apple Developer Program | активен; Team ID `UWJ374YJ4M`, приложение в App Store Connect создано |
| Дроплет DigitalOcean | `there1-1gb-fra1`, 1 ГБ / 25 ГБ, настроен (`make provision`), сервер выложен |
| DNS A-записи `@`, `api`, `www` | прописаны, HTTPS работает |
| GitHub `leechy/there-chat` (публичный) | создан |
| Firebase-проект (только FCM для Android) | не создан |
| Почта `support@there-chat.com` | настроена |
| Сайт, `/privacy/`, `/terms/` | выложены (`make web`) |

## 0. Аккаунты и долгие ожидания

- **Apple Developer Program** ($99/год). Без него не работают Universal Links, push, TestFlight.
  Нужен **Team ID** (для `apple-app-site-association`) и ключ APNs `.p8` (Key ID + Team ID).
- **Google Play Console.** Если аккаунт личный и создан после ноября 2023, до публикации
  нужен закрытый тест: 12 тестеров в течение 14 дней. Проверить в консоли, распространяется ли
  это на наш аккаунт, и начать тест как можно раньше.
  Включить Play App Signing и взять оттуда SHA-256 ключа подписи (для `assetlinks.json`).
- **Почта на домене** (`support@`, `abuse@`): пересылка у регистратора, ImprovMX или Cloudflare
  Email Routing. Нужна магазинам и для политики конфиденциальности.

## 1. Дроплет и DNS

**Дроплет**
- Ubuntu 24.04, Basic 1 ГБ / 25 ГБ ($6/мес), регион Frankfurt или Amsterdam.
  Когда понадобится больше — Resize только CPU/RAM (без диска, чтобы можно было вернуться).
  Диск не растим заранее: текстовые сообщения — это ~300 МБ на миллион.
- Swap-файл 1 ГБ: в дроплетах DO swap по умолчанию нет.
- Ограничить журналы: `SystemMaxUse=200M` в `/etc/systemd/journald.conf`.
- На дроплете ничего не собираем: бинарник собирается на маке и копируется готовым.
- Reserved IP (бесплатно), DNS указывает на него, а не на сам дроплет.
- Cloud Firewall: входящие 22, 80, 443.
- Еженедельные бэкапы дроплета (+20%).
- Вход только по SSH-ключу; отдельный пользователь `there` без shell для сервиса.

**DNS у регистратора** (A-записи на Reserved IP; AAAA, если включён IPv6):

| Имя | Назначение |
|---|---|
| `there-chat.com` | сайт, страницы приглашений, `.well-known` |
| `api.there-chat.com` | API для приложений |
| `admin.there-chat.com` | админ-консоль операторов |

**Сервер на машине**
- Go-бинарник (`CGO_ENABLED=0 GOOS=linux GOARCH=amd64`, SQLite на чистом Go) в `/opt/there/`,
  база в `/var/lib/there/there.db`.
- systemd-юнит `there.service` (`Restart=always`, `User=there`, переменные `THERE_*`).
- Caddy перед ним: автоматический HTTPS, проксирование по доменам.
- **Litestream** непрерывно реплицирует SQLite в DO Spaces ($5/мес). Восстановление:
  `litestream restore`. Проверить восстановление один раз до запуска.
- Мониторинг: внешний пинг `https://api.there-chat.com/healthz` (UptimeRobot или DO Uptime).

**Деплой**
- Автоматически при пуше в `main`, через GitHub Actions (ниже).
- Вручную с мака: `make deploy` (тесты → сборка → `scp` → `systemctl restart there`) и `make web`.

**Выкладка через GitHub Actions**
- `.github/workflows/deploy-server.yml`: при изменениях в `server/` тесты, сборка, загрузка,
  перезапуск, проверка `/healthz`. На пул-реквестах — только тесты.
- `.github/workflows/deploy-web.yml`: при изменениях в `web/` загрузка сайта.
- Обе можно запустить вручную: Actions → workflow → Run workflow.
- Заходят на дроплет пользователем `deploy`: он владеет `/opt/there` и `/var/www/there` и через
  sudo может только `systemctl restart there`. Его единственный ключ — публичная половина в
  `deploy/github-actions.pub`; ключ ограничен опцией `restrict` (без терминала и туннелей).
  Пользователя создаёт `make provision`.
- В GitHub (Settings → Environments → `production`):
  - секрет `DEPLOY_SSH_KEY` — приватный ключ к `deploy/github-actions.pub`;
  - переменная `DEPLOY_IP` — IP дроплета;
  - переменная `DEPLOY_KNOWN_HOSTS` — строка `ssh-keyscan -t ed25519 <IP>`, сверенная с
    `~/.ssh/known_hosts` на маке.
- Пул-реквесты из форков секретов не получают и в `production` не выкладывают.
- Заменить ключ: новый `ssh-keygen -t ed25519`, публичную половину в `deploy/github-actions.pub`,
  `make provision`, приватную — в секрет.

## 2. Ссылки-приглашения

Firebase Dynamic Links закрыты (август 2025), поэтому всё на своём домене.

- **Приложение установлено**: `https://there-chat.com/<код>` система открывает сразу в приложении.
  - iOS (Universal Links): `https://there-chat.com/.well-known/apple-app-site-association`
    (`application/json`, без редиректов) с `TEAMID.com.there-chat.app`;
    в приложении entitlement `applinks:there-chat.com`.
  - Android (App Links): `https://there-chat.com/.well-known/assetlinks.json` с
    `com.therechat.app` и SHA-256 ключа подписи из Play Console (и отладочного ключа для тестов);
    в манифесте intent-filter с `android:autoVerify="true"` (уже есть).
- **Приложения нет**: веб-страница приглашения.
  - Название и эмодзи чата; og-теги для превью в мессенджерах (скрытые чаты без названия).
  - Кнопки App Store / Google Play, на iOS Smart App Banner (`apple-itunes-app`).
- **Переход в чат после установки**: на Android через Play Install Referrer
  (`referrer=<код>`); на iOS чистого способа нет, в v1 текст «после установки нажмите ссылку ещё раз».

Страницу и оба `.well-known`-файла отдаёт тот же Go-сервер: весь продакшн — один бинарник.

## 3. Push-уведомления

- **Android**: FCM HTTP v1. Нужен Firebase-проект, приложение `com.therechat.app`,
  `google-services.json` в приложении и ключ сервисного аккаунта на сервере.
- **iOS**: напрямую в APNs из Go по токену (ключ `.p8`), без Firebase SDK в приложении:
  меньше зависимостей и честнее для анонимного приложения.
- Сервер: очередь отправки, учёт `notify` по каждому участию, удаление невалидных токенов.
  Код ещё не написан.

## Сборка Android для Google Play

- Upload key: `android/upload-keystore.jks`, пароль и алиас лежат в `android/keystore.properties`.
  Оба файла в git не попадают, их резервная копия хранится в менеджере паролей. Если ключ
  потерян, его можно заменить через поддержку Play, но на это уходят дни.
- Play переподписывает сборки своим ключом (Play App Signing, Play Console → Test and
  release → App integrity). Все SHA-1 ниже должны быть в ограничениях ключа Google Maps
  вместе с пакетом `com.therechat.app`, иначе карта будет пустой:

  | Ключ | SHA-1 |
  |---|---|
  | Play App Signing (основной) | `66:9F:E4:10:E4:05:DB:EC:14:93:DF:FA:B8:C8:69:E8:77:50:1C:80` |
  | Play, дополнительный | `C3:EB:B9:26:13:A7:88:B9:64:3A:11:E1:2A:B2:7D:F5:DC:29:EE:7C` |
  | Play, дополнительный | `B3:4C:64:24:98:FC:77:9E:34:2A:A8:22:B2:13:8F:F0:86:23:C1:AE` |
  | Upload key | `4A:B5:42:02:DD:EF:C1:9E:23:F4:BF:61:57:2D:09:EC:0D:4C:15:92` |
  | Debug (этот мак) | `E4:69:27:23:09:5F:2A:C1:5C:1C:0E:24:A1:F5:41:DD:3D:E6:64:25` |
- Сборка: `cd android && ./gradlew :app:bundleRelease`, на выходе
  `app/build/outputs/bundle/release/app-release.aab`. Перед каждой загрузкой нужно
  увеличивать `versionCode` в `app/build.gradle.kts`.

## Сборка iOS для TestFlight

- Подпись автоматическая, команда `UWJ374YJ4M` (в `ios/project.yml`). В Xcode нужно войти
  Apple ID: Settings → Accounts.
- `cd ios && xcodegen generate`, открыть `There.xcodeproj`, устройство «Any iOS Device (arm64)»,
  Product → Archive → Distribute App → App Store Connect → Upload.
- Перед каждой загрузкой увеличивать `CURRENT_PROJECT_VERSION` в `ios/project.yml`
  (версию `MARKETING_VERSION` — при новом релизе).
- `ITSAppUsesNonExemptEncryption = NO`: только HTTPS, вопрос про шифрование не задаётся.

## 4. Доработки сервера и приложений до выхода

- [ ] Rate limiting: регистрация устройств, отправка сообщений, создание чатов, вступления.
- [ ] Реальный IP из `X-Forwarded-For` только от Caddy.
- [ ] `.well-known` и страница приглашения `there-chat.com/<код>`.
- [ ] Push (см. выше).
- [ ] Личная блокировка («скрыть этого человека») в дополнение к блокировке админом чата —
      требование App Store 1.2 для приложений с пользовательским контентом.
- [ ] Политика конфиденциальности и условия использования (с нулевой терпимостью к нарушителям)
      на `there-chat.com/privacy` и `/terms`; принятие условий при первом запуске.
- [ ] Privacy Labels (App Store) и Data Safety (Google Play), обоснование геолокации.
- [ ] `make deploy`, systemd-юнит, конфиг Caddy, конфиг Litestream в репозитории (`deploy/`).

## 5. Админ-консоль операторов

Для команды There, а не для админов чатов. Нужна, чтобы реагировать на жалобы в течение
24 часов (требование App Store).

- **Функции**: очередь жалоб; сообщение в контексте чата; удалить сообщение или чат;
  скрыть чат с карты; глобально забанить устройство; простая статистика.
- **Реализация**: часть того же Go-бинарника, страницы на `html/template` + htmx,
  без отдельной фронтенд-сборки.
- **Где**: `admin.there-chat.com`; консоль слушает отдельный порт на `127.0.0.1`, поэтому
  недоступна через API. Снаружи — Caddy с `basic_auth`; позже можно закрыть Tailscale.
- **Деплой**: вместе с сервером.
- **Почта поддержки**: письма на `support@` показывать в консоли рядом с очередью жалоб
  (сервер забирает их по IMAP или получает вебхук от почтового провайдера). В закрытый чат
  операторов в There — только уведомление «новое письмо, тема такая-то», без текста: в письмах
  бывают чужие адреса, запросы на удаление и описания насилия над детьми, им не место в
  обычных сообщениях и push. Когда-нибудь потом — отвечать на письма прямо из консоли
  (свой маленький email-клиент).

## Расходы

| Статья | Сумма |
|---|---|
| Дроплет 1 ГБ + бэкапы | ~$7/мес |
| DO Spaces (Litestream) | $5/мес |
| Apple Developer Program | $99/год |
| Google Play | $25 разово (уже оплачено) |
