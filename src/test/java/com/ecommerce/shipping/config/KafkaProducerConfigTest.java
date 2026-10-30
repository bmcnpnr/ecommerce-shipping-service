package com.ecommerce.shipping.config;

import com.ecommerce.shipping.event.ShipmentCreatedEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.errors.NetworkException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.databind.json.JsonMapper;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** How {@link KafkaProducerConfig} sets up the Kafka clients and what a failing record is allowed to cost. */
class KafkaProducerConfigTest {

    private static final String BOOTSTRAP = "broker-1:9092";
    private static final ConsumerRecord<String, String> RECORD = new ConsumerRecord<>("payment.completed", 0, 5L, "7", "{}");

    private KafkaProducerConfig config;

    @BeforeEach
    void setUp() {
        config = new KafkaProducerConfig();
        ReflectionTestUtils.setField(config, "bootstrapServers", BOOTSTRAP);
    }

    @Test
    void theProducerIsBounded_aDeadBrokerCannotHoldACallerForMinutes() {
        Map<String, Object> props = ((DefaultKafkaProducerFactory<?, ?>) config.producerFactory()).getConfigurationProperties();

        assertThat(props)
                .containsEntry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP)
                .containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000)
                .containsEntry(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000)
                .containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30000)
                .containsEntry(ProducerConfig.ACKS_CONFIG, "all")
                .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
                .containsEntry(ProducerConfig.RECONNECT_BACKOFF_MS_CONFIG, 1000)
                .containsEntry(ProducerConfig.RECONNECT_BACKOFF_MAX_MS_CONFIG, 10000);
    }

    @Test
    void theConsumerBacksOffItsReconnects_soADeadBrokerLogsOneWarningPerTenSeconds() {
        Map<String, Object> props = ((DefaultKafkaConsumerFactory<?, ?>) config.consumerFactory()).getConfigurationProperties();

        assertThat(props)
                .containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "shipping-service")
                .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                .containsEntry(ConsumerConfig.RECONNECT_BACKOFF_MS_CONFIG, 1000)
                .containsEntry(ConsumerConfig.RECONNECT_BACKOFF_MAX_MS_CONFIG, 10000);
    }

    @Test
    void thereIsOneStringTemplate_forTheRelayAndTheDeadLetters() {
        KafkaTemplate<String, String> template = config.kafkaTemplate();

        assertThat(template.getProducerFactory().getValueSerializerSupplier().get())
                .isInstanceOf(org.apache.kafka.common.serialization.StringSerializer.class);
        assertThat(template.getProducerFactory().getKeySerializerSupplier().get())
                .isInstanceOf(org.apache.kafka.common.serialization.StringSerializer.class);
    }

    @Test
    void theContainerFactoryGetsTheDeadLetterErrorHandler() {
        var recoverer = config.deadLetterRecoverer(config.kafkaTemplate(), new org.springframework.beans.factory.support.DefaultListableBeanFactory().getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));
        var handler = config.kafkaErrorHandler(recoverer);

        ConcurrentKafkaListenerContainerFactory<String, String> factory = config.kafkaListenerContainerFactory(handler);

        assertThat(ReflectionTestUtils.getField(factory, "commonErrorHandler")).isSameAs(handler);
        recoverer.close();
    }

    // ───────────────────────────── back-off per failure ─────────────────────────────

    static Stream<Exception> dependenciesThatDoNotAnswer() {
        return Stream.of(
                new CannotCreateTransactionException("Hikari: connection is not available"),
                new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection"),
                new DataAccessResourceFailureException("connection lost"),
                new QueryTimeoutException("statement timed out"),
                new RecoverableDataAccessException("connection reset"),
                new NetworkException("broker unreachable"),
                new ListenerExecutionFailedException("Listener method threw", new CannotCreateTransactionException("db down")),
                // the connection died mid-transaction: the failed rollback replaces the original exception
                new ListenerExecutionFailedException("Listener method threw", new TransactionSystemException("Could not roll back JPA transaction",
                        new org.hibernate.TransactionException("Unable to rollback against JDBC Connection", new SQLException("This connection has been closed.")))));
    }

    @ParameterizedTest
    @MethodSource("dependenciesThatDoNotAnswer")
    void aDependencyThatDoesNotAnswer_isWaitedFor_1sDoublingTo30s_withNoDeadline(Exception failure) {
        BackOff backOff = KafkaProducerConfig.backOffFor(RECORD, failure);

        assertThat(backOff).isInstanceOf(ExponentialBackOff.class);
        BackOffExecution execution = backOff.start();
        long[] first = {1_000, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000};
        for (long expected : first) {
            assertThat(execution.nextBackOff()).isEqualTo(expected);
        }
        // never gives up: the partition waits for the database instead of dead-lettering good records,
        // and every single pause stays far below max.poll.interval.ms (5 minutes)
        for (int i = 0; i < 10_000; i++) {
            assertThat(execution.nextBackOff()).isEqualTo(30_000);
        }
    }

    static Stream<Exception> everythingElse() {
        return Stream.of(
                new IllegalStateException("bug"),
                new DataIntegrityViolationException("value too long for column"),
                new ListenerExecutionFailedException("Listener method threw", new NullPointerException()),
                new TransactionSystemException("Could not commit JPA transaction", new IllegalStateException("not a connection problem")));
    }

    @ParameterizedTest
    @MethodSource("everythingElse")
    void anyOtherFailure_getsTwoMoreAttemptsASecondApart_thenTheDeadLetterTopic(Exception failure) {
        BackOff backOff = KafkaProducerConfig.backOffFor(RECORD, failure);

        assertThat(backOff).isInstanceOf(FixedBackOff.class);
        BackOffExecution execution = backOff.start();
        assertThat(execution.nextBackOff()).isEqualTo(1_000);
        assertThat(execution.nextBackOff()).isEqualTo(1_000);
        assertThat(execution.nextBackOff()).isEqualTo(BackOffExecution.STOP);
    }

    // ───────────────────────────── the wire format ─────────────────────────────

    @Test
    void eventJson_keepsJavaTimeAsIntegerArrays() {
        JsonMapper mapper = KafkaProducerConfig.eventJsonMapper();

        String json = mapper.writeValueAsString(ShipmentCreatedEvent.builder().shipmentId(1L).orderId(2L)
                .estimatedDelivery(LocalDateTime.of(2026, 11, 3, 14, 30)).build());

        assertThat(json).contains("\"estimatedDelivery\":[2026,11,3,14,30]");
    }
}
