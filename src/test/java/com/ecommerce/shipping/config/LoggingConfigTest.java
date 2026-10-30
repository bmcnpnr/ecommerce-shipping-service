package com.ecommerce.shipping.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** The logging defaults that keep a failure from filling a disk (L1, L8 of the resilience spec). */
class LoggingConfigTest {

    private static Properties application() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yaml"));
        return yaml.getObject();
    }

    @Test
    void everyServiceUsesTheOneLogFormat_thatPromtailParses() {
        assertThat(application().getProperty("logging.pattern.console")).isEqualTo(
                "%d{yyyy-MM-dd HH:mm:ss} [%thread] [%X{correlationId:-}] [%X{traceId:-}] %-5level %logger{36} - %msg%n");
    }

    @Test
    void debugIsNotTheDefault_itIsOneEnvironmentVariableAway() {
        // LOGGING_LEVEL_COM_ECOMMERCE=DEBUG turns it on when needed
        assertThat(application().getProperty("logging.level.com.ecommerce", "INFO")).isEqualTo("INFO");
        assertThat(application().getProperty("spring.jpa.show-sql", "false")).isEqualTo("false");
    }
}
