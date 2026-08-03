package com.acxiom.emailaudit.utilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lightweight run-level performance counters for audit timing diagnostics.
 */
public final class PerformanceMetrics {

    private static final Logger log = LoggerFactory.getLogger(PerformanceMetrics.class);

    private static final LongAdder chromeLaunchMs = new LongAdder();
    private static final LongAdder emailRenderMs = new LongAdder();
    private static final LongAdder linkDiscoveryMs = new LongAdder();
    private static final LongAdder navigationMs = new LongAdder();
    private static final LongAdder stabilizationMs = new LongAdder();
    private static final LongAdder screenshotMs = new LongAdder();
    private static final LongAdder linkTotalMs = new LongAdder();
    private static final LongAdder validatedLinks = new LongAdder();
    private static final AtomicLong runStartNanos = new AtomicLong();

    private PerformanceMetrics() {}

    public static void reset() {
        chromeLaunchMs.reset();
        emailRenderMs.reset();
        linkDiscoveryMs.reset();
        navigationMs.reset();
        stabilizationMs.reset();
        screenshotMs.reset();
        linkTotalMs.reset();
        validatedLinks.reset();
        runStartNanos.set(System.nanoTime());
    }

    public static void recordChromeLaunch(final long millis) {
        chromeLaunchMs.add(Math.max(0, millis));
    }

    public static void recordEmailRender(final long millis) {
        emailRenderMs.add(Math.max(0, millis));
    }

    public static void recordLinkDiscovery(final long millis) {
        linkDiscoveryMs.add(Math.max(0, millis));
    }

    public static void recordLink(
            final String label,
            final long clickMillis,
            final long navigationMillis,
            final long stabilizationMillis,
            final long screenshotMillis,
            final long totalMillis) {

        navigationMs.add(Math.max(0, navigationMillis));
        stabilizationMs.add(Math.max(0, stabilizationMillis));
        screenshotMs.add(Math.max(0, screenshotMillis));
        linkTotalMs.add(Math.max(0, totalMillis));
        validatedLinks.increment();

        log.info("Link timing – '{}' click={}ms navigation={}ms stabilization={}ms screenshot={}ms total={}ms",
                compact(label),
                Math.max(0, clickMillis),
                Math.max(0, navigationMillis),
                Math.max(0, stabilizationMillis),
                Math.max(0, screenshotMillis),
                Math.max(0, totalMillis));
    }

    public static void logSummary(final long overallExecutionMillis) {
        final long linkCount = validatedLinks.sum();
        final long averagePerLink = linkCount == 0 ? 0 : linkTotalMs.sum() / linkCount;
        final long overall = overallExecutionMillis >= 0
                ? overallExecutionMillis
                : elapsedMillis(runStartNanos.get());

        log.info("""
                
                =========================================
                Performance Summary
                =========================================
                Chrome Launch: {}ms
                Email Render: {}ms
                Link Discovery: {}ms
                
                Total Navigation Time: {}ms
                Total Stabilization Time: {}ms
                Total Screenshot Time: {}ms
                
                Average Per Link: {}ms ({} link(s))
                
                Overall Execution Time: {}ms
                =========================================""",
                chromeLaunchMs.sum(),
                emailRenderMs.sum(),
                linkDiscoveryMs.sum(),
                navigationMs.sum(),
                stabilizationMs.sum(),
                screenshotMs.sum(),
                averagePerLink,
                linkCount,
                overall);
    }

    public static long elapsedMillis(final long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static String compact(final String label) {
        if (label == null || label.isBlank()) {
            return "(no visible text)";
        }
        final String normalized = label.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 77) + "...";
    }
}
