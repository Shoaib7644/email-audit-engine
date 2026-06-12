package com.acxiom.emailaudit.orchestration;

import com.acxiom.emailaudit.config.ConfigurationManager;
import com.acxiom.emailaudit.core.AuditContext;
import com.acxiom.emailaudit.core.AuditContext.AuditStatus;
import com.acxiom.emailaudit.evidence.ScreenshotService;
import com.acxiom.emailaudit.ingestion.ArchiveManager;
import com.acxiom.emailaudit.ingestion.DuplicateDetector;
import com.acxiom.emailaudit.ingestion.FileScanner;
import com.acxiom.emailaudit.rendering.HtmlRenderer;
import com.acxiom.emailaudit.reporting.ReportManager;
import com.acxiom.emailaudit.rules.*;
import com.acxiom.emailaudit.state.StateRegistry;
import com.acxiom.emailaudit.utilities.HashUtil;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class AuditOrchestrator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AuditOrchestrator.class);

    private static final String KEY_INPUT_DIR     = "ingestion.input.dir";
    private static final String DEFAULT_INPUT_DIR = "input";

    // -------------------------------------------------------------------------
    // Collaborators
    // -------------------------------------------------------------------------

    private final FileScanner       fileScanner;
    private final StateRegistry     stateRegistry;
    private final DuplicateDetector duplicateDetector;
    private final HtmlRenderer      htmlRenderer;
    private final RuleExecutor      ruleExecutor;
    private final ScreenshotService screenshotService;
    private final ReportManager     reportManager;
    private final ArchiveManager    archiveManager;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Creates an {@code AuditOrchestrator} wiring all collaborators from
     * {@link ConfigurationManager}, registering the default rule set.
     *
     * @throws OrchestrationException if any collaborator fails to initialise
     */
    public AuditOrchestrator() {
        this(resolveInputDir(), defaultRuleRegistry());
    }

    /**
     * Creates an {@code AuditOrchestrator} for a specific input directory using
     * the supplied rule registry. Primarily used in integration tests.
     *
     * @param inputDir     directory to scan for HTML files
     * @param ruleRegistry pre-populated registry of audit rules
     * @throws OrchestrationException if any collaborator fails to initialise
     */
    public AuditOrchestrator(final Path inputDir, final RuleRegistry ruleRegistry) {
        Objects.requireNonNull(inputDir,     "inputDir must not be null");
        Objects.requireNonNull(ruleRegistry, "ruleRegistry must not be null");

        log.info("Initialising AuditOrchestrator – input directory: '{}'", inputDir);

        final FileScanner       scanner;
        final StateRegistry     stateReg;
        final DuplicateDetector dupDetector;
        HtmlRenderer            renderer = null;
        final RuleExecutor      executor;
        final ScreenshotService screenshotSvc;
        final ReportManager     reportMgr;
        final ArchiveManager    archiveMgr;

        try {
            scanner       = new FileScanner(inputDir);
            stateReg      = new StateRegistry();
            dupDetector   = new DuplicateDetector();
            renderer      = new HtmlRenderer();
            executor      = new RuleExecutor(ruleRegistry);
            screenshotSvc = new ScreenshotService();
            reportMgr     = new ReportManager();
            archiveMgr    = new ArchiveManager();
        } catch (final Exception e) {
            // Release the Playwright browser if it was launched before a later
            // collaborator failed to initialise.
            if (renderer != null) {
                try {
                    renderer.close();
                } catch (final Exception closeEx) {
                    log.warn("Error closing HtmlRenderer during failed initialisation: {}",
                            closeEx.getMessage());
                }
            }
            throw new OrchestrationException(
                    "Failed to initialise AuditOrchestrator: " + e.getMessage(), e);
        }

        this.fileScanner       = scanner;
        this.stateRegistry     = stateReg;
        this.duplicateDetector = dupDetector;
        this.htmlRenderer      = renderer;
        this.ruleExecutor      = executor;
        this.screenshotService = screenshotSvc;
        this.reportManager     = reportMgr;
        this.archiveManager    = archiveMgr;

        log.info("AuditOrchestrator ready – {} rule(s) registered",
                ruleRegistry.getEnabledRules().size());
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Executes the full audit pipeline for every HTML file discovered under
     * the configured input directory, then flushes the consolidated report.
     *
     * @return summary of the run; never {@code null}
     */
    public RunSummary run() {
        final Instant runStart = Instant.now();
        log.info("=== Audit run starting ===");

        // Reset within-run duplicate tracking so a prior run on this same
        // orchestrator instance cannot cause false-positive duplicates here.
        duplicateDetector.reset();

        final List<Path> htmlFiles = fileScanner.scan();
        log.info("Discovered {} HTML file(s) under '{}'",
                htmlFiles.size(), fileScanner.getRootDirectory());

        int processed = 0;
        int skipped   = 0;
        int succeeded = 0;
        int failed    = 0;
        int errored   = 0;

        for (final Path file : htmlFiles) {
            final FileOutcome outcome = processFile(file);

            switch (outcome) {
                case SKIPPED  -> skipped++;
                case SUCCESS  -> { processed++; succeeded++; }
                case FAILED   -> { processed++; failed++; }
                case ERROR    -> { processed++; errored++; }
            }
        }

        final Path reportPath = reportManager.flush();

        final Duration runDuration = Duration.between(runStart, Instant.now());
        log.info("=== Audit run complete in {}ms – total: {}, processed: {}, skipped: {}, "
                        + "success: {}, failed: {}, error: {} – report: '{}' ===",
                runDuration.toMillis(), htmlFiles.size(), processed, skipped,
                succeeded, failed, errored, reportPath);

        return new RunSummary(
                htmlFiles.size(), processed, skipped, succeeded, failed, errored, reportPath);
    }

    /**
     * Releases the underlying Playwright browser and SLF4J resources held by
     * this orchestrator. Safe to call multiple times.
     */
    @Override
    public void close() {
        log.info("Shutting down AuditOrchestrator");
        htmlRenderer.close();
    }

    // -------------------------------------------------------------------------
    // Internal – per-file pipeline
    // -------------------------------------------------------------------------

    /**
     * Runs the full pipeline for a single file and returns its outcome.
     * All exceptions are caught internally — this method never throws.
     */
    private FileOutcome processFile(final Path file) {
        final String fileName = file.getFileName().toString();
        final Instant fileStart = Instant.now();

        log.info("--- Processing: {} ---", fileName);

        // ── Stage 2: State registry check (cross-run dedup) ──────────────────
        if (stateRegistry.isAlreadyProcessed(file)) {
            log.info("Skipping '{}' – already processed successfully with unchanged content", fileName);
            recordSkipped(file, fileStart, "Already processed (StateRegistry)");
            return FileOutcome.SKIPPED;
        }

        // ── Stage 3: Duplicate check (within-run dedup) ──────────────────────
        if (duplicateDetector.isDuplicate(file)) {
            log.info("Skipping '{}' – duplicate content detected in this run", fileName);
            recordSkipped(file, fileStart, "Duplicate content (DuplicateDetector)");
            return FileOutcome.SKIPPED;
        }

        Page page = null;

        try {
            // ── Stage 4: Render ───────────────────────────────────────────────
            page = htmlRenderer.render(file);

            // ── Stage 5: Execute rules ────────────────────────────────────────
            final List<RuleResult> ruleResults = ruleExecutor.execute(page);

            // ── Stage 6: Capture evidence ─────────────────────────────────────
            final Path screenshotPath = captureScreenshotSafely(page, fileName);

            // ── Stage 7: Build AuditContext ───────────────────────────────────
            final String fileHash = HashUtil.hashFileSafe(file);
            final AuditStatus status = AuditStatus.fromResults(ruleResults);

            final AuditContext context = AuditContext.builder(file)
                    .withFileHash(fileHash)
                    .withScreenshotPath(screenshotPath)
                    .withRuleResults(ruleResults)
                    .withStartTime(fileStart)
                    .withStatus(status)
                    .completedNow()
                    .build();

            return finalizeFile(context);

        } catch (final Exception e) {
            log.error("Unexpected error while processing '{}': {}", fileName, e.getMessage(), e);
            return finalizeError(file, fileStart, e);

        } finally {
            closePageQuietly(page);
        }
    }

    /**
     * Records report entry, archives the file, and updates state based on a
     * fully-built {@link AuditContext}.
     */
    private FileOutcome finalizeFile(final AuditContext context) {
        final String fileName = context.getFileName();
        final AuditStatus status = context.getStatus();

        // ── Stage 8: Report ───────────────────────────────────────────────────
        reportManager.recordFileResults(
                fileName, context.getRuleResults(), context.getScreenshotPath());

        // ── Stage 9 & 10: State update + Archive ──────────────────────────────
        // State must be updated BEFORE the file is archived/moved, otherwise
        // StateRegistry's hash computation reads from a path that no longer
        // exists (resulting in an "UNREADABLE" content hash). The hash already
        // computed for AuditContext is reused here to avoid a second disk read.
        switch (status) {
            case SUCCESS -> {
                stateRegistry.markSuccess(context.getHtmlFile(), context.getFileHash());
                archiveSafely(context.getHtmlFile(), true);
                log.info("'{}' completed: SUCCESS ({}ms, {} rule(s))",
                        fileName, context.getDuration().toMillis(), context.getRuleResults().size());
                return FileOutcome.SUCCESS;
            }
            case FAILED -> {
                stateRegistry.markFailed(context.getHtmlFile(), context.getFileHash(),
                        context.getAttentionCount() + " rule(s) reported findings");
                archiveSafely(context.getHtmlFile(), false);
                log.warn("'{}' completed: FAILED ({}ms, {} finding rule(s))",
                        fileName, context.getDuration().toMillis(), context.getAttentionCount());
                return FileOutcome.FAILED;
            }
            case ERROR -> {
                stateRegistry.markFailed(context.getHtmlFile(), context.getFileHash(),
                        "One or more rules threw an unexpected error");
                archiveSafely(context.getHtmlFile(), false);
                log.error("'{}' completed: ERROR ({}ms)",
                        fileName, context.getDuration().toMillis());
                return FileOutcome.ERROR;
            }
            default -> {
                // SKIPPED / IN_PROGRESS should not reach here via fromResults(),
                // but handle defensively.
                log.warn("'{}' completed with unexpected status: {}", fileName, status);
                return FileOutcome.ERROR;
            }
        }
    }

    /**
     * Handles an unexpected exception that escaped rule execution or rendering:
     * builds an ERROR context with no rule results, reports it, archives the
     * file as failed, and updates state.
     */
    private FileOutcome finalizeError(final Path file, final Instant fileStart, final Exception e) {
        final AuditContext context = AuditContext.builder(file)
                .withRuleResults(List.of())
                .withStartTime(fileStart)
                .withStatus(AuditStatus.ERROR)
                .completedNow()
                .build();

        reportManager.recordFileResults(context.getFileName(), context.getRuleResults(), null);

        // Compute the hash once, before archiving moves the file, and reuse it
        // for the state update (see Issues 1 & 2).
        final String fileHash = HashUtil.hashFileSafe(file);
        stateRegistry.markFailed(file, fileHash, "Pipeline error: "
                + e.getClass().getSimpleName() + ": " + e.getMessage());
        archiveSafely(file, false);

        return FileOutcome.ERROR;
    }

    /**
     * Records a skipped file in the report with an empty result set so the
     * dashboard reflects every discovered file, including skips.
     */
    private void recordSkipped(final Path file, final Instant fileStart, final String reason) {
        final AuditContext context = AuditContext.builder(file)
                .withRuleResults(List.of())
                .withStartTime(fileStart)
                .withStatus(AuditStatus.SKIPPED)
                .completedNow()
                .build();

        log.debug("Recording skipped file '{}': {}", context.getFileName(), reason);
        reportManager.recordFileResults(context.getFileName(), context.getRuleResults(), null);
    }

    // -------------------------------------------------------------------------
    // Internal – evidence / archive helpers (each fault-isolated)
    // -------------------------------------------------------------------------

    /**
     * Captures a screenshot, returning {@code null} (rather than throwing) if
     * capture fails — a screenshot failure must not abort rule reporting.
     */
    private Path captureScreenshotSafely(final Page page, final String fileName) {
        final String sourceName = stripExtension(fileName);
        try {
            return screenshotService.capture(page, sourceName);
        } catch (final ScreenshotService.ScreenshotException e) {
            log.warn("Screenshot capture failed for '{}': {}", fileName, e.getMessage());
            return null;
        }
    }

    /**
     * Archives a file, logging but not propagating failures — archival issues
     * must not prevent state updates or report flushing.
     */
    private void archiveSafely(final Path file, final boolean success) {
        try {
            if (success) {
                archiveManager.archiveSuccess(file);
            } else {
                archiveManager.archiveFailed(file);
            }
        } catch (final ArchiveManager.ArchiveException e) {
            log.error("Archiving failed for '{}': {}", file, e.getMessage(), e);
        }
    }

    /**
     * Closes the Playwright page (and its parent context) after a file's
     * evaluation completes, regardless of success or failure.
     */
    private void closePageQuietly(final Page page) {
        if (page == null) return;
        try {
            page.context().close();
        } catch (final Exception e) {
            log.debug("Error closing page/context: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Internal – static configuration helpers
    // -------------------------------------------------------------------------

    private static Path resolveInputDir() {
        final String configured = ConfigurationManager.getInstance()
                .getOrDefault(KEY_INPUT_DIR, DEFAULT_INPUT_DIR);
        return Paths.get(configured);
    }

    /**
     * Builds the default {@link RuleRegistry} containing the standard rule set
     * shipped with the audit engine.
     */
    private static RuleRegistry defaultRuleRegistry() {
        final RuleRegistry registry = new RuleRegistry();
        registry.register(new AccessibilityRule());
        registry.register(new AltTextValidationRule());
        registry.register(new ContentValidationRule());
        registry.register(new DuplicateIdRule());
        registry.register(new LinkValidationRule());
        return registry;
    }

    private static String stripExtension(final String fileName) {
        final int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    // -------------------------------------------------------------------------
    // Internal types
    // -------------------------------------------------------------------------

    /** Per-file terminal outcome used for run-level aggregation. */
    private enum FileOutcome {
        SKIPPED, SUCCESS, FAILED, ERROR
    }

    /**
     * Immutable summary of one orchestrator run, returned by {@link #run()}.
     *
     * @param totalDiscovered total HTML files found by {@link FileScanner}
     * @param processed       files that went through the full pipeline (not skipped)
     * @param skipped         files skipped due to state or duplicate checks
     * @param succeeded       files where every rule passed
     * @param failed          files where at least one rule reported a finding
     * @param errored         files where at least one rule (or the pipeline) threw
     * @param reportPath      absolute path to the generated HTML report
     */
    public record RunSummary(
            int totalDiscovered,
            int processed,
            int skipped,
            int succeeded,
            int failed,
            int errored,
            Path reportPath) {
    }

    // -------------------------------------------------------------------------
    // Exception
    // -------------------------------------------------------------------------

    /**
     * Unchecked exception thrown when the orchestrator cannot be initialised.
     */
    public static final class OrchestrationException extends RuntimeException {

        public OrchestrationException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}