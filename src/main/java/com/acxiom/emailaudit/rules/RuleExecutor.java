package com.acxiom.emailaudit.rules;

import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Executes all enabled {@link AuditRule} strategies registered in a
 * {@link RuleRegistry} against a single Playwright {@link Page} and returns
 * the aggregated {@link RuleResult} list.
 *
 * <h2>Execution model</h2>
 * <p>Rules are executed <strong>sequentially</strong> in registration order on
 * the calling thread.  Playwright {@link Page} objects are not thread-safe;
 * parallelism across files is achieved at the file level (one thread per file,
 * each with its own {@link Page}), not within a single page evaluation.</p>
 *
 * <h2>Fault isolation</h2>
 * <p>Each rule is wrapped in a try/catch.  An unhandled exception from one
 * rule produces an {@link RuleResult.Status#ERROR} result and execution
 * continues with the next rule.  No single broken rule can abort the
 * evaluation of a file.</p>
 *
 * <h2>Thread safety</h2>
 * <p>{@code RuleExecutor} is stateless after construction.  The
 * {@link RuleRegistry} reference is read-only during execution.  Multiple
 * threads may hold their own {@code RuleExecutor} instance (sharing the same
 * registry) and call {@link #execute(Page)} concurrently without external
 * synchronisation.</p>
 */
public final class RuleExecutor {

    private static final Logger log = LoggerFactory.getLogger(RuleExecutor.class);

    private final RuleRegistry registry;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Creates a {@code RuleExecutor} backed by the supplied registry.
     *
     * @param registry non-null registry of rules to execute
     */
    public RuleExecutor(final RuleRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Executes all enabled rules in registration order against {@code page}
     * and returns the aggregated results.
     *
     * <p>The returned list is unmodifiable and contains exactly one
     * {@link RuleResult} per enabled rule (PASS, FAIL, ERROR, or SKIPPED).
     * Disabled rules are silently omitted.</p>
     *
     * @param page fully loaded Playwright page; must not be {@code null}
     * @return unmodifiable list of results in execution order; never {@code null}
     */
    public List<RuleResult> execute(final Page page) {
        Objects.requireNonNull(page, "page must not be null");

        final List<AuditRule> rules = registry.getEnabledRules();

        if (rules.isEmpty()) {
            log.warn("RuleExecutor: no enabled rules registered – returning empty result set");
            return Collections.emptyList();
        }

        log.info("Executing {} rule(s) against: {}", rules.size(), safeUrl(page));

        final List<RuleResult> results = new ArrayList<>(rules.size());

        for (final AuditRule rule : rules) {
            final RuleResult result = executeSingle(rule, page, results);
            results.add(result);
            logResult(result);
        }

        final ExecutionSummary summary = summarise(results);
        log.info("Rule execution complete – pass: {}, fail: {}, error: {}, skipped: {} | total: {}ms",
                summary.passed(), summary.failed(), summary.errored(),
                summary.skipped(), summary.totalDurationMs());

        return Collections.unmodifiableList(results);
    }

    /**
     * Executes a single named rule against {@code page}.
     * Useful for targeted re-runs or debugging.
     *
     * @param ruleId rule identifier to execute
     * @param page   target page
     * @return result of the rule execution, or a SKIPPED result if the rule
     *         is not found in the registry
     */
    public RuleResult executeSingle(final String ruleId, final Page page) {
        Objects.requireNonNull(ruleId, "ruleId must not be null");
        Objects.requireNonNull(page,   "page must not be null");

        return registry.findById(ruleId)
                .map(rule -> executeSingle(rule, page))
                .orElseGet(() -> {
                    log.warn("Rule '{}' not found in registry – returning SKIPPED", ruleId);
                    return buildNotFoundResult(ruleId);
                });
    }

    // -------------------------------------------------------------------------
    // Internal – single rule execution with full fault isolation
    // -------------------------------------------------------------------------

    private static RuleResult executeSingle(final AuditRule rule, final Page page) {
        return executeSingle(rule, page, List.of());
    }

    private static RuleResult executeSingle(
            final AuditRule rule,
            final Page page,
            final List<RuleResult> previousResults) {
        if (!rule.isEnabled()) {
            log.debug("Skipping disabled rule: '{}'", rule.ruleId());
            return RuleResult.skipped(rule, "Rule is disabled");
        }

        final long startMs = System.currentTimeMillis();
        log.debug("Executing rule: '{}' [{}]", rule.ruleId(), rule.category());

        try {
            final RuleResult result = rule instanceof ContextAwareAuditRule contextAwareRule
                    ? contextAwareRule.execute(page, Collections.unmodifiableList(previousResults))
                    : rule.execute(page);

            if (result == null) {
                log.error("Rule '{}' returned null – treating as ERROR", rule.ruleId());
                return RuleResult.error(rule, startMs, "Rule returned null result");
            }

            return result;

        } catch (final Exception e) {
            // Catch Exception (not Throwable) so OutOfMemoryError still propagates.
            log.error("Rule '{}' threw an unhandled exception: {}", rule.ruleId(), e.getMessage(), e);
            return RuleResult.error(rule, startMs, e);
        }
    }

    // -------------------------------------------------------------------------
    // Internal – helpers
    // -------------------------------------------------------------------------

    private static String safeUrl(final Page page) {
        try {
            return page.url();
        } catch (Exception e) {
            return "<unavailable>";
        }
    }

    private static void logResult(final RuleResult result) {
        switch (result.getStatus()) {
            case PASS    -> log.debug("[PASS]    '{}' ({}ms)", result.getRuleId(), result.getDurationMs());
            case FAIL    -> log.warn("[FAIL]    '{}' – {} finding(s) ({}ms)",
                    result.getRuleId(), result.getFindings().size(), result.getDurationMs());
            case ERROR   -> log.error("[ERROR]   '{}' – {} ({}ms)",
                    result.getRuleId(), result.getErrorMessage(), result.getDurationMs());
            case SKIPPED -> log.info("[SKIPPED] '{}' – {}", result.getRuleId(), result.getErrorMessage());
        }
    }

    private static RuleResult buildNotFoundResult(final String ruleId) {
        // Construct a minimal synthetic result without an AuditRule instance.
        return RuleResult.builder(
                        new MissingRuleStub(ruleId),
                        RuleResult.Status.SKIPPED,
                        System.currentTimeMillis())
                .withErrorMessage("Rule '" + ruleId + "' not found in registry")
                .build();
    }

    private static ExecutionSummary summarise(final List<RuleResult> results) {
        int passed = 0, failed = 0, errored = 0, skipped = 0;
        long totalMs = 0L;
        for (final RuleResult r : results) {
            totalMs += r.getDurationMs();
            switch (r.getStatus()) {
                case PASS    -> passed++;
                case FAIL    -> failed++;
                case ERROR   -> errored++;
                case SKIPPED -> skipped++;
            }
        }
        return new ExecutionSummary(passed, failed, errored, skipped, totalMs);
    }

    // -------------------------------------------------------------------------
    // Internal records / stubs
    // -------------------------------------------------------------------------

    private record ExecutionSummary(
            int passed,
            int failed,
            int errored,
            int skipped,
            long totalDurationMs) {}

    /**
     * Minimal no-op {@link AuditRule} used only to satisfy {@link RuleResult}
     * construction when a requested rule ID is absent from the registry.
     */
    /**
     * Minimal no-op {@link AuditRule} used only to satisfy {@link RuleResult}
     * construction when a requested rule ID is absent from the registry.
     */
    private record MissingRuleStub(String ruleId) implements AuditRule {

        @Override
        public String description() {
            return "Rule not found: " + ruleId;
        }

        @Override
        public RuleCategory category() {
            return RuleCategory.CUSTOM;
        }

        @Override
        public RuleSeverity severity() {
            return RuleSeverity.INFO;
        }

        @Override
        public String passImpact() {
            return "No impact — this rule ID could not be resolved in the registry.";
        }

        @Override
        public String failImpact() {
            return "Rule '" + ruleId + "' could not be found, so this check was not evaluated.";
        }

        @Override
        public RuleResult execute(final Page page) {
            return RuleResult.skipped(this, "Stub – should never be executed");
        }
    }
}
