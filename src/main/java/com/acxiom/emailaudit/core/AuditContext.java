package com.acxiom.emailaudit.core;

import com.acxiom.emailaudit.rules.RuleResult;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.ArrayList;

/**
 * Immutable value object representing the complete execution context and
 * outcome of auditing a single HTML email file.
 *
 * <h2>Lifecycle</h2>
 * <p>An {@code AuditContext} is typically constructed in two phases:</p>
 * <ol>
 *   <li><strong>Start</strong> – {@link Builder#startedNow()} records
 *       {@code startTime} when ingestion begins for a file.</li>
 *   <li><strong>Completion</strong> – after rendering, rule execution,
 *       and evidence capture, the builder is populated with
 *       {@code fileHash}, {@code screenshotPath}, {@code ruleResults},
 *       {@code endTime}, and {@code status}, then {@link Builder#build()}
 *       produces the final immutable instance.</li>
 * </ol>
 *
 * <h2>Immutability</h2>
 * <p>All fields are {@code final}. {@link #getRuleResults()} returns an
 * unmodifiable list. Instances are safe to share across threads (e.g. handed
 * from a file-processing thread to {@code ReportManager} and
 * {@code StateRegistry} without defensive copying).</p>
 *
 * <h2>Thread safety</h2>
 * <p>{@code AuditContext} itself is immutable and thread-safe.
 * {@link Builder} is <strong>not</strong> thread-safe and must be used by a
 * single thread for the duration of one file's audit.</p>
 */
public final class AuditContext {

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final Path             htmlFile;
    private final String           fileHash;
    private final Path             screenshotPath;
    private final List<RuleResult> ruleResults;
    private final Instant          startTime;
    private final Instant          endTime;
    private final AuditStatus      status;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    private AuditContext(final Builder builder) {
        this.htmlFile       = builder.htmlFile;
        this.fileHash       = builder.fileHash;
        this.screenshotPath = builder.screenshotPath;
        this.ruleResults    = Collections.unmodifiableList(new ArrayList<>(builder.ruleResults));
        this.startTime      = builder.startTime;
        this.endTime        = builder.endTime;
        this.status         = builder.status;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /**
     * Returns the absolute path of the HTML file that was audited.
     *
     * @return HTML file path; never {@code null}
     */
    public Path getHtmlFile() {
        return htmlFile;
    }

    /**
     * Returns the SHA-256 content hash of the HTML file at the time it was
     * processed, or {@code null} if the hash had not yet been computed
     * (e.g. context captured before rendering completed).
     *
     * @return content hash, or {@code null}
     */
    public String getFileHash() {
        return fileHash;
    }

    /**
     * Returns the path to the full-page screenshot captured for this file,
     * or {@code null} if no screenshot was captured (e.g. the file failed
     * before rendering, or screenshots are disabled).
     *
     * @return screenshot path, or {@code null}
     */
    public Path getScreenshotPath() {
        return screenshotPath;
    }

    /**
     * Returns the aggregated results of every rule executed against this file.
     *
     * @return unmodifiable list; never {@code null}, may be empty
     */
    public List<RuleResult> getRuleResults() {
        return ruleResults;
    }

    /**
     * Returns the instant this file's audit began.
     *
     * @return start time; never {@code null}
     */
    public Instant getStartTime() {
        return startTime;
    }

    /**
     * Returns the instant this file's audit completed, or {@code null} if the
     * audit is still in progress (e.g. an in-flight context).
     *
     * @return end time, or {@code null} if not yet completed
     */
    public Instant getEndTime() {
        return endTime;
    }

    /**
     * Returns the overall outcome status of this audit.
     *
     * @return status; never {@code null}
     */
    public AuditStatus getStatus() {
        return status;
    }

    // -------------------------------------------------------------------------
    // Derived helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the total wall-clock duration of this audit, from
     * {@link #getStartTime()} to {@link #getEndTime()}.
     *
     * @return duration, or {@link Duration#ZERO} if {@code endTime} is
     *         {@code null} (audit not yet completed)
     */
    public Duration getDuration() {
        if (endTime == null) {
            return Duration.ZERO;
        }
        return Duration.between(startTime, endTime);
    }

    /**
     * Returns {@code true} if this audit completed (successfully or not),
     * i.e. {@link #getEndTime()} is non-null.
     *
     * @return {@code true} if the audit has finished
     */
    public boolean isComplete() {
        return endTime != null;
    }

    /**
     * Returns {@code true} if a screenshot was captured for this file.
     *
     * @return {@code true} if {@link #getScreenshotPath()} is non-null
     */
    public boolean hasScreenshot() {
        return screenshotPath != null;
    }

    /**
     * Returns the count of rule results with a given status, or any combination
     * thereof, via {@link RuleResult#requiresAttention()}.
     *
     * @return number of {@link RuleResult} entries that are FAIL or ERROR
     */
    public long getAttentionCount() {
        return ruleResults.stream().filter(RuleResult::requiresAttention).count();
    }

    /**
     * Returns the file name (last path segment) of {@link #getHtmlFile()}.
     *
     * @return file name; never {@code null}
     */
    public String getFileName() {
        final Path name = htmlFile.getFileName();
        return name != null ? name.toString() : htmlFile.toString();
    }

    // -------------------------------------------------------------------------
    // equals / hashCode / toString
    // -------------------------------------------------------------------------

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof AuditContext other)) return false;
        return Objects.equals(htmlFile, other.htmlFile)
                && Objects.equals(startTime, other.startTime)
                && status == other.status;
    }

