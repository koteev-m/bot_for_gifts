# Бот-витрина цифровых кейсов с призами

## Стек и версии (зафиксировано)
| Компонент | Версия | Источник |
| --- | --- | --- |
| Kotlin | 2.2.20 | Kotlin 2.2.20 Release Notes |
| Gradle | 9.0.0 | Gradle 9.0 Release Notes |
| JDK (toolchain) | 21 | Temurin 21 LTS |
| Ktor (server & client) | 3.3.0 | Ktor 3.3.0 Docs |
| kotlinx.coroutines | 1.10.2 | Maven Central |
| kotlinx.serialization | 1.9.0 | Maven Central |
| SLF4J | 2.0.17 | slf4j.org |
| Logback | 1.5.18 | logback.qos.ch |
| Micrometer BOM | 1.15.1 | Micrometer Docs |
| Micrometer Prometheus Registry | 1.15.1 | Micrometer Docs |
| JUnit BOM | 5.13.4 | JUnit Release Notes |
| MockK | 1.14.5 | Maven Central |
| HikariCP | 7.0.2 | Maven Central |
| PostgreSQL JDBC | 42.7.7 | postgresql.org |
| JDBI | 3.49.5 | JDBI Documentation |
| dotenv-kotlin | 6.5.1 | Maven Central |
| ktlint-gradle | 13.1.0 | ktlint-gradle Release Notes |
| detekt | 1.23.8 | detekt Releases |
| Versions Plugin | 0.52.0 | Gradle Versions Plugin |

## Совместимость и заметки
- Kotlin 2.2.20 совместим с kotlinx.coroutines 1.10.2 и kotlinx.serialization 1.9.0, которая требует Kotlin 2.2+.
- Ktor 3.3.0 покрывает сервер и клиент, включая плагины CallId и MicrometerMetrics.
- SLF4J 2.0.17 согласован с Logback 1.5.18.
- Gradle 9.0 с toolchain JDK 21 требует минимум Java 17 в окружении.
- Все версии управляются через Version Catalog; плагины подключены по alias’ам (кроме стандартного `application`).
- Используются только стабильные релизы без SNAPSHOT-зависимостей.

## Команды проверки
- `./gradlew -v`
- `./gradlew dependencies`
- `./gradlew build`
- `./gradlew staticCheck`
- `./gradlew run`

## Documentation
- [Telegram Integration Operations Guide](docs/telegram-integration.md)

## Сервер приложения

### Что делает сервис
- HTTP-сервис на Ktor 3.3.0, который запускается на Netty и обслуживает базовые маршруты `/health`, `/metrics`, `/version`, а также SPA Mini App по `/app` с отдачей ассетов из `miniapp/dist`.
- Для статики Mini App включены `ETag`/`Last-Modified`, чтобы браузер корректно кэшировал бандлы.
- Конфигурация портов и путей читается из переменных окружения (`PORT`, `HEALTH_PATH`, `METRICS_PATH`) или из `application.conf`; логирование и Prometheus-метрики настроены по умолчанию.

### Быстрый старт без Docker
1. Скопируйте пример конфигурации: `cp .env.example .env` и при необходимости обновите значения (`PORT`, `LOG_LEVEL`, `HEALTH_PATH`, `METRICS_PATH`).
2. Соберите Mini App: `cd miniapp && npm ci && npm run build && cd ..` — статические файлы окажутся в `miniapp/dist`.
3. Установите токен бота: экспортируйте `TELEGRAM_BOT_TOKEN` (или пропишите в `.env`). Он используется для HMAC-проверки `initData` и обязателен для доступа к `/api/miniapp/*`.
4. Запустите приложение: `./gradlew run` — сервер поднимется на `http://localhost:8080` или на порт из переменной `PORT` и будет отдавать Mini App по `/app`.

Путь к бандлу можно переопределить переменной `MINIAPP_DIST`, системным свойством `miniapp.dist` или ключом `app.miniapp.dist` в `application.conf`.

