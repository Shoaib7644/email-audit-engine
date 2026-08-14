package com.acxiom.emailaudit.output;

import com.acxiom.emailaudit.campaign.CampaignSpecification;
import com.acxiom.emailaudit.campaign.CampaignSpecificationModule;
import com.acxiom.emailaudit.core.ClientContext;
import com.acxiom.emailaudit.core.ExecutionContext;
import com.acxiom.emailaudit.core.ValidationMode;
import com.acxiom.emailaudit.gmail.GmailMetadata;
import com.acxiom.emailaudit.gmail.GmailMetadataContext;
import com.acxiom.emailaudit.orchestration.AuditOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns all filesystem paths for a single audit execution.
 */
public final class ExecutionOutputManager {

    private static final Logger log = LoggerFactory.getLogger(ExecutionOutputManager.class);

    private static final Path OUTPUT_ROOT = Paths.get("output");
    private static final Path RUNTIME_ROOT = OUTPUT_ROOT.resolve("runtime");
    private static final Path LATEST_ROOT = OUTPUT_ROOT.resolve("latest");
    private static final String LEGACY_SCREENSHOT_DIR = "output/screenshots";
    private static final String LEGACY_ARCHIVE_DIR = "output/archive";
    private static final String LEGACY_STATE_REGISTRY = "output/state/audit-registry.json";
    private static final String LEGACY_GMAIL_TEMP_DIR = "output/post-send-temp";
    private static final String LEGACY_BROWSER_PROFILE_DIR = "output/browser-profile/chrome-user-data";
    private static final String RUNTIME_STATE_REGISTRY = "output/runtime/state/audit-registry.json";
    private static final String RUNTIME_GMAIL_TEMP_DIR = "output/runtime/post-send-temp";
    private static final String RUNTIME_BROWSER_PROFILE_DIR =
            "output/runtime/browser-profile/chrome-user-data";
    private static final String EXECUTION_JSON = "execution.json";
    private static final String LATEST_POINTER = "execution-path.txt";
    private static final DateTimeFormatter FOLDER_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final AtomicReference<ExecutionOutput> CURRENT = new AtomicReference<>();

    private ExecutionOutputManager() {
    }

    public static ExecutionOutput startNewExecution() {
        final ExecutionOutput output = createExecutionOutput();
        CURRENT.set(output);
        log.info("Execution output folder initialised: {}", output.executionRoot().toAbsolutePath());
        return output;
    }

    public static ExecutionOutput ensureCurrentExecution() {
        final ExecutionOutput current = CURRENT.get();
        return current == null ? startNewExecution() : current;
    }

    public static Optional<ExecutionOutput> currentExecution() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static boolean isManagedScreenshotDir(final String value) {
        return isBlankOrEqual(value, LEGACY_SCREENSHOT_DIR);
    }

    public static boolean isManagedArchiveDir(final String value) {
        return isBlankOrEqual(value, LEGACY_ARCHIVE_DIR);
    }

    public static boolean isManagedStateRegistry(final String value) {
        return isBlankOrEqual(value, LEGACY_STATE_REGISTRY)
                || RUNTIME_STATE_REGISTRY.equals(cleanPathString(value));
    }

    public static boolean isManagedGmailTempDir(final String value) {
        return isBlankOrEqual(value, LEGACY_GMAIL_TEMP_DIR)
                || RUNTIME_GMAIL_TEMP_DIR.equals(cleanPathString(value));
    }

    public static boolean isManagedBrowserProfileDir(final String value) {
        return isBlankOrEqual(value, LEGACY_BROWSER_PROFILE_DIR)
                || RUNTIME_BROWSER_PROFILE_DIR.equals(cleanPathString(value));
    }

    public static void completeExecution(
            final AuditOrchestrator.RunSummary summary,
            final Path summaryWorkbookPath,
            final String status) {

        final ExecutionOutput output = ensureCurrentExecution();
        final Map<String, Object> metadata = output.metadata(summary, summaryWorkbookPath, status);
        ensureLogsFile(output);
        writeJson(output.executionJsonPath(), metadata);
        updateLatestPointer(output, metadata);
    }

