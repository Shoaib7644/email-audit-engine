package com.acxiom.emailaudit.reporting;

import java.util.Locale;

/**
 * Converts verbose technical findings into concise, business-friendly
 * summaries for use in the Business Impact column of the Excel report.
 *
 * <p>Changes from previous version:</p>
 * <ul>
 *   <li>All return strings rewritten to avoid technical terminology
 *       (no exception class names, no HTTP status codes, no HTML tag
 *       names, no WCAG rule IDs, no axe rule IDs).</li>
 *   <li>New patterns added: color-contrast, lorem ipsum / placeholder
 *       content, skipped heading level, URL defense wrappers, broken
 *       image source (IMAGE_SRC_VALIDATION).</li>
 *   <li>Existing patterns tightened to match more real-world variants.</li>
 *   <li>Order of checks preserved where precedence matters
 *       (e.g. privacy-specific checks before generic broken-link check;
 *       broken-image-source checked before the missing-alt-text check so
 *       the two distinct image failure modes don't collide).</li>
 * </ul>
 *
 * <p>The Technical Details column always receives the original raw
 * findings string — this class is only responsible for the summary.</p>
 *
 * <p>Stateless and thread-safe.</p>
 */
public final class FindingSummarizer {

    private FindingSummarizer() {
        // Utility class
    }