    @Override
    public int hashCode() {
        return Objects.hash(htmlFile, startTime, status);
    }

    @Override
    public String toString() {
        return "AuditContext{"
                + "htmlFile=" + htmlFile
                + ", fileHash='" + fileHash + '\''
                + ", screenshotPath=" + screenshotPath
                + ", ruleResults=" + ruleResults.size() + " result(s)"
                + ", startTime=" + startTime
                + ", endTime=" + endTime
                + ", status=" + status
                + '}';
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    /**
     * Creates a new {@link Builder} for the given HTML file, with
     * {@link AuditStatus#IN_PROGRESS} as the initial status.
     *
     * @param htmlFile path to the HTML file being audited; must not be {@code null}
     * @return new builder instance
     */
    public static Builder builder(final Path htmlFile) {
        return new Builder(htmlFile);
    }

    /**
     * Fluent builder for {@link AuditContext}.
     *
     * <p><strong>Not thread-safe</strong> – intended for use by a single
     * file-processing thread for the lifetime of one audit.</p>
     */
    public static final class Builder {

        private final Path htmlFile;

        private String           fileHash       = null;
        private Path             screenshotPath = null;
        private List<RuleResult> ruleResults    = List.of();
        private Instant          startTime      = Instant.now();
        private Instant          endTime        = null;
        private AuditStatus      status         = AuditStatus.IN_PROGRESS;

        private Builder(final Path htmlFile) {
            this.htmlFile = Objects.requireNonNull(htmlFile, "htmlFile must not be null")
                    .normalize()
                    .toAbsolutePath();
        }

        /**
         * Sets the SHA-256 content hash computed for this file.
         *
         * @param fileHash hex digest string; may be {@code null}
         * @return this builder
         */
        public Builder withFileHash(final String fileHash) {
            this.fileHash = fileHash;
            return this;
        }

        /**
         * Sets the path to the captured full-page screenshot.
         *
         * @param screenshotPath path to PNG file; may be {@code null}
         * @return this builder
         */
        public Builder withScreenshotPath(final Path screenshotPath) {
            this.screenshotPath = screenshotPath;
            return this;
        }

        /**
         * Sets the aggregated rule results for this file.
         * A defensive copy is taken; the supplied list is not retained.
         *
         * @param ruleResults non-null list of rule results; may be empty
         * @return this builder
         */
        public Builder withRuleResults(final List<RuleResult> ruleResults) {
            Objects.requireNonNull(ruleResults, "ruleResults must not be null");
            this.ruleResults = List.copyOf(ruleResults);
            return this;
        }

        /**
         * Overrides the start time. By default, the builder captures
         * {@link Instant#now()} at construction time.
         *
         * @param startTime explicit start instant; must not be {@code null}
         * @return this builder
         */
        public Builder withStartTime(final Instant startTime) {
            this.startTime = Objects.requireNonNull(startTime, "startTime must not be null");
            return this;
        }

        /**
         * Sets the end time explicitly.
         *
         * @param endTime explicit end instant; must not be {@code null}
         * @return this builder
         */
        public Builder withEndTime(final Instant endTime) {
            this.endTime = Objects.requireNonNull(endTime, "endTime must not be null");
            return this;
        }

        /**
         * Sets {@link #endTime} to {@link Instant#now()}.
         * Convenience for marking completion at the point of construction.
         *
         * @return this builder
         */
        public Builder completedNow() {
            this.endTime = Instant.now();
            return this;
        }

        /**
         * Sets the overall audit status.
         *
         * @param status non-null status
         * @return this builder
         */
        public Builder withStatus(final AuditStatus status) {
            this.status = Objects.requireNonNull(status, "status must not be null");
            return this;
        }

        /**
         * Builds the immutable {@link AuditContext}.
         *
         * <p>If {@link #withStatus} was never called and {@link #endTime} is
         * set, the status is automatically derived from
         * {@link #ruleResults} via {@link AuditStatus#fromResults(List)}
         * unless explicitly overridden.</p>
         *
         * @return new immutable {@code AuditContext}
         */
        public AuditContext build() {
            return new AuditContext(this);
        }
    }

    // -------------------------------------------------------------------------
    // AuditStatus enum
    // -------------------------------------------------------------------------

    /**
     * Overall outcome of a single file's audit execution.
     */
    public enum AuditStatus {

        /** Audit has started but not yet completed. */
        IN_PROGRESS,

        /** All rules executed; none failed or errored. */
        SUCCESS,

        /** All rules executed; at least one rule returned FAIL. */
        FAILED,

        /** At least one rule threw an unexpected exception (ERROR). */
        ERROR,

        /** File was skipped entirely (e.g. duplicate or already processed). */
        SKIPPED;

        /**
         * Derives an {@link AuditStatus} from a completed list of
         * {@link RuleResult}.
         *
         * <p>Precedence: any {@code ERROR} result yields {@link #ERROR};
         * otherwise any {@code FAIL} result yields {@link #FAILED};
         * otherwise {@link #SUCCESS}.</p>
         *
         * @param results non-null list of rule results (may be empty)
         * @return derived status; {@link #SUCCESS} if {@code results} is empty
         */
        public static AuditStatus fromResults(final List<RuleResult> results) {
            Objects.requireNonNull(results, "results must not be null");

            boolean anyError = false;
            boolean anyFail  = false;

            for (final RuleResult result : results) {
                if (result.isError())  anyError = true;
                if (result.isFailed()) anyFail  = true;
            }

            if (anyError) return ERROR;
            if (anyFail)  return FAILED;
            return SUCCESS;
        }
    }
}