    public static void writeLogs(final String logs) {
        final ExecutionOutput output = ensureCurrentExecution();
        try {
            Files.createDirectories(output.executionRoot());
            Files.writeString(
                    output.logsPath(),
                    logs == null ? "" : logs,
                    StandardCharsets.UTF_8);
        } catch (final IOException ex) {
            log.warn("Unable to write execution logs: {}", ex.getMessage());
        }
    }

    private static ExecutionOutput createExecutionOutput() {
        final String client = cleanClient(ClientContext.selectedClient());
        final ValidationMode mode = ExecutionContext.validationMode();
        final String timestamp = FOLDER_TIMESTAMP.format(LocalDateTime.now());
        Path root = OUTPUT_ROOT.resolve(safeSegment(client)).resolve(mode.name()).resolve(timestamp);

        int suffix = 2;
        while (Files.exists(root)) {
            root = OUTPUT_ROOT.resolve(safeSegment(client)).resolve(mode.name()).resolve(timestamp + "_" + suffix++);
        }

        final CampaignMetadata campaign = campaignMetadata();
        final ExecutionOutput output = new ExecutionOutput(
                UUID.randomUUID().toString(),
                client,
                mode.name(),
                ExecutionContext.inputSource(),
                Instant.now(),
                campaign.spreadsheet(),
                campaign.worksheet(),
                root);
        output.createDirectories();
        return output;
    }

    private static CampaignMetadata campaignMetadata() {
        return CampaignSpecificationModule.activeSpecification()
                .map(ExecutionOutputManager::campaignMetadata)
                .orElseGet(() -> new CampaignMetadata("", ""));
    }

    private static CampaignMetadata campaignMetadata(final CampaignSpecification specification) {
        final Path sourceFile = specification.sourceFile();
        final String spreadsheet = sourceFile == null || sourceFile.getFileName() == null
                ? ""
                : sourceFile.getFileName().toString();
        return new CampaignMetadata(spreadsheet, specification.worksheetName());
    }

    private static void updateLatestPointer(
            final ExecutionOutput output,
            final Map<String, Object> metadata) {

        try {
            Files.createDirectories(LATEST_ROOT);
            Files.writeString(
                    LATEST_ROOT.resolve(LATEST_POINTER),
                    output.executionRoot().toAbsolutePath().toString(),
                    StandardCharsets.UTF_8);
            writeJson(LATEST_ROOT.resolve(EXECUTION_JSON), metadata);
            mirrorLatestArtifacts(output);
        } catch (final IOException ex) {
            log.warn("Unable to update latest execution pointer: {}", ex.getMessage());
        }
    }

    private static void writeJson(final Path path, final Map<String, Object> metadata) {
        try {
            Files.createDirectories(path.getParent());
            MAPPER.writeValue(path.toFile(), metadata);
        } catch (final IOException ex) {
            throw new ExecutionOutputException("Unable to write " + path + ": " + ex.getMessage(), ex);
        }
    }

    private static void ensureLogsFile(final ExecutionOutput output) {
        if (Files.exists(output.logsPath())) {
            return;
        }
        try {
            Files.createDirectories(output.executionRoot());
            Files.writeString(output.logsPath(), "", StandardCharsets.UTF_8);
        } catch (final IOException ex) {
            log.warn("Unable to initialise execution logs file: {}", ex.getMessage());
        }
    }

    private static void mirrorLatestArtifacts(final ExecutionOutput output) throws IOException {
        Files.createDirectories(LATEST_ROOT);
        deleteIfExists(LATEST_ROOT.resolve("reports"));
        copyDirectoryIfExists(output.dashboardDir(), LATEST_ROOT.resolve("dashboard"));
        copyDirectoryIfExists(output.screenshotsDir(), LATEST_ROOT.resolve("screenshots"));
        copyFileIfExists(output.summaryWorkbookPath(), LATEST_ROOT.resolve("EmailAuditSummary.xlsx"));
        copyFileIfExists(output.linkImagePdfPath(), LATEST_ROOT.resolve("LinkImageValidationReport.pdf"));
        copyFileIfExists(output.logsPath(), LATEST_ROOT.resolve("logs.txt"));
        copyFileIfExists(output.executionJsonPath(), LATEST_ROOT.resolve(EXECUTION_JSON));
        writeDashboardShortcut();
    }

