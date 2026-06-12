package com.acxiom.emailaudit.state;

import com.acxiom.emailaudit.config.ConfigurationManager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe, JSON-backed registry that tracks which HTML files have already
 * been processed by the audit pipeline.
 *
 * <h2>Responsibility</h2>
 * <p>Implements the <em>State Management</em> layer described in
 * {@code Architecture.md}: prevents duplicate processing across scheduler
 * runs by persisting a {@link ProcessedFileRecord} for every file that enters
 * the pipeline.</p>
 *
 * <h2>Storage format</h2>
 * <p>State is persisted as a single pretty-printed JSON file whose location is
 * derived from {@code ConfigurationManager} key {@code state.registry.file},
 * defaulting to {@code target/audit-state/registry.json}.  The file is written
 * atomically (write-to-temp + rename) to prevent corruption on JVM crash.</p>
 *
 * <h2>Thread safety</h2>
 * <ul>
 *   <li>In-memory state is held in a {@link ConcurrentHashMap}.</li>
 *   <li>A {@link ReentrantReadWriteLock} serialises disk reads/writes while
 *       allowing concurrent in-memory reads.</li>
 *   <li>The {@link ObjectMapper} instance is configured once and reused;
 *       Jackson's {@code ObjectMapper} is thread-safe after configuration.</li>
 * </ul>
 *
 * <h2>Duplicate detection strategy</h2>
 * <p>A file is considered already-processed when both its path <em>and</em>
 * SHA-256 content hash match a stored record whose status is
 * {@link ProcessedFileRecord.ProcessingStatus#SUCCESS}.  A previously-failed
 * file with an unchanged hash will be retried; a file whose content has
 * changed will always be reprocessed regardless of prior status.</p>
 */
public final class StateRegistry {

    private static final Logger log = LoggerFactory.getLogger(StateRegistry.class);

    private static final String CONFIG_KEY_REGISTRY_FILE = "state.registry.file";
    private static final String DEFAULT_REGISTRY_FILE    = "target/audit-state/registry.json";
    private static final String HASH_ALGORITHM           = "SHA-256";
    private static final TypeReference<Map<String, ProcessedFileRecord>> STATE_TYPE_REF =
            new TypeReference<>() {};

    // -------------------------------------------------------------------------
    // Infrastructure
    // -------------------------------------------------------------------------

    /** Shared, thread-safe ObjectMapper – configured once at class-load time. */
    private static final ObjectMapper MAPPER = buildMapper();

    private static ObjectMapper buildMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    /** Key: absolute file path string → Value: latest ProcessedFileRecord. */
    private final ConcurrentHashMap<String, ProcessedFileRecord> registry;

    /** Guards all disk I/O operations; read-lock covers in-memory queries. */
    private final ReentrantReadWriteLock ioLock = new ReentrantReadWriteLock();

    private final Path registryFile;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Creates a registry backed by the file path resolved from
     * {@link ConfigurationManager}. Existing state is loaded eagerly.
     */
    public StateRegistry() {
        this(resolveRegistryPath());
    }

    /**
     * Creates a registry backed by an explicit file path. Primarily used in tests.
     *
     * @param registryFile path to the JSON state file (need not exist yet)
     */
    public StateRegistry(final Path registryFile) {
        this.registryFile = registryFile.normalize().toAbsolutePath();
        this.registry     = new ConcurrentHashMap<>(loadFromDisk(this.registryFile));
        log.info("StateRegistry initialised – file: {}, entries loaded: {}",
                this.registryFile, this.registry.size());
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} when the file at {@code filePath} has already been
     * successfully processed with the same content it currently has on disk.
     *
     * <p>Files that previously {@code FAILED} or whose content has changed
     * since the last run are treated as unprocessed and will return
     * {@code false}.</p>
     *
     * @param filePath path to the HTML file to check
     * @return {@code true} if the file should be skipped
     */
    public boolean isAlreadyProcessed(final Path filePath) {
        final String key  = toKey(filePath);
        final String hash = computeHash(filePath);

        final ProcessedFileRecord existing = registry.get(key);
        if (existing == null) {
            log.debug("No prior record for '{}' – will process", filePath);
            return false;
        }

        if (!existing.getContentHash().equals(hash)) {
            log.info("Content hash changed for '{}' – will reprocess", filePath);
            return false;
        }

        if (!existing.isSuccessful()) {
            log.info("Prior record for '{}' has status {} – will retry", filePath, existing.getStatus());
            return false;
        }

        log.debug("Skipping already-processed file: {}", filePath);
        return true;
    }

    /**
     * Records a successful processing outcome for {@code filePath} and
     * persists the updated registry to disk.
     *
     * @param filePath path of the successfully processed file
     */
    public void markSuccess(final Path filePath) {
        final String hash   = computeHash(filePath);
        final ProcessedFileRecord record =
                ProcessedFileRecord.of(
                        filePath.toAbsolutePath().toString(),
                        hash,
                        ProcessedFileRecord.ProcessingStatus.SUCCESS);
        persist(toKey(filePath), record);
        log.info("Marked SUCCESS: {}", filePath);
    }

    /**
     * Records a failed processing outcome for {@code filePath} and persists
     * the updated registry to disk.
     *
     * @param filePath     path of the file that failed
     * @param errorMessage description of the failure
     */
    public void markFailed(final Path filePath, final String errorMessage) {
        final String hash   = computeHash(filePath);
        final ProcessedFileRecord record =
                ProcessedFileRecord.failed(
                        filePath.toAbsolutePath().toString(),
                        hash,
                        errorMessage);
        persist(toKey(filePath), record);
        log.warn("Marked FAILED: {} – {}", filePath, errorMessage);
    }

    /**
     * Records a successful processing outcome for {@code filePath} using a
     * pre-computed content hash, avoiding a redundant disk read when the
     * caller (e.g. the orchestrator, via {@code AuditContext}) has already
     * hashed the file.
     *
     * <p>If {@code contentHash} is {@code null}, falls back to computing the
     * hash from disk as in {@link #markSuccess(Path)}.</p>
     *
     * @param filePath    path of the successfully processed file
     * @param contentHash pre-computed SHA-256 hex digest, or {@code null}
     */
    public void markSuccess(final Path filePath, final String contentHash) {
        final String hash = contentHash != null ? contentHash : computeHash(filePath);
        final ProcessedFileRecord record =
                ProcessedFileRecord.of(
                        filePath.toAbsolutePath().toString(),
                        hash,
                        ProcessedFileRecord.ProcessingStatus.SUCCESS);
        persist(toKey(filePath), record);
        log.info("Marked SUCCESS: {}", filePath);
    }

    /**
     * Records a failed processing outcome for {@code filePath} using a
     * pre-computed content hash, avoiding a redundant disk read when the
     * caller (e.g. the orchestrator, via {@code AuditContext}) has already
     * hashed the file.
     *
     * <p>If {@code contentHash} is {@code null}, falls back to computing the
     * hash from disk as in {@link #markFailed(Path, String)}.</p>
     *
     * @param filePath     path of the file that failed
     * @param contentHash  pre-computed SHA-256 hex digest, or {@code null}
     * @param errorMessage description of the failure
     */
    public void markFailed(final Path filePath, final String contentHash, final String errorMessage) {
        final String hash = contentHash != null ? contentHash : computeHash(filePath);
        final ProcessedFileRecord record =
                ProcessedFileRecord.failed(
                        filePath.toAbsolutePath().toString(),
                        hash,
                        errorMessage);
        persist(toKey(filePath), record);
        log.warn("Marked FAILED: {} – {}", filePath, errorMessage);
    }

    /**
     * Returns the most recent {@link ProcessedFileRecord} for {@code filePath},
     * or {@link Optional#empty()} if the file has never been processed.
     *
     * @param filePath path to query
     * @return optional record
     */
    public Optional<ProcessedFileRecord> getRecord(final Path filePath) {
        return Optional.ofNullable(registry.get(toKey(filePath)));
    }

    /**
     * Returns an unmodifiable snapshot of all records currently held in memory.
     *
     * @return unmodifiable map of path key → record
     */
    public Map<String, ProcessedFileRecord> snapshot() {
        return Collections.unmodifiableMap(registry);
    }

    /**
     * Removes the record for {@code filePath} from the in-memory registry and
     * persists the change. Forces the file to be reprocessed on next scan.
     *
     * @param filePath path whose record should be evicted
     * @return {@code true} if a record existed and was removed
     */
    public boolean evict(final Path filePath) {
        final String key    = toKey(filePath);
        final boolean found = registry.remove(key) != null;
        if (found) {
            flushToDisk();
            log.info("Evicted state record for: {}", filePath);
        }
        return found;
    }

    /**
     * Clears all in-memory records and deletes the backing JSON file.
     * Intended for integration test teardown.
     */
    public void clearAll() {
        final ReentrantReadWriteLock.WriteLock writeLock = ioLock.writeLock();
        writeLock.lock();
        try {
            registry.clear();
            Files.deleteIfExists(registryFile);
            log.warn("StateRegistry cleared – all records removed and registry file deleted");
        } catch (IOException e) {
            log.error("Failed to delete registry file: {}", registryFile, e);
        } finally {
            writeLock.unlock();
        }
    }

    // -------------------------------------------------------------------------
    // Internal – persistence
    // -------------------------------------------------------------------------

    private void persist(final String key, final ProcessedFileRecord record) {
        registry.put(key, record);
        flushToDisk();
    }

    /**
     * Writes the current in-memory registry to disk atomically.
     * Uses write-to-temp + atomic rename to prevent partial writes on crash.
     */
    private void flushToDisk() {
        final ReentrantReadWriteLock.WriteLock writeLock = ioLock.writeLock();
        writeLock.lock();
        try {
            ensureParentDirectories(registryFile);

            final Path tempFile = registryFile.resolveSibling(
                    registryFile.getFileName() + ".tmp");

            try (OutputStream out = Files.newOutputStream(tempFile,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                MAPPER.writeValue(out, registry);
            }

            atomicMove(tempFile, registryFile);
            log.debug("Registry flushed to disk – {} entries", registry.size());

        } catch (IOException e) {
            log.error("Failed to persist registry to '{}': {}", registryFile, e.getMessage(), e);
        } finally {
            writeLock.unlock();
        }
    }

    private static void atomicMove(final Path source, final Path target) throws IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            log.warn("Atomic move not supported on this file system – falling back to non-atomic move");
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // -------------------------------------------------------------------------
    // Internal – load
    // -------------------------------------------------------------------------

    private Map<String, ProcessedFileRecord> loadFromDisk(final Path file) {
        final ReentrantReadWriteLock.ReadLock readLock = ioLock.readLock();
        readLock.lock();
        try {
            if (!Files.exists(file)) {
                log.info("No existing registry file found at '{}' – starting fresh", file);
                return new ConcurrentHashMap<>();
            }

            try (InputStream in = Files.newInputStream(file, StandardOpenOption.READ)) {
                final Map<String, ProcessedFileRecord> loaded = MAPPER.readValue(in, STATE_TYPE_REF);
                log.info("Loaded {} record(s) from registry file '{}'", loaded.size(), file);
                return new ConcurrentHashMap<>(loaded);
            } catch (IOException e) {
                log.error("Corrupted registry file '{}' – starting fresh. Cause: {}",
                        file, e.getMessage(), e);
                backupCorruptedFile(file);
                return new ConcurrentHashMap<>();
            }
        } finally {
            readLock.unlock();
        }
    }

    private void backupCorruptedFile(final Path file) {
        try {
            final Path backup = file.resolveSibling(
                    file.getFileName() + ".corrupted." + System.currentTimeMillis());
            Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
            log.warn("Corrupted registry backed up to: {}", backup);
        } catch (IOException ex) {
            log.error("Could not back up corrupted registry file: {}", file, ex);
        }
    }

    // -------------------------------------------------------------------------
    // Internal – hashing
    // -------------------------------------------------------------------------

    /**
     * Computes a SHA-256 hex digest of the file's contents.
     * Returns a sentinel value ({@code "UNREADABLE"}) if the file cannot be read,
     * so the file will always be scheduled for processing rather than silently skipped.
     */
    private static String computeHash(final Path filePath) {
        try {
            final MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            final byte[] bytes = Files.readAllBytes(filePath);
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK spec – this should never happen.
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        } catch (IOException e) {
            log.warn("Cannot read '{}' for hashing – treating as unreadable: {}", filePath, e.getMessage());
            return "UNREADABLE";
        }
    }

    // -------------------------------------------------------------------------
    // Internal – helpers
    // -------------------------------------------------------------------------

    private static String toKey(final Path filePath) {
        return filePath.normalize().toAbsolutePath().toString();
    }

    private static void ensureParentDirectories(final Path file) throws IOException {
        final Path parent = file.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
            log.debug("Created registry parent directories: {}", parent);
        }
    }

    private static Path resolveRegistryPath() {
        final String configured = ConfigurationManager.getInstance()
                .getOrDefault(CONFIG_KEY_REGISTRY_FILE, DEFAULT_REGISTRY_FILE);
        return Paths.get(configured);
    }
}