Мини-API `/api/miniapp/*` защищено плагином `WebAppAuthPlugin`: он извлекает `initData` из query/body/headers, сверяет HMAC-SHA256 по алгоритму Telegram и прокидывает `user_id`, `chat_type`, `auth_date` в `call.attributes`.

Эндпоинт `/api/miniapp/cases` возвращает публичные данные витрины (id, название, цену в звёздах, миниатюру и короткое описание) из `src/main/resources/cases.yaml`. Ответы помечаются `Cache-Control: no-store`, чтобы Mini App всегда подтягивал актуальный конфиг.
3. Запустите приложение: `./gradlew run` — сервер поднимется на `http://localhost:8080` или на порт из переменной `PORT` и будет отдавать Mini App по `/app`.

Путь к бандлу можно переопределить переменной `MINIAPP_DIST`, системным свойством `miniapp.dist` или ключом `app.miniapp.dist` в `application.conf`.

### Проверка ручками
```bash
curl -sS localhost:8080/health
curl -sS localhost:8080/metrics | head -n 5
curl -sS localhost:8080/version
curl -I localhost:8080/app
```

### Docker
```bash
docker build -t gifts-bot:dev .
docker run --rm -p 8080:8080 --env-file .env gifts-bot:dev

# либо docker-compose
docker-compose up --build
```

### Примечание
Интеграция с Telegram появится в Промпте №4.

## 1. Обзор проекта
- Telegram-бот с витриной кейсов и цифровыми призами, доступной через Mini App/WebApp.
- Пользователи оплачивают кейсы в звёздах (XTR), участвуют в розыгрышах и получают Gifts или Telegram Premium.
- Ценность: прозрачные кейсы с предсказуемой экономикой для владельца, быстрые цифровые призы для пользователей, поддержка Stars как нативного метода оплаты в Telegram.
- Поддерживаемые режимы получения апдейтов Bot API: Webhook и Long Polling.

## 2. Норматив и ограничения платформы Telegram
- Платежи за цифровые товары: используем только Telegram Stars (валюта «XTR») через `sendInvoice(currency="XTR")`, `provider_token` не требуется.
- Сценарий оплаты: `sendInvoice` → `pre_checkout_query` → `answerPreCheckoutQuery` → `successful_payment`; приз доставляется после фиксации `successful_payment`. Возвраты выполняются методом `refundStarPayment`.
- Вебхуки: при `setWebhook` обязательно указывать `secret_token`; входящие запросы содержат заголовок `X-Telegram-Bot-Api-Secret-Token`, который проверяем на сервере.
- Mini Apps/WebApp: параметр `initData` валидируем по алгоритму HMAC-SHA256 с ключом «WebAppData»; данные `initDataUnsafe` игнорируем.
- Подарки: каталог получаем через `getAvailableGifts`, выдачу выполняем `sendGift`.
- Telegram Premium: используем `giftPremiumSubscription` с фиксированными периодами 3/6/12 месяцев в XTR.
- Официальные разделы для изучения: Telegram Payments, Stars & Monetization, Bot API Updates & Webhooks, Web Apps for Bots, Gifts & Premium.

## 3. Глоссарий проекта
- **XTR** — Telegram Stars, внутренняя валюта экосистемы для оплаты цифровых товаров.
- **Цена кейса P (XTR)** — стоимость кейса, списываемая при оплате.
- **EV_ext** — ожидаемая внешняя себестоимость приза на кейс (учитываются только Gifts и Premium, реальные расходы в XTR).
- **RTP_ext = EV_ext / P** — доля цены кейса, уходящая во внешние затраты.
- **XT_net (нетто, XTR)** — ожидаемый чистый результат на кейс: `XT_net = P − EV_ext − JP − EV_cashback`, где `JP = α·P` — отчисления в джекпот, `EV_cashback` — ожидаемый кэшбэк.
- **Профили экономики** — режимы «бережный», «сбалансированный», «щедрый» в пределах допустимых окон RTP_ext.
- **Pity-таймер** — гарантия среднего внешнего приза после N неудач; его влияние включается в расчёт `EV_ext`.
- **Джекпот** — накопительный пул из доли `α` цены каждого кейса; вероятность выигрыша выбираем так, чтобы `EV(jackpot) ≤ α·P`.

