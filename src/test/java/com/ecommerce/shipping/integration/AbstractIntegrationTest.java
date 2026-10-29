package com.ecommerce.shipping.integration;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import static org.awaitility.Awaitility.await;

/**
 * Base for every {@code *IT}: the full application against a real PostgreSQL (prod image,
 * schema from the real Flyway migrations, Hibernate {@code ddl-auto=validate}) and a real
 * Kafka broker (prod image).
 *
 * <p>The containers are static singletons started once per JVM and shared by every IT class
 * (Ryuk removes them at exit); all subclasses use identical configuration, so they also share
 * one cached Spring context (MockMvc is configured here for the same reason). Postgres is wired with {@code @ServiceConnection}. Kafka is wired
 * through {@code spring.kafka.bootstrap-servers}: the service uses plain spring-kafka (no Boot
 * Kafka auto-configuration, so no {@code KafkaConnectionDetails} consumer) and
 * {@code KafkaProducerConfig} reads exactly that property.
 *
 * <p>Tests never clean tables: each one uses fresh order ids from {@link #nextOrderId()}.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "eureka.client.enabled=false",
        "eureka.client.register-with-eureka=false",
        "eureka.client.fetch-registry=false",
        "management.tracing.sampling.probability=0",
        "management.tracing.export.enabled=false"
})
@AutoConfigureMockMvc
abstract class AbstractIntegrationTest {

    static final String PAYMENT_COMPLETED = "payment.completed";
    static final String SHIPMENT_CREATED = "shipment.created";
    static final String SHIPMENT_UPDATED = "shipment.updated";

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("shippingdb").withUsername("shippinguser").withPassword("shippingpass");

    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.6.0");

    static final JsonMapper JSON = JsonMapper.builder().build();

    /** Distinct per JVM run, so reruns against a reused DB never collide either. */
    private static final AtomicLong ORDER_IDS = new AtomicLong(System.currentTimeMillis() % 1_000_000_000L * 1000);

    static {
        POSTGRES.start();
        KAFKA.start();
        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(
                    new NewTopic(PAYMENT_COMPLETED, 1, (short) 1),
                    new NewTopic(SHIPMENT_CREATED, 1, (short) 1),
                    new NewTopic(SHIPMENT_UPDATED, 1, (short) 1))).all().get();
        } catch (Exception e) {
            throw new IllegalStateException("Could not create Kafka topics", e);
        }
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    static long nextOrderId() {
        return ORDER_IDS.incrementAndGet();
    }

    /** Publishes a raw JSON string with no type headers, exactly like the platform's producers. */
    static void publishRaw(String topic, String key, String json) {
        Map<String, Object> props = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(topic, key, json)).get();
        } catch (Exception e) {
            throw new IllegalStateException("Publishing to " + topic + " failed", e);
        }
    }

    /**
     * Reads {@code topic} from the beginning with a throw-away consumer group and returns the
     * raw records (key + JSON tree) with the given key (any key when null), once {@code until} holds for them.
     */
    static List<Received> awaitRecords(String topic, String key, Predicate<List<Received>> until) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<Received> received = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ZERO).until(() -> {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(250))) {
                    if (key == null || key.equals(r.key())) {
                        received.add(new Received(r.key(), JSON.readTree(r.value())));
                    }
                }
                return until.test(received);
            });
        }
        return received;
    }

    record Received(String key, JsonNode value) {
    }
}
