package com.ecommerce.shipping.logging;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class LogThrottleTest {

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final LogThrottle throttle = new LogThrottle(10, TimeUnit.SECONDS, nanos::get);

    private void advanceSeconds(long seconds) {
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(seconds));
    }

    @Test
    void firstOccurrenceLogs_withNothingSuppressed() {
        assertThat(throttle.acquire("db-down")).isZero();
    }

    @Test
    void repeatsInsideTheWindowAreSuppressed() {
        throttle.acquire("db-down");
        advanceSeconds(3);

        assertThat(throttle.acquire("db-down")).isEqualTo(-1);
        assertThat(throttle.acquire("db-down")).isEqualTo(-1);
    }

    @Test
    void theNextWindowLogsAgain_andReportsHowManyWereSuppressed() {
        throttle.acquire("db-down");
        for (int i = 0; i < 5; i++) {
            assertThat(throttle.acquire("db-down")).isEqualTo(-1);
        }
        advanceSeconds(10);

        assertThat(throttle.acquire("db-down")).isEqualTo(5);
        // and the count starts again from zero
        assertThat(throttle.acquire("db-down")).isEqualTo(-1);
        advanceSeconds(10);
        assertThat(throttle.acquire("db-down")).isEqualTo(1);
    }

    @Test
    void keysAreIndependent() {
        throttle.acquire("a");

        assertThat(throttle.acquire("b")).isZero();
        assertThat(throttle.acquire("a")).isEqualTo(-1);
    }

    @Test
    void trackedKeysAreBounded_pastTheCapNewKeysShareOneBucket() {
        for (int i = 0; i < LogThrottle.MAX_KEYS; i++) {
            assertThat(throttle.acquire("key-" + i)).isZero();
        }

        // the 257th and 258th distinct keys land in the shared overflow bucket
        assertThat(throttle.acquire("one-more")).isZero();
        assertThat(throttle.acquire("and-another")).isEqualTo(-1);
        // keys seen before the cap keep their own window
        assertThat(throttle.acquire("key-0")).isEqualTo(-1);
    }

    @Test
    void suppressedSuffix_isEmptyUnlessSomethingWasSuppressed() {
        assertThat(LogThrottle.suppressedSuffix(0)).isEmpty();
        assertThat(LogThrottle.suppressedSuffix(-1)).isEmpty();
        assertThat(LogThrottle.suppressedSuffix(12)).isEqualTo(" (12 similar suppressed)");
    }

    @Test
    void keyOf_picksTheFirstFrameInOurOwnCode() {
        RuntimeException ex = new RuntimeException("boom");
        ex.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("org.springframework.jdbc.Template", "query", "Template.java", 10),
                new StackTraceElement("com.ecommerce.shipping.service.Thing", "load", "Thing.java", 42),
                new StackTraceElement("com.ecommerce.shipping.controller.Api", "get", "Api.java", 7)});

        assertThat(LogThrottle.keyOf(ex)).isEqualTo("java.lang.RuntimeException@com.ecommerce.shipping.service.Thing:42");
    }

    @Test
    void keyOf_fallsBackToTheTopFrame_orJustTheClass() {
        RuntimeException framework = new RuntimeException();
        framework.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("org.springframework.jdbc.Template", "query", "Template.java", 10)});
        RuntimeException bare = new RuntimeException();
        bare.setStackTrace(new StackTraceElement[0]);

        assertThat(LogThrottle.keyOf(framework)).isEqualTo("java.lang.RuntimeException@org.springframework.jdbc.Template:10");
        assertThat(LogThrottle.keyOf(bare)).isEqualTo("java.lang.RuntimeException");
    }
}