## 4. Цели и SLA/качественные требования
- Бизнес-цель: положительная валовая маржа в среднем, ориентир ≥35% на массовых кейсах, устойчивость к пиковым нагрузкам и доказуемая честность (provably fair).
- Надёжность: 99.9% успешных ответов webhook, p95 времени обработки <100 мс, устойчивость к дубликатам апдейтов.
- Идемпотентность финансовых операций: выплаты, возвраты, начисления джекпота выполняются ровно один раз на `telegram_payment_charge_id` и `invoice_payload`.
- Логирование: без секретов, но с трассировкой `update_id`, `telegram_payment_charge_id` и бизнес-контекста.

## 5. Поток платежей и доставки призов (высокоуровневый)
- Платёж в XTR: `sendInvoice` → `pre_checkout_query` → `answerPreCheckoutQuery` → `successful_payment` → запись транзакции → розыгрыш кейса → выдача приза → квитирование пользователя.
- Возвраты: инициируем `refundStarPayment` при сбоях доставки, ошибках инвентаря или отмене заказа до выдачи приза.
- Идемпотентность: все операции идентифицируются `telegram_payment_charge_id` и `invoice_payload`; повторная обработка апдейтов не приводит к двойной выдаче.
- Ретраи: сетевые сбои и временные 5xx повторяем с экспоненциальной задержкой; повторные апдейты от Telegram обрабатываются безопасно.

## 6. Безопасность
- Проверяем заголовок `X-Telegram-Bot-Api-Secret-Token` для каждого webhook-запроса.
- Валидируем `initData` Mini App HMAC-SHA256 с ключом «WebAppData»; `initDataUnsafe` не используем.
- Секреты (bot token, ключи подписи) получаем только из переменных окружения или секрет-хранилищ; в логах не печатаем.
- Анти-фрод: ограничение частоты покупок, капы на редкие призы, аномалия-детект по сочетанию user_id и платежных идентификаторов.

## 7. Экономика: рамки по умолчанию
- Базовая линейка цен кейсов (XTR): 29, 99, 299, 999, 2499, 4999.
- Целевые окна RTP_ext по тиру:
  - Micro 29: 35–45%
  - Bronze 99: 45–50%
  - Silver 299: 47–55%
  - Gold 999: 40–50%
  - Diamond 2499: 30–40%
  - Mythic 4999: 25–35%
- Отчисления в джекпот `α`: 1–5% в зависимости от тира.
- Это стартовые коридоры; точные значения и витрину подарков уточним на этапе эконом-конфигурации.

## 8. Чек-лист соблюдения правил Telegram
- Платежи цифровых товаров — только Stars/XTR; `provider_token` не требуется.
- Приз выдаём строго после `successful_payment`.
- Вебхук настраиваем с `secret_token`; Mini App всегда проверяет `initData`.
- Gifts — через `getAvailableGifts` и `sendGift`; Premium — через `giftPremiumSubscription` с периодами 3/6/12 месяцев.
- Обязательные команды бота: `/terms`, `/paysupport`, `/privacy` (контент подготовим отдельно).

## Ссылки
- Telegram Payments — описание платежного API и цифровых товаров на Stars.
- Stars & Monetization — правила валюты XTR и возвратов.
- Bot API Updates & Webhooks — требования к webhook, `secret_token`, ретраям.
- Web Apps for Bots — спецификация Mini App и алгоритм проверки `initData`.
- Gifts & Premium — каталоги подарков и выдача Telegram Premium.

Что дальше: перейти к настройке версий и скелету проекта (Промпт №2).
