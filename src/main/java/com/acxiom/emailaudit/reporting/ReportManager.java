package com.acxiom.emailaudit.reporting;

import com.acxiom.emailaudit.rules.RuleResult;
import com.acxiom.emailaudit.config.ConfigurationManager;
import com.acxiom.emailaudit.rules.AuditRule;
import com.aventstack.extentreports.ExtentReports;
import com.aventstack.extentreports.ExtentTest;
import com.aventstack.extentreports.MediaEntityBuilder;
import com.aventstack.extentreports.Status;
import com.aventstack.extentreports.reporter.ExtentSparkReporter;
import com.aventstack.extentreports.reporter.configuration.Theme;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Central reporting facade that builds and flushes a single
 * ExtentReports Spark HTML dashboard for the entire audit run.
 *
 * <h2>Architecture contract</h2>
 * <p>Per {@code Architecture.md}: rules never interact with reporting.
 * {@code ReportManager} only receives aggregated {@link RuleResult} lists
 * from the orchestration layer — it never calls rule logic itself.</p>
 *
 * <h2>Dashboard layout</h2>
 * <pre>
 *  Dashboard
 *   └─ Test: &lt;filename&gt;                     (one per HTML file)
 *       ├─ Node: CATEGORY / RULE_ID          (one per rule result)
 *       │    ├─ PASS / FAIL / ERROR / SKIPPED status
 *       │    ├─ Finding messages             (FAIL only)
 *       │    └─ Screenshot                   (when path supplied)
 *       └─ Summary attributes               (metrics written to test)
 * </pre>
 *
 * <h2>Thread safety</h2>
 * <ul>
 *   <li>{@link ExtentReports} is <em>not</em> thread-safe for test creation.
 *       A {@link ReentrantLock} serialises all calls to
 *       {@link ExtentReports#createTest}.</li>
 *   <li>{@link ExtentTest} child nodes are created on the same test object
 *       from a single thread (the file-processing thread), so no additional
 *       locking is needed per test.</li>
 *   <li>{@link #flush()} is guarded by the same lock; calling it while
 *       another thread is still writing tests is safe.</li>
 *   <li>Summary counters use {@link AtomicInteger} for lock-free increments
 *       from parallel file-processing threads.</li>
 * </ul>
 *
 * <h2>Configuration keys</h2>
 * <table>
 *   <tr><td>{@code report.output.dir}</td>
 *       <td>Directory for the HTML report (default: {@code target/audit-reports})</td></tr>
 *   <tr><td>{@code report.title}</td>
 *       <td>Dashboard browser-tab title (default: {@code Email Audit Report})</td></tr>
 *   <tr><td>{@code report.name}</td>
 *       <td>Heading shown inside the dashboard (default: {@code Audit Run})</td></tr>
 * </table>
 */
public final class ReportManager {

    private static final Logger log = LoggerFactory.getLogger(ReportManager.class);

    // -------------------------------------------------------------------------
    // Configuration keys & defaults
    // -------------------------------------------------------------------------

    private static final String KEY_REPORT_DIR   = "report.output.dir";
    private static final String KEY_REPORT_TITLE = "report.title";
    private static final String KEY_REPORT_NAME  = "report.name";

    private static final String DEFAULT_REPORT_DIR   = "audit-results/reports";
    private static final String DEFAULT_REPORT_TITLE = "Email Audit Report";
    private static final String DEFAULT_REPORT_NAME  = "Audit Run";

    private static final String REPORT_FILENAME = "audit-report.html";

    private static final DateTimeFormatter RUN_TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
                    .withZone(ZoneOffset.UTC);

    // -------------------------------------------------------------------------
    // ExtentReports infrastructure
    // -------------------------------------------------------------------------

    private final ExtentReports extent;
    private final Path           reportPath;

    /** Serialises all ExtentReports mutations. */
    private final ReentrantLock  extentLock = new ReentrantLock();

    // -------------------------------------------------------------------------
    // Run-level summary counters (thread-safe)
    // -------------------------------------------------------------------------

    private final AtomicInteger totalFiles    = new AtomicInteger();
    private final AtomicInteger totalRules    = new AtomicInteger();
    private final AtomicInteger totalPass     = new AtomicInteger();
    private final AtomicInteger totalFail     = new AtomicInteger();
    private final AtomicInteger totalError    = new AtomicInteger();
    private final AtomicInteger totalSkipped  = new AtomicInteger();

    /** Maps ruleId → fail count for the per-rule summary section. */
    private final ConcurrentHashMap<String, AtomicInteger> ruleFailCounts =
            new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Creates a {@code ReportManager} using settings from
     * {@link ConfigurationManager}.
     *
     * @throws ReportException if the report directory cannot be created
     */
    public ReportManager() {
        this(
                ConfigurationManager.getInstance().getOrDefault(KEY_REPORT_DIR,   DEFAULT_REPORT_DIR),
                ConfigurationManager.getInstance().getOrDefault(KEY_REPORT_TITLE, DEFAULT_REPORT_TITLE),
                ConfigurationManager.getInstance().getOrDefault(KEY_REPORT_NAME,  DEFAULT_REPORT_NAME)
        );
    }

    /**
     * Creates a {@code ReportManager} with explicit parameters.
     * Primarily used in tests.
     *
     * @param reportDir   directory where {@code audit-report.html} will be written
     * @param reportTitle browser-tab title
     * @param reportName  dashboard heading
     */
    public ReportManager(
            final String reportDir,
            final String reportTitle,
            final String reportName) {

        Objects.requireNonNull(reportDir,   "reportDir must not be null");
        Objects.requireNonNull(reportTitle, "reportTitle must not be null");
        Objects.requireNonNull(reportName,  "reportName must not be null");

        this.reportPath = Paths.get(reportDir)
                .normalize()
                .toAbsolutePath()
                .resolve(REPORT_FILENAME);

        ensureDirectory(reportPath.getParent());

        this.extent = buildExtentReports(reportPath, reportTitle, reportName);

        log.info("ReportManager initialised – report will be written to '{}'", reportPath);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Records the audit results for a single HTML file as a top-level test in
     * the Spark dashboard.
     *
     * <p>One child node is created per {@link RuleResult}. Failing and erroring
     * nodes include all finding messages. If {@code screenshotPath} is non-null
     * and the file exists, it is embedded in the test node.</p>
     *
     * @param fileName       display name for the test (typically the HTML file name)
     * @param results        aggregated rule results for this file; must not be null
     * @param screenshotPath optional path to a PNG screenshot; may be {@code null}
     */
    public void recordFileResults(
            final String       fileName,
            final List<RuleResult> results,
            final Path         screenshotPath) {

        Objects.requireNonNull(fileName, "fileName must not be null");
        Objects.requireNonNull(results,  "results must not be null");

        totalFiles.incrementAndGet();

        final ExtentTest fileTest = createTest(fileName);

        // Attach screenshot to the top-level test if one was captured.
        if (screenshotPath != null && Files.exists(screenshotPath)) {
            attachScreenshot(fileTest, screenshotPath, "Page Screenshot");
        }

        // Add system information attributes for this file.
        fileTest.assignCategory(deriveOverallCategory(results));
        fileTest.info("File: <b>" + fileName + "</b>");
        fileTest.info("Rules evaluated: <b>" + results.size() + "</b>");
        fileTest.info("Evaluated at: <b>" + RUN_TIMESTAMP_FMT.format(Instant.now()) + "</b>");

        // Write one child node per rule result.
        for (final RuleResult result : results) {
            recordRuleResult(fileTest, result);
            accumulateCounters(result);
        }

        // Append a per-file summary table at the bottom of the test.
        appendFileSummaryTable(fileTest, results);

        log.info("Recorded {} result(s) for file '{}'", results.size(), fileName);
    }

    /**
     * Convenience overload for callers that have no screenshot.
     *
     * @param fileName display name for the test
     * @param results  aggregated rule results for this file
     */
    public void recordFileResults(
            final String fileName,
            final List<RuleResult> results) {
        recordFileResults(fileName, results, null);
    }

    /**
     * Flushes all buffered test data to the HTML report file and appends a
     * run-level summary section.
     *
     * <p>Must be called exactly once after all files have been processed.
     * Subsequent calls are safe (ExtentReports re-writes the same file).</p>
     *
     * @return absolute path of the written HTML report file
     */
    public Path flush() {
        extentLock.lock();
        try {
            appendRunSummaryTest();
            extent.flush();
            log.info("Report flushed to: '{}'", reportPath);
            return reportPath;
        } finally {
            extentLock.unlock();
        }
    }

    /**
     * Returns the absolute path the report will be written to.
     *
     * @return report file path; never {@code null}
     */
    public Path getReportPath() {
        return reportPath;
    }

    // -------------------------------------------------------------------------
    // Internal – test creation (locked)
    // -------------------------------------------------------------------------

    private ExtentTest createTest(final String name) {
        extentLock.lock();
        try {
            return extent.createTest(name);
        } finally {
            extentLock.unlock();
        }
    }

    // -------------------------------------------------------------------------
    // Internal – rule result recording
    // -------------------------------------------------------------------------

    /**
     * Creates a child node under {@code fileTest} for a single rule result and
     * populates it with status, findings, and optional screenshot.
     */
    private static void recordRuleResult(
            final ExtentTest    fileTest,
            final RuleResult    result) {

        final String nodeName = String.format("[%s] %s",
                result.getCategory(), result.getRuleId());

        final ExtentTest node = fileTest.createNode(nodeName, result.getDescription());

        // Assign severity as a tag so it appears in dashboard filters.
        node.assignCategory(result.getSeverity().name());

        switch (result.getStatus()) {
            case PASS    -> node.pass("All checks passed ("
                    + result.getDurationMs() + "ms)");

            case FAIL    -> {
                node.fail("Rule failed with " + result.getFindings().size()
                        + " finding(s) (" + result.getDurationMs() + "ms)");
                result.getFindings().forEach(finding ->
                        node.fail("<b>Finding:</b> " + escapeHtml(finding)));
            }

            case ERROR   -> node.log(Status.FAIL, "⚠ Rule threw an error ("
                    + result.getDurationMs() + "ms): "
                    + escapeHtml(nullSafe(result.getErrorMessage())));

            case SKIPPED -> node.skip("Skipped: "
                    + escapeHtml(nullSafe(result.getErrorMessage())));
        }
    }

    // -------------------------------------------------------------------------
    // Internal – screenshot attachment
    // -------------------------------------------------------------------------

    private void attachScreenshot(
            final ExtentTest fileTest,
            final Path screenshotPath,
            final String title) {

        try {

            final String screenshotUri =
                    screenshotPath.toAbsolutePath()
                            .normalize()
                            .toUri()
                            .toString();

            fileTest.info(
                    title,
                    MediaEntityBuilder
                            .createScreenCaptureFromPath(screenshotUri)
                            .build());

            log.debug("Screenshot attached: '{}'", screenshotUri);

        } catch (Exception e) {

            log.warn(
                    "Failed to attach screenshot '{}': {}",
                    screenshotPath,
                    e.getMessage());

            fileTest.warning(
                    "Screenshot available at: "
                            + screenshotPath.toAbsolutePath());
        }
    }

    // -------------------------------------------------------------------------
    // Internal – per-file summary table
    // -------------------------------------------------------------------------

    /**
     * Appends an HTML summary table to {@code fileTest} showing pass/fail/error
     * counts and total evaluation time for the file.
     */
    private static void appendFileSummaryTable(
            final ExtentTest    fileTest,
            final List<RuleResult> results) {

        final long passed  = results.stream().filter(RuleResult::isPassed).count();
        final long failed  = results.stream().filter(RuleResult::isFailed).count();
        final long errored = results.stream().filter(RuleResult::isError).count();
        final long skipped = results.stream().filter(RuleResult::isSkipped).count();
        final long totalMs = results.stream().mapToLong(RuleResult::getDurationMs).sum();

        final String table = "<table style='border-collapse:collapse;font-size:13px'>"
                + "<tr style='background:#4a4a4a;color:#fff'>"
                + "<th style='padding:6px 12px'>Pass</th>"
                + "<th style='padding:6px 12px'>Fail</th>"
                + "<th style='padding:6px 12px'>Error</th>"
                + "<th style='padding:6px 12px'>Skipped</th>"
                + "<th style='padding:6px 12px'>Duration</th></tr>"
                + "<tr style='text-align:center'>"
                + "<td style='padding:6px 12px;color:#2ecc71'><b>" + passed  + "</b></td>"
                + "<td style='padding:6px 12px;color:#e74c3c'><b>" + failed  + "</b></td>"
                + "<td style='padding:6px 12px;color:#e67e22'><b>" + errored + "</b></td>"
                + "<td style='padding:6px 12px;color:#95a5a6'><b>" + skipped + "</b></td>"
                + "<td style='padding:6px 12px'>"               + totalMs + "ms</td>"
                + "</tr></table>";

        if (failed > 0 || errored > 0) {
            fileTest.fail(table);
        } else {
            fileTest.pass(table);
        }
    }

    // -------------------------------------------------------------------------
    // Internal – run-level summary test
    // -------------------------------------------------------------------------

    /**
     * Creates a dedicated "Run Summary" test at the top of the dashboard with
     * aggregate counters and a per-rule failure leaderboard.
     */
    private void appendRunSummaryTest() {
        final ExtentTest summary = extent.createTest(
                "📊 Run Summary – " + RUN_TIMESTAMP_FMT.format(Instant.now()));

        summary.assignCategory("SUMMARY");

        final int files   = totalFiles.get();
        final int rules   = totalRules.get();
        final int pass    = totalPass.get();
        final int fail    = totalFail.get();
        final int error   = totalError.get();
        final int skipped = totalSkipped.get();
        final int total   = pass + fail + error + skipped;
        final double passRate = total > 0 ? (pass * 100.0 / total) : 0.0;

        summary.info(buildRunMetricsTable(files, rules, pass, fail, error, skipped, passRate));

        if (!ruleFailCounts.isEmpty()) {
            summary.info("<b>Rule Failure Leaderboard</b>");
            summary.info(buildRuleLeaderboardTable());
        }

        if (fail == 0 && error == 0) {
            summary.pass("All rules passed across all files.");
        } else {
            summary.fail(String.format(
                    "%d failure(s) and %d error(s) detected across %d file(s).",
                    fail, error, files));
        }
    }

    private static String buildRunMetricsTable(
            final int files, final int rules,
            final int pass, final int fail,
            final int error, final int skipped,
            final double passRate) {

        return "<table style='border-collapse:collapse;font-size:13px;min-width:420px'>"
                + "<tr style='background:#34495e;color:#fff'>"
                + "<th style='padding:7px 14px'>Metric</th>"
                + "<th style='padding:7px 14px'>Value</th></tr>"
                + metricRow("Files Audited",          String.valueOf(files))
                + metricRow("Total Rule Evaluations", String.valueOf(rules))
                + metricRow("Passed",  "<span style='color:#2ecc71'><b>" + pass    + "</b></span>")
                + metricRow("Failed",  "<span style='color:#e74c3c'><b>" + fail    + "</b></span>")
                + metricRow("Errored", "<span style='color:#e67e22'><b>" + error   + "</b></span>")
                + metricRow("Skipped", "<span style='color:#95a5a6'><b>" + skipped + "</b></span>")
                + metricRow("Pass Rate", String.format("<b>%.1f%%</b>", passRate))
                + "</table>";
    }

    private String buildRuleLeaderboardTable() {
        final StringBuilder sb = new StringBuilder(
                "<table style='border-collapse:collapse;font-size:13px;min-width:320px'>"
                        + "<tr style='background:#34495e;color:#fff'>"
                        + "<th style='padding:7px 14px'>Rule ID</th>"
                        + "<th style='padding:7px 14px'>Failures</th></tr>");

        ruleFailCounts.entrySet().stream()
                .sorted(Map.Entry.<String, AtomicInteger>comparingByValue(
                        (a, b) -> Integer.compare(b.get(), a.get())))
                .limit(20)
                .forEach(e -> sb.append(metricRow(e.getKey(), String.valueOf(e.getValue().get()))));

        sb.append("</table>");
        return sb.toString();
    }

    private static String metricRow(final String label, final String value) {
        return "<tr>"
                + "<td style='padding:6px 14px;border-bottom:1px solid #ddd'>" + label + "</td>"
                + "<td style='padding:6px 14px;border-bottom:1px solid #ddd;text-align:center'>"
                + value + "</td>"
                + "</tr>";
    }

    // -------------------------------------------------------------------------
    // Internal – counters
    // -------------------------------------------------------------------------

    private void accumulateCounters(final RuleResult result) {
        totalRules.incrementAndGet();
        switch (result.getStatus()) {
            case PASS    -> totalPass.incrementAndGet();
            case FAIL    -> {
                totalFail.incrementAndGet();
                ruleFailCounts
                        .computeIfAbsent(result.getRuleId(), k -> new AtomicInteger())
                        .incrementAndGet();
            }
            case ERROR   -> totalError.incrementAndGet();
            case SKIPPED -> totalSkipped.incrementAndGet();
        }
    }

    // -------------------------------------------------------------------------
    // Internal – derive overall test status category label
    // -------------------------------------------------------------------------

    private static String deriveOverallCategory(final List<RuleResult> results) {
        final boolean anyFail  = results.stream().anyMatch(RuleResult::isFailed);
        final boolean anyError = results.stream().anyMatch(RuleResult::isError);
        if (anyError) return "ERROR";
        if (anyFail)  return "FAIL";
        return "PASS";
    }

    // -------------------------------------------------------------------------
    // Internal – ExtentReports construction
    // -------------------------------------------------------------------------

    private static ExtentReports buildExtentReports(
            final Path   reportPath,
            final String reportTitle,
            final String reportName) {

        final ExtentSparkReporter spark =
                new ExtentSparkReporter(reportPath.toFile());

        spark.config().setTheme(Theme.DARK);
        spark.config().setDocumentTitle(reportTitle);
        spark.config().setReportName(reportName);
        spark.config().setEncoding("UTF-8");
        spark.config().setTimeStampFormat("yyyy-MM-dd HH:mm:ss");
        spark.config().setCss(
                ".badge-primary { background-color: #5d6d7e; } "
                        + "body { font-family: 'Segoe UI', Arial, sans-serif; }");

        final ExtentReports extent = new ExtentReports();
        extent.attachReporter(spark);
        extent.setSystemInfo("Framework",    "Email Audit Engine");
        extent.setSystemInfo("Java Version", System.getProperty("java.version"));
        extent.setSystemInfo("OS",           System.getProperty("os.name"));
        extent.setSystemInfo("Run Started",  RUN_TIMESTAMP_FMT.format(Instant.now()));

        return extent;
    }

    // -------------------------------------------------------------------------
    // Internal – helpers
    // -------------------------------------------------------------------------

    private static void ensureDirectory(final Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (final IOException e) {
            throw new ReportException(
                    "Cannot create report directory '" + dir + "': " + e.getMessage(), e);
        }
    }

    /**
     * Escapes the minimum HTML characters to prevent finding text from being
     * interpreted as markup inside ExtentReports HTML cells.
     */
    static String escapeHtml(final String input) {
        if (input == null) return "";
        return input
                .replace("&",  "&amp;")
                .replace("<",  "&lt;")
                .replace(">",  "&gt;")
                .replace("\"", "&quot;")
                .replace("'",  "&#x27;");
    }

    private static String nullSafe(final String value) {
        return value != null ? value : "";
    }

    // -------------------------------------------------------------------------
    // Exception
    // -------------------------------------------------------------------------

    /**
     * Unchecked exception thrown when the report cannot be initialised or flushed.
     */
    public static final class ReportException extends RuntimeException {

        public ReportException(final String message) {
            super(message);
        }

        public ReportException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
