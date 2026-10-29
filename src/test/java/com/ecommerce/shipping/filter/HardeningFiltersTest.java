package com.ecommerce.shipping.filter;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("security")
class HardeningFiltersTest {

    private final AtomicBoolean chainReached = new AtomicBoolean();
    private final FilterChain chain = (req, res) -> chainReached.set(true);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"has space", "new\nline", "a/b", "<script>", "ünï"})
    void correlationId_thatDoesNotMatch_isReplacedByAUuid_andNeverEchoed(String bad) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/shipments/order/1");
        request.addHeader("X-Correlation-Id", bad);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new CorrelationIdFilter().doFilter(request, response, chain);

        assertThat(UUID.fromString(response.getHeader("X-Correlation-Id"))).isNotNull();
        assertThat(response.getHeader("X-Correlation-Id")).isNotEqualTo(bad);
    }

    @Test
    void correlationId_of65Characters_isReplaced() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/shipments/order/1");
        request.addHeader("X-Correlation-Id", "x".repeat(65));
        MockHttpServletResponse response = new MockHttpServletResponse();

        new CorrelationIdFilter().doFilter(request, response, chain);

        assertThat(response.getHeader("X-Correlation-Id")).hasSize(36);
    }

    @Test
    void correlationId_of64AllowedCharacters_isKept() throws Exception {
        String ok = "a.B_c-9".repeat(10).substring(0, 64);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/shipments/order/1");
        request.addHeader("X-Correlation-Id", ok);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new CorrelationIdFilter().doFilter(request, response, chain);

        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo(ok);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/shipments/order/1", "/internal/v1/x"})
    void apiAndInternalResponses_getNosniffAndNoStore(String path) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new SecurityHeadersFilter().doFilter(new MockHttpServletRequest("GET", path), response, chain);

        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(chainReached).isTrue();
    }

    @Test
    void serviceRegistry_fromARemoteAddress_is403_andNeverReachesTheEndpoint() throws Exception {
        for (String addr : new String[]{"10.1.2.3", "172.18.0.5", "::ffff:10.0.0.1", "not-an-ip", ""}) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/actuator/serviceregistry");
            request.setRemoteAddr(addr);
            MockHttpServletResponse response = new MockHttpServletResponse();
            chainReached.set(false);

            new ActuatorServiceRegistryFilter().doFilter(request, response, chain);

            assertThat(response.getStatus()).as(addr).isEqualTo(403);
            assertThat(chainReached).as(addr).isFalse();
        }
    }

    @Test
    void serviceRegistry_fromLoopback_passes_andOtherActuatorPathsAreUntouched() throws Exception {
        for (String addr : new String[]{"127.0.0.1", "::1", "0:0:0:0:0:0:0:1"}) {
            chainReached.set(false);
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/actuator/serviceregistry");
            request.setRemoteAddr(addr);
            new ActuatorServiceRegistryFilter().doFilter(request, new MockHttpServletResponse(), chain);
            assertThat(chainReached).as(addr).isTrue();
        }
        chainReached.set(false);
        MockHttpServletRequest health = new MockHttpServletRequest("GET", "/actuator/health");
        health.setRemoteAddr("10.1.2.3");
        new ActuatorServiceRegistryFilter().doFilter(health, new MockHttpServletResponse(), chain);
        assertThat(chainReached).isTrue();
    }
}
