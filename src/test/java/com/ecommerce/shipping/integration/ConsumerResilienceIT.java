package com.ecommerce.shipping.integration;

import com.ecommerce.shipping.model.Shipment;
import com.ecommerce.shipping.repository.ShipmentRepository;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * What a record that fails costs, on a real broker and a real database: the payment.completed listener
 * must neither drop a paid order's event because the database was away for a while, nor stay stuck on a
 * record that can never be processed, nor write more than a handful of lines for either.
 */
class ConsumerResilienceIT extends AbstractIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(60);

    @Autowired ShipmentRepository shipmentRepository;
    @Autowired DataSource dataSource;
    @Autowired MeterRegistry meters;

    /** Records dead-lettered from payment.completed so far; the log line itself is throttled to one per 10 s. */
    private double deadLettered() {
        var counter = meters.find("kafka.dlt.published").tag("topic", "payment.completed").counter();
        return counter == null ? 0 : counter.count();
    }

    private static String paymentCompleted(long orderId, String customerId) {
        return """
                {"paymentId":501,"orderId":%d,"customerId":"%s","amount":99.98,\
                "transactionId":"TXN-1A2B3C4D","completedAt":[2026,10,29,14,30,15,123456789]}""".formatted(orderId, customerId);
    }

    /** payment.completed.DLT records by key, read from the start with a throw-away group; waits until {@code key} is there. */
    private static Map<String, String> awaitDeadLetter(String key) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        Map<String, String> seen = new HashMap<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of("payment.completed.DLT"));
            await().atMost(WAIT).pollInterval(Duration.ZERO).until(() -> {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(300))) {
                    seen.put(r.key(), r.value());
                }
                return seen.containsKey(key);
            });
        }
        return seen;
    }

    private static boolean isDeadLettered(String key) {
        try {
            Map<String, Object> props = Map.of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                    ConsumerConfig.GROUP_ID_CONFIG, "dlt-it-" + UUID.randomUUID(),
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
                consumer.subscribe(List.of("payment.completed.DLT"));
                long end = System.nanoTime() + Duration.ofSeconds(4).toNanos();
                while (System.nanoTime() < end) {
                    for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(300))) {
                        if (key.equals(r.key())) {
                            return true;
                        }
                    }
                }
            }
            return false;
        } catch (RuntimeException e) {
            return false; // topic does not exist yet
        }
    }

    // ---- a record that can never be processed --------------------------------------------------------------

    @Test
    void aRecordThatIsNotAPaymentEvent_isDeadLetteredWithItsOriginalValue_andCostsOneLogLine() throws Exception {
        String key = "dlt-" + UUID.randomUUID();
        double before = deadLettered();
        try (LogCapture logs = new LogCapture()) {
            publishRaw(PAYMENT_COMPLETED, key, "this is not json at all");

            Map<String, String> deadLetters = awaitDeadLetter(key);
            Thread.sleep(3_000); // a loop would show up as more lines in these seconds

            assertThat(deadLetters.get(key)).isEqualTo("this is not json at all");
            assertThat(deadLettered()).isEqualTo(before + 1);
            // one WARN, or none when the previous dead letter was less than 10 s ago (throttled), never a trace or a loop
            assertThat(logs.problems()).as(logs.describe()).hasSizeLessThanOrEqualTo(1);
            assertThat(logs.stackTraceLines()).isZero();
        }
    }

    @Test
    void aRecordNearTheSizeLimit_isDeadLetteredOnce_insteadOfLooping() throws Exception {
        // 1,047,000 bytes: the broker takes it, but a dead-letter copy with exception headers used to be too
        // large to publish, so it was retried at poll rate with three stack traces a round
        String key = "dlt-large-" + UUID.randomUUID();
        String almostTooLarge = "x".repeat(1_047_000);
        double before = deadLettered();
        try (LogCapture logs = new LogCapture()) {
            publishRaw(PAYMENT_COMPLETED, key, almostTooLarge);

            Map<String, String> deadLetters = awaitDeadLetter(key);
            Thread.sleep(3_000);

            assertThat(deadLetters.get(key)).hasSize(1_047_000);
            assertThat(deadLettered()).isEqualTo(before + 1);
            assertThat(logs.problems()).as(logs.describe()).hasSizeLessThanOrEqualTo(1);
            assertThat(logs.stackTraceLines()).isZero();
        }
        // and the record behind it is processed: a bad record never blocks the partition
        long sentinel = nextOrderId();
        publishRaw(PAYMENT_COMPLETED, String.valueOf(sentinel), paymentCompleted(sentinel, "sentinel-after-large"));
        await().atMost(WAIT).until(() -> shipmentRepository.findByOrderId(sentinel), Optional::isPresent);
    }

    // ---- a database that goes away for a while --------------------------------------------------------------

    @Test
    void whenTheDatabaseIsAway_thePaymentIsWaitedFor_notDeadLettered_andTheShipmentIsCreatedWhenItIsBack() throws Exception {
        long orderId = nextOrderId();
        String key = String.valueOf(orderId);
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        exec("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'ops') THEN "
                + "CREATE ROLE ops SUPERUSER LOGIN PASSWORD 'ops'; END IF; END $$", "shippinguser", "shippingpass");

        try (LogCapture logs = new LogCapture()) {
            // no new connections for the application's role; the ones it holds are retired
            exec("ALTER ROLE shippinguser NOLOGIN", "ops", "ops");
            try {
                pool.getHikariPoolMXBean().softEvictConnections();
                publishRaw(PAYMENT_COMPLETED, key, paymentCompleted(orderId, "outage-customer"));

                Thread.sleep(14_000); // two or three attempts, each waiting for a connection for 5 s

                assertThat(shipmentRowViaOps(orderId)).as("nothing could be created yet").isEmpty();
                // the log shows the outage once per 10 s window and kind, without a stack trace
                assertThat(logs.problems()).as(logs.describe()).hasSizeLessThanOrEqualTo(4);
                assertThat(logs.stackTraceLines()).isZero();
            } finally {
                exec("ALTER ROLE shippinguser LOGIN", "ops", "ops");
            }

            // the database is back: the very same record is processed, nothing was dead-lettered
            await().atMost(WAIT).until(() -> shipmentRepository.findByOrderId(orderId), Optional::isPresent);
            Shipment shipment = shipmentRepository.findByOrderId(orderId).orElseThrow();
            List<Received> created = awaitRecords(SHIPMENT_CREATED, key, r -> !r.isEmpty());
            assertThat(created).hasSize(1);
            assertThat(created.get(0).value().get("shipmentId").asLong()).isEqualTo(shipment.getId());
            assertThat(isDeadLettered(key)).as("a good record must not be dead-lettered because the database was away").isFalse();
        }
    }

    /** Reads through a fresh connection of the test's own: the application's role cannot log in during the outage. */
    private Optional<Long> shipmentRowViaOps(long orderId) throws SQLException {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "ops", "ops");
             Statement st = c.createStatement();
             var rs = st.executeQuery("SELECT id FROM shipments WHERE order_id = " + orderId)) {
            return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
        }
    }

    private static void exec(String sql, String user, String password) throws SQLException {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), user, password);
             Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }
}
