package com.acxiom.emailaudit.rendering;

import com.acxiom.emailaudit.config.ConfigurationManager;
import com.acxiom.emailaudit.utilities.PerformanceMetrics;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.ColorScheme;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;

/**
 * Renders a local HTML file in a Playwright-managed browser and returns the
 * fully loaded {@link Page} object for downstream rule evaluation.
 *
 * <h2>Lifecycle contract</h2>
 * <p>{@code HtmlRenderer} owns a single {@link Playwright} instance for its
 * lifetime. Each call to {@link #render(Path)} launches a persistent browser
 * context from the configured Chrome user data directory and creates a new
 * {@link Page}. The caller is responsible for closing the returned
 * {@link Page} (and its parent context) after rule evaluation completes.</p>
 *
 * <h2>Thread safety</h2>
 * <p>Playwright's Java bindings are <strong>not</strong> thread-safe: each
 * thread must own its own {@link Playwright} and {@link Browser} instance.
 * {@code HtmlRenderer} is therefore designed to be used as a
 * <em>per-thread</em> object (e.g. via a {@link ThreadLocal} or TestNG
 * data-provider scoped to a single thread).  Construction and teardown should
 * occur on the same thread that calls {@link #render(Path)}.</p>
 *
 * <h2>Configuration keys</h2>
 * <table>
 *   <tr><td>{@code playwright.browser}</td>
 *       <td>Browser engine: {@code chrome} (default), {@code chromium},
 *           {@code firefox}, {@code webkit}</td></tr>
 *   <tr><td>{@code playwright.browser.channel}</td>
 *       <td>Browser channel for Chromium-family browsers (default:
 *           {@code chrome})</td></tr>
 *   <tr><td>{@code playwright.headless}</td>
 *       <td>{@code true} / {@code false} (default)</td></tr>
 *   <tr><td>{@code playwright.timeout.ms}</td>
 *       <td>Navigation + action timeout in milliseconds (default: 30 000)</td></tr>
 *   <tr><td>{@code browser.profile.directory}</td>
 *       <td>Persistent browser profile directory</td></tr>
 * </table>
 */
