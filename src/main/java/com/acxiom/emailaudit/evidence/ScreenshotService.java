package com.acxiom.emailaudit.evidence;

import com.acxiom.emailaudit.config.ConfigurationManager;
import com.acxiom.emailaudit.output.ExecutionOutputManager;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Captures full-page PNG screenshots from a Playwright {@link Page} and
 * persists them to a configurable output directory with timestamp-based names.
 *
 * <h2>Naming convention</h2>
 * <pre>
 *   &lt;sanitised-source-name&gt;_&lt;yyyyMMdd'T'HHmmssSSS'Z'&gt;.png
 *
 *   Examples:
 *     index_20240601T103045123Z.png
 *     welcome-email_20240601T103045124Z.png
 * </pre>
 *
 * <h2>Thread safety</h2>
 * <p>{@code ScreenshotService} is immutable after construction: the output
 * directory path is resolved and created once and never mutated. Multiple
 * threads may call {@link #capture(Page, String)} concurrently; each
 * invocation derives a unique file name from the current {@link Instant},
 * and directory creation is idempotent via {@link Files#createDirectories}.
 * Playwright {@link Page} objects are <em>not</em> thread-safe and must not
 * be shared between threads regardless of this class.</p>
 *
 * <h2>Configuration key</h2>
 * <table>
 *   <tr><td>{@code screenshot.output.dir}</td>
 *       <td>Directory for PNG output (default: {@code audit-results/screenshots})</td></tr>
 * </table>
 */
public final class ScreenshotService {

    private static final Logger log = LoggerFactory.getLogger(ScreenshotService.class);

    private static final String CONFIG_KEY_SCREENSHOT_DIR  = "screenshot.output.dir";
    private static final String DEFAULT_SCREENSHOT_DIR     = "audit-results/screenshots";

    /** ISO-like UTC timestamp with millisecond precision; safe for file names on all OS. */
    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'")
                    .withZone(ZoneOffset.UTC);

    /** Regex matching any character that is not safe in a cross-platform file name. */
    private static final String UNSAFE_FILENAME_CHARS = "[^a-zA-Z0-9._-]";

    private static final String PNG_EXTENSION    = ".png";
    private static final int    MAX_SOURCE_LENGTH = 80;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final Path screenshotDir;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Creates a {@code ScreenshotService} whose output directory is resolved
     * from {@link ConfigurationManager}.
     *
     * @throws ScreenshotException if the output directory cannot be created
     */
    public ScreenshotService() {
        this(resolveScreenshotDir());
    }

    /**
     * Creates a {@code ScreenshotService} with an explicit output directory.
     * Primarily used in tests.
     *
     * @param screenshotDir directory where PNG files will be written
     * @throws ScreenshotException if the directory cannot be created
     */
    public ScreenshotService(final Path screenshotDir) {
        Objects.requireNonNull(screenshotDir, "screenshotDir must not be null");
        this.screenshotDir = screenshotDir.normalize().toAbsolutePath();
        ensureDirectory(this.screenshotDir);
        log.info("ScreenshotService ready – output dir: '{}'", this.screenshotDir);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Captures a full-page PNG screenshot of {@code page} and saves it to the
     * configured output directory.
     *
     * <p>The file name is derived from {@code sourceName} (sanitised for
     * cross-platform safety) combined with a UTC timestamp:
     * {@code <sourceName>_<timestamp>.png}.</p>
     *
     * @param page       live Playwright {@link Page}; must not be {@code null}
     * @param sourceName logical name for the screenshot (typically the HTML
     *                   file name without extension); must not be {@code null}
     *                   or blank
     * @return absolute {@link Path} of the saved PNG file
     * @throws ScreenshotException if the screenshot cannot be taken or saved
     */
    public Path capture(final Page page, final String sourceName) {
        Objects.requireNonNull(page,       "page must not be null");
        Objects.requireNonNull(sourceName, "sourceName must not be null");

        if (sourceName.isBlank()) {
            throw new IllegalArgumentException("sourceName must not be blank");
        }

        final Path outputPath = buildOutputPath(sourceName);
        log.debug("Capturing full-page screenshot → '{}'", outputPath.getFileName());

        final Page.ScreenshotOptions options = new Page.ScreenshotOptions()
                .setFullPage(true)
                .setPath(outputPath);

        try {
            page.screenshot(options);
        } catch (PlaywrightException e) {
            throw new ScreenshotException(
                    "Playwright failed to capture screenshot for '" + sourceName
                            + "': " + e.getMessage(), e);
        }

        if (!Files.exists(outputPath)) {
            throw new ScreenshotException(
                    "Screenshot file was not created at expected path: " + outputPath);
        }

        log.info("Screenshot saved: '{}' ({} bytes)",
                outputPath.getFileName(), fileSizeQuietly(outputPath));

        return outputPath;
    }

    /**
     * Captures a full-page PNG screenshot using the page's current URL or
     * title as the source name.  Convenience overload when no logical file
     * name is available.
     *
     * @param page live Playwright {@link Page}; must not be {@code null}
     * @return absolute {@link Path} of the saved PNG file
     * @throws ScreenshotException if the screenshot cannot be taken or saved
     */
    public Path capture(final Page page) {
        Objects.requireNonNull(page, "page must not be null");
        final String derivedName = deriveSourceName(page);
        return capture(page, derivedName);
    }

    /**
     * Captures a cropped PNG screenshot of a single rendered element.
     *
     * <p>The same output directory and timestamp-based naming convention used
     * for full-page screenshots is reused here; only the Playwright capture
     * target changes from {@link Page} to {@link Locator}.</p>
     *
     * @param locator    Playwright locator resolving to the element to capture
     * @param sourceName logical name for the screenshot; must not be blank
     * @return absolute {@link Path} of the saved PNG file
     * @throws ScreenshotException if the screenshot cannot be taken or saved
     */
    public Path capture(final Locator locator, final String sourceName) {
        Objects.requireNonNull(locator,    "locator must not be null");
        Objects.requireNonNull(sourceName, "sourceName must not be null");

        if (sourceName.isBlank()) {
            throw new IllegalArgumentException("sourceName must not be blank");
        }

        final Path outputPath = buildOutputPath(sourceName);
        log.debug("Capturing element screenshot → '{}'", outputPath.getFileName());

        final Locator.ScreenshotOptions options = new Locator.ScreenshotOptions()
                .setPath(outputPath);

        try {
            locator.screenshot(options);
        } catch (PlaywrightException e) {
            throw new ScreenshotException(
                    "Playwright failed to capture screenshot for '" + sourceName
                            + "': " + e.getMessage(), e);
        }

        if (!Files.exists(outputPath)) {
            throw new ScreenshotException(
                    "Screenshot file was not created at expected path: " + outputPath);
        }

        log.info("Screenshot saved: '{}' ({} bytes)",
                outputPath.getFileName(), fileSizeQuietly(outputPath));

        return outputPath;
    }

    /**
     * Returns the absolute path of the directory where screenshots are saved.
     *
     * @return screenshot output directory; never {@code null}
     */
    public Path getScreenshotDir() {
        return screenshotDir;
    }

    // -------------------------------------------------------------------------
    // Internal – path construction
    // -------------------------------------------------------------------------

    /**
     * Builds a unique, OS-safe output path for a screenshot.
     *
     * <pre>
     *   sourceName = "Welcome Email"
     *   →  &lt;screenshotDir&gt;/Welcome_Email_20240601T103045123Z.png
     * </pre>
     */
    private Path buildOutputPath(final String sourceName) {
        final String safe      = sanitise(sourceName);
        final String timestamp = TIMESTAMP_FMT.format(Instant.now());
        final String fileName  = safe + "_" + timestamp + PNG_EXTENSION;
        return screenshotDir.resolve(fileName);
    }

    /**
     * Strips or replaces characters that are unsafe in file names across
     * Windows, macOS, and Linux, and truncates to {@value #MAX_SOURCE_LENGTH}
     * characters to avoid path-length limits.
     */
    static String sanitise(final String sourceName) {
        // Replace unsafe chars with underscore, collapse consecutive underscores.
        String safe = sourceName
                .trim()
                .replaceAll(UNSAFE_FILENAME_CHARS, "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", ""); // strip leading/trailing underscores

        if (safe.isEmpty()) {
            safe = "screenshot";
        }

        if (safe.length() > MAX_SOURCE_LENGTH) {
            safe = safe.substring(0, MAX_SOURCE_LENGTH);
        }

        return safe;
    }

    // -------------------------------------------------------------------------
    // Internal – helpers
    // -------------------------------------------------------------------------

    private static String deriveSourceName(final Page page) {
        try {
            final String title = page.title();
            if (title != null && !title.isBlank()) {
                return title;
            }
        } catch (PlaywrightException ignored) {
            // fall through to URL
        }

        try {
            final String url = page.url();
            if (url != null && !url.isBlank()) {
                // Extract file name from file:/// URLs; use last path segment otherwise.
                final int slash = url.lastIndexOf('/');
                final String segment = slash >= 0 ? url.substring(slash + 1) : url;
                if (!segment.isBlank()) {
                    return segment;
                }
            }
        } catch (PlaywrightException ignored) {
            // fall through to fallback
        }

        return "screenshot";
    }

    private static void ensureDirectory(final Path dir) {
        try {
            Files.createDirectories(dir);
            log.debug("Screenshot directory ready: {}", dir);
        } catch (IOException e) {
            throw new ScreenshotException(
                    "Cannot create screenshot directory '" + dir + "': " + e.getMessage(), e);
        }
    }

    private static long fileSizeQuietly(final Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return -1L;
        }
    }

    private static Path resolveScreenshotDir() {
        final String dir = ConfigurationManager.getInstance()
                .getOrDefault(CONFIG_KEY_SCREENSHOT_DIR, "");
        if (ExecutionOutputManager.isManagedScreenshotDir(dir)
                || DEFAULT_SCREENSHOT_DIR.equals(dir)) {
            return ExecutionOutputManager.ensureCurrentExecution().screenshotsDir();
        }
        return Paths.get(dir);
    }

    // -------------------------------------------------------------------------
    // Exception
    // -------------------------------------------------------------------------

    /**
     * Unchecked exception thrown when a screenshot cannot be captured or saved.
     */
    public static final class ScreenshotException extends RuntimeException {

        public ScreenshotException(final String message) {
            super(message);
        }

        public ScreenshotException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
