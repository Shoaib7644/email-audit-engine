package com.acxiom.emailaudit.reporting;

import com.acxiom.emailaudit.reporting.dashboard.FileAuditData;
import com.acxiom.emailaudit.reporting.dashboard.RunAuditData;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Renders the custom audit dashboard by loading the HTML template
 * and injecting dashboard JSON data.
 */
public final class ReportTemplateRenderer {

    private static final Logger log =
            LoggerFactory.getLogger(ReportTemplateRenderer.class);

    private static final String TEMPLATE_PATH =
            "reporting/dashboard.html";

    private static final String DATA_PLACEHOLDER =
            "{{DASHBOARD_DATA}}";

    private final ObjectMapper objectMapper;

    public ReportTemplateRenderer() {

        this.objectMapper = new ObjectMapper();

        this.objectMapper.registerModule(
                new JavaTimeModule());

        this.objectMapper.disable(
                SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Loads the dashboard template and injects dashboard data as JSON.
     *
     * @param data dashboard data model
     * @return rendered HTML content
     */
    public String renderDashboard(
            final RunAuditData data) {

        Objects.requireNonNull(
                data,
                "RunAuditData must not be null");

        final String template =
                loadTemplate();

        final String jsonPayload =
                toJson(data);

        validateAndLog(data, jsonPayload);

        final String rendered =
                template.replace(
                        DATA_PLACEHOLDER,
                        jsonPayload);

        validatePlaceholderReplaced(rendered);

        return rendered;
    }

    // -------------------------------------------------------------------------
    // Validation and logging
    // -------------------------------------------------------------------------

    /**
     * Validates the serialised JSON payload and logs the three required
     * dashboard generation metrics: JSON size, file count, and total rule count.
     *
     * <p>A warning is emitted when:</p>
     * <ul>
     *   <li>The JSON payload is empty after serialisation.</li>
     *   <li>The serialised JSON does not contain the {@code "rules"} key,
     *       indicating that {@link com.acxiom.emailaudit.reporting.dashboard.RuleAuditData}
     *       entries were not included in serialisation (e.g. Jackson visibility
     *       filtering or a missing accessor on {@code FileAuditData}).</li>
     *   <li>The serialised JSON does not contain the {@code "findings"} key,
     *       indicating findings arrays were dropped during serialisation.</li>
     * </ul>
     *
     * @param data        the dashboard data model
     * @param jsonPayload the JSON string produced by {@link #toJson(RunAuditData)}
     */
    private static void validateAndLog(
            final RunAuditData data,
            final String jsonPayload) {

        // ── Metric 1: JSON size ───────────────────────────────────────────────
        final int jsonSizeBytes =
                jsonPayload.getBytes(StandardCharsets.UTF_8).length;

        // ── Metric 2: file count ──────────────────────────────────────────────
        final int fileCount =
                data.files() != null
                        ? data.files().size()
                        : 0;

        // ── Metric 3: total rule count across all files ───────────────────────
        final long totalRuleCount =
                data.files() == null
                        ? 0L
                        : data.files().stream()
                          .map(FileAuditData::rules)
                          .filter(Objects::nonNull)
                          .mapToLong(java.util.List::size)
                          .sum();

        log.info("Dashboard JSON size   : {} bytes", jsonSizeBytes);
        log.info("Dashboard file count  : {}", fileCount);
        log.info("Dashboard rule count  : {}", totalRuleCount);

        // ── Serialisation verification: RuleAuditData present ────────────────
        if (jsonPayload.isEmpty()) {
            log.warn("Dashboard JSON payload is empty – serialisation produced no output");
        } else if (!jsonPayload.contains("\"rules\"")) {
            log.warn("Dashboard JSON does not contain a \"rules\" key – "
                    + "RuleAuditData entries may not have been serialised. "
                    + "Verify that FileAuditData.rules() is accessible to Jackson "
                    + "(record accessors are not JavaBean getters; "
                    + "ensure jackson-modules-java21 or a matching visibility strategy is configured).");
        } else {
            log.info("Dashboard JSON serialisation verified: \"rules\" key present");
        }

        // ── Serialisation verification: findings array preserved ─────────────
        if (!jsonPayload.isEmpty() && !jsonPayload.contains("\"findings\"")) {
            log.warn("Dashboard JSON does not contain a \"findings\" key – "
                    + "findings arrays may have been dropped during serialisation.");
        } else if (!jsonPayload.isEmpty()) {
            log.info("Dashboard JSON serialisation verified: \"findings\" key present");
        }

        // ── Sanity check: data shape ──────────────────────────────────────────
        if (fileCount == 0) {
            log.warn("Dashboard data contains no file results – "
                    + "RunAuditData.files() returned null or an empty list");
        }

        if (totalRuleCount == 0 && fileCount > 0) {
            log.warn("Dashboard data contains {} file(s) but zero rule results – "
                            + "RuleAuditData list may not have been populated by DashboardDataCollector",
                    fileCount);
        }
    }

    /**
     * Verifies that the {@code {{DASHBOARD_DATA}}} placeholder was actually
     * replaced in the rendered template. If it is still present after
     * replacement, the JSON injection silently failed (e.g. the placeholder
     * text in the template does not match exactly).
     */
    private static void validatePlaceholderReplaced(
            final String rendered) {

        if (rendered.contains(DATA_PLACEHOLDER)) {
            log.error("Dashboard template placeholder '{}' was not replaced – "
                            + "the generated HTML will not contain audit data. "
                            + "Verify that the template file at '{}' contains the exact "
                            + "placeholder string.",
                    DATA_PLACEHOLDER,
                    TEMPLATE_PATH);
        } else {
            log.info("Dashboard template injection complete – placeholder replaced successfully");
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String loadTemplate() {

        try (InputStream inputStream =
                     Thread.currentThread()
                             .getContextClassLoader()
                             .getResourceAsStream(
                                     TEMPLATE_PATH)) {

            if (inputStream == null) {

                throw new IllegalStateException(
                        "Dashboard template not found: "
                                + TEMPLATE_PATH);
            }

            return new String(
                    inputStream.readAllBytes(),
                    StandardCharsets.UTF_8);

        } catch (IOException ex) {

            throw new RuntimeException(
                    "Failed to load dashboard template: "
                            + TEMPLATE_PATH,
                    ex);
        }
    }

    private String toJson(
            final RunAuditData data) {

        try {

            return objectMapper.writeValueAsString(
                    data);

        } catch (JsonProcessingException ex) {

            throw new RuntimeException(
                    "Failed to serialize dashboard data to JSON",
                    ex);
        }
    }
}