    /**
     * Converts a raw technical findings string into a concise
     * business-friendly summary.
     *
     * @param findings raw technical findings (may be {@code null} or blank)
     * @return business-friendly summary, or an empty string if no findings
     */
    public static String summarize(final String findings) {

        if (findings == null || findings.isBlank()) {
            return "";
        }

        final String n = findings.toLowerCase(Locale.ROOT);

        /* ── Unsubscribe link ── */
        if (n.contains("unsubscribe")) {
            if (n.contains("not found") || n.contains("missing")) {
                return "Unsubscribe link is missing.";
            }
            if (_isUnreachable(n)) {
                return "Unsubscribe destination cannot be reached.";
            }
            if (_isNotFound(n)) {
                return "Unsubscribe page not found.";
            }
            if (_isEmptyPage(n)) {
                return "Unsubscribe page appears empty.";
            }
            return "Unsubscribe link requires attention.";
        }

        /* ── Privacy policy (check before generic broken-link) ── */
        if (n.contains("privacy policy") || n.contains("privacy link")) {

            if (n.contains("not found") || n.contains("missing")) {
                return "Privacy policy link is missing.";
            }
            if (_isUnreachable(n)) {
                return "Privacy policy destination cannot be reached.";
            }
            if (_isNotFound(n)) {
                return "Privacy policy page not found.";
            }
            if (_isEmptyPage(n)) {
                return "Privacy policy page appears empty.";
            }
            return "Privacy policy link requires attention.";
        }

        /* ── View Online link (check before generic broken-link) ── */
        if (n.contains("view online") || n.contains("view in browser")) {

            if (n.contains("not found") || n.contains("missing")) {
                return "View in Browser link is missing.";
            }
            if (_isUnreachable(n)) {
                return "View in Browser destination cannot be reached.";
            }
            if (_isNotFound(n)) {
                return "View in Browser page not found.";
            }
            if (_isEmptyPage(n)) {
                return "View in Browser page appears empty.";
            }
            return "View in Browser link requires attention.";
        }

        /* ── Broken anchor / unsubscribe navigation ── */
        if (n.contains("broken anchor")
                || n.contains("has no element with id")
                || n.contains("anchor target")) {
            return "Internal email navigation link is broken.";
        }

        /* ── Broken image source (IMAGE_SRC_VALIDATION) — check before the
           missing-alt-text pattern below, since a broken src and a missing
           alt attribute are different failure modes that can both mention
           "image". ── */
        if (n.contains("image source validation")
                || n.contains("image failed to load")
                || n.contains("does not resolve to a valid image")
                || n.contains("0-width natural size")
                || n.contains("broken image")) {
            return "One or more images will not display for recipients.";
        }

        /* ── Missing ALT text ── */
        if (n.contains("missing alt")
                || n.contains("image-alt")
                || n.contains("alternate text")
                || n.contains("alt attribute")) {
            return "One or more images are missing descriptive text.";
        }

        /* ── URL defense wrappers ── */
        if (n.contains("url defense")
                || n.contains("urldefense")
                || n.contains("proofpoint")
                || n.contains("safelinks")) {
            return "Destination link is wrapped by a security filter.";
        }

        /* ── Unreachable destination (generic) ── */
        if (_isUnreachable(n)
                || n.contains("broken link")
                || n.contains("broken url")
                || n.contains("link(s) detected")) {
            return "Campaign destination cannot be reached.";
        }

        /* ── Page not found (generic) ── */
        if (_isNotFound(n)) {
            return "Destination page not found.";
        }

        /* ── Empty / thin page ── */
        if (_isEmptyPage(n)) {
            return "Destination page appears empty.";
        }

        /* ── Placeholder / lorem ipsum content ── */
        if (n.contains("lorem ipsum")
                || n.contains("placeholder")
                || n.contains("draft content")
                || n.contains("test content")) {
            return "Placeholder content detected — review before sending.";
        }

        /* ── Duplicate HTML identifiers ── */
        if (n.contains("duplicate id")
                || n.contains("duplicate identifier")) {
            return "Duplicate HTML identifier detected.";
        }

        /* ── Skipped / broken heading structure ── */
        if (n.contains("skipped heading")
                || n.contains("heading level")
                || n.contains("heading structure")
                || n.contains("heading hierarchy")) {
            return "Heading structure is not accessible.";
        }

        /* ── Missing primary heading ── */
        if (n.contains("missing <h1>")
                || n.contains("no top-level heading")
                || n.contains("page-has-heading-one")) {
            return "Email is missing a primary heading.";
        }

        /* ── Missing document title ── */
        if (n.contains("missing or blank <title>")
                || n.contains("missing title")
                || n.contains("document-title")) {
            return "Email is missing a document title.";
        }

        /* ── Colour contrast / readability ── */
        if (n.contains("color-contrast")
                || n.contains("colour contrast")
                || n.contains("contrast ratio")) {
            return "Text may not be readable for all recipients.";
        }

        /* ── Accessibility (generic — after specific axe checks above) ── */
        if (n.contains("accessibility")
                || n.contains("wcag")
                || n.contains("html-has-lang")
                || n.contains("landmark")
                || n.contains("region")
                || n.contains("aria")) {
            return "Email accessibility issue requires attention.";
        }

        /* ── CTA / call-to-action ── */
        if (n.contains("call-to-action")
                || n.contains("cta")) {
            return "Call-to-action destination requires attention.";
        }

        /* ── Generic link text ── */
        if (n.contains("generic link text")
                || n.contains("descriptive text")
                || n.contains("click here")
                || n.contains("read more")) {
            return "One or more links have unclear or non-descriptive link text.";
        }

        /* ── Disclaimer / do-not-reply ── */
        if (n.contains("disclaimer")
                || n.contains("do not reply")
                || n.contains("mailbox is not monitored")) {
            return "Required disclaimer information is missing.";
        }

        /* ── Content / macro placeholders ── */
        if (n.contains("content validation")
                || n.contains("required content")
                || n.contains("macro")) {
            return "Required email content is missing or unresolved.";
        }

        /* ── Fallback: return as-is so no finding is silently dropped ── */
        return findings;
    }

    /* ── Private pattern helpers ─────────────────────────────────── */

    /** True when the findings text suggests an unreachable host. */
    private static boolean _isUnreachable(final String n) {
        return n.contains("unknownhostexception")
                || n.contains("connection refused")
                || n.contains("connection timed out")
                || n.contains("unable to reach")
                || n.contains("network error");
    }

    /** True when the findings text suggests an HTTP 404 / not found. */
    private static boolean _isNotFound(final String n) {
        return n.contains("http 404")
                || n.contains("404 not found")
                || n.contains("status 404")
                || n.contains("page not found");
    }

    /** True when the findings text suggests an empty or thin page. */
    private static boolean _isEmptyPage(final String n) {
        return n.contains("little or no content")
                || n.contains("empty page")
                || n.contains("blank page")
                || n.contains("no visible content");
    }
}
