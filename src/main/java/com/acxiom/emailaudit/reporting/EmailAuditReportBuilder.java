package com.acxiom.emailaudit.reporting;

import com.acxiom.emailaudit.rules.AuditRule;
import com.acxiom.emailaudit.rules.RuleResult;
import com.aventstack.extentreports.ExtentTest;
import com.aventstack.extentreports.MediaEntityBuilder;
import com.aventstack.extentreports.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Fluent builder that constructs a fully structured per-email section inside
 * an existing {@link ExtentTest} node created by {@link ReportManager}.
 *
 * <h2>Section anatomy</h2>
 * <pre>
 *  ExtentTest (file-level, owned by ReportManager)
 *   │
 *   ├─ [Header block]  file metadata + status badge
 *   │
 *   ├─ [Evidence block]  embedded screenshot(s) with caption
 *   │
 *   ├─ [Category node]   e.g. ACCESSIBILITY
 *   │    ├─ [Rule node]  ACCESSIBILITY_AXE  →  PASS / FAIL / ERROR
 *   │    │    ├─ Duration + status line
 *   │    │    └─ Finding detail rows (FAIL / ERROR only)
 *   │    └─ …
 *   │
 *   ├─ [Category node]   e.g. CONTENT
 *   │    └─ …
 *   │
 *   └─ [Summary block]  metrics table + severity breakdown
 * </pre>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * EmailAuditReportBuilder.forTest(fileTest)
 *     .withFileName("welcome.html")
 *     .withFilePath(path)
 *     .withResults(ruleResults)
 *     .withScreenshot(screenshotPath)
 *     .withAdditionalEvidence("Axe JSON", axeJson)
 *     .build();
 * }</pre>
 *
 * <h2>Architecture contract</h2>
 * <p>This class only reads {@link RuleResult} values — it never invokes rule
 * logic.  It delegates final ExtentTest writes to {@link ReportManager}
 * conventions for status colours and HTML escaping.</p>
 *
 * <h2>Thread safety</h2>
 * <p>Each builder instance is single-use and single-threaded: one instance is
 * created per file on the file-processing thread, fully configured, and then
 * {@link #build()} is called once.  The {@link ExtentTest} node passed in must
 * already have been created under {@link ReportManager}'s lock.  No shared
 * mutable state exists after construction.</p>
 */
public final class EmailAuditReportBuilder {

    private static final Logger log = LoggerFactory.getLogger(EmailAuditReportBuilder.class);

    private static final DateTimeFormatter DISPLAY_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
                    .withZone(ZoneOffset.UTC);

    // -------------------------------------------------------------------------
    // Severity → CSS colour mapping
    // -------------------------------------------------------------------------

    private static final Map<AuditRule.RuleSeverity, String> SEVERITY_COLOURS =
            Map.of(
                    AuditRule.RuleSeverity.CRITICAL, "#c0392b",
                    AuditRule.RuleSeverity.HIGH,     "#e74c3c",
                    AuditRule.RuleSeverity.MEDIUM,   "#e67e22",
                    AuditRule.RuleSeverity.LOW,      "#f1c40f",
                    AuditRule.RuleSeverity.INFO,     "#7f8c8d"
            );

    // -------------------------------------------------------------------------
    // Builder state
    // -------------------------------------------------------------------------

    private final ExtentTest fileTest;

    private String       fileName        = "unknown.html";
    private Path         filePath        = null;
    private List<RuleResult> results     = Collections.emptyList();
    private Path         screenshotPath  = null;
    private final List<EvidenceEntry> additionalEvidence = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    private EmailAuditReportBuilder(final ExtentTest fileTest) {
        this.fileTest = Objects.requireNonNull(fileTest, "fileTest must not be null");
    }

    /**
     * Entry point. Returns a new builder bound to the supplied ExtentTest node.
     *
     * @param fileTest the top-level test node created by {@link ReportManager}
     * @return new builder instance
     */
    public static EmailAuditReportBuilder forTest(final ExtentTest fileTest) {
        return new EmailAuditReportBuilder(fileTest);
    }

    // -------------------------------------------------------------------------
    // Fluent setters
    // -------------------------------------------------------------------------

    /**
     * Sets the display name of the HTML file (used in headings and labels).
     *
     * @param fileName file name, e.g. {@code "welcome-email.html"}
     * @return this builder
     */
    public EmailAuditReportBuilder withFileName(final String fileName) {
        this.fileName = Objects.requireNonNull(fileName, "fileName must not be null");
        return this;
    }

    /**
     * Sets the absolute filesystem path of the HTML file (used to display
     * file size and last-modified metadata in the header block).
     *
     * @param filePath absolute path to the source HTML file; may be {@code null}
     * @return this builder
     */
    public EmailAuditReportBuilder withFilePath(final Path filePath) {
        this.filePath = filePath;
        return this;
    }

    /**
     * Sets the aggregated rule results for this email.
     *
     * @param results non-null list of {@link RuleResult}; may be empty
     * @return this builder
     */
    public EmailAuditReportBuilder withResults(final List<RuleResult> results) {
        this.results = Objects.requireNonNull(results, "results must not be null");
        return this;
    }

    /**
     * Sets the primary full-page screenshot to embed in the evidence block.
     *
     * @param screenshotPath path to a PNG file; may be {@code null}
     * @return this builder
     */
    public EmailAuditReportBuilder withScreenshot(final Path screenshotPath) {
        this.screenshotPath = screenshotPath;
        return this;
    }

    /**
     * Adds a named evidence artifact (e.g. raw JSON, accessibility tree dump,
     * HTML snippet) rendered as a pre-formatted code block in the evidence section.
     *
     * @param label   short label displayed above the block
     * @param content content to render; truncated to 8 000 characters to
     *                prevent bloating the report file
     * @return this builder
     */
    public EmailAuditReportBuilder withAdditionalEvidence(
            final String label,
            final String content) {
        Objects.requireNonNull(label,   "label must not be null");
        Objects.requireNonNull(content, "content must not be null");
        this.additionalEvidence.add(new EvidenceEntry(label, content));
        return this;
    }

    // -------------------------------------------------------------------------
    // Terminal operation
    // -------------------------------------------------------------------------

    /**
     * Writes all sections to the bound {@link ExtentTest} node.
     * Must be called exactly once per builder instance.
     */
    public void build() {
        log.debug("Building report section for '{}'", fileName);

        writeHeaderBlock();
        writeEvidenceBlock();
        writeCategoryNodes();
        writeSummaryBlock();

        log.debug("Report section complete for '{}'", fileName);
    }

    // -------------------------------------------------------------------------
    // Section writers
    // -------------------------------------------------------------------------

    /**
     * Header block: file name, path, size, last-modified, overall status badge.
     */
    private void writeHeaderBlock() {
        final boolean anyFail  = results.stream().anyMatch(RuleResult::isFailed);
        final boolean anyError = results.stream().anyMatch(RuleResult::isError);

        final String statusBadge = buildStatusBadge(anyError, anyFail);
        final String fileInfo    = buildFileInfoTable();

        fileTest.info(
                "<div style='margin-bottom:8px'>"
                        + "<span style='font-size:15px;font-weight:bold'>"
                        + ReportManager.escapeHtml(fileName)
                        + "</span>&nbsp;&nbsp;" + statusBadge
                        + "</div>"
                        + fileInfo);
    }

    private String buildStatusBadge(final boolean anyError, final boolean anyFail) {
        final String colour;
        final String label;
        if (anyError)      { colour = "#e67e22"; label = "ERROR"; }
        else if (anyFail)  { colour = "#e74c3c"; label = "FAIL";  }
        else               { colour = "#27ae60"; label = "PASS";  }

        return String.format(
                "<span style='background:%s;color:#fff;padding:2px 8px;"
                        + "border-radius:3px;font-size:12px;font-weight:bold'>%s</span>",
                colour, label);
    }

    private String buildFileInfoTable() {
        final StringBuilder sb = new StringBuilder(
                "<table style='border-collapse:collapse;font-size:12px;color:#ccc'>");

        sb.append(infoRow("File name", ReportManager.escapeHtml(fileName)));
        sb.append(infoRow("Evaluated at", DISPLAY_FMT.format(Instant.now())));
        sb.append(infoRow("Total rules",  String.valueOf(results.size())));

        if (filePath != null && Files.exists(filePath)) {
            try {
                final long sizeKb       = Files.size(filePath) / 1_024;
                final String lastMod    = DISPLAY_FMT.format(
                        Files.getLastModifiedTime(filePath).toInstant());
                sb.append(infoRow("File size",     sizeKb + " KB"));
                sb.append(infoRow("Last modified", lastMod));
            } catch (final IOException e) {
                log.debug("Could not read file metadata for '{}': {}", filePath, e.getMessage());
            }
        }

        sb.append("</table>");
        return sb.toString();
    }

    /**
     * Evidence block: primary screenshot + any additional evidence artifacts.
     */
    private void writeEvidenceBlock() {
        if (screenshotPath == null && additionalEvidence.isEmpty()) return;

        fileTest.info("<b>📎 Evidence</b>");

        // Primary screenshot.
        if (screenshotPath != null && Files.exists(screenshotPath)) {
            fileTest.info(
                    "Full-page screenshot: <em>" + screenshotPath.getFileName() + "</em>",
                    MediaEntityBuilder
                            .createScreenCaptureFromPath(screenshotPath.toString())
                            .build());
        } else if (screenshotPath != null) {
            fileTest.warning("Screenshot file not found: "
                    + ReportManager.escapeHtml(screenshotPath.toString()));
        }

        // Additional evidence artifacts (code blocks).
        for (final EvidenceEntry entry : additionalEvidence) {
            fileTest.info(buildEvidenceCodeBlock(entry));
        }
    }

    /**
     * Category nodes: groups rule results by {@link AuditRule.RuleCategory},
     * each rendered as an ExtentTest child node containing per-rule child nodes.
     */
    private void writeCategoryNodes() {
        if (results.isEmpty()) {
            fileTest.info("No rules were evaluated for this file.");
            return;
        }

        // Group results by category, preserving encounter order.
        final Map<AuditRule.RuleCategory, List<RuleResult>> byCategory =
                groupByCategory(results);

        for (final Map.Entry<AuditRule.RuleCategory, List<RuleResult>> entry
                : byCategory.entrySet()) {

            final AuditRule.RuleCategory category = entry.getKey();
            final List<RuleResult>       catResults = entry.getValue();

            final boolean catFailed = catResults.stream().anyMatch(RuleResult::requiresAttention);
            final String  catLabel  = category.name()
                    + " (" + catResults.size() + " rule" + (catResults.size() == 1 ? "" : "s") + ")";

            final ExtentTest categoryNode = fileTest.createNode(catLabel);

            for (final RuleResult result : catResults) {
                writeRuleNode(categoryNode, result);
            }

            // Mark the category node with the worst status of its children.
            if (catResults.stream().anyMatch(RuleResult::isError)) {
                categoryNode.getModel().setStatus(Status.FAIL);
            } else if (catResults.stream().anyMatch(RuleResult::isFailed)) {
                categoryNode.getModel().setStatus(Status.FAIL);
            } else if (catResults.stream().anyMatch(RuleResult::isSkipped)) {
                categoryNode.getModel().setStatus(Status.SKIP);
            } else {
                categoryNode.getModel().setStatus(Status.PASS);
            }
        }
    }

    /**
     * Writes a single rule result as a child node under its category node.
     */
    private static void writeRuleNode(
            final ExtentTest categoryNode,
            final RuleResult result) {

        final String severityColour = SEVERITY_COLOURS.getOrDefault(
                result.getSeverity(), "#7f8c8d");

        final String nodeName = String.format(
                "<span style='font-family:monospace'>%s</span>"
                        + "&nbsp;<span style='color:%s;font-size:11px'>[%s]</span>",
                ReportManager.escapeHtml(result.getRuleId()),
                severityColour,
                result.getSeverity().name());

        final ExtentTest ruleNode = categoryNode.createNode(nodeName, result.getDescription());

        // Status line.
        final String durationLabel = "(" + result.getDurationMs() + "ms)";

        switch (result.getStatus()) {
            case PASS -> ruleNode.pass("All checks passed " + durationLabel);

            case FAIL -> {
                ruleNode.fail("Failed with <b>" + result.getFindings().size()
                        + "</b> finding(s) " + durationLabel);
                writeFindingRows(ruleNode, result.getFindings(), result.getSeverity());
            }

            case ERROR -> ruleNode.log(Status.FAIL, "⚠ Rule error " + durationLabel + ": "
                    + ReportManager.escapeHtml(nullSafe(result.getErrorMessage())));

            case SKIPPED -> ruleNode.skip(
                    "Skipped: " + ReportManager.escapeHtml(nullSafe(result.getErrorMessage())));
        }
    }

    /**
     * Writes individual finding rows under a failing rule node.
     * Each finding is rendered with a severity-coloured left border.
     */
    private static void writeFindingRows(
            final ExtentTest           ruleNode,
            final List<String>         findings,
            final AuditRule.RuleSeverity severity) {

        final String borderColour = SEVERITY_COLOURS.getOrDefault(severity, "#e74c3c");
        int index = 1;

        for (final String finding : findings) {
            final String row =
                    "<div style='border-left:4px solid " + borderColour + ";"
                            + "padding:4px 10px;margin:4px 0;font-size:12px;"
                            + "background:rgba(0,0,0,0.15);border-radius:2px'>"
                            + "<b>Finding " + index + ":</b> "
                            + ReportManager.escapeHtml(finding)
                            + "</div>";
            ruleNode.fail(row);
            index++;
        }
    }

    /**
     * Summary block: metrics table + severity breakdown table.
     */
    private void writeSummaryBlock() {
        fileTest.info("<b>📊 File Audit Summary</b>");
        fileTest.info(buildSummaryMetricsTable());
        fileTest.info(buildSeverityBreakdownTable());
    }

    private String buildSummaryMetricsTable() {
        final long pass    = results.stream().filter(RuleResult::isPassed).count();
        final long fail    = results.stream().filter(RuleResult::isFailed).count();
        final long error   = results.stream().filter(RuleResult::isError).count();
        final long skipped = results.stream().filter(RuleResult::isSkipped).count();
        final long totalMs = results.stream().mapToLong(RuleResult::getDurationMs).sum();
        final double pct   = results.isEmpty() ? 0.0 : (pass * 100.0 / results.size());

        return "<table style='border-collapse:collapse;font-size:12px;min-width:380px'>"
                + "<tr style='background:#2c3e50;color:#fff'>"
                + "<th style='padding:6px 12px'>Pass</th>"
                + "<th style='padding:6px 12px'>Fail</th>"
                + "<th style='padding:6px 12px'>Error</th>"
                + "<th style='padding:6px 12px'>Skipped</th>"
                + "<th style='padding:6px 12px'>Pass Rate</th>"
                + "<th style='padding:6px 12px'>Total Time</th></tr>"
                + "<tr style='text-align:center'>"
                + td("<span style='color:#27ae60'><b>" + pass    + "</b></span>")
                + td("<span style='color:#e74c3c'><b>" + fail    + "</b></span>")
                + td("<span style='color:#e67e22'><b>" + error   + "</b></span>")
                + td("<span style='color:#95a5a6'><b>" + skipped + "</b></span>")
                + td(String.format("<b>%.1f%%</b>", pct))
                + td(totalMs + "ms")
                + "</tr></table>";
    }

    private String buildSeverityBreakdownTable() {
        final Map<AuditRule.RuleSeverity, Long> failsBySeverity = new LinkedHashMap<>();

        for (final AuditRule.RuleSeverity sev : AuditRule.RuleSeverity.values()) {
            final long count = results.stream()
                    .filter(RuleResult::isFailed)
                    .filter(r -> r.getSeverity() == sev)
                    .count();
            if (count > 0) failsBySeverity.put(sev, count);
        }

        if (failsBySeverity.isEmpty()) return "";

        final StringBuilder sb = new StringBuilder(
                "<table style='border-collapse:collapse;font-size:12px;margin-top:6px'>"
                        + "<tr style='background:#2c3e50;color:#fff'>"
                        + "<th style='padding:5px 12px'>Severity</th>"
                        + "<th style='padding:5px 12px'>Failures</th></tr>");

        for (final Map.Entry<AuditRule.RuleSeverity, Long> entry : failsBySeverity.entrySet()) {
            final String colour = SEVERITY_COLOURS.getOrDefault(entry.getKey(), "#7f8c8d");
            sb.append("<tr><td style='padding:5px 12px'>"
                    + "<span style='color:" + colour + ";font-weight:bold'>"
                    + entry.getKey().name() + "</span></td>"
                    + "<td style='padding:5px 12px;text-align:center'>"
                    + entry.getValue() + "</td></tr>");
        }

        sb.append("</table>");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Internal – HTML helpers
    // -------------------------------------------------------------------------

    private static String buildEvidenceCodeBlock(final EvidenceEntry entry) {
        final String safeLabel   = ReportManager.escapeHtml(entry.label());
        final int    maxLen      = 8_000;
        final String rawContent  = entry.content().length() > maxLen
                ? entry.content().substring(0, maxLen) + "\n… [truncated]"
                : entry.content();
        final String safeContent = ReportManager.escapeHtml(rawContent);

        return "<div style='margin:6px 0'>"
                + "<b>" + safeLabel + "</b>"
                + "<pre style='background:#1e1e1e;color:#d4d4d4;padding:8px 12px;"
                + "border-radius:4px;font-size:11px;overflow-x:auto;"
                + "white-space:pre-wrap;word-break:break-all;max-height:300px;overflow-y:auto'>"
                + safeContent
                + "</pre></div>";
    }

    private static String infoRow(final String label, final String value) {
        return "<tr>"
                + "<td style='padding:2px 10px 2px 0;color:#aaa'>" + label + "</td>"
                + "<td style='padding:2px 0'>" + value + "</td>"
                + "</tr>";
    }

    private static String td(final String content) {
        return "<td style='padding:6px 12px;border-bottom:1px solid #333'>"
                + content + "</td>";
    }

    // -------------------------------------------------------------------------
    // Internal – grouping
    // -------------------------------------------------------------------------

    private static Map<AuditRule.RuleCategory, List<RuleResult>> groupByCategory(
            final List<RuleResult> results) {
        final Map<AuditRule.RuleCategory, List<RuleResult>> map = new LinkedHashMap<>();
        for (final RuleResult r : results) {
            map.computeIfAbsent(r.getCategory(), k -> new ArrayList<>()).add(r);
        }
        return map;
    }

    // -------------------------------------------------------------------------
    // Internal – helpers
    // -------------------------------------------------------------------------

    private static String nullSafe(final String value) {
        return value != null ? value : "";
    }

    // -------------------------------------------------------------------------
    // Internal records
    // -------------------------------------------------------------------------

    /**
     * An additional evidence artifact (label + content) to render as a code block.
     */
    private record EvidenceEntry(String label, String content) {}
}
