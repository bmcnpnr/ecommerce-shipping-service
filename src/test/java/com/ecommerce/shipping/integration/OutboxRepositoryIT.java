package com.ecommerce.shipping.integration;

import com.ecommerce.shipping.outbox.OutboxEvent;
import com.ecommerce.shipping.outbox.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The outbox table and the relay's locking query on real PostgreSQL (the schema is validated against the entity at startup). */
class OutboxRepositoryIT extends AbstractIntegrationTest {

    @Autowired OutboxEventRepository outbox;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    private static OutboxEvent row(String key) {
        return OutboxEvent.builder().topic("shipment.updated").eventKey(key).payload("{}").createdAt(LocalDateTime.now()).build();
    }

    @Test
    void theTableHasTheAgreedShape() {
        Map<String, Map<String, Object>> columns = new java.util.HashMap<>();
        jdbc.queryForList("SELECT column_name, data_type, is_nullable, column_default FROM information_schema.columns WHERE table_name = 'outbox_event'")
                .forEach(r -> columns.put((String) r.get("column_name"), r));

        assertThat(columns.keySet()).containsExactlyInAnyOrder("id", "topic", "event_key", "payload", "created_at", "attempts", "last_error", "parked_at");
        assertThat(columns.get("payload")).containsEntry("data_type", "text").containsEntry("is_nullable", "NO");
        assertThat(columns.get("attempts")).containsEntry("is_nullable", "NO");
        assertThat(String.valueOf(columns.get("attempts").get("column_default"))).isEqualTo("0");
        assertThat(columns.get("event_key")).containsEntry("is_nullable", "YES");
        assertThat(columns.get("parked_at")).containsEntry("is_nullable", "YES");
        assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'outbox_event'", String.class))
                .contains("idx_outbox_event_pending");
    }

    @Test
    void lockBatch_returnsUnparkedRowsOldestFirst_upToTheLimit() {
        // inside one transaction that is rolled back: the live relay never sees these rows
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            String marker = "locknext-" + UUID.randomUUID();
            OutboxEvent first = outbox.saveAndFlush(row(marker + "-1"));
            OutboxEvent parked = row(marker + "-parked");
            parked.setParkedAt(LocalDateTime.now());
            outbox.saveAndFlush(parked);
            OutboxEvent second = outbox.saveAndFlush(row(marker + "-2"));
            OutboxEvent third = outbox.saveAndFlush(row(marker + "-3"));

            // rows other tests committed a moment ago may still be waiting for the relay: look at ours only
            List<OutboxEvent> all = outbox.lockBatch(1000);
            List<OutboxEvent> ours = all.stream().filter(e -> e.getEventKey().startsWith(marker)).toList();

            assertThat(ours).extracting(OutboxEvent::getId).containsExactly(first.getId(), second.getId(), third.getId());
            assertThat(all).noneMatch(e -> e.getEventKey().endsWith("-parked"));
            assertThat(all).extracting(OutboxEvent::getId).isSorted();
            assertThat(outbox.lockBatch(2)).hasSize(2);
            status.setRollbackOnly();
        });
    }
}
