package com.acxiom.emailaudit.rules;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable value object returned by every {@link AuditRule} execution.
 *
 * <h2>Structure</h2>
 * <pre>
 *  RuleResult
 *   ├─ ruleId          – which rule produced this result
 *   ├─ status          – PASS | FAIL | ERROR | SKIPPED
 *   ├─ severity        – inherited from the rule at creation time
 *   ├─ findings        – zero or more human-readable finding messages
 *   ├─ errorMessage    – populated only when status == ERROR
 *   ├─ durationMs      – wall-clock execution time in milliseconds
 *   └─ evaluatedAt     – UTC instant the result was created
 * </pre>
 *
 * <h2>Building results</h2>
 * <p>Use the static factory methods rather than the builder directly where
 * the intent is obvious:</p>
 * <pre>{@code
 * // Simple pass
 * return RuleResult.pass(rule);
 *
 * // One or more findings
 * return RuleResult.fail(rule, startMs, List.of("Missing alt on 3 images"));
 *
 * // Internal rule error
 * return RuleResult.error(rule, startMs, e);
 *
 * // Skipped (rule disabled or precondition not met)
 * return RuleResult.skipped(rule, "No images found on page");
 * }</pre>
 */
public final class RuleResult {

    // -------------------------------------------------------------------------
    // Status enum
    // -------------------------------------------------------------------------

    /**
     * Outcome of a single rule execution.
     */
    public enum Status {
        /** Rule ran and found no issues. */
        PASS,
        /** Rule ran and found one or more issues. */
        FAIL,
        /** Rule threw an unexpected exception and could not complete. */
        ERROR,
        /** Rule was not executed (disabled or precondition unmet). */
        SKIPPED
    }

    // -------------------------------------------------------------------------
    // Fields (all final)
    // -------------------------------------------------------------------------

    private final String             ruleId;
    private final String             description;
    private final AuditRule.RuleCategory  category;
    private final AuditRule.RuleSeverity  severity;
    private final Status             status;
    private final List<String>       findings;
    private final String             errorMessage;
    private final long               durationMs;
    private final Instant            evaluatedAt;

    // -------------------------------------------------------------------------
    // Private constructor – use static factories or Builder
    // -------------------------------------------------------------------------

    private RuleResult(final Builder builder) {
        this.ruleId       = builder.ruleId;
        this.description  = builder.description;
        this.category     = builder.category;
        this.severity     = builder.severity;
        this.status       = builder.status;
        this.findings     = Collections.unmodifiableList(new ArrayList<>(builder.findings));
        this.errorMessage = builder.errorMessage;
        this.durationMs   = builder.durationMs;
        this.evaluatedAt  = builder.evaluatedAt;
    }

    // -------------------------------------------------------------------------
    // Static factories
    // -------------------------------------------------------------------------

    /**
     * Creates a PASS result with zero findings.
     *
     * @param rule      the rule that passed
     * @param startMs   {@link System#currentTimeMillis()} at execution start
     * @return PASS result
     */
    public static RuleResult pass(final AuditRule rule, final long startMs) {
        return builder(rule, Status.PASS, startMs).build();
    }

    /**
     * Creates a FAIL result with one or more findings.
     *
     * @param rule      the rule that failed
     * @param startMs   {@link System#currentTimeMillis()} at execution start
     * @param findings  human-readable descriptions of issues found
     * @return FAIL result
     */
    public static RuleResult fail(
            final AuditRule rule,
            final long startMs,
            final List<String> findings) {
        return builder(rule, Status.FAIL, startMs)
                .withFindings(findings)
                .build();
    }

    /**
     * Creates a FAIL result with a single finding message.
     *
     * @param rule     the rule that failed
     * @param startMs  {@link System#currentTimeMillis()} at execution start
     * @param finding  description of the issue found
     * @return FAIL result
     */
    public static RuleResult fail(
            final AuditRule rule,
            final long startMs,
            final String finding) {
        return fail(rule, startMs, List.of(finding));
    }

    /**
     * Creates an ERROR result from an exception thrown during rule execution.
     *
     * @param rule     the rule that errored
     * @param startMs  {@link System#currentTimeMillis()} at execution start
     * @param cause    exception that prevented the rule from completing
     * @return ERROR result
     */
    public static RuleResult error(
            final AuditRule rule,
            final long startMs,
            final Throwable cause) {
        final String message = cause != null
                ? cause.getClass().getSimpleName() + ": " + cause.getMessage()
                : "Unknown error";
        return builder(rule, Status.ERROR, startMs)
                .withErrorMessage(message)
                .build();
    }

