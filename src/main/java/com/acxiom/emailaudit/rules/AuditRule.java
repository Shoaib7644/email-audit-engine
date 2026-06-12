package com.acxiom.emailaudit.rules;

import com.microsoft.playwright.Page;

/**
 * Strategy interface for every audit rule in the pipeline.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>Each implementation encapsulates a single, cohesive audit concern
 *       (e.g. broken links, accessibility, spell-check).</li>
 *   <li>Implementations <strong>must not</strong> interact with reporting
 *       directly — they return a {@link RuleResult} and the engine aggregates
 *       them (Architecture.md: "Rules never interact with reporting").</li>
 *   <li>Implementations must be stateless or internally thread-safe so that
 *       a single instance may be shared across parallel TestNG threads.</li>
 *   <li>Implementations must never throw unchecked exceptions out of
 *       {@link #execute(Page)} — all failures must be captured as a
 *       {@link RuleResult} with {@link RuleResult.Status#ERROR}.</li>
 * </ul>
 *
 * <h2>Adding a new rule</h2>
 * <ol>
 *   <li>Create a class in {@code com.audit.rules.impl} that implements
 *       {@code AuditRule}.</li>
 *   <li>Annotate it so {@link RuleRegistry} can discover it, or register it
 *       programmatically via {@link RuleRegistry#register(AuditRule)}.</li>
 * </ol>
 */
public interface AuditRule {

    /**
     * Returns a short, unique identifier for this rule used in reports and logs.
     *
     * <p>Convention: {@code SCREAMING_SNAKE_CASE}, e.g. {@code BROKEN_LINKS},
     * {@code ALT_TEXT_MISSING}.</p>
     *
     * @return rule ID; never {@code null} or blank
     */
    String ruleId();

    /**
     * Returns a human-readable description of what this rule checks.
     *
     * @return description; never {@code null}
     */
    String description();

    /**
     * Returns the {@link RuleCategory} this rule belongs to, used for grouping
     * in the ExtentReports dashboard.
     *
     * @return category; never {@code null}
     */
    RuleCategory category();

    /**
     * Returns the severity assigned to a finding raised by this rule.
     * Defaults to {@link RuleSeverity#MEDIUM} if not overridden.
     *
     * @return severity; never {@code null}
     */
    default RuleSeverity severity() {
        return RuleSeverity.MEDIUM;
    }

    /**
     * Executes the audit rule against the supplied Playwright {@link Page}.
     *
     * <p>Implementations must:</p>
     * <ul>
     *   <li>Catch all exceptions internally and return an
     *       {@link RuleResult.Status#ERROR} result rather than propagating.</li>
     *   <li>Never navigate away from the current page URL.</li>
     *   <li>Never close the {@link Page} — lifecycle is managed by the caller.</li>
     * </ul>
     *
     * @param page live, fully loaded Playwright page; never {@code null}
     * @return evaluation result; never {@code null}
     */
    RuleResult execute(Page page);

    /**
     * Returns whether this rule is enabled and should be executed.
     * Disabled rules are skipped silently by {@link RuleExecutor}.
     * Defaults to {@code true}.
     *
     * @return {@code true} if the rule should run
     */
    default boolean isEnabled() {
        return true;
    }

    // -------------------------------------------------------------------------
    // Nested enums kept with the interface for discoverability
    // -------------------------------------------------------------------------

    /**
     * Logical grouping for rules shown in the ExtentReports dashboard.
     */
    enum RuleCategory {
        ACCESSIBILITY,
        CONTENT,
        LINKS,
        IMAGES,
        PERFORMANCE,
        SEO,
        SPELLING,
        STRUCTURE,
        HTML,
        CUSTOM
    }

    /**
     * Severity of a finding raised by a rule.
     * Drives colour coding and filtering in the report.
     */
    enum RuleSeverity {
        /** Informational only; no action required. */
        INFO,
        /** Minor issue; should be reviewed. */
        LOW,
        /** Significant issue; should be fixed. */
        MEDIUM,
        /** Serious issue; must be fixed. */
        HIGH,
        /** Blocking issue; deployment should be halted. */
        CRITICAL
    }
}
