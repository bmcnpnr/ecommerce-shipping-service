package com.ecommerce.shipping.integration;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** The production connection pool is bounded: nothing waits for a database that does not answer for minutes. */
class ConnectionSettingsIT extends AbstractIntegrationTest {

    @Autowired DataSource dataSource;

    @Test
    void aRequestWaitsAtMostFiveSecondsForAConnection_andNoReadBlocksForever() throws Exception {
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);

        assertThat(pool.getConnectionTimeout()).isEqualTo(5_000);
        Properties driver = pool.getDataSourceProperties();
        assertThat(driver.getProperty("connectTimeout")).isEqualTo("5");
        assertThat(driver.getProperty("socketTimeout")).isEqualTo("30");
        assertThat(driver.getProperty("tcpKeepAlive")).isEqualTo("true");
        // and the driver really applied it: JDBC reports the socket read timeout of the live connection
        try (var connection = pool.getConnection()) {
            assertThat(connection.getNetworkTimeout()).isEqualTo(30_000);
        }
    }
}
