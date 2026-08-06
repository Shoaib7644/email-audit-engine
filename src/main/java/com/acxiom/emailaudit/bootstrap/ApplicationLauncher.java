package com.acxiom.emailaudit.bootstrap;

import com.acxiom.emailaudit.core.ExecutionContext;
import com.acxiom.emailaudit.core.ValidationMode;
import com.acxiom.emailaudit.orchestration.AuditOrchestrator;
import com.acxiom.emailaudit.output.ExecutionOutputManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Application entry point for the email audit engine.
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Construct the {@link AuditOrchestrator} and all of its collaborators.</li>
 *   <li>Execute a single end-to-end audit run via {@link AuditOrchestrator#run()}.</li>
 *   <li>Log the resulting {@link AuditOrchestrator.RunSummary} in full.</li>
 *   <li>Translate startup and execution failures into clear log output and a
 *       non-zero process exit code, suitable for CI pipelines.</li>
 * </ul>
 *
 * <h2>Exit codes</h2>
 * <table>
 *   <tr><td>{@code 0}</td><td>Run completed; zero failed and zero errored files.</td></tr>
 *   <tr><td>{@code 1}</td><td>Run completed; one or more files failed or errored.</td></tr>
 *   <tr><td>{@code 2}</td><td>Orchestrator could not be initialised
 *       (configuration, Playwright, or filesystem failure).</td></tr>
 *   <tr><td>{@code 3}</td><td>An unexpected exception occurred during
 *       {@link AuditOrchestrator#run()} itself.</td></tr>
 * </table>
 *
 * <h2>Resource management</h2>
 * <p>{@link AuditOrchestrator} is {@link AutoCloseable} and owns a Playwright
 * browser process. It is constructed inside a try-with-resources block so the
 * browser is reliably closed whether the run completes, fails, or throws.</p>
 */
public final class ApplicationLauncher {

    private static final Logger log = LoggerFactory.getLogger(ApplicationLauncher.class);

    // -------------------------------------------------------------------------
    // Exit codes
    // -------------------------------------------------------------------------

    private static final int EXIT_SUCCESS              = 0;
    private static final int EXIT_RUN_HAD_FAILURES     = 1;
    private static final int EXIT_INITIALISATION_ERROR = 2;
    private static final int EXIT_RUN_ERROR            = 3;

    /** Utility class – not instantiable. */
    private ApplicationLauncher() {
        throw new UnsupportedOperationException("ApplicationLauncher is a utility class");
    }

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    /**
     * Launches a single audit run and exits the JVM with a status code
     * reflecting the outcome.
     *
     * @param args not used
     */
    public static void main(final String[] args) {
        log.info("==================================================");
        log.info(" Email Audit Engine – starting");
        log.info("==================================================");

        final int exitCode = execute();

        log.info("Email Audit Engine – exiting with code {}", exitCode);
        System.exit(exitCode);
    }
    public static AuditOrchestrator.RunSummary runAudit() {
        return runAudit(ValidationMode.PRE_SEND, ExecutionContext.DEFAULT_INPUT_SOURCE);
    }

    public static AuditOrchestrator.RunSummary runAudit(
            final ValidationMode validationMode,
            final String inputSource) {

        return runAudit(validationMode, inputSource, null);
    }

    public static AuditOrchestrator.RunSummary runAudit(
            final ValidationMode validationMode,
            final String inputSource,
            final Path inputDirectory) {

        return runAudit(validationMode, inputSource, inputDirectory, false);
    }

    public static AuditOrchestrator.RunSummary runAuditUsingCurrentOutput(
            final ValidationMode validationMode,
            final String inputSource,
            final Path inputDirectory) {

        return runAudit(validationMode, inputSource, inputDirectory, true);
    }

    private static AuditOrchestrator.RunSummary runAudit(
            final ValidationMode validationMode,
            final String inputSource,
            final Path inputDirectory,
            final boolean reuseCurrentOutput) {

        ExecutionContext.configure(validationMode, inputSource);
        if (reuseCurrentOutput) {
            ExecutionOutputManager.ensureCurrentExecution();
        } else {
            ExecutionOutputManager.startNewExecution();
        }

        try (AuditOrchestrator orchestrator = inputDirectory == null
                ? new AuditOrchestrator()
                : new AuditOrchestrator(inputDirectory, AuditOrchestrator.defaultRuleRegistry())) {

            return orchestrator.run();
        }
    }
    // -------------------------------------------------------------------------
    // Internal – orchestration
    // -------------------------------------------------------------------------

    /**
     * Constructs the orchestrator, runs the pipeline, and logs the summary.
     *
     * @return process exit code based on initialisation and run outcome
     */
    private static int execute() {
        ExecutionContext.configure(ValidationMode.PRE_SEND, ExecutionContext.DEFAULT_INPUT_SOURCE);
        ExecutionOutputManager.startNewExecution();

        try (AuditOrchestrator orchestrator = new AuditOrchestrator()) {

            final AuditOrchestrator.RunSummary summary;
            try {
                summary = orchestrator.run();
            } catch (final Exception e) {
                log.error("Audit run failed with an unexpected exception: {}", e.getMessage(), e);
                return EXIT_RUN_ERROR;
            }

            logRunSummary(summary);
            ExecutionOutputManager.completeExecution(summary, null, executionStatus(summary));

            return (summary.failed() == 0 && summary.errored() == 0)
                    ? EXIT_SUCCESS
                    : EXIT_RUN_HAD_FAILURES;

        } catch (final AuditOrchestrator.OrchestrationException e) {
            log.error("Failed to initialise the audit engine: {}", e.getMessage(), e);
            log.error("No files were processed. Check configuration (application.properties), "
                    + "Playwright browser installation, and input/output directory permissions.");
            return EXIT_INITIALISATION_ERROR;

        } catch (final Exception e) {
            // Catch-all for any other unexpected startup failure (e.g. classpath
            // or environment issues not wrapped as OrchestrationException).
            log.error("Unexpected startup failure: {}", e.getMessage(), e);
            return EXIT_INITIALISATION_ERROR;
        }
    }

    // -------------------------------------------------------------------------
    // Internal – summary logging
    // -------------------------------------------------------------------------

    /**
     * Logs every field of the {@link AuditOrchestrator.RunSummary} in a
     * human-readable block, using WARN level if any files failed or errored
     * so the summary is visually distinct in CI logs.
     */
    private static void logRunSummary(final AuditOrchestrator.RunSummary summary) {
        final boolean hasIssues = summary.failed() > 0 || summary.errored() > 0;

        log.info("==================================================");
        log.info(" Audit Run Summary");
        log.info("==================================================");
        log.info(" Total files discovered : {}", summary.totalDiscovered());
        log.info(" Processed              : {}", summary.processed());
        log.info(" Skipped                : {}", summary.skipped());
        log.info(" Succeeded              : {}", summary.succeeded());

        if (hasIssues) {
            log.warn(" Failed                 : {}", summary.failed());
            log.warn(" Errored                : {}", summary.errored());
        } else {
            log.info(" Failed                 : {}", summary.failed());
            log.info(" Errored                : {}", summary.errored());
        }

        log.info(" Dashboard location     : {}", summary.dashboardPath());
        log.info("==================================================");

        if (hasIssues) {
            log.warn("Audit run completed with {} failed and {} errored file(s). "
                            + "Review the dashboard at '{}' for details.",
                    summary.failed(), summary.errored(), summary.dashboardPath());
        } else {
            log.info("Audit run completed successfully with no failures or errors.");
        }
    }

    private static String executionStatus(final AuditOrchestrator.RunSummary summary) {
        if (summary.errored() > 0) {
            return "ERROR";
        }
        if (summary.failed() > 0) {
            return "FAIL";
        }
        return "PASS";
    }
}
