package com.acxiom.emailaudit.orchestration;

import com.acxiom.emailaudit.config.ConfigurationManager;
import com.acxiom.emailaudit.core.AuditContext;
import com.acxiom.emailaudit.core.AuditContext.AuditStatus;
import com.acxiom.emailaudit.evidence.ScreenshotService;
import com.acxiom.emailaudit.ingestion.ArchiveManager;
import com.acxiom.emailaudit.ingestion.DuplicateDetector;
import com.acxiom.emailaudit.ingestion.FileScanner;
import com.acxiom.emailaudit.output.ExecutionOutputManager;
import com.acxiom.emailaudit.rendering.HtmlRenderer;
import com.acxiom.emailaudit.rules.*;
import com.acxiom.emailaudit.state.StateRegistry;
import com.acxiom.emailaudit.utilities.HashUtil;
import com.acxiom.emailaudit.utilities.PerformanceMetrics;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.acxiom.emailaudit.reporting.CustomDashboardGenerator;
import com.acxiom.emailaudit.reporting.dashboard.DashboardDataCollector;
import com.acxiom.emailaudit.reporting.dashboard.RunAuditData;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class AuditOrchestrator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AuditOrchestrator.class);

    private static final String KEY_INPUT_DIR   = "ingestion.input.dir";
    private static final String DEFAULT_INPUT_DIR = "input";

    private final FileScanner       fileScanner;
    private final StateRegistry     stateRegistry;
    private final DuplicateDetector duplicateDetector;
    private final HtmlRenderer      htmlRenderer;
    private final RuleExecutor      ruleExecutor;
    private final ScreenshotService screenshotService;
    private final ArchiveManager    archiveManager;

    public AuditOrchestrator() {
        this(resolveInputDir(), defaultRuleRegistry());
    }

    public AuditOrchestrator(final Path inputDir, final RuleRegistry ruleRegistry) {
        Objects.requireNonNull(inputDir,      "inputDir must not be null");
        Objects.requireNonNull(ruleRegistry,  "ruleRegistry must not be null");
        ExecutionOutputManager.ensureCurrentExecution();

        log.info("Initialising AuditOrchestrator – input directory: '{}'", inputDir);

        final FileScanner       scanner;
        final StateRegistry     stateReg;
        final DuplicateDetector dupDetector;
        HtmlRenderer            renderer = null;
        final RuleExecutor      executor;
        final ScreenshotService screenshotSvc;
        final ArchiveManager    archiveMgr;

        try {
            scanner       = new FileScanner(inputDir);
            stateReg      = new StateRegistry();
            dupDetector   = new DuplicateDetector();
            renderer      = new HtmlRenderer();
            executor      = new RuleExecutor(ruleRegistry);
            screenshotSvc = new ScreenshotService();
            archiveMgr    = new ArchiveManager();
        } catch (final Exception e) {
            if (renderer != null) {
                try { renderer.close(); }
                catch (final Exception closeEx) {
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
        this.archiveManager    = archiveMgr;

        log.info("AuditOrchestrator ready – {} rule(s) registered",
                ruleRegistry.getEnabledRules().size());
    }

    public RunSummary run() {
        final Instant runStart = Instant.now();
        PerformanceMetrics.reset();
        log.info("=== Audit run starting ===");

        duplicateDetector.reset();

        final List<Path>         htmlFiles    = fileScanner.scan();
        final List<AuditContext> auditResults = new ArrayList<>();

        log.info("Discovered {} HTML file(s) under '{}'",
                htmlFiles.size(), fileScanner.getRootDirectory());

        int processed = 0;
        int skipped   = 0;
        int succeeded = 0;
        int failed    = 0;
        int errored   = 0;

        for (final Path file : htmlFiles) {
            final FileProcessingResult result = processFile(file);
            auditResults.add(result.context());

            switch (result.outcome()) {
                case SKIPPED -> skipped++;
                case SUCCESS -> { processed++; succeeded++; }
                case FAILED  -> { processed++; failed++;    }
                case ERROR   -> { processed++; errored++;   }
            }
        }

        // ── Compute duration HERE so it is available for both the dashboard
        //    data and the log line below. Previously it was computed after
        //    DashboardDataCollector.collect(), so it never reached the JSON.
        final long runDurationMs =
                Duration.between(runStart, Instant.now()).toMillis();

        Path dashboardPath = null;

        RunSummary runSummary = new RunSummary(
                htmlFiles.size(),
                processed,
                skipped,
                succeeded,
                failed,
                errored,
                null,
                auditResults,
                runDurationMs);   // ← now carried into RunSummary

        try {
            final RunAuditData dashboardData =
                    DashboardDataCollector.collect(runSummary);  // picks up executionTimeMs

            final CustomDashboardGenerator dashboardGenerator =
                    new CustomDashboardGenerator();

            dashboardPath = dashboardGenerator.generate(dashboardData);

            runSummary = new RunSummary(
                    runSummary.totalDiscovered(),
                    runSummary.processed(),
                    runSummary.skipped(),
                    runSummary.succeeded(),
                    runSummary.failed(),
                    runSummary.errored(),
                    dashboardPath,
                    runSummary.auditResults(),
                    runSummary.executionTimeMs());   // ← preserve when rebuilding

            log.info("Custom dashboard generated successfully: {}",
                    dashboardPath.toAbsolutePath());

        } catch (final Exception ex) {
            log.error("Failed to generate custom dashboard", ex);
        }

        log.info(
                "=== Audit run complete in {}ms – total: {}, processed: {}, skipped: {}, success: {}, failed: {}, error: {} – dashboard: '{}' ===",
                runDurationMs,
                htmlFiles.size(),
                processed,
                skipped,
                succeeded,
                failed,
                errored,
                dashboardPath);
        PerformanceMetrics.logSummary(runDurationMs);

        return runSummary;
    }

    @Override
    public void close() {
        log.info("Shutting down AuditOrchestrator");
        htmlRenderer.close();
    }

    private FileProcessingResult processFile(final Path file) {
        final String  fileName  = file.getFileName().toString();
        final Instant fileStart = Instant.now();

        log.info("--- Processing: {} ---", fileName);

        if (stateRegistry.isAlreadyProcessed(file)) {
            log.info("Skipping '{}' – already processed successfully with unchanged content", fileName);
            return new FileProcessingResult(
                    recordSkipped(file, fileStart, "Already processed (StateRegistry)"),
                    FileOutcome.SKIPPED);
        }

        if (duplicateDetector.isDuplicate(file)) {
            log.info("Skipping '{}' – duplicate content detected in this run", fileName);
            return new FileProcessingResult(
                    recordSkipped(file, fileStart, "Duplicate content (DuplicateDetector)"),
                    FileOutcome.SKIPPED);
        }

        Page page = null;
        try {
            page = htmlRenderer.render(file);

            final List<RuleResult> ruleResults    = ruleExecutor.execute(page);
            final Path             screenshotPath = captureScreenshotSafely(page, fileName);
            final String           fileHash       = HashUtil.hashFileSafe(file);
            final AuditStatus      status         = AuditStatus.fromResults(ruleResults);

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

    private FileProcessingResult finalizeFile(final AuditContext context) {
        final String      fileName = context.getFileName();
        final AuditStatus status   = context.getStatus();

        switch (status) {
            case SUCCESS -> {
                stateRegistry.markSuccess(context.getHtmlFile(), context.getFileHash());
                archiveSafely(context.getHtmlFile(), true);
                log.info("'{}' completed: SUCCESS ({}ms, {} rule(s))",
                        fileName, context.getDuration().toMillis(), context.getRuleResults().size());
                return new FileProcessingResult(context, FileOutcome.SUCCESS);
            }
            case FAILED -> {
                stateRegistry.markFailed(context.getHtmlFile(), context.getFileHash(),
                        context.getAttentionCount() + " rule(s) reported findings");
                archiveSafely(context.getHtmlFile(), false);
                log.warn("'{}' completed: FAILED ({}ms, {} finding rule(s))",
                        fileName, context.getDuration().toMillis(), context.getAttentionCount());
                return new FileProcessingResult(context, FileOutcome.FAILED);
            }
            case ERROR -> {
                stateRegistry.markFailed(context.getHtmlFile(), context.getFileHash(),
                        "One or more rules threw an unexpected error");
                archiveSafely(context.getHtmlFile(), false);
                log.error("'{}' completed: ERROR ({}ms)", fileName, context.getDuration().toMillis());
                return new FileProcessingResult(context, FileOutcome.ERROR);
            }
            default -> {
                log.warn("'{}' completed with unexpected status: {}", fileName, status);
                return new FileProcessingResult(context, FileOutcome.ERROR);
            }
        }
    }

    private FileProcessingResult finalizeError(
            final Path file, final Instant fileStart, final Exception e) {

        final AuditContext context = AuditContext.builder(file)
                .withRuleResults(List.of())
                .withStartTime(fileStart)
                .withStatus(AuditStatus.ERROR)
                .completedNow()
                .build();

        final String fileHash = HashUtil.hashFileSafe(file);
        stateRegistry.markFailed(file, fileHash,
                "Pipeline error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        archiveSafely(file, false);

        return new FileProcessingResult(context, FileOutcome.ERROR);
    }

    private AuditContext recordSkipped(
            final Path file, final Instant fileStart, final String reason) {

        final AuditContext context = AuditContext.builder(file)
                .withRuleResults(List.of())
                .withStartTime(fileStart)
                .withStatus(AuditStatus.SKIPPED)
                .completedNow()
                .build();

        log.debug("Recording skipped file '{}': {}", context.getFileName(), reason);
        return context;
    }

    private Path captureScreenshotSafely(final Page page, final String fileName) {
        try {
            return screenshotService.capture(page, stripExtension(fileName));
        } catch (final ScreenshotService.ScreenshotException e) {
            log.warn("Screenshot capture failed for '{}': {}", fileName, e.getMessage());
            return null;
        }
    }

    private void archiveSafely(final Path file, final boolean success) {
        try {
            if (success) { archiveManager.archiveSuccess(file); }
            else         { archiveManager.archiveFailed(file);  }
        } catch (final ArchiveManager.ArchiveException e) {
            log.error("Archiving failed for '{}': {}", file, e.getMessage(), e);
        }
    }

    private void closePageQuietly(final Page page) {
        if (page == null) return;
        try { page.context().close(); }
        catch (final Exception e) { log.debug("Error closing page/context: {}", e.getMessage()); }
    }

    private static Path resolveInputDir() {
        return Paths.get(ConfigurationManager.getInstance()
                .getOrDefault(KEY_INPUT_DIR, DEFAULT_INPUT_DIR));
    }

    public static RuleRegistry defaultRuleRegistry() {
        final RuleRegistry registry = new RuleRegistry();
        registry.register(new LinkValidationRule());
        registry.register(new ImageValidationRule());
        registry.register(new AccessibilityRule());
        registry.register(new BrokenAnchorRule());
        registry.register(new ImageSourceValidationRule());
        registry.register(new AltTextValidationRule());
        registry.register(new CtaValidationRule());
        registry.register(new ContentValidationRule());
        registry.register(new DuplicateIdRule());
        registry.register(new LinkTextValidationRule());
        registry.register(new HeaderEmojiEncodingRule());
        registry.register(new HeadingHierarchyRule());
        registry.register(new PreheaderPunctuationRule());
        registry.register(new PreheaderTrimRule());
        registry.register(new PrivacyLinkRule());
        registry.register(new ViewOnlineLinkRule());
        registry.register(new DisclaimerRule());
        registry.register(new HeaderEmojiEncodingRule());
        registry.register(new CampaignValidationRule());
        return registry;
    }

    private static String stripExtension(final String fileName) {
        final int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    public enum FileOutcome { SKIPPED, SUCCESS, FAILED, ERROR }

    public record RunSummary(
            int totalDiscovered,
            int processed,
            int skipped,
            int succeeded,
            int failed,
            int errored,
            Path dashboardPath,
            List<AuditContext> auditResults,
            long executionTimeMs) {   // ← added
    }

    public static final class OrchestrationException extends RuntimeException {
        public OrchestrationException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
