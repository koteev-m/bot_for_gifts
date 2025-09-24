# Telegram Integration Operations Guide

## Table of Contents
- [1. Overview](#1-overview)
- [2. Environment Variables](#2-environment-variables)
- [3. Webhook (Production Mode)](#3-webhook-production-mode)
- [4. Long Polling (Local/Debug)](#4-long-polling-localdebug)
- [5. Admin API](#5-admin-api)
- [6. Security](#6-security)
- [7. Micrometer/Prometheus Metrics](#7-micrometerprometheus-metrics)
- [8. SLO and Alerting](#8-slo-and-alerting)
- [9. Common Issues and Runbook](#9-common-issues-and-runbook)
- [10. Go-Live Checklist](#10-go-live-checklist)
- [11. Official References](#11-official-references)

## 1. Overview
- Документ описывает операционные аспекты Telegram-интеграции в сервисе на Ktor 3.3.0 и Kotlin 2.2.20: получение апдейтов, безопасность, админ-интерфейс, метрики и SLO.
- Актуальные режимы получения апдейтов: **Webhook** с проверкой `secret_token` и **Long Polling** через `getUpdates(timeout=25)`.
- Режимы взаимоисключаемы: активный webhook блокирует `getUpdates`. Перед запуском Long Polling нужно вызывать `deleteWebhook` без очистки очереди, а перед переключением обратно — останавливать LP-воркер.

## 2. Environment Variables
| Key | Required | Example | Purpose |
| --- | --- | --- | --- |
| `BOT_TOKEN` | yes | `123456:ABC-DEF...` | Токен бота для вызовов Bot API. |
| `BOT_MODE` | no (defaults to `webhook`) | `long_polling` | Выбор режима получения апдейтов. |
| `WEBHOOK_SECRET_TOKEN` | yes in webhook mode | `super-secret-value` | Секрет для `setWebhook` и валидации заголовка `X-Telegram-Bot-Api-Secret-Token`. |
| `WEBHOOK_PATH` | no (defaults to `/telegram/webhook`) | `/custom/telegram` | Локальный путь вебхука, добавляется к `PUBLIC_BASE_URL`. |
| `PUBLIC_BASE_URL` | yes for admin-triggered `setWebhook` | `https://bot.example.com` | Публичный базовый URL, доступный Bot API. |
| `ADMIN_TOKEN` | no | `random-admin-token` | Токен для защиты `/internal`-маршрутов; без него админ-эндпоинты не поднимаются. |

## 3. Webhook (Production Mode)
- Подключение: `setWebhook(url = PUBLIC_BASE_URL + WEBHOOK_PATH, secret_token = WEBHOOK_SECRET_TOKEN)`. Telegram принимает HTTPS на портах 443, 80, 88 или 8443; сертификат должен быть валидным.
- На каждый запрос Bot API отправляет заголовок `X-Telegram-Bot-Api-Secret-Token`; приложение обязано сверять значение с `WEBHOOK_SECRET_TOKEN` и при несоответствии возвращать `403`.
- Ограничения приёма:
  - Поддерживаем только `application/json`; пустой `Content-Type` допускается и обрабатывается как JSON.
  - Лимит тела запроса — 1 МБ. Превышение завершается `413 Payload Too Large` без попытки обработки.
  - Обработчик мгновенно отвечает `200 OK` с телом `"ok"`. Дальнейшая обработка апдейта выполняется асинхронно через очередь, чтобы не блокировать Bot API.
- Примерные команды Bot API (замените плейсхолдеры):
  ```bash
  curl -X POST "https://api.telegram.org/bot<YOUR_BOT_TOKEN>/setWebhook" \
    -d "url=<PUBLIC_BASE_URL><WEBHOOK_PATH>" \
    -d "secret_token=<WEBHOOK_SECRET_TOKEN>" \
    -d "drop_pending_updates=false"

  curl -X POST "https://api.telegram.org/bot<YOUR_BOT_TOKEN>/deleteWebhook" \
    -d "drop_pending_updates=true"

  curl "https://api.telegram.org/bot<YOUR_BOT_TOKEN>/getWebhookInfo"
  ```

## 4. Long Polling (Local/Debug)
- Перед запуском Long Polling обязательно вызвать `deleteWebhook(drop_pending_updates=false)` и дождаться подтверждения Bot API.
- Цикл опроса: `getUpdates(offset, timeout=25)`. После успешной обработки батча необходимо увеличивать `offset` до `lastUpdateId + 1`; это подтверждает апдейты и предотвращает повторную доставку.
- Допускается только один LP-воркер на бота. При переключении на webhook LP-воркер нужно останавливать.

## 5. Admin API
- Все эндпоинты защищены заголовком `X-Admin-Token: <ADMIN_TOKEN>` и не доступны, если переменная `ADMIN_TOKEN` не задана.
- `POST /internal/telegram/webhook/set`
  - JSON-параметры: `url` (по умолчанию `PUBLIC_BASE_URL + WEBHOOK_PATH`), `allowedUpdates` (список строк), `maxConnections` (1–100), `dropPending` (bool).
  - Действие: вызывает `setWebhook` с переданными параметрами и `WEBHOOK_SECRET_TOKEN`.
- `POST /internal/telegram/webhook/delete?dropPending=true|false`
  - Удаляет webhook, проксируя `drop_pending_updates`. Используется перед запуском LP или при обслуживании.
- `GET /internal/telegram/webhook/info`
  - Возвращает актуальный ответ `getWebhookInfo` без секретных значений; токены не логируются.

## 6. Security
- Webhook-приёмник сравнивает заголовок `X-Telegram-Bot-Api-Secret-Token` с `WEBHOOK_SECRET_TOKEN`; расхождение приводит к `403`.
- Ограничиваем размер тела (1 МБ) и отвечаем `413`, если лимит превышен. Несоответствие `Content-Type` → `415`.
- Логи и метрики не содержат секретов. Для трассировки используем `CallId`/`X-Request-Id` (плагин Ktor CallId). Payload апдейтов не логируем.

## 7. Micrometer/Prometheus Metrics
- Поставщик метрик — Micrometer 1.15.1 с Prometheus Registry; экспозиция доступна на `/metrics`.
- Имена метрик фиксированы, без динамических лейблов:
  - **Webhook:** `tg_webhook_updates_total`, `tg_webhook_rejected_total`, `tg_webhook_body_too_large_total`, `tg_webhook_enqueue_seconds` (timer).
  - **Очередь:** `tg_queue_size` (gauge), `tg_updates_enqueued_total`, `tg_updates_duplicated_total`, `tg_updates_dropped_total`, `tg_update_handle_seconds` (timer), `tg_updates_processed_total`.
  - **Long Polling:** `tg_lp_requests_total`, `tg_lp_responses_total`, `tg_lp_batches_total`, `tg_lp_updates_total`, `tg_lp_request_seconds` (timer), `tg_lp_errors_total`, `tg_lp_retries_total`, `tg_lp_cycles_total`, `tg_lp_offset_current` (gauge).
  - **Admin:** `tg_admin_webhook_set_total`, `tg_admin_webhook_delete_total`, `tg_admin_webhook_info_total`, `tg_admin_webhook_fail_total`.
- Типовые правила alerting в Prometheus:
  - Резкий рост `...errors_total` или `tg_admin_webhook_fail_total`.
  - Отсутствие инкремента `...updates_total` (по вебхуку или LP) дольше ожидаемого окна.
  - `tg_queue_size` выше заданного порога (сигнал перегрузки или стопора воркеров).

## 8. SLO and Alerting
- **Webhook:** успешная доставка ≥ 99.9% за 30 дней; `p95` `tg_webhook_enqueue_seconds` < 100 мс.
- **Long Polling:** доля успешных `getUpdates` ≥ 99%; `tg_lp_offset_current` должен монотонно расти.
- **Очередь:** `tg_queue_size` не упирается в верхний предел; `tg_updates_duplicated_total` остаётся в пределах исторической нормы.
- Минимальный набор алертов:
  - Высокая доля 5xx на вебхуке или LP-обработчике.
  - Всплески `tg_webhook_body_too_large_total` (проверить источник и лимиты).
  - `tg_admin_webhook_fail_total > 0` (проверить доступность Bot API и токены).

## 9. Common Issues and Runbook
- **403 на webhook:** убедитесь, что `secret_token` в `setWebhook` совпадает с `WEBHOOK_SECRET_TOKEN`, и что прокси/балансировщик не вырезает заголовок `X-Telegram-Bot-Api-Secret-Token`.
- **415 Unsupported Media Type:** клиент или прокси отправляет неподдерживаемый `Content-Type`. Зафиксируйте `application/json` или пустой заголовок.
- **413 Payload Too Large:** входящий апдейт превысил 1 МБ. Проверьте источник и при необходимости увеличьте лимит, согласовав с рисками.
- **Long Polling "молчит":** webhook не удалён. Вызовите `deleteWebhook`, проверьте, что `tg_lp_offset_current` растёт.
- **Дубликаты апдейтов:** это нормальное поведение при ретраях Bot API. Система хранит TTL дедупликации ≈ 26 часов; следите за `tg_updates_duplicated_total`.
- **Пики нагрузки:** очередь настроена на режим `DROP_OLDEST`. При росте `tg_updates_dropped_total` масштабируйте воркеры или оптимизируйте обработку.

## 10. Go-Live Checklist
- Webhook зарегистрирован с корректным `secret_token`; `/metrics` отдаёт метрики, алерты заведены в мониторинге.
- Убедитесь, что включён только один режим: webhook активен — LP остановлен (и наоборот).
- Все админ-маршруты защищены `X-Admin-Token`, секреты загружены из ENV, логи не содержат конфиденциальных данных.
- Проведён нагрузочный дым-тест: серия апдейтов, проверка метрик и соблюдения SLO.

## 11. Official References
- Telegram Bot API — Updates, Webhooks: <https://core.telegram.org/bots/api#update>
- Telegram Bot API — setWebhook/deleteWebhook/getWebhookInfo: <https://core.telegram.org/bots/api#setwebhook>
- Telegram Bot API — getUpdates: <https://core.telegram.org/bots/api#getupdates>
- Telegram Bot API — Bot API FAQ: <https://core.telegram.org/bots/faq>
- Ktor Server — CallId plugin: <https://ktor.io/docs/v2/server-call-id.html>
- Ktor Server — Micrometer Metrics: <https://ktor.io/docs/v2/server-monitoring-micrometer.html>
