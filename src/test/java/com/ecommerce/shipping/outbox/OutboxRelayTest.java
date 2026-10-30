package com.ecommerce.shipping.outbox;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ecommerce.shipping.logging.LogThrottle;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OutboxRelayTest {

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AtomicLong nanos = new AtomicLong(1_000_000_000_000L);
    private final Logger logger = (Logger) LoggerFactory.getLogger(OutboxRelay.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        logs.start();
        logger.addAppender(logs);
        relay = new OutboxRelay(repository, kafka, transactionManager, meters, 100, 35_000, nanos::get,
                new LogThrottle(60, TimeUnit.SECONDS));
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        logs.stop();
        Thread.interrupted(); // a test may leave the flag set
    }

    private static OutboxEvent row(long id, String topic) {
        return OutboxEvent.builder().id(id).topic(topic).eventKey(String.valueOf(id))
                .payload("{\"orderId\":" + id + "}").createdAt(LocalDateTime.now()).build();
    }

    private void rows(OutboxEvent... rows) {
        when(repository.lockBatch(100)).thenReturn(new ArrayList<>(List.of(rows)));
    }

    private static CompletableFuture<SendResult<String, String>> acknowledged() {
        return CompletableFuture.completedFuture(new SendResult<>(null, null));
    }

    private static CompletableFuture<SendResult<String, String>> failed(Throwable cause) {
        return CompletableFuture.failedFuture(cause);
    }

    private void advanceMillis(long millis) {
        nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
    }

    private List<ILoggingEvent> lines(Level level) {
        return logs.list.stream().filter(e -> e.getLevel() == level).toList();
    }

    private double count(String name) {
        return meters.get(name).counter().count();
    }

    // ───────────────────────────── happy path ─────────────────────────────

    @Test
    void sendsTheRowsOldestFirst_waitsForEachAck_andDeletesWhatWasAcknowledged() {
        OutboxEvent first = row(1, "shipment.created");
        OutboxEvent second = row(2, "shipment.updated");
        rows(first, second);
        when(kafka.send(any(String.class), any(String.class), any(String.class))).thenReturn(acknowledged());

        relay.publishPending();

        InOrder order = inOrder(kafka, repository);
        order.verify(kafka).send("shipment.created", "1", "{\"orderId\":1}");
        order.verify(kafka).send("shipment.updated", "2", "{\"orderId\":2}");
        order.verify(repository).deleteAllByIdInBatch(List.of(1L, 2L));
        assertThat(count("outbox.events.published")).isEqualTo(2);
        assertThat(count("outbox.publish.failures")).isZero();
        assertThat(logs.list).isEmpty();
    }

    @Test
    void anEmptyOutbox_costsOneQuery_andNothingElse() {
        rows();

        relay.publishPending();
        relay.publishPending();

        verifyNoInteractions(kafka);
        verify(repository, never()).deleteAllByIdInBatch(anyList());
        assertThat(logs.list).isEmpty();
    }

    @Test
    void theGaugesFollowTheTable_andAreRefreshedByTheRelayNotByAScrape() {
        rows();
        when(repository.countByParkedAtIsNull()).thenReturn(5L);
        when(repository.countByParkedAtIsNotNull()).thenReturn(2L);

        relay.publishPending();

        assertThat(meters.get("outbox.events.pending").gauge().value()).isEqualTo(5);
        assertThat(meters.get("outbox.events.parked").gauge().value()).isEqualTo(2);
        // reading the gauge again does not touch the database
        meters.get("outbox.events.pending").gauge().value();
        verify(repository, times(1)).countByParkedAtIsNull();
    }

    // ───────────────────────────── Kafka down ─────────────────────────────

    @Test
    void aTransientFailure_stopsTheBatch_keepsOrder_countsTheAttempt_andStoresTheError() {
        OutboxEvent first = row(1, "shipment.created");
        OutboxEvent second = row(2, "shipment.updated");
        OutboxEvent third = row(3, "shipment.updated");
        rows(first, second, third);
        when(kafka.send("shipment.created", "1", first.getPayload())).thenReturn(acknowledged());
        when(kafka.send("shipment.updated", "2", second.getPayload())).thenReturn(failed(new TimeoutException("Topic not present in metadata after 5000 ms.")));
        when(repository.countByParkedAtIsNull()).thenReturn(2L);

        relay.publishPending();

        // row 1 was acknowledged and goes; row 2 stays with its attempt recorded; row 3 is not even tried
        verify(repository).deleteAllByIdInBatch(List.of(1L));
        verify(kafka, never()).send("shipment.updated", "3", third.getPayload());
        assertThat(second.getAttempts()).isEqualTo(1);
        assertThat(second.getLastError()).startsWith("TimeoutException: Topic not present");
        assertThat(second.getParkedAt()).isNull();
        assertThat(third.getAttempts()).isZero();
        assertThat(count("outbox.events.published")).isEqualTo(1);
        assertThat(count("outbox.publish.failures")).isEqualTo(1);
    }

    @Test
    void aRealProducerWithNoBrokerToTalkTo_failsTransiently_notPermanently() {
        // what a dead broker looks like to the real client: send() waits max.block.ms for metadata, then fails
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1");
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 300);
        props.put(ProducerConfig.RECONNECT_BACKOFF_MS_CONFIG, 1000);
        DefaultKafkaProducerFactory<String, String> producers =
                new DefaultKafkaProducerFactory<>(props, new StringSerializer(), new StringSerializer());
        OutboxRelay real = new OutboxRelay(repository, new KafkaTemplate<>(producers), transactionManager, meters, 100, 35_000,
                nanos::get, new LogThrottle(60, TimeUnit.SECONDS));
        OutboxEvent only = row(1, "shipment.created");
        rows(only);
        when(repository.countByParkedAtIsNull()).thenReturn(1L);
        try {
            long started = System.nanoTime();
            real.publishPending();

            assertThat((System.nanoTime() - started) / 1_000_000).as("bounded by max.block.ms").isLessThan(5_000);
            assertThat(only.getParkedAt()).isNull();
            assertThat(only.getAttempts()).isEqualTo(1);
            assertThat(only.getLastError()).contains("TimeoutException");
            verify(repository, never()).deleteAllByIdInBatch(anyList());
            assertThat(lines(Level.WARN)).hasSize(1);
            assertThat(lines(Level.WARN).get(0).getThrowableProxy()).isNull();
            assertThat(lines(Level.ERROR)).isEmpty();
        } finally {
            producers.destroy();
        }
    }

    @Test
    void lastErrorIsCappedAt500Characters() {
        OutboxEvent only = row(1, "shipment.created");
        rows(only);
        when(kafka.send(any(String.class), any(String.class), any(String.class)))
                .thenReturn(failed(new TimeoutException("x".repeat(5_000))));

        relay.publishPending();

        assertThat(only.getLastError()).hasSize(500);
    }

    @Test
    void whileKafkaIsDown_theRelayBacksOff_1s_2s_4s_up_to_30s_andDoesNotHitTheDatabaseInBetween() {
        OutboxEvent only = row(1, "shipment.created");
        rows(only);
        when(kafka.send(any(String.class), any(String.class), any(String.class))).thenReturn(failed(new TimeoutException("down")));

        relay.publishPending(); // attempt 1 fails
        int attempts = 1;
        for (long pause : new long[] {1_000, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000}) {
            advanceMillis(pause - 1);
            relay.publishPending(); // still inside the pause: no query, no send
            verify(repository, times(attempts)).lockBatch(anyInt());
            advanceMillis(1);
            relay.publishPending(); // the pause is over: one more attempt, which fails again
            attempts++;
            verify(repository, times(attempts)).lockBatch(anyInt());
        }
        assertThat(count("outbox.publish.failures")).isEqualTo(8);
    }

    @Test
    void failuresAreLoggedOnceWithoutAStackTrace_andRecoveryIsOneInfoLine() {
        OutboxEvent only = row(1, "shipment.created");
        rows(only);
        when(kafka.send(any(String.class), any(String.class), any(String.class)))
                .thenReturn(failed(new TimeoutException("down")))
                .thenReturn(failed(new TimeoutException("down")))
                .thenReturn(failed(new TimeoutException("down")))
                .thenReturn(acknowledged());
        when(repository.countByParkedAtIsNull()).thenReturn(1L);

        for (int i = 0; i < 3; i++) {
            relay.publishPending();
            advanceMillis(40_000);                       // always past the pause
        }
        // within the minute, the second and third failure stay silent
        assertThat(lines(Level.WARN)).hasSize(1);
        assertThat(lines(Level.WARN).get(0).getThrowableProxy()).isNull();
        assertThat(lines(Level.WARN).get(0).getFormattedMessage())
                .contains("Outbox relay cannot publish to shipment.created").contains("TimeoutException: down")
                .contains("1 event(s) pending").contains("retrying in 1000 ms");

        relay.publishPending();                                   // Kafka is back

        assertThat(lines(Level.INFO)).hasSize(1);
        assertThat(lines(Level.INFO).get(0).getFormattedMessage()).isEqualTo("Outbox relay resumed after 3 failed attempts");
        assertThat(lines(Level.ERROR)).isEmpty();
        // and a later outage reports itself again, immediately
        when(kafka.send(any(String.class), any(String.class), any(String.class))).thenReturn(failed(new TimeoutException("down again")));
        rows(row(2, "shipment.updated"));
        relay.publishPending();
        assertThat(lines(Level.WARN)).hasSize(2);
    }

    @Test
    void aDatabaseThatDoesNotAnswer_isHandledLikeKafkaBeingDown() {
        when(transactionManager.getTransaction(any())).thenThrow(new CannotCreateTransactionException("Hikari: connection is not available"));

        relay.publishPending();
        relay.publishPending();                                   // paused

        verifyNoInteractions(kafka);
        verify(transactionManager, times(1)).getTransaction(any());
        assertThat(lines(Level.WARN)).hasSize(1);
        assertThat(lines(Level.WARN).get(0).getThrowableProxy()).isNull();
        assertThat(lines(Level.WARN).get(0).getFormattedMessage()).contains("CannotCreateTransactionException");
        assertThat(count("outbox.publish.failures")).isEqualTo(1);
    }

    @Test
    void anInterruptedWait_stopsTheBatch_andKeepsTheInterruptFlag() {
        OutboxEvent first = row(1, "shipment.created");
        OutboxEvent second = row(2, "shipment.updated");
        rows(first, second);
        CompletableFuture<SendResult<String, String>> neverCompletes = new CompletableFuture<>();
        when(kafka.send("shipment.created", "1", first.getPayload())).thenReturn(neverCompletes);
        Thread.currentThread().interrupt();

        relay.publishPending();

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verify(kafka, never()).send("shipment.updated", "2", second.getPayload());
        verify(repository, never()).deleteAllByIdInBatch(anyList());
        // shutting down is not an outage: no failure, no back-off, no log line
        assertThat(count("outbox.publish.failures")).isZero();
        assertThat(logs.list).isEmpty();
    }

    @Test
    void aSendThatNeverCompletes_isGivenUpOnAfterTheSendTimeout() {
        OutboxRelay impatient = new OutboxRelay(repository, kafka, transactionManager, meters, 100, 50, nanos::get,
                new LogThrottle(60, TimeUnit.SECONDS));
        OutboxEvent only = row(1, "shipment.created");
        rows(only);
        when(kafka.send(any(String.class), any(String.class), any(String.class))).thenReturn(new CompletableFuture<>());
        when(repository.countByParkedAtIsNull()).thenReturn(1L);

        long started = System.nanoTime();
        impatient.publishPending();

        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(2_000);
        assertThat(only.getAttempts()).isEqualTo(1);
        assertThat(only.getLastError()).startsWith("TimeoutException");
        assertThat(count("outbox.publish.failures")).isEqualTo(1);
        assertThat(lines(Level.WARN)).hasSize(1);
    }

    @Test
    void theGauges_areRefreshedAtMostEveryFiveSeconds_whateverTheRelayRate() {
        rows();
        when(repository.countByParkedAtIsNull()).thenReturn(1L, 2L);
        when(repository.countByParkedAtIsNotNull()).thenReturn(0L);

        for (int i = 0; i < 4; i++) {
            relay.publishPending();      // t = 0 s, 1 s, 2 s, 3 s
            advanceMillis(1_000);
        }
        verify(repository, times(1)).countByParkedAtIsNull();
        assertThat(meters.get("outbox.events.pending").gauge().value()).isEqualTo(1);

        advanceMillis(1_000);            // t = 5 s
        relay.publishPending();

        verify(repository, times(2)).countByParkedAtIsNull();
        assertThat(meters.get("outbox.events.pending").gauge().value()).isEqualTo(2);
    }

    @Test
    void whenTheCountQueryFails_theGaugesKeepTheirLastValue_andNothingIsThrown() {
        rows();
        when(repository.countByParkedAtIsNull()).thenReturn(7L).thenThrow(new CannotCreateTransactionException("db down"));
        relay.publishPending();
        advanceMillis(6_000);

        relay.publishPending();

        assertThat(meters.get("outbox.events.pending").gauge().value()).isEqualTo(7);
    }

    @Test
    void backoffGrowsFromOneSecond_andStopsAtThirty() {
        assertThat(OutboxRelay.backoffMillis(1)).isEqualTo(1_000);
        assertThat(OutboxRelay.backoffMillis(2)).isEqualTo(2_000);
        assertThat(OutboxRelay.backoffMillis(5)).isEqualTo(16_000);
        assertThat(OutboxRelay.backoffMillis(6)).isEqualTo(30_000);
        assertThat(OutboxRelay.backoffMillis(1_000)).isEqualTo(30_000);
    }

    // ───────────────────────────── permanent failures ─────────────────────────────

    @Test
    void aRowThatCanNeverBePublished_isParked_logsTheSqlToUnpark_andTheNextRowIsStillSent() {
        OutboxEvent tooBig = row(1, "shipment.updated");
        OutboxEvent fine = row(2, "shipment.updated");
        rows(tooBig, fine);
        when(kafka.send("shipment.updated", "1", tooBig.getPayload())).thenReturn(failed(new RecordTooLargeException("1048600 bytes")));
        when(kafka.send("shipment.updated", "2", fine.getPayload())).thenReturn(acknowledged());

        relay.publishPending();

        assertThat(tooBig.getParkedAt()).isNotNull();
        assertThat(tooBig.getAttempts()).isEqualTo(1);
        assertThat(tooBig.getLastError()).contains("RecordTooLargeException");
        verify(repository).deleteAllByIdInBatch(List.of(2L));
        assertThat(lines(Level.ERROR)).hasSize(1);
        assertThat(lines(Level.ERROR).get(0).getThrowableProxy()).isNull();
        assertThat(lines(Level.ERROR).get(0).getFormattedMessage())
                .contains("Outbox event 1 for topic shipment.updated can never be published").contains("parked")
                .contains("UPDATE outbox_event SET parked_at = NULL WHERE id = 1");
        // a parked row is not a reason to back off: the very next run goes ahead (the parked row is no longer selected)
        assertThat(lines(Level.WARN)).isEmpty();
        rows();
        relay.publishPending();
        verify(repository, times(2)).lockBatch(100);
        assertThat(count("outbox.publish.failures")).as("a parked row is not a failed cycle").isZero();
        assertThat(count("outbox.events.published")).isEqualTo(1);
    }

    @Test
    void permanentFailures_areRecognisedThroughTheCauseChain() {
        assertThat(OutboxRelay.isPermanent(new RecordTooLargeException("big"))).isTrue();
        assertThat(OutboxRelay.isPermanent(new InvalidTopicException("bad topic"))).isTrue();
        assertThat(OutboxRelay.isPermanent(new org.apache.kafka.common.errors.SerializationException("x"))).isTrue();
        assertThat(OutboxRelay.isPermanent(new org.apache.kafka.common.errors.RecordBatchTooLargeException("big"))).isTrue();
        assertThat(OutboxRelay.isPermanent(new RuntimeException("wrapped", new RecordTooLargeException("big")))).isTrue();
        assertThat(OutboxRelay.isPermanent(new TimeoutException("down"))).isFalse();
        assertThat(OutboxRelay.isPermanent(new RuntimeException("other"))).isFalse();
    }

    @Test
    void aSendThatThrowsSynchronously_isClassifiedLikeAFailedFuture() {
        OutboxEvent only = row(1, "shipment.created");
        rows(only);
        when(kafka.send(any(String.class), any(String.class), any(String.class))).thenThrow(new IllegalStateException("producer closed"));

        relay.publishPending();

        assertThat(only.getAttempts()).isEqualTo(1);
        assertThat(only.getParkedAt()).isNull();
        verify(repository, never()).deleteAllByIdInBatch(anyList());
        assertThat(lines(Level.WARN)).hasSize(1);
    }
}
