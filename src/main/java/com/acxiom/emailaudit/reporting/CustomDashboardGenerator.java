package com.acxiom.emailaudit.reporting;

import com.acxiom.emailaudit.reporting.dashboard.RunAuditData;
import com.acxiom.emailaudit.output.ExecutionOutputManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;

/**
 * Generates the standalone audit dashboard and required static assets.
 *
 * <p>Output layout is written to the current execution dashboard directory:</p>
 * <pre>
 *   dashboard-v2.html        ← rendered template with injected JSON
 *   dashboard-v2.css         ← stylesheet
 *   js/
 *     utils.js
 *     parser.js
 *     state.js
 *     statistics.js
 *     categories.js
 *     findings.js
 *     renderer.js
 *     sidebar.js
 *     emailSelector.js
 *     integration.js
 *     app.js
 * </pre>
 */
public final class CustomDashboardGenerator {

    private static final Logger log =
            LoggerFactory.getLogger(CustomDashboardGenerator.class);

    private static final String DASHBOARD_HTML =
            "dashboard-v2.html";

    private static final String DASHBOARD_CSS =
            "dashboard-v2.css";

    // ── Classpath resource paths ──────────────────────────────────────────────
    private static final String RESOURCE_HTML =
            "reporting/dashboard-v2.html";

    private static final String RESOURCE_CSS =
            "reporting/dashboard-v2.css";

    /**
     * All JS modules under {@code reporting/js/} that must be copied alongside
     * the HTML.  Order matches the script load order declared in the HTML.
     */
    private static final List<String> JS_MODULES = List.of(
            "utils.js",
            "parser.js",
            "state.js",
            "statistics.js",
            "categories.js",
            "findings.js",
            "renderer.js",
            "sidebar.js",
            "emailSelector.js",
            "integration.js",
            "app.js"
    );

    private final ReportTemplateRenderer templateRenderer;

    public CustomDashboardGenerator() {
        this.templateRenderer = new ReportTemplateRenderer();
    }

    /**
     * Generates a complete standalone dashboard.
     *
     * <p>Steps:</p>
     * <ol>
     *   <li>Create the execution dashboard directory and its {@code js/} subdirectory.</li>
     *   <li>Copy {@code dashboard-v2.css} next to the HTML.</li>
     *   <li>Copy all JS modules into the {@code js/} subdirectory.</li>
     *   <li>Render the HTML template with injected JSON and write it.</li>
     * </ol>
     *
     * @param data dashboard data
     * @return path to the generated {@code dashboard-v2.html}
     */
    public Path generate(final RunAuditData data) {

        Objects.requireNonNull(data, "RunAuditData must not be null");

        try {
            // ── 1. Create output directories ──────────────────────────────────
            final Path dashboardDirectory =
                    ExecutionOutputManager.ensureCurrentExecution().dashboardDir();
            final Path jsDirectory        = dashboardDirectory.resolve("js");

            Files.createDirectories(dashboardDirectory);
            Files.createDirectories(jsDirectory);

            // ── 2. Copy CSS ───────────────────────────────────────────────────
            copyResource(
                    RESOURCE_CSS,
                    dashboardDirectory.resolve(DASHBOARD_CSS));

            // ── 3. Copy every JS module into js/ ──────────────────────────────
            for (final String module : JS_MODULES) {
                copyResource(
                        "reporting/js/" + module,
                        jsDirectory.resolve(module));
            }

            // ── 4. Render HTML with injected JSON and write it ────────────────
            final String renderedHtml = templateRenderer.renderDashboard(data);
            final Path   dashboardPath = dashboardDirectory.resolve(DASHBOARD_HTML);

            Files.writeString(dashboardPath, renderedHtml);

            log.info("Dashboard written to: {}", dashboardPath.toAbsolutePath());

            return dashboardPath;

        } catch (Exception ex) {
            throw new RuntimeException("Failed to generate dashboard", ex);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Copies a classpath resource to a target file on disk,
     * replacing it if it already exists.
     *
     * @param resourcePath classpath-relative resource path
     * @param targetFile   destination on the filesystem
     */
    private void copyResource(
            final String resourcePath,
            final Path   targetFile) {

        try (InputStream inputStream =
                     Thread.currentThread()
                             .getContextClassLoader()
                             .getResourceAsStream(resourcePath)) {

            if (inputStream == null) {
                throw new IllegalStateException(
                        "Resource not found on classpath: " + resourcePath);
            }

            Files.copy(inputStream, targetFile, StandardCopyOption.REPLACE_EXISTING);

            log.debug("Copied resource {} → {}", resourcePath, targetFile);

        } catch (IOException ex) {
            throw new RuntimeException(
                    "Failed to copy resource: " + resourcePath, ex);
        }
    }
}
