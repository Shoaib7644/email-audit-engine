package com.acxiom.emailaudit.ingestion;

import com.acxiom.emailaudit.utilities.HashUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, in-memory duplicate detector for HTML files within a single
 * pipeline execution.
 *
 * <h2>Responsibility</h2>
 * <p>Detects files whose <em>content</em> is identical to a file already seen
 * in the current run, regardless of file name or path.  Content identity is
 * determined by SHA-256 hash computed via {@link HashUtil}.</p>
 *
 * <h2>Scope</h2>
 * <p>This class operates at the <em>ingestion</em> layer and covers within-run
 * deduplication only.  Cross-run deduplication (i.e. skipping files processed
 * in a previous scheduler cycle) is handled by the
 * {@code StateRegistry} in the State Management layer.</p>
 *
 * <h2>Thread safety</h2>
 * <ul>
 *   <li>Internal state is held in {@link ConcurrentHashMap} key-sets, which
 *       provide atomic check-and-insert via
 *       {@link ConcurrentHashMap#putIfAbsent}.</li>
 *   <li>No external synchronisation is required by callers.</li>
 *   <li>All public methods are safe for concurrent use from multiple
 *       TestNG threads.</li>
 * </ul>
 *
 * <h2>Duplicate decision logic</h2>
 * <pre>
 *  isDuplicate(path)?
 *    ├─ hash(path) already seen  →  true  (same content seen before)
 *    └─ hash(path) not seen yet  →  false (register and continue)
 * </pre>
 */
public final class DuplicateDetector {

    private static final Logger log = LoggerFactory.getLogger(DuplicateDetector.class);

    /**
     * Stores hashes of every file accepted so far in this run.
     * Key: SHA-256 hex digest → Value: absolute path of the first file seen with that hash.
     */
    private final ConcurrentHashMap<String, String> seenHashes = new ConcurrentHashMap<>();

    /**
     * Stores absolute path strings that were identified as duplicates.
     * Used for reporting and diagnostics only.
     */
    private final Set<String> duplicatePaths =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Checks whether {@code filePath} is a duplicate of a file already seen
     * in this run, registering it if it is not.
     *
     * <p>This is the primary method for pipeline use. It is atomic: the check
     * and the registration happen in a single operation with no window for a
     * race condition.</p>
     *
     * @param filePath path to the HTML file to test; must not be {@code null}
     * @return {@code true} if the file's content has already been seen; the
     *         file should be skipped
     */
    public boolean isDuplicate(final Path filePath) {
        if (filePath == null) {
            throw new IllegalArgumentException("filePath must not be null");
        }

        final String hash        = HashUtil.hashFileSafe(filePath);
        final String absolutePath = filePath.normalize().toAbsolutePath().toString();

        if (HashUtil.isSentinel(hash)) {
            // Unreadable files are never treated as duplicates – they will be
            // marked FAILED by the pipeline; let them through.
            log.warn("File '{}' could not be hashed – not treating as duplicate", filePath);
            return false;
        }

        // putIfAbsent returns null  → this hash was not present → file is new.
        // putIfAbsent returns value → this hash already existed → file is a duplicate.
        final String firstSeen = seenHashes.putIfAbsent(hash, absolutePath);

        if (firstSeen == null) {
            log.debug("New content registered – hash {} → '{}'", abbreviated(hash), filePath.getFileName());
            return false;
        }

        // Duplicate detected.
        duplicatePaths.add(absolutePath);
        log.warn("Duplicate content detected: '{}' is identical to '{}' (hash: {})",
                filePath.getFileName(), firstSeen, abbreviated(hash));
        return true;
    }

    /**
     * Returns the absolute path of the <em>first</em> file seen with the same
     * content as {@code filePath}, or {@link Optional#empty()} if no duplicate
     * has been detected for that file.
     *
     * <p>Note: this method hashes {@code filePath} on every call; prefer caching
     * the result of {@link #isDuplicate} in hot loops.</p>
     *
     * @param filePath path to check
     * @return optional path of the original file
     */
    public Optional<String> getOriginalFor(final Path filePath) {
        if (filePath == null) {
            throw new IllegalArgumentException("filePath must not be null");
        }
        final String hash = HashUtil.hashFileSafe(filePath);
        if (HashUtil.isSentinel(hash)) {
            return Optional.empty();
        }
        return Optional.ofNullable(seenHashes.get(hash));
    }

    /**
     * Returns an unmodifiable view of the absolute paths of all files that
     * were identified as duplicates during this run.
     *
     * @return unmodifiable set; never {@code null}
     */
    public Set<String> getDuplicatePaths() {
        return Collections.unmodifiableSet(duplicatePaths);
    }

    /**
     * Returns the total number of unique content hashes registered so far.
     *
     * @return count of distinct files seen
     */
    public int uniqueCount() {
        return seenHashes.size();
    }

    /**
     * Returns the total number of duplicate files detected so far.
     *
     * @return duplicate count
     */
    public int duplicateCount() {
        return duplicatePaths.size();
    }

    /**
     * Returns {@code true} if no files have been registered yet.
     *
     * @return {@code true} when the detector is in its initial state
     */
    public boolean isEmpty() {
        return seenHashes.isEmpty();
    }

    /**
     * Clears all internal state, resetting the detector to its initial condition.
     * Intended for test teardown or between logical pipeline phases.
     */
    public void reset() {
        final int hadUnique     = seenHashes.size();
        final int hadDuplicates = duplicatePaths.size();
        seenHashes.clear();
        duplicatePaths.clear();
        log.info("DuplicateDetector reset – cleared {} unique hash(es) and {} duplicate path(s)",
                hadUnique, hadDuplicates);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /** Returns the first 12 characters of a hash for readable log output. */
    private static String abbreviated(final String hash) {
        return hash.length() > 12 ? hash.substring(0, 12) + "…" : hash;
    }
}
