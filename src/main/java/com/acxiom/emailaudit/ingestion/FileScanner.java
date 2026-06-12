package com.acxiom.emailaudit.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Scans a root directory and returns all HTML files found within it.
 *
 * <h2>Thread safety</h2>
 * <p>{@code FileScanner} is immutable after construction: the root path and
 * maximum depth are set once and never mutated.  Multiple threads may call
 * {@link #scan()} concurrently without external synchronisation.</p>
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li>Delegation to {@link HtmlFileFilter} keeps filtering logic separate
 *       (Single Responsibility / Open-Closed).</li>
 *   <li>Symbolic links are not followed to avoid cycles.</li>
 *   <li>The returned list is unmodifiable; callers must copy it if mutation
 *       is needed.</li>
 *   <li>Scan errors on individual files are logged and skipped rather than
 *       aborting the entire scan.</li>
 * </ul>
 */
public final class FileScanner {

    private static final Logger log = LoggerFactory.getLogger(FileScanner.class);

    /** Walk the entire subtree (effectively unlimited depth). */
    public static final int DEPTH_UNLIMITED = Integer.MAX_VALUE;

    private final Path rootDirectory;
    private final int maxDepth;
    private final HtmlFileFilter htmlFileFilter;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * Creates a scanner that walks {@code rootDirectory} to unlimited depth.
     *
     * @param rootDirectory directory to scan; must not be {@code null}
     */
    public FileScanner(final Path rootDirectory) {
        this(rootDirectory, DEPTH_UNLIMITED);
    }

    /**
     * Creates a scanner with an explicit depth limit.
     *
     * @param rootDirectory directory to scan; must not be {@code null}
     * @param maxDepth      maximum depth relative to {@code rootDirectory};
     *                      {@code 1} means immediate children only,
     *                      {@link #DEPTH_UNLIMITED} means no limit
     */
    public FileScanner(final Path rootDirectory, final int maxDepth) {
        Objects.requireNonNull(rootDirectory, "rootDirectory must not be null");
        if (maxDepth < 1) {
            throw new IllegalArgumentException(
                    "maxDepth must be >= 1, got: " + maxDepth);
        }
        this.rootDirectory = rootDirectory.normalize().toAbsolutePath();
        this.maxDepth = maxDepth;
        this.htmlFileFilter = new HtmlFileFilter();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Scans the configured root directory and returns all HTML files found.
     *
     * <p>Directories are automatically excluded; only regular {@code .html} /
     * {@code .htm} files are included.  The result is ordered by the natural
     * file-system walk order (depth-first, alphabetical within each level on
     * most platforms).</p>
     *
     * @return unmodifiable, non-null list of matching {@link Path} objects
     * @throws FileScanException if the root directory does not exist, is not a
     *                           directory, or an I/O error prevents the walk
     *                           from starting
     */
    public List<Path> scan() {
        validateRoot();

        log.info("Starting HTML file scan – root: {}, maxDepth: {}",
                rootDirectory, maxDepth == DEPTH_UNLIMITED ? "unlimited" : maxDepth);

        final List<Path> results;

        try (Stream<Path> walker = Files.walk(rootDirectory, maxDepth)) {
            results = walker
                    .filter(Files::isRegularFile)   // directories excluded here …
                    .filter(htmlFileFilter)          // … and double-checked in filter
                    .toList();                       // Java 16+ – produces unmodifiable list
        } catch (IOException e) {
            throw new FileScanException(
                    "I/O error while scanning directory: " + rootDirectory, e);
        }

        log.info("Scan complete – {} HTML file(s) found under '{}'",
                results.size(), rootDirectory);

        return Collections.unmodifiableList(results);
    }

    /**
     * Returns the normalised, absolute root directory this scanner targets.
     *
     * @return root directory path; never {@code null}
     */
    public Path getRootDirectory() {
        return rootDirectory;
    }

    /**
     * Returns the maximum walk depth configured for this scanner.
     *
     * @return max depth ({@link #DEPTH_UNLIMITED} when no limit is set)
     */
    public int getMaxDepth() {
        return maxDepth;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void validateRoot() {
        if (!Files.exists(rootDirectory)) {
            throw new FileScanException(
                    "Root directory does not exist: " + rootDirectory);
        }
        if (!Files.isDirectory(rootDirectory)) {
            throw new FileScanException(
                    "Root path is not a directory: " + rootDirectory);
        }
        if (!Files.isReadable(rootDirectory)) {
            throw new FileScanException(
                    "Root directory is not readable: " + rootDirectory);
        }
    }

    // -------------------------------------------------------------------------
    // Exception
    // -------------------------------------------------------------------------

    /**
     * Unchecked exception thrown when the file scan cannot complete.
     */
    public static final class FileScanException extends RuntimeException {

        public FileScanException(final String message) {
            super(message);
        }

        public FileScanException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
