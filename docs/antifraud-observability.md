# Антифрод: наблюдаемость и эксплуатация

## 1. Обзор
Антифродовый контур покрывает RateLimitPlugin (ограничения по IP и Subject), VelocityChecker с эвристиками, бан-лист IP, платежные пути (`/telegram/invoice`, `/telegram/pre-checkout`, `/telegram/success`), обработку вебхука, очередь Telegram, RNG-компоненты. Мы блокируем попытки только до фактической оплаты: на стадиях invoice и pre-checkout возможен HARD_BLOCK. После списания средств действуют только режимы `LOG_ONLY` и `SOFT_CAP`, блокировки не применяются.

## 2. Таксономия метрик
Все метрики отдаются через Micrometer PrometheusRegistry. Теги фиксированы, без динамических значений по path/IP/userId.

### Rate limit (10.2)
- `af_rl_allowed_total{type="ip|subject"}` — разрешенные попытки RateLimitPlugin.
- `af_rl_blocked_total{type="ip|subject"}` — заблокированные RateLimitPlugin.
- `af_rl_retry_after_seconds_count` / `af_rl_retry_after_seconds_sum` — гистограмма времени ожидания, без тегов.

### Admin bans (10.4)
- `af_ip_suspicious_mark_total`
- `af_ip_ban_total{type="perm|temp"}`
- `af_ip_unban_total`
- `af_ip_forbidden_total` — срабатывает при обращении забаненного IP до распределения в бакеты.

### Velocity / Payments (10.3/10.5/7.x)
- `pay_af_flags_total{flag="<VelocityFlag>"}` — учет эвристических флагов.
- `pay_af_decisions_total{type="invoice|precheckout|success|webhook",action="LOG_ONLY|SOFT_CAP|HARD_BLOCK"}` — финальные решения.
- `pay_af_blocks_total{type="invoice|precheckout"}` — жесткие блокировки до оплаты.

### Payments
- `pay_success_total`
- `pay_success_idempotent_total`
- `pay_success_fail_total`
- `award_gift_total`
- `award_premium_total`
- `award_internal_total`
- `award_fail_total`
- `refund_total`
- `refund_fail_total`

### Telegram / Webhook / Queue
- `tg_webhook_updates_total`
- `tg_webhook_rejected_total`
- `tg_webhook_body_too_large_total`
- `tg_webhook_enqueue_seconds_count`
- `tg_updates_enqueued_total`
- `tg_updates_duplicated_total`
- `tg_updates_dropped_total`
- `tg_update_handle_seconds_count`
- `tg_updates_processed_total`
- `tg_queue_size` (gauge)

### RNG
- `rng_commit_total`
- `rng_reveal_total`
- `rng_draw_total`
- `rng_draw_idempotent_total`
- `rng_draw_fail_total`

### HTTP (Micrometer/Ktor)
- `http_server_requests_seconds_bucket|count|sum` — стандартные гистограммы латентности с тегами `uri`, `status`, `method`. Используем шаблонные пути, динамический raw-path/IP/userId запрещены.

**Запрещено** добавлять теги `path`, `ip`, `userId` и другие высоко-кардинальные метки.

## 3. SLI/SLO
### Webhook
- **SLO:** коэффициент успешных ответов (2xx) ≥ 99.9% на 30-дневном окне.
- **SLI:** `webhook_success_ratio = sum(rate(http_server_requests_seconds_count{uri="/telegram/webhook",status=~"2.."}[5m])) / sum(rate(http_server_requests_seconds_count{uri="/telegram/webhook"}[5m]))`
- **Latency SLO:** `p95(http_server_requests_seconds{uri="/telegram/webhook"}) ≤ 0.1s` (окно 30 дней, бюджет ошибок 0.1%).

### Pre-checkout SLA
- **SLO:** ответы на `pre_checkout_query` ≤ 10s в 99.9% случаев за 30 дней.
- **SLI:** `p999(http_server_requests_seconds{uri="/telegram/pre-checkout"}) ≤ 10s`.

### Rate-limit health
- **SLO:** доля 429 (RateLimitPlugin) ≤ 5% за 1 час на invoice.
- **SLI:** `block_rate = sum(rate(af_rl_blocked_total{type="ip"}[5m])) / (sum(rate(af_rl_blocked_total{type="ip"}[5m])) + sum(rate(af_rl_allowed_total{type="ip"}[5m])))`.

### Antifraud hard blocks
- **SLO:** доля `pay_af_blocks_total{type="invoice|precheckout"}` ≤ 2% от всех попыток (часовой интервал, бюджет ошибок 2%).

## 4. PromQL и алерты

