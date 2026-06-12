package com.acxiom.emailaudit.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class PropertyLoader {

    private static final Logger log = LoggerFactory.getLogger(PropertyLoader.class);
    private static final String DEFAULT_CONFIG_FILE = "application.properties";

    private final String resourcePath;

    /**
     * Creates a loader targeting {@code application.properties} on the classpath.
     */
    public PropertyLoader() {
        this(DEFAULT_CONFIG_FILE);
    }

    /**
     * Creates a loader targeting the given classpath resource.
     *
     * @param resourcePath classpath-relative path (e.g. {@code "config/test.properties"})
     */
    public PropertyLoader(final String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("resourcePath must not be null or blank");
        }
        this.resourcePath = resourcePath;
    }

    /**
     * Loads and returns a new {@link Properties} object.
     * Each invocation opens a fresh stream – callers own the returned instance.
     *
     * @return populated {@link Properties}; never {@code null}
     * @throws PropertyLoadException if the resource is missing or unreadable
     */
    public Properties load() {
        log.debug("Loading properties from classpath resource: {}", resourcePath);

        final Properties properties = new Properties();

        try (InputStream stream = resolveStream()) {
            if (stream == null) {
                throw new PropertyLoadException(
                        "Classpath resource not found: " + resourcePath);
            }
            properties.load(stream);
            log.info("Successfully loaded {} properties from '{}'",
                    properties.size(), resourcePath);
        } catch (IOException e) {
            throw new PropertyLoadException(
                    "Failed to read classpath resource: " + resourcePath, e);
        }

        return properties;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private InputStream resolveStream() {
        // Prefer the thread-context class loader so the resource is visible
        // even when the caller sits in a child class-loader (e.g. TestNG forks).
        final ClassLoader tcl = Thread.currentThread().getContextClassLoader();
        if (tcl != null) {
            final InputStream stream = tcl.getResourceAsStream(resourcePath);
            if (stream != null) {
                return stream;
            }
        }
        return PropertyLoader.class.getClassLoader().getResourceAsStream(resourcePath);
    }

    // -------------------------------------------------------------------------
    // Checked exception wrapper
    // -------------------------------------------------------------------------

    /**
     * Unchecked exception thrown when a properties file cannot be loaded.
     */
    public static final class PropertyLoadException extends RuntimeException {

        public PropertyLoadException(final String message) {
            super(message);
        }

        public PropertyLoadException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
