package com.ecommerce.shipping.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorrelationIdFilterTest {

    private static final String HEADER = "X-Correlation-Id";

    private final CorrelationIdFilter filter = new CorrelationIdFilter();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/shipments/order/1");
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final AtomicReference<String> mdcSeenByChain = new AtomicReference<>();
    private final FilterChain recordingChain = (req, res) -> mdcSeenByChain.set(MDC.get("correlationId"));

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void reusesIncomingCorrelationId() throws Exception {
        request.addHeader(HEADER, "abc-123");

        filter.doFilter(request, response, recordingChain);

        assertThat(mdcSeenByChain.get()).isEqualTo("abc-123");
        assertThat(response.getHeader(HEADER)).isEqualTo("abc-123");
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void generatesUuidWhenHeaderMissing() throws Exception {
        filter.doFilter(request, response, recordingChain);

        String generated = response.getHeader(HEADER);
        assertThat(generated).isNotBlank();
        assertThat(UUID.fromString(generated).toString()).isEqualTo(generated);
        assertThat(mdcSeenByChain.get()).isEqualTo(generated);
        assertThat(MDC.get("correlationId")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void generatesUuidWhenHeaderBlank(String blank) throws Exception {
        request.addHeader(HEADER, blank);

        filter.doFilter(request, response, recordingChain);

        assertThat(UUID.fromString(response.getHeader(HEADER))).isNotNull();
        assertThat(mdcSeenByChain.get()).isEqualTo(response.getHeader(HEADER));
    }

    @Test
    void eachRequestWithoutHeaderGetsItsOwnId() throws Exception {
        MockHttpServletResponse second = new MockHttpServletResponse();

        filter.doFilter(request, response, recordingChain);
        filter.doFilter(new MockHttpServletRequest("GET", "/x"), second, recordingChain);

        assertThat(response.getHeader(HEADER)).isNotEqualTo(second.getHeader(HEADER));
    }

    @Test
    void clearsMdcEvenWhenChainThrows() {
        request.addHeader(HEADER, "boom-1");
        FilterChain failing = (req, res) -> {
            throw new ServletException("downstream failure");
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, failing)).isInstanceOf(ServletException.class);

        assertThat(MDC.get("correlationId")).isNull();
        assertThat(response.getHeader(HEADER)).isEqualTo("boom-1");
    }
}
