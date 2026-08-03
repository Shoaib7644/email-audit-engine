package com.acxiom.emailaudit.rules.util;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the preheader / preview-text block from a rendered email's raw
 * HTML source, in both raw-markup and decoded-plain-text forms.
 *
 * <h2>Expected markup</h2>
 * <p>This extractor targets the conventional ESP preheader pattern:</p>
 * <pre>{@code
 * <!-- previewText -->
 * <p style="display:none; mso-hide:all; ..." class="hidden">
 *     Actual preheader message&nbsp;&zwnj;&nbsp;&zwnj;...(padding)...
 * </p>
 * }</pre>
 *
 * <h2>Padding suffix</h2>
 * <p>ESPs commonly append a long chain of {@code &nbsp;&zwnj;} (or their
 * decoded Unicode equivalents, U+00A0 and U+200C) after the authored message
 * to control how much preview text inbox providers display. This is
 * deliberate content, not accidental whitespace, so
 * {@link #extractAuthoredText(String)} strips it before returning the
 * message — callers checking for accidental leading/trailing whitespace or
 * terminal punctuation should operate on the authored text, not the raw
 * block.</p>
 *
 * <h2>Thread safety</h2>
 * <p>Stateless; all patterns are compiled once at class-load time. Safe for
 * concurrent use.</p>
 */
public final class PreheaderExtractor {

    private PreheaderExtractor() {
        // no instances
    }

    // -------------------------------------------------------------------------
    // Block location patterns
    // -------------------------------------------------------------------------

    /** Preferred: the {@code <p>} block immediately following the ESP convention comment. */
    private static final Pattern COMMENT_ANCHORED_BLOCK = Pattern.compile(
            "<!--\\s*previewText\\s*-->\\s*<p\\b[^>]*>(.*?)</p>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Fallback: any {@code <p>} carrying a {@code class="hidden"} (or similar) marker. */
    private static final Pattern CLASS_HIDDEN_BLOCK = Pattern.compile(
            "<p\\b[^>]*class\\s*=\\s*\"[^\"]*\\bhidden\\b[^\"]*\"[^>]*>(.*?)</p>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Fallback: common email preheader block hidden with inline {@code display:none}. */
    private static final Pattern DISPLAY_NONE_DIV_BLOCK = Pattern.compile(
            "<div\\b(?=[^>]*style\\s*=\\s*\"[^\"]*display\\s*:\\s*none)"
                    + "[^>]*>(.*?)</div>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // -------------------------------------------------------------------------
    // Padding / entity patterns
    // -------------------------------------------------------------------------

    /** Matches one-or-more trailing padding tokens (encoded or decoded form) at the end of the string. */
    private static final Pattern TRAILING_PADDING = Pattern.compile(
            "(?i)(?:(?:&nbsp;|&#160;|&#xA0;|\\u00A0|&zwnj;|&#8204;|&#x200C;|\\u200C)\\s*)+$");

    private static final Pattern NUMERIC_ENTITY_DEC = Pattern.compile("&#(\\d+);");
    private static final Pattern NUMERIC_ENTITY_HEX = Pattern.compile("(?i)&#x([0-9a-f]+);");

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns the raw, un-decoded inner HTML of the preheader block exactly
     * as authored — including any padding suffix and HTML entities.
     * Intended for markup-level checks (e.g. unencoded-character scans)
     * where decoding would destroy the signal being checked for.
     *
     * @param pageHtml full page HTML source (e.g. from {@code page.content()})
     * @return raw inner HTML, or empty if no preheader block is found
     */
    public static Optional<String> extractRawHtml(final String pageHtml) {
        if (pageHtml == null || pageHtml.isBlank()) {
            return Optional.empty();
        }

        Matcher matcher = COMMENT_ANCHORED_BLOCK.matcher(pageHtml);
        if (matcher.find()) {
            return Optional.of(matcher.group(1));
        }

        matcher = CLASS_HIDDEN_BLOCK.matcher(pageHtml);
        if (matcher.find()) {
            return Optional.of(matcher.group(1));
        }

        matcher = DISPLAY_NONE_DIV_BLOCK.matcher(pageHtml);
        while (matcher.find()) {
            final String rawInnerHtml = matcher.group(1);
            final String authoredText = decodeEntities(stripTrailingPadding(rawInnerHtml)).strip();
            if (!authoredText.isEmpty()) {
                return Optional.of(rawInnerHtml);
            }
        }

        return Optional.empty();
    }

    /**
     * Returns the authored preheader message: HTML entities decoded, and any
     * trailing ESP padding chain (see class Javadoc) removed. Leading and
     * trailing plain whitespace, if present in the authored text itself, is
     * deliberately <strong>not</strong> stripped — callers checking for
     * accidental whitespace need to see it.
     *
     * @param pageHtml full page HTML source
     * @return authored text, or empty if no preheader block is found
     */
    public static Optional<String> extractAuthoredText(final String pageHtml) {
        return extractRawHtml(pageHtml)
                .map(PreheaderExtractor::stripTrailingPadding)
                .map(PreheaderExtractor::decodeEntities);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static String stripTrailingPadding(final String rawInnerHtml) {
        return TRAILING_PADDING.matcher(rawInnerHtml).replaceAll("");
    }

    /**
     * Decodes the common named entities plus numeric (decimal/hex) character
     * references. This is intentionally not a full HTML entity table — only
     * what realistically appears in preheader copy.
     */
    private static String decodeEntities(final String html) {
        String result = html;

        result = NUMERIC_ENTITY_DEC.matcher(result).replaceAll(mr ->
                new String(Character.toChars(Integer.parseInt(mr.group(1)))));
        result = NUMERIC_ENTITY_HEX.matcher(result).replaceAll(mr ->
                new String(Character.toChars(Integer.parseInt(mr.group(1), 16))));

        result = result
                .replace("&nbsp;", " ")
                .replace("&zwnj;", "")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&#39;", "'");

        // Strip any incidental inline tags (e.g. <span>) — preheader blocks
        // are expected to be plain text but some templates wrap spans.
        result = result.replaceAll("<[^>]+>", "");

        return result;
    }
}
