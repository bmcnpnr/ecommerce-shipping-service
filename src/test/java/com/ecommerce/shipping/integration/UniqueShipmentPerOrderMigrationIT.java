package com.ecommerce.shipping.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V3 on a database that already holds duplicate shipments for one order: the earliest row (lowest id) and
 * its history survive, later duplicates (and their history) go, and the constraint then holds.
 */
class UniqueShipmentPerOrderMigrationIT extends AbstractIntegrationTest {

    @Test
    void v3_keepsTheEarliestShipmentPerOrder_andItsEvents_thenEnforcesUniqueness() {
        DriverManagerDataSource ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "shippinguser", "shippingpass");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP SCHEMA IF EXISTS migtest CASCADE");

        Flyway upTo2 = Flyway.configure().dataSource(ds).schemas("migtest").target("2").load();
        upTo2.migrate();

        String insert = "INSERT INTO migtest.shipments (order_id, customer_id, tracking_number, origin_address, destination_address) VALUES (?, ?, ?, 'o', 'd')";
        jdbc.update(insert, 900L, "alice", "T-FIRST");
        jdbc.update(insert, 900L, "mallory", "T-SECOND");
        jdbc.update(insert, 900L, "mallory", "T-THIRD");
        jdbc.update(insert, 901L, "bob", "T-OTHER");
        for (String t : new String[]{"T-FIRST", "T-SECOND", "T-OTHER"}) {
            jdbc.update("INSERT INTO migtest.shipment_events (shipment_id, new_status) SELECT id, 'PENDING' FROM migtest.shipments WHERE tracking_number = ?", t);
        }

        Flyway.configure().dataSource(ds).schemas("migtest").load().migrate();

        assertThat(jdbc.queryForList("SELECT tracking_number FROM migtest.shipments ORDER BY id", String.class))
                .containsExactly("T-FIRST", "T-OTHER");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM migtest.shipment_events", Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM migtest.shipment_events e JOIN migtest.shipments s ON s.id = e.shipment_id
                WHERE s.tracking_number = 'T-FIRST'""", Long.class)).isEqualTo(1L);
        assertThatThrownBy(() -> jdbc.update(insert, 900L, "x", "T-FOURTH")).isInstanceOf(DataIntegrityViolationException.class);

        jdbc.execute("DROP SCHEMA migtest CASCADE");
    }
}