    private static void copyDirectoryIfExists(final Path source, final Path target) throws IOException {
        deleteIfExists(target);
        if (!Files.exists(source)) {
            return;
        }
        try (var paths = Files.walk(source)) {
            for (final Path path : paths.toList()) {
                final Path relative = source.relativize(path);
                final Path destination = target.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void copyFileIfExists(final Path source, final Path target) throws IOException {
        if (!Files.exists(source)) {
            Files.deleteIfExists(target);
            return;
        }
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void deleteIfExists(final Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (final Path child : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(child);
            }
        }
    }

    private static void writeDashboardShortcut() throws IOException {
        final String html = """
                <!doctype html>
                <html lang="en">
                <head>
                    <meta charset="utf-8">
                    <meta http-equiv="refresh" content="0; url=dashboard/dashboard-v2.html">
                    <title>Latest Email Audit Dashboard</title>
                    <script>window.location.replace('dashboard/dashboard-v2.html');</script>
                </head>
                <body>
                    <a href="dashboard/dashboard-v2.html">Open latest dashboard</a>
                </body>
                </html>
                """;
        Files.writeString(LATEST_ROOT.resolve("dashboard.html"), html, StandardCharsets.UTF_8);
    }

    private static String cleanClient(final String client) {
        return client == null || client.isBlank() ? ClientContext.DEFAULT_CLIENT : client.trim();
    }

    private static boolean isBlankOrEqual(final String value, final String expected) {
        return value == null || value.isBlank() || expected.equals(value.trim());
    }

    private static String cleanPathString(final String value) {
        return value == null ? "" : value.trim().replace('\\', '/');
    }

    private static String safeSegment(final String value) {
        final String safe = cleanClient(value)
                .replaceAll("[^A-Za-z0-9._-]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        return safe.isBlank() ? ClientContext.DEFAULT_CLIENT : safe;
    }

    private record CampaignMetadata(String spreadsheet, String worksheet) {
    }

    public record ExecutionOutput(
            String executionId,
            String client,
            String validationMode,
            String inputSource,
            Instant startedAt,
            String spreadsheet,
            String worksheet,
            Path executionRoot) {

        public ExecutionOutput {
            inputSource = inputSource == null || inputSource.isBlank()
                    ? ExecutionContext.DEFAULT_INPUT_SOURCE
                    : inputSource.trim();
            spreadsheet = spreadsheet == null ? "" : spreadsheet;
            worksheet = worksheet == null ? "" : worksheet;
            executionRoot = executionRoot.normalize().toAbsolutePath();
        }

        public Path dashboardDir() {
            return executionRoot.resolve("dashboard");
        }

        public Path screenshotsDir() {
            return executionRoot.resolve("screenshots");
        }

        public Path archiveSuccessDir() {
            return executionRoot.resolve("archive").resolve("success");
        }

        public Path archiveFailedDir() {
            return executionRoot.resolve("archive").resolve("failed");
        }

        public Path postSendTempDir() {
            return RUNTIME_ROOT.resolve("post-send-temp").normalize().toAbsolutePath();
        }

        public Path browserProfileDir() {
            return RUNTIME_ROOT
                    .resolve("browser-profile")
                    .resolve("chrome-user-data")
                    .normalize()
                    .toAbsolutePath();
        }

        public Path stateRegistryPath() {
            return RUNTIME_ROOT
                    .resolve("state")
                    .resolve("audit-registry.json")
                    .normalize()
                    .toAbsolutePath();
        }

        public Path dashboardPath() {
            return dashboardDir().resolve("dashboard-v2.html");
        }

        public Path summaryWorkbookPath() {
            return executionRoot.resolve("EmailAuditSummary.xlsx");
        }

        public Path linkImagePdfPath() {
            return executionRoot.resolve("LinkImageValidationReport.pdf");
        }

        public Path executionJsonPath() {
            return executionRoot.resolve(EXECUTION_JSON);
        }

        public Path logsPath() {
            return executionRoot.resolve("logs.txt");
        }

        private void createDirectories() {
            try {
                Files.createDirectories(RUNTIME_ROOT);
                Files.createDirectories(dashboardDir());
                Files.createDirectories(screenshotsDir());
                Files.createDirectories(archiveSuccessDir());
                Files.createDirectories(archiveFailedDir());
                Files.createDirectories(postSendTempDir());
                Files.createDirectories(browserProfileDir());
                Files.createDirectories(stateRegistryPath().getParent());
            } catch (final IOException ex) {
                throw new ExecutionOutputException(
                        "Unable to create execution output folders: " + ex.getMessage(), ex);
            }
        }

        private Map<String, Object> metadata(
                final AuditOrchestrator.RunSummary summary,
                final Path summaryWorkbookPath,
                final String status) {

            final Map<String, Object> metadata = new LinkedHashMap<>();
            final Optional<GmailMetadata> gmailMetadata = GmailMetadataContext.current();
            final long executionTimeMs = summary == null ? 0L : summary.executionTimeMs();
            final String executionResult = status == null || status.isBlank() ? "UNKNOWN" : status;
            metadata.put("executionId", executionId);
            metadata.put("timestamp", DateTimeFormatter.ISO_LOCAL_DATE_TIME
                    .withZone(ZoneId.systemDefault())
                    .format(startedAt));
            metadata.put("client", client);
            metadata.put("validationMode", validationMode);
            metadata.put("executionDateTime", startedAt);
            metadata.put("duration", executionTimeMs == 0L ? "" : executionTimeMs + "ms");
            metadata.put("executionTimeSeconds", executionTimeMs == 0L
                    ? 0
                    : Math.max(1, Math.round(executionTimeMs / 1000.0d)));
            metadata.put("status", summary == null || "ERROR".equalsIgnoreCase(executionResult)
                    ? "Failed"
                    : "Completed");
            metadata.put("executionResult", executionResult);
            metadata.put("spreadsheet", spreadsheet);
            metadata.put("campaignSpecification", !spreadsheet.isBlank());
            metadata.put("worksheet", worksheet);
            metadata.put("inputSource", inputSource);
            metadata.put("emailSubject", gmailMetadata.map(GmailMetadata::subject).orElse(""));
            metadata.put("gmailFolder", gmailFolder(inputSource));
            metadata.put("totalFiles", summary == null ? 0 : summary.totalDiscovered());
            metadata.put("processed", summary == null ? 0 : summary.processed());
            metadata.put("passed", summary == null ? 0 : summary.succeeded());
            metadata.put("failed", summary == null ? 0 : summary.failed() + summary.errored());
            metadata.put("warnings", warningCount(summary));
            metadata.put("dashboard", "dashboard/dashboard-v2.html");
            metadata.put("excel", "EmailAuditSummary.xlsx");
            metadata.put("linkImagePdf", Files.exists(linkImagePdfPath())
                    ? "LinkImageValidationReport.pdf"
                    : "");
            metadata.put("screenshots", "screenshots/");
            metadata.put("dashboardPath", pathString(summary == null ? dashboardPath() : summary.dashboardPath()));
            metadata.put("summaryPath", pathString(summaryWorkbookPath));
            metadata.put("linkImagePdfPath", Files.exists(linkImagePdfPath())
                    ? pathString(linkImagePdfPath())
                    : "");
            metadata.put("outputFolder", pathString(executionRoot));
            metadata.put("logsPath", pathString(logsPath()));
            return metadata;
        }

        private static long warningCount(final AuditOrchestrator.RunSummary summary) {
            return 0L;
        }

        private static String gmailFolder(final String inputSource) {
            if (inputSource == null || !inputSource.startsWith("Gmail Inbox")) {
                return "";
            }
            final int slash = inputSource.lastIndexOf('/');
            if (slash < 0 || slash + 1 >= inputSource.length()) {
                return "Inbox";
            }
            return inputSource.substring(slash + 1).trim();
        }

        private static String pathString(final Path path) {
            return path == null ? "" : path.toAbsolutePath().toString();
        }
    }

    public static final class ExecutionOutputException extends RuntimeException {
        public ExecutionOutputException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