    /**
     * Creates an ERROR result from a plain message.
     *
     * @param rule         the rule that errored
     * @param startMs      {@link System#currentTimeMillis()} at execution start
     * @param errorMessage description of the error
     * @return ERROR result
     */
    public static RuleResult error(
            final AuditRule rule,
            final long startMs,
            final String errorMessage) {
        return builder(rule, Status.ERROR, startMs)
                .withErrorMessage(errorMessage)
                .build();
    }

    /**
     * Creates a SKIPPED result with a reason message.
     *
     * @param rule   the rule that was skipped
     * @param reason why the rule was not executed
     * @return SKIPPED result
     */
    public static RuleResult skipped(final AuditRule rule, final String reason) {
        return builder(rule, Status.SKIPPED, System.currentTimeMillis())
                .withErrorMessage(reason)
                .build();
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public String getRuleId()                    { return ruleId; }
    public String getDescription()               { return description; }
    public AuditRule.RuleCategory getCategory()  { return category; }
    public AuditRule.RuleSeverity getSeverity()  { return severity; }
    public Status getStatus()                    { return status; }
    public List<String> getFindings()            { return findings; }
    public String getErrorMessage()              { return errorMessage; }
    public long getDurationMs()                  { return durationMs; }
    public Instant getEvaluatedAt()              { return evaluatedAt; }

    // -------------------------------------------------------------------------
    // Derived helpers
    // -------------------------------------------------------------------------

    public boolean isPassed()  { return Status.PASS    == status; }
    public boolean isFailed()  { return Status.FAIL    == status; }
    public boolean isError()   { return Status.ERROR   == status; }
    public boolean isSkipped() { return Status.SKIPPED == status; }

    /** Returns {@code true} when this result requires attention (FAIL or ERROR). */
    public boolean requiresAttention() {
        return isFailed() || isError();
    }

    // -------------------------------------------------------------------------
    // equals / hashCode / toString
    // -------------------------------------------------------------------------

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof RuleResult other)) return false;
        return Objects.equals(ruleId, other.ruleId)
                && Objects.equals(evaluatedAt, other.evaluatedAt)
                && status == other.status;
    }

    @Override
    public int hashCode() {
        return Objects.hash(ruleId, evaluatedAt, status);
    }

    @Override
    public String toString() {
        return "RuleResult{"
                + "ruleId='" + ruleId + '\''
                + ", status=" + status
                + ", findings=" + findings.size()
                + ", durationMs=" + durationMs
                + ", evaluatedAt=" + evaluatedAt
                + '}';
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    /**
     * Returns a pre-populated {@link Builder} from an {@link AuditRule} instance.
     */
    public static Builder builder(
            final AuditRule rule,
            final Status status,
            final long startMs) {
        Objects.requireNonNull(rule,   "rule must not be null");
        Objects.requireNonNull(status, "status must not be null");
        return new Builder(rule, status, startMs);
    }

    /**
     * Fluent builder for {@link RuleResult}.
     * Not thread-safe; use one builder per thread.
     */
    public static final class Builder {

        private final String                  ruleId;
        private final String                  description;
        private final AuditRule.RuleCategory  category;
        private final AuditRule.RuleSeverity  severity;
        private final Status                  status;
        private final long                    durationMs;
        private final Instant                 evaluatedAt;

        private final List<String> findings     = new ArrayList<>();
        private       String       errorMessage = null;

        private Builder(final AuditRule rule, final Status status, final long startMs) {
            this.ruleId      = rule.ruleId();
            this.description = rule.description();
            this.category    = rule.category();
            this.severity    = rule.severity();
            this.status      = status;
            this.evaluatedAt = Instant.now();
            this.durationMs  = Math.max(0L, evaluatedAt.toEpochMilli() - startMs);
        }

        public Builder withFinding(final String finding) {
            if (finding != null && !finding.isBlank()) {
                this.findings.add(finding);
            }
            return this;
        }

        public Builder withFindings(final List<String> findings) {
            if (findings != null) {
                findings.stream()
                        .filter(f -> f != null && !f.isBlank())
                        .forEach(this.findings::add);
            }
            return this;
        }

        public Builder withErrorMessage(final String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public RuleResult build() {
            return new RuleResult(this);
        }
    }
}
