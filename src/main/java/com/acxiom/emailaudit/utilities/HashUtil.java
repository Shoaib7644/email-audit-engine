package com.acxiom.emailaudit.utilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Stateless utility class providing SHA-256 hashing for files and strings.
 *
 * <h2>Thread safety</h2>
 * <p>All methods are static and stateless. {@link MessageDigest} instances are
 * obtained per-call because {@code MessageDigest} is <em>not</em> thread-safe;
 * obtaining a fresh instance is cheap and avoids synchronisation entirely.</p>
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li>File hashing uses a streaming {@link DigestInputStream} with a fixed
 *       8 KiB buffer, so arbitrarily large files are handled without loading
 *       them fully into heap.</li>
 *   <li>{@link HexFormat} (Java 17 built-in) produces lowercase hex output
 *       without any third-party dependency.</li>
 *   <li>{@link NoSuchAlgorithmException} is wrapped in an
 *       {@link IllegalStateException} – SHA-256 is mandated by the JDK spec
 *       (JCA, §3.2) and must always be available.</li>
 * </ul>
 */
public final class HashUtil {

    private static final Logger log = LoggerFactory.getLogger(HashUtil.class);

    private static final String ALGORITHM       = "SHA-256";
    private static final int    BUFFER_SIZE     = 8 * 1024; // 8 KiB
    private static final String SENTINEL_UNREADABLE = "UNREADABLE_" + "0".repeat(55);

    /** Utility class – no instances. */
    private HashUtil() {
        throw new UnsupportedOperationException("HashUtil is a utility class");
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Computes the SHA-256 hex digest of a file's contents using streaming I/O.
     *
     * @param filePath path to the file; must not be {@code null}
     * @return lowercase 64-character hex string
     * @throws HashComputationException if the file cannot be read
     */
    public static String hashFile(final Path filePath) {
        if (filePath == null) {
            throw new IllegalArgumentException("filePath must not be null");
        }

        log.debug("Computing SHA-256 for file: {}", filePath);

        final MessageDigest digest = newDigest();

        try (InputStream raw = Files.newInputStream(filePath);
             DigestInputStream dis = new DigestInputStream(raw, digest)) {

            final byte[] buffer = new byte[BUFFER_SIZE];
            // noinspection StatementWithEmptyBody
            while (dis.read(buffer) != -1) { /* digest fed via DigestInputStream */ }

        } catch (IOException e) {
            throw new HashComputationException(
                    "Cannot read file for hashing: " + filePath, e);
        }

        final String hash = HexFormat.of().formatHex(digest.digest());
        log.debug("SHA-256 [{}] = {}", filePath.getFileName(), hash);
        return hash;
    }

    /**
     * Computes the SHA-256 hex digest of a file's contents, returning a sentinel
     * value instead of throwing when the file is unreadable.
     *
     * <p>The sentinel ({@value #SENTINEL_UNREADABLE}) is designed to be
     * recognisable in logs but will never match a real hash, so unreadable
     * files are always re-scheduled for processing.</p>
     *
     * @param filePath path to the file
     * @return 64-character hex string or the sentinel value
     */
    public static String hashFileSafe(final Path filePath) {
        try {
            return hashFile(filePath);
        } catch (HashComputationException e) {
            log.warn("Could not hash '{}' – returning sentinel. Cause: {}",
                    filePath, e.getMessage());
            return SENTINEL_UNREADABLE;
        }
    }

    /**
     * Computes the SHA-256 hex digest of a UTF-8 encoded string.
     *
     * @param input string to hash; must not be {@code null}
     * @return lowercase 64-character hex string
     */
    public static String hashString(final String input) {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }

        final MessageDigest digest = newDigest();
        final byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    /**
     * Computes the SHA-256 hex digest of a raw byte array.
     *
     * @param bytes bytes to hash; must not be {@code null}
     * @return lowercase 64-character hex string
     */
    public static String hashBytes(final byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("bytes must not be null");
        }

        final MessageDigest digest = newDigest();
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    /**
     * Returns the sentinel value used by {@link #hashFileSafe(Path)} when a
     * file cannot be read. Callers can use this to detect unreadable-file
     * conditions stored in persistent state.
     *
     * @return sentinel string; never {@code null}
     */
    public static String unreadableSentinel() {
        return SENTINEL_UNREADABLE;
    }

    /**
     * Returns {@code true} when {@code hash} is the unreadable sentinel value.
     *
     * @param hash hash string to test
     * @return {@code true} if this is a sentinel, not a real digest
     */
    public static boolean isSentinel(final String hash) {
        return SENTINEL_UNREADABLE.equals(hash);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Allocates a fresh {@link MessageDigest} for SHA-256.
     * Called per operation because {@code MessageDigest} is not thread-safe.
     */
    static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK specification – unreachable in practice.
            throw new IllegalStateException(
                    "SHA-256 MessageDigest unavailable – JDK installation may be corrupt", e);
        }
    }

    // -------------------------------------------------------------------------
    // Exception
    // -------------------------------------------------------------------------

    /**
     * Unchecked exception thrown when a hash cannot be computed due to an I/O error.
     */
    public static final class HashComputationException extends RuntimeException {

        public HashComputationException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
