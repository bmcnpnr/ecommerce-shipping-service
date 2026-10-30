# ecommerce-shipping-service

Shipments and parcel tracking. When payment-service publishes
`payment.completed`, this service creates a shipment for the order: status
`PENDING`, carrier DHL, a `TRACK-XXXXXXXX-X` tracking number, delivery in five
days. It then publishes `shipment.created`. Status changes come through
`PATCH /api/v1/shipments/{id}/status`, which records a history entry and
publishes `shipment.updated`. Anyone with a tracking number can look a shipment
up, without logging in.

| | |
|---|---|
| Port | 8086 |
| Gateway routes | `/api/v1/shipments/**` (`/tracking/**` is public) |
| Data store | PostgreSQL `shippingdb` (`shipments`, `shipment_events`, Flyway) |
| Publishes / consumes | `shipment.created`, `shipment.updated` / `payment.completed` (group `shipping-service`) |
| Docs | [Deep dive](https://github.com/bmcnpnr/ecommerce-platform/blob/develop/docs/services/shipping-service.md) · [API reference](https://github.com/bmcnpnr/ecommerce-platform/blob/develop/docs/api/shipping-service.md) · [Event catalog](https://github.com/bmcnpnr/ecommerce-platform/blob/develop/docs/events.md) · Swagger UI at http://localhost:8080/swagger-ui.html (`shipping-service`) |

With the repositories checked out side by side, the same docs are in
`../ecommerce-platform/docs/`.

> **Known gaps** (see the deep dive): shipments created from Kafka get
> placeholder addresses, because the order's address never reaches this
> service; any authenticated user can change any shipment's status; public
> tracking reveals the customer ID and addresses.

## Build and run

```bash
mvn -B verify                       # unit and contract tests (H2, embedded Kafka)
mvn -B clean package -DskipTests    # the jar the Docker image copies
cd ../ecommerce-platform && docker compose up -d --build shipping-service
```

Kafka is configured in `config/KafkaProducerConfig.java`. Events are JSON written with
the mapper the Kafka producer always used, with `LocalDateTime` kept as integer arrays
(`WRITE_DATES_AS_TIMESTAMPS`), and stored in the `outbox_event` table in the same
transaction as the change (`messaging/ShipmentEventProducer`); `outbox/OutboxRelay`
publishes them to Kafka and deletes each row once it is acknowledged (a row it can never
publish is parked: `UPDATE outbox_event SET parked_at = NULL WHERE id = ?` sends it again
after the cause is fixed; metrics `outbox_events_pending`, `outbox_events_parked`,
`outbox_events_published_total`, `outbox_publish_failures_total`). A `payment.completed`
that fails goes to `payment.completed.DLT` (`kafka/DeadLetterRecoverer`); while the
database is away the listener waits with back-off instead of dead-lettering good
records. CI is GitHub Actions (`.github/workflows/ci-cd.yml`).

## Contract tests (Pact)

- `contract/PaymentEventsConsumerPactTest`: **consumer** of payment-service's `payment.completed`, delivered through the real listener container (`StringDeserializer` + `JacksonJsonMessageConverter`) on an embedded Kafka. Writes `target/pacts/shipping-service-payment-service.json`.
- `contract/ShipmentEventsProviderPactTest`: **provider** of `shipment.created` / `shipment.updated`. Replays notification-service's pact (`src/test/resources/pacts/`) against the real `PaymentEventConsumer` / `ShipmentService`; the verified bytes are the payload stored in the outbox, which is what the relay puts on the wire.

Run them alone with `mvn test -Dtest='*PactTest'`. Redistribute pacts with
`ecommerce-platform/sync-pacts.sh`.