public final class HtmlRenderer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HtmlRenderer.class);

    private static final String FILE_URL_PREFIX = "file:///";

    // -------------------------------------------------------------------------
    // Playwright infrastructure
    // -------------------------------------------------------------------------

    private final Playwright playwright;
    private Browser          browser;
    private final long       timeoutMs;
    private final String     browserName;
    private final String     browserChannel;
    private final boolean    headless;
    private final Path       profileDirectory;
    private final String     locale;
    private final String     timezoneId;
    private final int        viewportWidth;
    private final int        viewportHeight;
    private final double     deviceScaleFactor;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Creates an {@code HtmlRenderer} using settings from
     * {@link ConfigurationManager}.
     *
     * @throws RenderException if the browser cannot be launched
     */
    public HtmlRenderer() {
        this(ConfigurationManager.getInstance());
    }

    /**
     * Creates an {@code HtmlRenderer} from a supplied {@link ConfigurationManager}.
     * Primarily used in tests with a custom configuration.
     *
     * @param config non-null configuration source
     * @throws RenderException if the browser cannot be launched
     */
    public HtmlRenderer(final ConfigurationManager config) {
        Objects.requireNonNull(config, "config must not be null");

        this.timeoutMs = config.getTimeoutMs();
        this.browserName = config.getBrowser();
        this.browserChannel = config.getBrowserChannel();
        this.headless = config.isHeadless();
        this.profileDirectory = resolveProfileDirectory(config.getBrowserProfileDirectory());
        this.locale = config.getBrowserLocale();
        this.timezoneId = configuredTimezone(config.getBrowserTimezone());
        this.viewportWidth = config.getBrowserViewportWidth();
        this.viewportHeight = config.getBrowserViewportHeight();
        this.deviceScaleFactor = config.getBrowserDeviceScaleFactor();

        log.info("Preparing {} browser (channel={}, headless={}, timeout={}ms, profile={})",
                browserName, browserChannel, headless, timeoutMs, profileDirectory);

        try {
            this.playwright = Playwright.create();
        } catch (PlaywrightException e) {
            throw new RenderException(
                    "Failed to initialise Playwright: " + e.getMessage(), e);
        }

        log.info("HtmlRenderer ready – browser: {}", browserName);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Navigates a new browser page to the given local HTML file and waits
     * until the DOM content has fully loaded.
     *
     * <p>The returned {@link Page} (and its parent {@link BrowserContext}) are
     * owned by the caller and <strong>must</strong> be closed after use to
     * release OS resources:</p>
     * <pre>{@code
     * try (Page page = renderer.render(filePath)) {
     *     // run audit rules …
     * }
     * }</pre>
     *
     * @param filePath absolute or relative path to the local HTML file
     * @return fully loaded {@link Page}; never {@code null}
     * @throws RenderException if the file does not exist, is not readable,
     *                         or navigation fails
     */
    public Page render(final Path filePath) {
        Objects.requireNonNull(filePath, "filePath must not be null");
        validateFile(filePath);

        final long renderStartNanos = System.nanoTime();
        final String fileUrl = toFileUrl(filePath);
        log.info("Rendering: {}", fileUrl);

        final BrowserContext context = newPersistentContext();
        configureContext(context);

        final Page page = context.newPage();
        configurePageTimeout(page);

        try {
            final Page.NavigateOptions options = new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(timeoutMs);

            page.navigate(fileUrl, options);

            // Email audits should operate on DOM readiness and must not wait for external resources
            // such as images, stylesheets, tracking pixels, or third-party assets.

            log.info("Render complete – title: '{}', url: {}",
                    safeTitleOf(page), page.url());
            PerformanceMetrics.recordEmailRender(PerformanceMetrics.elapsedMillis(renderStartNanos));

            return page;

        } catch (PlaywrightException e) {
            closeSilently(page, context);
            throw new RenderException(
                    "Navigation failed for '" + filePath.getFileName() + "': " + e.getMessage(), e);
        }
    }

    /**
     * Closes the underlying {@link Browser} and {@link Playwright} instances.
     *
     * <p>This method is idempotent: calling it more than once has no effect.</p>
     */
    @Override
    public void close() {
        log.info("Closing HtmlRenderer");
        try {
            if (browser != null && browser.isConnected()) {
                browser.close();
            }
        } catch (PlaywrightException e) {
            log.warn("Error closing browser: {}", e.getMessage(), e);
        }
        try {
            if (playwright != null) {
                playwright.close();
            }
        } catch (PlaywrightException e) {
            log.warn("Error closing Playwright: {}", e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Internal – browser setup
    // -------------------------------------------------------------------------

    private BrowserContext newPersistentContext() {
        ensureProfileDirectory(profileDirectory);
        final long launchStartNanos = System.nanoTime();

        final BrowserType browserType = browserType(playwright, browserName);
        final BrowserType.LaunchPersistentContextOptions options =
                new BrowserType.LaunchPersistentContextOptions()
                        .setHeadless(headless)
                        .setTimeout(timeoutMs)
                        .setLocale(locale)
                        .setTimezoneId(timezoneId)
                        .setViewportSize(viewportWidth, viewportHeight)
                        .setScreenSize(viewportWidth, viewportHeight)
                        .setDeviceScaleFactor(deviceScaleFactor)
                        .setColorScheme(ColorScheme.LIGHT)
                        .setJavaScriptEnabled(true)
                        .setOffline(false)
                        .setExtraHTTPHeaders(Map.of("Accept-Language", "en-US,en"));

        final String channel = effectiveChannel(browserName, browserChannel);
        if (!channel.isBlank() && isChromiumFamily(browserName)) {
            options.setChannel(channel);
        }

        final BrowserContext context = browserType.launchPersistentContext(profileDirectory, options);
        this.browser = context.browser();
        PerformanceMetrics.recordChromeLaunch(PerformanceMetrics.elapsedMillis(launchStartNanos));
        return context;
    }

    private static BrowserType browserType(final Playwright playwright, final String browserName) {
        return switch (browserName.toLowerCase().trim()) {
            case "firefox" -> playwright.firefox();
            case "webkit"  -> playwright.webkit();
            default        -> playwright.chromium();
        };
    }

    private static boolean isChromiumFamily(final String browserName) {
        final String normalized = browserName == null ? "" : browserName.toLowerCase().trim();
        return normalized.isBlank()
                || "chrome".equals(normalized)
                || "chromium".equals(normalized)
                || "msedge".equals(normalized);
    }

    private static String effectiveChannel(final String browserName, final String configuredChannel) {
        if (configuredChannel != null && !configuredChannel.isBlank()) {
            return configuredChannel.trim();
        }
        return "chrome".equalsIgnoreCase(browserName) ? "chrome" : "";
    }

    private void configureContext(final BrowserContext context) {
        context.setDefaultTimeout(timeoutMs);
        context.setDefaultNavigationTimeout(timeoutMs);
    }

    private void configurePageTimeout(final Page page) {
        page.setDefaultTimeout(timeoutMs);
        page.setDefaultNavigationTimeout(timeoutMs);
    }

    // -------------------------------------------------------------------------
    // Internal – helpers
    // -------------------------------------------------------------------------

    private static void validateFile(final Path filePath) {
        final Path absolute = filePath.normalize().toAbsolutePath();
        if (!Files.exists(absolute)) {
            throw new RenderException("File does not exist: " + absolute);
        }
        if (Files.isDirectory(absolute)) {
            throw new RenderException("Path is a directory, not an HTML file: " + absolute);
        }
        if (!Files.isReadable(absolute)) {
            throw new RenderException("File is not readable: " + absolute);
        }
    }

    /**
     * Converts a filesystem path to a {@code file:///} URL that Playwright
     * accepts on all platforms.
     */
    private static String toFileUrl(final Path filePath) {
        final String absolute = filePath.normalize().toAbsolutePath().toString();
        // On Windows, Path separators are backslashes; replace with forward slashes.
        final String normalized = absolute.replace('\\', '/');
        return FILE_URL_PREFIX + (normalized.startsWith("/")
                ? normalized.substring(1)
                : normalized);
    }

    private static String safeTitleOf(final Page page) {
        try {
            return page.title();
        } catch (PlaywrightException e) {
            return "<unavailable>";
        }
    }

    private static void closeSilently(final Page page, final BrowserContext context) {
        try {
            if (page != null) {
                page.close();
            }
        } catch (PlaywrightException e) {
            log.debug("Suppressed error closing page after render failure: {}", e.getMessage());
        }
        try {
            if (context != null) {
                context.close();
            }
        } catch (PlaywrightException e) {
            log.debug("Suppressed error closing context after render failure: {}", e.getMessage());
        }
    }

    private static Path resolveProfileDirectory(final String configuredPath) {
        final String value = configuredPath == null || configuredPath.isBlank()
                ? "output/browser-profile/chrome-user-data"
                : configuredPath;
        return Paths.get(value).normalize().toAbsolutePath();
    }

    private static void ensureProfileDirectory(final Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (final Exception e) {
            throw new RenderException("Could not create browser profile directory: " + directory, e);
        }
    }

    private static String configuredTimezone(final String configuredTimezone) {
        return configuredTimezone == null || configuredTimezone.isBlank()
                ? ZoneId.systemDefault().getId()
                : configuredTimezone;
    }

    // -------------------------------------------------------------------------
    // Exception
    // -------------------------------------------------------------------------

    /**
     * Unchecked exception thrown when a local HTML file cannot be rendered.
     */
    public static final class RenderException extends RuntimeException {

        public RenderException(final String message) {
            super(message);
        }

        public RenderException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
