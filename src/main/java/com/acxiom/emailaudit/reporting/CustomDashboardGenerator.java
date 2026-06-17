package com.acxiom.emailaudit.reporting;

import com.acxiom.emailaudit.reporting.dashboard.RunAuditData;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/**
 * Generates the standalone audit dashboard and required static assets.
 */
public final class CustomDashboardGenerator {

    private static final String OUTPUT_DIRECTORY =
            "output/reports/dashboard";

    private static final String DASHBOARD_HTML =
            "dashboard.html";

    private static final String DASHBOARD_CSS =
            "dashboard.css";

    private static final String DASHBOARD_JS =
            "dashboard.js";

    private static final String RESOURCE_HTML =
            "reporting/dashboard.html";

    private static final String RESOURCE_CSS =
            "reporting/dashboard.css";

    private static final String RESOURCE_JS =
            "reporting/dashboard.js";

    private final ReportTemplateRenderer templateRenderer;

    public CustomDashboardGenerator() {
        this.templateRenderer = new ReportTemplateRenderer();
    }

    /**
     * Generates a complete standalone dashboard.
     *
     * @param data dashboard data
     * @return path to generated dashboard.html
     */
    public Path generate(
            final RunAuditData data) {

        Objects.requireNonNull(
                data,
                "RunAuditData must not be null");

        try {

            final Path dashboardDirectory =
                    Paths.get(OUTPUT_DIRECTORY);

            Files.createDirectories(
                    dashboardDirectory);

            copyResource(
                    RESOURCE_CSS,
                    dashboardDirectory.resolve(
                            DASHBOARD_CSS));

            copyResource(
                    RESOURCE_JS,
                    dashboardDirectory.resolve(
                            DASHBOARD_JS));

            final String renderedHtml =
                    templateRenderer.renderDashboard(
                            data);

            final Path dashboardPath =
                    dashboardDirectory.resolve(
                            DASHBOARD_HTML);

            Files.writeString(
                    dashboardPath,
                    renderedHtml);

            return dashboardPath;

        } catch (Exception ex) {

            throw new RuntimeException(
                    "Failed to generate dashboard",
                    ex);
        }
    }

    private void copyResource(
            final String resourcePath,
            final Path targetFile) {

        try (InputStream inputStream =
                     Thread.currentThread()
                             .getContextClassLoader()
                             .getResourceAsStream(
                                     resourcePath)) {

            if (inputStream == null) {

                throw new IllegalStateException(
                        "Resource not found: "
                                + resourcePath);
            }

            Files.copy(
                    inputStream,
                    targetFile,
                    StandardCopyOption.REPLACE_EXISTING);

        } catch (IOException ex) {

            throw new RuntimeException(
                    "Failed to copy resource: "
                            + resourcePath,
                    ex);
        }
    }
}