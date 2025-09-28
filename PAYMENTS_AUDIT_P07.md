# Резюме
1. Invoice (XTR): Pass — `TelegramInvoiceService` формирует один `LabeledPrice`, валюта XTR, `provider_token` не задаётся. См. `TelegramInvoiceService.kt`.
2. Pre-checkout: Pass — `WebhookUpdateRouter` роутит pre-checkout, `PreCheckoutHandler` отвечает через `withTimeout(10_000)`, валидирует валюту и сумму. См. `WebhookUpdateRouter.kt`, `PreCheckoutHandler.kt`.
3. Successful payment: Pass — `SuccessfulPaymentHandler` идемпотентен по `telegram_payment_charge_id`, парсит payload и вызывает `rngService.draw`; квитанция отправляется только при `receiptEnabled=true`. См. `SuccessfulPaymentHandler.kt`.
4. AwardService: Pass — `TelegramAwardService` покрывает подарки, Premium (3/6/12 мес, 1000/1500/2500 звёзд) и внутренние призы, идемпотентен и пишет метрики. См. `AwardService.kt`.
5. RefundService: Pass — `SuccessfulPaymentHandler` и `TelegramAwardService` вызывают `refundStarPayment` только для XTR, `TelegramApiClient.execute` ретраит сетевые/5xx; причины логируются без секретов. См. `SuccessfulPaymentHandler.kt`, `AwardService.kt`, `RefundService.kt`, `TelegramApiClient.kt`.
6. Безопасность/логи: Pass — секреты не логируются, webhook секрет и лимиты проверены (`TelegramIntegrationConfig`, `WebhookRoutes`).
7. Наблюдаемость: Pass — `pay_*`, `award_*`, `refund_*` метрики инкрементируются с фиксированными тегами. См. соответствующие хендлеры.
8. Тесты: Pass — `PaymentsFlowTest`, `PreCheckoutHandlerTest`, `SuccessfulPaymentHandlerTest`, `AwardServiceTest`, `RefundServiceTest` покрывают позитивные и негативные сценарии; `./gradlew test` зелёный.

# Команды прогонов
- `./gradlew ktlintCheck detekt --console=plain`
- `./gradlew test --console=plain`

# Несоответствия и фиксы
- Не выявлено.
