package com.acxiom.emailaudit.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Central, thread-safe configuration facade for the audit framework.
 *
 * <p>Initialization is lazy and idempotent: the first call to {@link #getInstance()}
 * loads {@code application.properties} via {@link PropertyLoader}; every subsequent
 * call returns the same singleton without re-reading the file.</p>
 *
 * <p>System properties and environment variables act as overrides in that order of
 * precedence: <em>system property &gt; environment variable &gt; application.properties</em>.
 * This lets CI pipelines inject values without modifying the properties file.</p>
 */
public final class ConfigurationManager {

    private static final Logger log = LoggerFactory.getLogger(ConfigurationManager.class);

    // -------------------------------------------------------------------------
    // Known configuration keys (avoids magic strings across the codebase)
    // -------------------------------------------------------------------------

    /** Base URL of the application under test. */
    public static final String KEY_BASE_URL = "app.base.url";

    /** Playwright browser to use: chromium | firefox | webkit. */
    public static final String KEY_BROWSER = "playwright.browser";

    /** Whether to run the browser in headless mode. */
    public static final String KEY_HEADLESS = "playwright.headless";

    /** Default timeout for Playwright actions in milliseconds. */
    public static final String KEY_TIMEOUT_MS = "playwright.timeout.ms";

    /** Number of parallel TestNG threads. */
    public static final String KEY_THREAD_COUNT = "testng.thread.count";

    /** Output directory for ExtentReports artefacts. */
    public static final String KEY_REPORT_DIR = "report.output.dir";

    /** Screenshot mode: ON_FAILURE | ALWAYS | NEVER. */
    public static final String KEY_SCREENSHOT_MODE = "report.screenshot.mode";

    /** Comma-separated list of scheduler cron expressions or intervals. */
    public static final String KEY_SCHEDULER_CRON = "scheduler.cron";

    // -------------------------------------------------------------------------
    // Singleton machinery – holder pattern is both lazy and thread-safe without
    // synchronisation overhead on the hot read path.
    // -------------------------------------------------------------------------

    private static final AtomicReference<ConfigurationManager> INSTANCE_REF =
            new AtomicReference<>();

    private final Properties properties;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    private ConfigurationManager(final Properties properties) {
        this.properties = properties;
    }

    /**
     * Returns the singleton {@link ConfigurationManager}, initialising it from
     * {@code application.properties} on the first call.
     *
     * @return singleton instance; never {@code null}
     */
    public static ConfigurationManager getInstance() {
        ConfigurationManager existing = INSTANCE_REF.get();
        if (existing != null) {
            return existing;
        }

        // Construct outside the CAS loop so that PropertyLoader exceptions
        // propagate cleanly without leaving a partial state.
        final ConfigurationManager candidate =
                new ConfigurationManager(new PropertyLoader().load());

        if (INSTANCE_REF.compareAndSet(null, candidate)) {
            log.info("ConfigurationManager initialised (application.properties loaded)");
            return candidate;
        }

        // Another thread won the race – discard our candidate.
        return INSTANCE_REF.get();
    }

    /**
     * Initialises (or replaces) the singleton with a custom {@link PropertyLoader}.
     * Primarily intended for integration tests that need an alternate config file.
     *
     * @param loader non-null loader
     * @return the new singleton instance
     */
    public static ConfigurationManager initialise(final PropertyLoader loader) {
        if (loader == null) {
            throw new IllegalArgumentException("loader must not be null");
        }
        final ConfigurationManager instance =
                new ConfigurationManager(loader.load());
        INSTANCE_REF.set(instance);
        log.info("ConfigurationManager re-initialised via custom PropertyLoader");
        return instance;
    }

    /**
     * Resets the singleton – <strong>test use only</strong>.
     * Calling this in production will force a full reload on the next access.
     */
    static void reset() {
        INSTANCE_REF.set(null);
        log.warn("ConfigurationManager reset – singleton cleared");
    }

    // -------------------------------------------------------------------------
    // Public accessors
    // -------------------------------------------------------------------------

    /**
     * Retrieves a configuration value by key, applying the override chain:
     * system property → environment variable → application.properties.
     *
     * @param key the property key; must not be {@code null}
     * @return an {@link Optional} containing the resolved value, or empty if absent
     */
    public Optional<String> get(final String key) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }

        // 1. System property (-Dkey=value)
        final String systemValue = System.getProperty(key);
        if (systemValue != null) {
            log.trace("Key '{}' resolved from system properties", key);
            return Optional.of(systemValue.trim());
        }

        // 2. Environment variable (KEY_WITH_DOTS replaced by underscores, uppercased)
        final String envKey = key.replace('.', '_').replace('-', '_').toUpperCase();
        final String envValue = System.getenv(envKey);
        if (envValue != null) {
            log.trace("Key '{}' resolved from environment variable '{}'", key, envKey);
            return Optional.of(envValue.trim());
        }

        // 3. application.properties
        final String fileValue = properties.getProperty(key);
        if (fileValue != null) {
            log.trace("Key '{}' resolved from application.properties", key);
            return Optional.of(fileValue.trim());
        }

        log.debug("Key '{}' not found in any configuration source", key);
        return Optional.empty();
    }

    /**
     * Returns the value for {@code key}, or {@code defaultValue} when absent.
     *
     * @param key          the property key
     * @param defaultValue fallback value
     * @return resolved value or {@code defaultValue}
     */
    public String getOrDefault(final String key, final String defaultValue) {
        return get(key).orElse(defaultValue);
    }

    /**
     * Returns the value for {@code key} as a required string.
     *
     * @param key the property key
     * @return non-null, non-blank value
     * @throws MissingConfigurationException if the key is absent from all sources
     */
    public String getRequired(final String key) {
        return get(key).orElseThrow(() ->
                new MissingConfigurationException(
                        "Required configuration key not found: " + key));
    }

    /**
     * Parses the value for {@code key} as an {@code int}.
     *
     * @param key          the property key
     * @param defaultValue fallback when the key is absent
     * @return parsed integer
     * @throws ConfigurationTypeException if the value cannot be parsed
     */
    public int getInt(final String key, final int defaultValue) {
        return get(key).map(v -> {
            try {
                return Integer.parseInt(v);
            } catch (NumberFormatException e) {
                throw new ConfigurationTypeException(
                        "Key '" + key + "' value '" + v + "' is not a valid integer", e);
            }
        }).orElse(defaultValue);
    }

    /**
     * Parses the value for {@code key} as a {@code long}.
     *
     * @param key          the property key
     * @param defaultValue fallback when the key is absent
     * @return parsed long
     */
    public long getLong(final String key, final long defaultValue) {
        return get(key).map(v -> {
            try {
                return Long.parseLong(v);
            } catch (NumberFormatException e) {
                throw new ConfigurationTypeException(
                        "Key '" + key + "' value '" + v + "' is not a valid long", e);
            }
        }).orElse(defaultValue);
    }

    /**
     * Parses the value for {@code key} as a {@code boolean}.
     * Accepts {@code "true"} / {@code "false"} (case-insensitive).
     *
     * @param key          the property key
     * @param defaultValue fallback when the key is absent
     * @return parsed boolean
     */
    public boolean getBoolean(final String key, final boolean defaultValue) {
        return get(key).map(v -> {
            if ("true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v)) {
                return Boolean.parseBoolean(v);
            }
            throw new ConfigurationTypeException(
                    "Key '" + key + "' value '" + v + "' is not a valid boolean");
        }).orElse(defaultValue);
    }

    // -------------------------------------------------------------------------
    // Convenience getters for well-known keys
    // -------------------------------------------------------------------------

    public String getBaseUrl() {
        return getRequired(KEY_BASE_URL);
    }

    public String getBrowser() {
        return getOrDefault(KEY_BROWSER, "chromium");
    }

    public boolean isHeadless() {
        return getBoolean(KEY_HEADLESS, true);
    }

    public long getTimeoutMs() {
        return getLong(KEY_TIMEOUT_MS, 30_000L);
    }

    public int getThreadCount() {
        return getInt(KEY_THREAD_COUNT, 1);
    }

    public String getReportOutputDir() {
        return getOrDefault(KEY_REPORT_DIR, "target/audit-reports");
    }

    public String getScreenshotMode() {
        return getOrDefault(KEY_SCREENSHOT_MODE, "ON_FAILURE");
    }

    public Optional<String> getSchedulerCron() {
        return get(KEY_SCHEDULER_CRON);
    }

    // -------------------------------------------------------------------------
    // Exceptions
    // -------------------------------------------------------------------------

    /** Thrown when a required configuration key is missing from all sources. */
    public static final class MissingConfigurationException extends RuntimeException {
        public MissingConfigurationException(final String message) {
            super(message);
        }
    }

    /** Thrown when a configuration value cannot be converted to the requested type. */
    public static final class ConfigurationTypeException extends RuntimeException {
        public ConfigurationTypeException(final String message) {
            super(message);
        }

        public ConfigurationTypeException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}