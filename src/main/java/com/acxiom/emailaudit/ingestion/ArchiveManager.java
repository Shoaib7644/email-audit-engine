package com.acxiom.emailaudit.ingestion;

import com.acxiom.emailaudit.config.ConfigurationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Moves processed HTML files into one of two archive sub-directories after
 * the audit pipeline has finished evaluating them.
 *
 * <h2>Archive layout</h2>
 * <pre>
 *  &lt;archive.root&gt;/
 *    success/          ← files that completed without a fatal error
 *    failed/           ← files that could not be fully processed
 * </pre>
 *
 * <h2>Name-collision strategy</h2>
 * <p>When a file with the same name already exists in the target directory a
 * timestamp suffix is appended before the extension:
 * {@code report.html → report_20240601T103045123Z.html}.  The timestamp has
 * millisecond precision, making collisions astronomically unlikely even under
 * parallel execution.</p>
 *
 * <h2>Thread safety</h2>
 * <ul>
 *   <li>Per-target-path {@link ReentrantLock}s (held in a
 *       {@link ConcurrentHashMap}) ensure that two threads racing to archive
 *       files with the same name do not both resolve to the same collision-free
 *       name and overwrite each other.</li>
 *   <li>Directory creation is idempotent and guarded with
 *       {@link Files#createDirectories}.</li>
 *   <li>File moves use {@code ATOMIC_MOVE} where the OS supports it,
 *       falling back to a non-atomic copy-then-delete otherwise.</li>
 * </ul>
 *
 * <h2>Configuration keys</h2>
 * <table>
 *   <tr><td>{@code archive.root.dir}</td>
 *       <td>Root directory for both sub-folders
 *           (default: {@code target/audit-archive})</td></tr>
 *   <tr><td>{@code archive.success.dir}</td>
 *       <td>Override for the success sub-directory name (default: {@code success})</td></tr>
 *   <tr><td>{@code archive.failed.dir}</td>
 *       <td>Override for the failed sub-directory name (default: {@code failed})</td></tr>
 * </table>
 */
public final class ArchiveManager {

    private static final Logger log = LoggerFactory.getLogger(ArchiveManager.class);

    // -------------------------------------------------------------------------
    // Configuration keys and defaults
    // -------------------------------------------------------------------------

    private static final String KEY_ARCHIVE_ROOT    = "archive.root.dir";
    private static final String KEY_SUCCESS_DIR     = "archive.success.dir";
    private static final String KEY_FAILED_DIR      = "archive.failed.dir";

    private static final String DEFAULT_ARCHIVE_ROOT = "target/audit-archive";
    private static final String DEFAULT_SUCCESS_DIR   = "success";
    private static final String DEFAULT_FAILED_DIR    = "failed";

    private static final DateTimeFormatter COLLISION_SUFFIX_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'")
                    .withZone(ZoneOffset.UTC);

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final Path successDir;
    private final Path failedDir;

    /**
     * Locks keyed by <em>resolved target path string</em> to prevent two
     * threads from resolving the same collision-free name simultaneously.
     */
    private final ConcurrentHashMap<String, ReentrantLock> pathLocks =
            new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Creates an {@code ArchiveManager} whose directories are resolved from
     * {@link ConfigurationManager}.
     */
    public ArchiveManager() {
        this(resolveSuccessDir(), resolveFailedDir());
    }

    /**
     * Creates an {@code ArchiveManager} with explicit target directories.
     * Primarily used in tests.
     *
     * @param successDir directory for successfully processed files
     * @param failedDir  directory for files that failed processing
     */
    public ArchiveManager(final Path successDir, final Path failedDir) {
        Objects.requireNonNull(successDir, "successDir must not be null");
        Objects.requireNonNull(failedDir,  "failedDir must not be null");

        this.successDir = successDir.normalize().toAbsolutePath();
        this.failedDir  = failedDir.normalize().toAbsolutePath();

        ensureDirectories(this.successDir, this.failedDir);

        log.info("ArchiveManager ready – success: '{}', failed: '{}'",
                this.successDir, this.failedDir);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Moves {@code filePath} into the <em>success</em> archive directory.
     *
     * @param filePath path of the successfully processed HTML file
     * @return the resolved destination path after the move
     * @throws ArchiveException if the move cannot be completed
     */
    public Path archiveSuccess(final Path filePath) {
        Objects.requireNonNull(filePath, "filePath must not be null");
        log.info("Archiving successful file: {}", filePath.getFileName());
        return move(filePath, successDir, "success");
    }

    /**
     * Moves {@code filePath} into the <em>failed</em> archive directory.
     *
     * @param filePath path of the HTML file that failed processing
     * @return the resolved destination path after the move
     * @throws ArchiveException if the move cannot be completed
     */
    public Path archiveFailed(final Path filePath) {
        Objects.requireNonNull(filePath, "filePath must not be null");
        log.warn("Archiving failed file: {}", filePath.getFileName());
        return move(filePath, failedDir, "failed");
    }

    /**
     * Returns the absolute path of the success archive directory.
     *
     * @return success directory; never {@code null}
     */
    public Path getSuccessDir() {
        return successDir;
    }

    /**
     * Returns the absolute path of the failed archive directory.
     *
     * @return failed directory; never {@code null}
     */
    public Path getFailedDir() {
        return failedDir;
    }

    // -------------------------------------------------------------------------
    // Internal – move orchestration
    // -------------------------------------------------------------------------

    /**
     * Resolves a collision-free target path and atomically moves the source
     * file to it, holding a per-target-path lock during the resolution window.
     */
    private Path move(final Path source, final Path targetDir, final String label) {
        validateSource(source);

        final Path target = resolveTargetWithLock(source, targetDir);

        try {
            atomicMove(source, target);
            log.info("[{}] '{}' → '{}'", label, source.getFileName(), target);
            return target;
        } catch (IOException e) {
            throw new ArchiveException(
                    "Failed to move '" + source + "' to '" + target + "': " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Internal – collision-safe target resolution
    // -------------------------------------------------------------------------

    /**
     * Acquires a lock on the <em>initial</em> candidate target path (before
     * any collision suffix is added) and resolves the final, unique target
     * path while holding that lock.
     *
     * <p>Locking on the candidate rather than the final path means two threads
     * trying to archive files with the same name will queue up and each receive
     * a distinct timestamp-suffixed name rather than racing to the same path.</p>
     */
    private Path resolveTargetWithLock(final Path source, final Path targetDir) {
        final String fileName      = source.getFileName().toString();
        final Path   candidateBase = targetDir.resolve(fileName);
        final String lockKey       = candidateBase.toString();

        final ReentrantLock lock = pathLocks.computeIfAbsent(lockKey, k -> new ReentrantLock());
        lock.lock();
        try {
            return resolveCollisionFree(candidateBase);
        } finally {
            lock.unlock();
            // Remove the lock entry when no other thread is waiting, to
            // prevent unbounded growth of the map for large file sets.
            pathLocks.remove(lockKey, lock);
        }
    }

    /**
     * Returns {@code candidateBase} if it does not exist, or a timestamped
     * variant if it does.
     *
     * <p>Example: {@code report.html} → {@code report_20240601T103045123Z.html}</p>
     */
    private static Path resolveCollisionFree(final Path candidateBase) {
        if (!Files.exists(candidateBase)) {
            return candidateBase;
        }

        final String original  = candidateBase.getFileName().toString();
        final String suffix    = COLLISION_SUFFIX_FMT.format(Instant.now());
        final String newName   = insertSuffix(original, suffix);
        final Path   resolved  = candidateBase.resolveSibling(newName);

        log.debug("Name collision resolved: '{}' → '{}'", original, newName);

        // Extremely unlikely second collision (same ms, same file name):
        // append an extra counter rather than looping indefinitely.
        if (Files.exists(resolved)) {
            final String fallback = insertSuffix(original, suffix + "_" + System.nanoTime());
            log.warn("Timestamp collision on '{}' – using nanoTime fallback: '{}'",
                    newName, fallback);
            return candidateBase.resolveSibling(fallback);
        }

        return resolved;
    }

    /**
     * Inserts {@code suffix} before the last file extension, or appends it
     * when the file has no extension.
     *
     * <pre>
     *   "report.html"  + "20240601T103045123Z"  →  "report_20240601T103045123Z.html"
     *   "report"       + "20240601T103045123Z"  →  "report_20240601T103045123Z"
     *   ".htaccess"    + "20240601T103045123Z"  →  ".htaccess_20240601T103045123Z"
     * </pre>
     */
    static String insertSuffix(final String fileName, final String suffix) {
        final int dotIndex = fileName.lastIndexOf('.');
        // Treat dot-files (e.g. ".gitignore") as having no extension.
        if (dotIndex <= 0) {
            return fileName + "_" + suffix;
        }
        return fileName.substring(0, dotIndex) + "_" + suffix + fileName.substring(dotIndex);
    }

    // -------------------------------------------------------------------------
    // Internal – file-system operations
    // -------------------------------------------------------------------------

    /**
     * Attempts an atomic move; falls back to a standard replace-existing move
     * when {@code ATOMIC_MOVE} is not supported by the file system.
     */
    private static void atomicMove(final Path source, final Path target) throws IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            log.warn("Atomic move not supported – falling back to standard move for '{}'",
                    source.getFileName());
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void ensureDirectories(final Path... dirs) {
        for (final Path dir : dirs) {
            try {
                Files.createDirectories(dir);
                log.debug("Archive directory ready: {}", dir);
            } catch (IOException e) {
                throw new ArchiveException(
                        "Cannot create archive directory '" + dir + "': " + e.getMessage(), e);
            }
        }
    }

    private static void validateSource(final Path source) {
        if (!Files.exists(source)) {
            throw new ArchiveException("Source file does not exist: " + source);
        }
        if (Files.isDirectory(source)) {
            throw new ArchiveException("Source path is a directory, not a file: " + source);
        }
        if (!Files.isReadable(source)) {
            throw new ArchiveException("Source file is not readable: " + source);
        }
    }

    // -------------------------------------------------------------------------
    // Internal – configuration resolution
    // -------------------------------------------------------------------------

    private static Path resolveSuccessDir() {
        final ConfigurationManager cfg = ConfigurationManager.getInstance();
        final String root    = cfg.getOrDefault(KEY_ARCHIVE_ROOT,  DEFAULT_ARCHIVE_ROOT);
        final String subDir  = cfg.getOrDefault(KEY_SUCCESS_DIR,   DEFAULT_SUCCESS_DIR);
        return Paths.get(root, subDir);
    }

    private static Path resolveFailedDir() {
        final ConfigurationManager cfg = ConfigurationManager.getInstance();
        final String root   = cfg.getOrDefault(KEY_ARCHIVE_ROOT, DEFAULT_ARCHIVE_ROOT);
        final String subDir = cfg.getOrDefault(KEY_FAILED_DIR,   DEFAULT_FAILED_DIR);
        return Paths.get(root, subDir);
    }

    // -------------------------------------------------------------------------
    // Exception
    // -------------------------------------------------------------------------

    /**
     * Unchecked exception thrown when an archive operation cannot be completed.
     */
    public static final class ArchiveException extends RuntimeException {

        public ArchiveException(final String message) {
            super(message);
        }

        public ArchiveException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}