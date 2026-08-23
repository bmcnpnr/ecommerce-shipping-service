# ecommerce-shipping-service
I will develop an e-commerce website backend (project is suggested by ChatGPT) to develop my azure, microservices and kubernetes experience
docker build -t shipping-service:0.0.1-SNAPSHOT -f docker/Dockerfile .
docker tag shipping-service:0.0.1-SNAPSHOT bmcnpnr/ecommerce-shipping-service:latest
docker push bmcnpnr/ecommerce-shipping-service:latest

## Contract tests (Pact)

- `src/test/java/.../contract/PaymentEventsConsumerPactTest` — **consumer** of payment-service's `payment.completed`, delivered through the real listener container on an embedded Kafka. Writes `target/pacts/shipping-service-payment-service.json`.
- `.../contract/ShipmentEventsProviderPactTest` — **provider** of `shipment.created` / `shipment.updated`; replays notification-service's pact (`src/test/resources/pacts/`) against the real `PaymentEventConsumer` / `ShipmentService` and the configured `JsonSerializer`.

Run them alone with `mvn test -Dtest='*PactTest'`; they are ordinary Surefire tests, so `mvn verify` and CI run them too. Regenerate and redistribute pacts across repositories with `ecommerce-platform/sync-pacts.sh` (see its README, "Contract tests").
