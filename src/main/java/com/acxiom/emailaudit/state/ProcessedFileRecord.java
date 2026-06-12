package com.acxiom.emailaudit.state;


import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable value object representing the audit state of a single processed file.
 *
 * <p>Instances are created either programmatically via the static factory
 * {@link #of(String, String, ProcessingStatus)} or by Jackson during
 * deserialisation from the JSON state store.</p>
 *
 * <p>Thread safety: this class is immutable – all fields are {@code final} and
 * no mutating methods are exposed. Safe for concurrent read access without
 * external synchronisation.</p>
 *
 * <h3>JSON shape</h3>
 * <pre>{@code
 * {
 *   "filePath"        : "/reports/index.html",
 *   "contentHash"     : "a3f5…",
 *   "status"          : "SUCCESS",
 *   "processedAt"     : "2024-06-01T10:15:30Z",
 *   "errorMessage"    : null
 * }
 * }</pre>
 */
public final class ProcessedFileRecord {

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    /** Absolute or relative path of the processed HTML file. */
    private final String filePath;

    /**
     * SHA-256 hex digest of the file's contents at the time of processing.
     * Used by {@link StateRegistry} to detect re-processing of changed files.
     */
    private final String contentHash;

    /** Processing outcome. */
    private final ProcessingStatus status;

    /** UTC timestamp when this record was created. */
    private final Instant processedAt;

    /**
     * Human-readable error detail when {@code status == FAILED}; {@code null} otherwise.
     */
    private final String errorMessage;

    // -------------------------------------------------------------------------
    // Jackson constructor (all args)
    // -------------------------------------------------------------------------

    @JsonCreator
    private ProcessedFileRecord(
            @JsonProperty("filePath")     final String filePath,
            @JsonProperty("contentHash")  final String contentHash,
            @JsonProperty("status")       final ProcessingStatus status,
            @JsonProperty("processedAt") final Instant processedAt,
            @JsonProperty("errorMessage") final String errorMessage) {

        this.filePath     = Objects.requireNonNull(filePath,     "filePath must not be null");
        this.contentHash  = Objects.requireNonNull(contentHash,  "contentHash must not be null");
        this.status       = Objects.requireNonNull(status,       "status must not be null");
        this.processedAt  = Objects.requireNonNull(processedAt,  "processedAt must not be null");
        this.errorMessage = errorMessage; // nullable
    }

    // -------------------------------------------------------------------------
    // Static factories
    // -------------------------------------------------------------------------

    /**
     * Creates a record stamped with the current UTC instant and no error message.
     *
     * @param filePath    absolute or project-relative path of the HTML file
     * @param contentHash SHA-256 hex digest of the file's contents
     * @param status      processing outcome
     * @return new {@code ProcessedFileRecord}
     */
    public static ProcessedFileRecord of(
            final String filePath,
            final String contentHash,
            final ProcessingStatus status) {
        return new ProcessedFileRecord(filePath, contentHash, status, Instant.now(), null);
    }

    /**
     * Creates a {@link ProcessingStatus#FAILED} record with an error message.
     *
     * @param filePath    path of the HTML file that failed
     * @param contentHash SHA-256 hex digest at the time of the attempt
     * @param errorMessage description of the failure; truncated to 2 000 chars
     * @return new {@code ProcessedFileRecord}
     */
    public static ProcessedFileRecord failed(
            final String filePath,
            final String contentHash,
            final String errorMessage) {
        final String safeMessage = errorMessage != null && errorMessage.length() > 2_000
                ? errorMessage.substring(0, 2_000) + "…"
                : errorMessage;
        return new ProcessedFileRecord(
                filePath, contentHash, ProcessingStatus.FAILED, Instant.now(), safeMessage);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    @JsonProperty("filePath")
    public String getFilePath() {
        return filePath;
    }

    @JsonProperty("contentHash")
    public String getContentHash() {
        return contentHash;
    }

    @JsonProperty("status")
    public ProcessingStatus getStatus() {
        return status;
    }

    @JsonProperty("processedAt")
    public Instant getProcessedAt() {
        return processedAt;
    }

    @JsonProperty("errorMessage")
    public String getErrorMessage() {
        return errorMessage;
    }

    // -------------------------------------------------------------------------
    // Derived helpers
    // -------------------------------------------------------------------------

    /** Returns {@code true} when this record represents a successful audit run. */
    public boolean isSuccessful() {
        return ProcessingStatus.SUCCESS == status;
    }

    /** Returns {@code true} when this record represents a failed audit run. */
    public boolean isFailed() {
        return ProcessingStatus.FAILED == status;
    }

    // -------------------------------------------------------------------------
    // equals / hashCode / toString
    // -------------------------------------------------------------------------

    /**
     * Two records are equal when they share the same {@code filePath} and
     * {@code contentHash} – i.e. the same file at the same content snapshot.
     */
    @Override
    public boolean equals(final Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ProcessedFileRecord other)) return false;
        return Objects.equals(filePath, other.filePath)
                && Objects.equals(contentHash, other.contentHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(filePath, contentHash);
    }

    @Override
    public String toString() {
        return "ProcessedFileRecord{"
                + "filePath='" + filePath + '\''
                + ", contentHash='" + contentHash + '\''
                + ", status=" + status
                + ", processedAt=" + processedAt
                + ", errorMessage='" + errorMessage + '\''
                + '}';
    }

    // -------------------------------------------------------------------------
    // Status enum
    // -------------------------------------------------------------------------

    /**
     * Possible outcomes of processing a single HTML file through the audit pipeline.
     */
    public enum ProcessingStatus {
        /** All audit rules executed without a fatal error. */
        SUCCESS,
        /** At least one fatal error prevented full processing. */
        FAILED,
        /** Processing was interrupted (e.g. JVM shutdown) before completion. */
        INTERRUPTED
    }
}
