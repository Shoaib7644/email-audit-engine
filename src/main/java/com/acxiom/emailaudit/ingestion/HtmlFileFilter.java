package com.acxiom.emailaudit.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.function.Predicate;

/**
 * Stateless, thread-safe predicate that accepts only regular HTML files.
 *
 * <p>Acceptance criteria:
 * <ul>
 *   <li>Path must not be a directory (enforced by the caller via
 *       {@link java.nio.file.Files#isRegularFile(Path, java.nio.file.LinkOption...)},
 *       but guarded here defensively).</li>
 *   <li>File name must match the glob {@code *.html} or {@code *.htm}
 *       (case-insensitive on all platforms).</li>
 * </ul>
 *
 * <p>Implements {@link Predicate}{@code <Path>} so it composes cleanly with
 * {@link java.util.stream.Stream#filter(Predicate)} in {@link FileScanner}.
 */
public final class HtmlFileFilter implements Predicate<Path> {

    private static final Logger log = LoggerFactory.getLogger(HtmlFileFilter.class);

    // PathMatcher is thread-safe once constructed.
    private static final PathMatcher HTML_MATCHER =
            FileSystems.getDefault().getPathMatcher("glob:*.{html,HTML,htm,HTM}");

    /**
     * Returns {@code true} when {@code path} is a regular HTML/HTM file.
     *
     * @param path candidate path; must not be {@code null}
     * @return {@code true} if the path should be ingested
     */
    @Override
    public boolean test(final Path path) {
        if (path == null) {
            log.warn("Null path supplied to HtmlFileFilter – rejected");
            return false;
        }

        // Guard: reject directories even if the caller forgot to filter them.
        if (path.toFile().isDirectory()) {
            log.debug("Skipping directory: {}", path);
            return false;
        }

        final Path fileName = path.getFileName();
        if (fileName == null) {
            log.debug("Path has no file-name component – rejected: {}", path);
            return false;
        }

        final boolean accepted = HTML_MATCHER.matches(fileName);
        if (accepted) {
            log.debug("Accepted HTML file: {}", path);
        } else {
            log.debug("Rejected non-HTML file: {}", path);
        }
        return accepted;
    }
}