```promql
# 4.1 Webhook success ratio (5m)
sum(rate(http_server_requests_seconds_count{uri="/telegram/webhook",status=~"2.."}[5m]))
/
sum(rate(http_server_requests_seconds_count{uri="/telegram/webhook"}[5m]))

# 4.2 Webhook p95 latency (5m)
histogram_quantile(
  0.95,
  sum by (le) (rate(http_server_requests_seconds_bucket{uri="/telegram/webhook"}[5m]))
)

# 4.3 RateLimit block rate (IP) (5m)
sum(rate(af_rl_blocked_total{type="ip"}[5m]))
/
(
 sum(rate(af_rl_blocked_total{type="ip"}[5m])) + sum(rate(af_rl_allowed_total{type="ip"}[5m]))
)

# 4.4 Antifraud hard blocks on invoice (5m)
sum(rate(pay_af_blocks_total{type="invoice"}[5m]))
```

### AlertingRule

```yaml
groups:
- name: antifraud-core
  rules:
  - alert: WebhookHighLatencyP95
    expr: histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{uri="/telegram/webhook"}[5m]))) > 0.2
    for: 10m
    labels:
      severity: warning
      team: antifraud
    annotations:
      summary: "Webhook p95 latency > 200ms"
      runbook_url: "/docs/antifraud-observability.md#runbook"

  - alert: RateLimitBlocksSpike
    expr: (sum(rate(af_rl_blocked_total{type="ip"}[5m])) / (sum(rate(af_rl_blocked_total{type="ip"}[5m])) + sum(rate(af_rl_allowed_total{type="ip"}[5m])))) > 0.15
    for: 15m
    labels:
      severity: critical
      team: antifraud
    annotations:
      summary: "429 rate spike on invoice/precheckout"
      description: "Investigate RateLimit/Velocity config and upstream traffic."

  - alert: AntifraudHardBlocksInvoice
    expr: sum(rate(pay_af_blocks_total{type="invoice"}[10m])) > 5
    for: 10m
    labels:
      severity: warning
      team: antifraud
    annotations:
      summary: "Many antifraud hard blocks on invoice"
      description: "Might be attack or mis-tuned thresholds."
```

## 5. Дэшборд
- **Webhook**: success ratio, p95/p99 latency, QPS, `tg_webhook_body_too_large_total`, `tg_webhook_rejected_total`, `tg_queue_size`.
- **Payments**: invoice count, pre-checkout ok/fail, успешные платежи, idempotent ratio (`pay_success_idempotent_total / pay_success_total`).
- **Antifraud**: `af_rl_allowed_total` vs `af_rl_blocked_total`, block rate, `pay_af_decisions_total` по type/action, топ флагов (`pay_af_flags_total`), действия банлиста (`af_ip_*`).
- **RNG**: `rng_draw_total`, `rng_draw_idempotent_total`, `rng_draw_fail_total`, `rng_commit_total`, `rng_reveal_total`.
- **Errors**: 4xx/5xx rate, `refund_fail_total`, `award_fail_total`.
- **Today view**: 15-минутные stacked панели по ключевым счетчикам для видимости спайков.

## 6. Кардинальность и best practices
- Исключаем теги с неограниченной кардинальностью (`path`, `ip`, `userId`).
- Допустимые теги: `type`, `action`, `flag`, стандартные `uri`, `status`, `method` Micrometer.
- Для HTTP метрик полагаемся на шаблонные URI Ktor (`/telegram/{route}`).
- Гистограммы Micrometer активируем для вебхука и pre-checkout (включены по умолчанию в MicrometerMetrics).

## 7. Логи, requestId и secret hygiene
- Все ошибки логируются с `requestId` (`%X{callId}` из Ktor CallIdPlugin).
- Запрещено выводить значения `BOT_TOKEN`, `WEBHOOK_SECRET_TOKEN`, `ADMIN_TOKEN`, `FAIRNESS_KEY`, `DATABASE_PASSWORD`, `serverSeed`.
- Логгер `io.ktor` выставлен на WARN, наш пакет следует уровню `LOG_LEVEL` (умолчание INFO).
- Payload Telegram и бинарные данные не логируем.

## 8. Runbook
### Spike 429 (invoice)
1. Проверить срабатывание `RateLimitBlocksSpike`, сопоставить с трафиком и маркетинговыми активностями.
2. Оценить рост флагов в Velocity (`pay_af_flags_total`).
3. При необходимости временно увеличить `burst`/`rps` (ENV) или сузить `include-paths`; рассмотреть авто-баны.
4. Залипшие IP банить через admin-роуты (TEMP), отслеживая `af_ip_forbidden_total`.

### Webhook latency
1. Оценить нагрузку на очередь (`tg_queue_size`), CPU, GC.
2. При необходимости увеличить количество воркеров, настроить пулы (DB, coroutine).

### Refund / award fails
1. Выполнить playbook `refundStarPayment`, сопоставить `charge_id` и статус в Telegram.
2. Проверить ответы Telegram API (429/5xx), включить ретраи и алерты.

## 9. Команды проверки
```bash
# локальные проверки
./gradlew ktlintCheck detekt test --console=plain
curl -s localhost:8080/metrics | head -n 50
```
