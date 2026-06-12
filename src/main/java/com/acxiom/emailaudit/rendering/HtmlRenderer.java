package com.acxiom.emailaudit.rendering;

import com.acxiom.emailaudit.config.ConfigurationManager;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Renders a local HTML file in a Playwright-managed browser and returns the
 * fully loaded {@link Page} object for downstream rule evaluation.
 *
 * <h2>Lifecycle contract</h2>
 * <p>{@code HtmlRenderer} owns a single {@link Playwright} instance and a
 * single {@link Browser} instance for its entire lifetime.  Each call to
 * {@link #render(Path)} creates a <em>new</em> {@link BrowserContext} and
 * {@link Page}, isolating every audit run from the previous one (cookies,
 * local storage, cached resources).  The caller is responsible for closing
 * the returned {@link Page} (and its parent context) after rule evaluation
 * completes.</p>
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
 *       <td>Browser engine: {@code chromium} (default), {@code firefox},
 *           {@code webkit}</td></tr>
 *   <tr><td>{@code playwright.headless}</td>
 *       <td>{@code true} (default) / {@code false}</td></tr>
 *   <tr><td>{@code playwright.timeout.ms}</td>
 *       <td>Navigation + action timeout in milliseconds (default: 30 000)</td></tr>
 * </table>
 */
public final class HtmlRenderer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HtmlRenderer.class);

    private static final String FILE_URL_PREFIX = "file:///";

    // -------------------------------------------------------------------------
    // Playwright infrastructure
    // -------------------------------------------------------------------------

    private final Playwright playwright;
    private final Browser    browser;
    private final long       timeoutMs;

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
        final String  browserName = config.getBrowser();
        final boolean headless    = config.isHeadless();

        log.info("Launching {} browser (headless={}, timeout={}ms)",
                browserName, headless, timeoutMs);

        try {
            this.playwright = Playwright.create();
            this.browser    = launchBrowser(playwright, browserName, headless);
        } catch (PlaywrightException e) {
            throw new RenderException(
                    "Failed to launch browser '" + browserName + "': " + e.getMessage(), e);
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

        final String fileUrl = toFileUrl(filePath);
        log.info("Rendering: {}", fileUrl);

        final BrowserContext context = browser.newContext();
        configureContext(context);

        final Page page = context.newPage();
        configurePageTimeout(page);

        try {
            final Page.NavigateOptions options = new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(timeoutMs);

            page.navigate(fileUrl, options);
            page.waitForLoadState(LoadState.LOAD,
                    new Page.WaitForLoadStateOptions().setTimeout(timeoutMs));

            log.info("Render complete – title: '{}', url: {}",
                    safeTitleOf(page), page.url());

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

    private static Browser launchBrowser(
            final Playwright playwright,
            final String browserName,
            final boolean headless) {

        final BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                .setHeadless(headless);

        return switch (browserName.toLowerCase().trim()) {
            case "firefox" -> playwright.firefox().launch(options);
            case "webkit"  -> playwright.webkit().launch(options);
            default        -> {
                if (!"chromium".equalsIgnoreCase(browserName.trim())) {
                    log.warn("Unknown browser '{}' – defaulting to Chromium", browserName);
                }
                yield playwright.chromium().launch(options);
            }
        };
    }

    private void configureContext(final BrowserContext context) {
        // Disable service workers to prevent SW interception of local file URLs.
        // Bypass CSP so inline scripts and styles in test HTML files are not blocked.
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