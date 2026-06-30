package com.acxiom.emailaudit.rules;

import java.util.Objects;

/**
 * Utility class that builds consistently structured, business-friendly finding
 * messages for the HTML audit dashboard.
 *
 * <h2>Design goals</h2>
 * <ul>
 *   <li>Every finding produced by any {@link AuditRule} looks the same to the
 *       dashboard — labelled fields, human-readable language, no raw exception
 *       class names.</li>
 *   <li>Zero dependencies on any rule implementation; the class is stateless
 *       and package-private-friendly via its static factory methods.</li>
 *   <li>No changes to {@link RuleResult} — findings are still plain
 *       {@code String}s; this class simply standardises how those strings are
 *       composed before they are passed to {@link RuleResult#fail} or
 *       {@link RuleResult#error}.</li>
 * </ul>
 *
 * <h2>Usage — one call per finding</h2>
 * <pre>{@code
 * // Link check
 * String f = FindingFormatter.linkFinding()
 *         .title("View Online Link")
 *         .displayText("View Online")
 *         .href("https://company.com/view")
 *         .failed("HTTP 404 Not Found")
 *         .build();
 *
 * // Broken anchor
 * String f = FindingFormatter.brokenAnchor("Shop Now", "https://brand.com/shop", "HTTP 404 Not Found");
 *
 * // Privacy / unsubscribe link absent
 * String f = FindingFormatter.missingPrivacyLink("unsubscribe");
 *
 * // CTA issue
 * String f = FindingFormatter.ctaFinding("<a>", "Shop Now", "CTA link has no href");
 *
 * // Generic rule finding
 * String f = FindingFormatter.generic("MISSING_ALT", "3 image(s) are missing alt text");
 * }</pre>
 *
 * <h2>Thread safety</h2>
 * <p>All methods are stateless and safe for concurrent use.</p>
 */
public final class FindingFormatter {

    // ── Shared field labels ────────────────────────────────────────────────────

    private static final String LABEL_TITLE       = "%-18s %s%n";
    private static final String LABEL_DISPLAY     = "  Displayed Text  : %s%n";
    private static final String LABEL_DESTINATION = "  Destination     : %s%n";
    private static final String LABEL_VALIDATION  = "  Validation      : %s%n";
    private static final String LABEL_REASON      = "  Reason          : %s";
    private static final String LABEL_ELEMENT     = "  Element         : %s%n";
    private static final String LABEL_DETAIL      = "  Detail          : %s";
    private static final String LABEL_EXPECTED    = "  Expected        : %s%n";
    private static final String LABEL_FOUND       = "  Found           : %s%n";

    private static final String STATUS_FAILED  = "FAILED";
    private static final String STATUS_PASSED  = "PASSED";
    private static final String STATUS_MISSING = "MISSING";
    private static final String STATUS_PRESENT = "PRESENT";
    private static final String STATUS_HIDDEN  = "HIDDEN";
    private static final String STATUS_INVALID = "INVALID";

    /** Prevents instantiation — use the static factory methods. */
    private FindingFormatter() {}

    // =========================================================================
    // 1. Link findings  (href + display text + HTTP validation result)
    // =========================================================================

    /**
     * Starts a fluent builder for a fully described link finding.
     *
     * <p>Example output:
     * <pre>
     * View Online Link
     *   Displayed Text  : View Online
     *   Destination     : https://company.com/view
     *   Validation      : FAILED
     *   Reason          : HTTP 404 Not Found
     * </pre>
     *
     * @return a new {@link LinkFindingBuilder}
     */
    public static LinkFindingBuilder linkFinding() {
        return new LinkFindingBuilder();
    }

    /**
     * Convenience shorthand for a broken HTTP link.
     *
     * @param displayText visible anchor text (may be blank/null)
     * @param href        the broken URL
     * @param reason      HTTP status or connection error description
     * @return formatted finding string
     */
    public static String brokenLink(
            final String displayText,
            final String href,
            final String reason) {
        return linkFinding()
                .title("Broken Link")
                .displayText(displayText)
                .href(href)
                .failed(reason)
                .build();
    }

    /**
     * Convenience shorthand for a broken anchor (no destination).
     *
     * @param displayText visible anchor text
     * @param href        the broken or empty URL
     * @param reason      human-readable explanation
     * @return formatted finding string
     */
    public static String brokenAnchor(
            final String displayText,
            final String href,
            final String reason) {
        return linkFinding()
                .title("Broken Anchor")
                .displayText(displayText)
                .href(coalesce(href, "(none)"))
                .status(STATUS_INVALID)
                .reason(reason)
                .build();
    }

    // =========================================================================
    // 2. Privacy / compliance link findings
    // =========================================================================

    /**
     * Finding for a missing mandatory privacy or compliance link.
     *
     * <p>Example output:
     * <pre>
     * Missing Privacy Link
     *   Expected        : unsubscribe link (CAN-SPAM / GDPR)
     *   Found           : none
     *   Detail          : Add an anchor whose text or href contains 'unsubscribe', 'opt-out', or 'email preferences'
     * </pre>
     *
     * @param linkType human label, e.g. {@code "unsubscribe"} or
     *                 {@code "privacy policy"}
     * @return formatted finding string
     */
    public static String missingPrivacyLink(final String linkType) {
        final String type = coalesce(linkType, "compliance");
        return "Missing Privacy Link\n"
                + String.format(LABEL_EXPECTED, type + " link (CAN-SPAM / GDPR)")
                + String.format(LABEL_FOUND,    "none")
                + String.format(LABEL_DETAIL,
                "Add an anchor whose text or href contains 'unsubscribe', "
                        + "'opt-out', or 'email preferences'");
    }

    /**
     * Finding for an insecure (HTTP) link that should use HTTPS.
     *
     * @param href the insecure URL
     * @return formatted finding string
     */
    public static String insecureLink(final String href) {
        return linkFinding()
                .title("Insecure Link (HTTP)")
                .href(coalesce(href, "(unknown)"))
                .status(STATUS_INVALID)
                .reason("Link uses http:// — must be upgraded to https:// to prevent "
                        + "mixed-content warnings and protect recipients")
                .build();
    }

    // =========================================================================
    // 3. CTA findings
    // =========================================================================

    /**
     * Finding for a CTA element that fails one validation check.
     *
     * <p>Example output:
     * <pre>
     * CTA Validation Issue
     *   Element         : &lt;a&gt; "Shop Now"
     *   Validation      : FAILED
     *   Reason          : CTA link has no href
     * </pre>
     *
     * @param tag     HTML tag, e.g. {@code "<a>"} or {@code "<button>"}
     * @param text    visible label of the CTA (may be blank/null)
     * @param reason  human-readable description of the specific issue
     * @return formatted finding string
     */
    public static String ctaFinding(
            final String tag,
            final String text,
            final String reason) {

        final String elementLabel = buildCtaLabel(tag, text);
        return "CTA Validation Issue\n"
                + String.format(LABEL_ELEMENT,    elementLabel)
                + String.format(LABEL_VALIDATION, STATUS_FAILED)
                + String.format(LABEL_REASON,     coalesce(reason, "Unknown issue"));
    }

    /**
     * Finding for a CTA that is hidden from the recipient.
     *
     * @param tag  HTML tag
     * @param text visible label of the CTA
     * @return formatted finding string
     */
    public static String hiddenCta(final String tag, final String text) {
        final String elementLabel = buildCtaLabel(tag, text);
        return "CTA Visibility Issue\n"
                + String.format(LABEL_ELEMENT,    elementLabel)
                + String.format(LABEL_VALIDATION, STATUS_HIDDEN)
                + String.format(LABEL_REASON,
                "CTA is not visible to the recipient "
                        + "(display:none, visibility:hidden, or opacity:0)");
    }

    /**
     * Finding for a CTA with no readable label text.
     *
     * @param tag HTML tag
     * @return formatted finding string
     */
    public static String emptyCtaText(final String tag) {
        return ctaFinding(tag, null, "CTA has no visible text — recipients cannot read or understand this button");
    }

    /**
     * Finding for a button-styled {@code <a>} element with no href destination.
     *
     * @param text visible label of the CTA (may be blank/null)
     * @return formatted finding string
     */
    public static String ctaMissingHref(final String text) {
        return ctaFinding("a", text, "CTA link has no href — clicking this button does nothing");
    }

    // =========================================================================
    // 4. Generic / fallback finding
    // =========================================================================

    /**
     * Generic finding for rules that do not need full link or CTA structure.
     *
     * <p>Example output:
     * <pre>
     * [MISSING_ALT] 3 image(s) are missing alt text
     * </pre>
     *
     * @param ruleId  rule identifier; displayed as a prefix tag
     * @param message human-readable finding message
     * @return formatted finding string
     */
    public static String generic(final String ruleId, final String message) {
        final String tag = (ruleId != null && !ruleId.isBlank()) ? "[" + ruleId + "] " : "";
        return tag + coalesce(message, "(no detail provided)");
    }

    /**
     * Generic finding with a structured detail block but without link or CTA
     * context; useful for image, accessibility, or content rules.
     *
     * <p>Example output:
     * <pre>
     * Font Size Violation
     *   Element         : &lt;td style="font-size:9px"&gt;
     *   Detail          : Font size 9px is below the recommended minimum of 13px
     * </pre>
     *
     * @param title   short human-readable title for the finding
     * @param element CSS selector, tag, or other element descriptor
     * @param detail  explanation of the issue
     * @return formatted finding string
     */
    public static String structuredFinding(
            final String title,
            final String element,
            final String detail) {
        return coalesce(title, "Validation Issue") + "\n"
                + (element != null && !element.isBlank()
                ? String.format(LABEL_ELEMENT, element)
                : "")
                + String.format(LABEL_DETAIL, coalesce(detail, "(no detail provided)"));
    }

    // =========================================================================
    // LinkFindingBuilder – fluent API
    // =========================================================================

    /**
     * Fluent builder for a structured link finding.
     *
     * <p>Mandatory: at least {@link #title(String)} should be set.
     * All other fields are optional and fall back to sensible placeholders.
     */
    public static final class LinkFindingBuilder {

        private String title;
        private String displayText;
        private String href;
        private String status  = STATUS_FAILED;
        private String reason;

        private LinkFindingBuilder() {}

        /** Sets the human-readable title line, e.g. {@code "View Online Link"}. */
        public LinkFindingBuilder title(final String title) {
            this.title = title;
            return this;
        }

        /** Sets the anchor's visible display text. */
        public LinkFindingBuilder displayText(final String displayText) {
            this.displayText = displayText;
            return this;
        }

        /** Sets the anchor's href / destination URL. */
        public LinkFindingBuilder href(final String href) {
            this.href = href;
            return this;
        }

        /** Sets the validation status and reason in one call for FAILED outcomes. */
        public LinkFindingBuilder failed(final String reason) {
            this.status = STATUS_FAILED;
            this.reason = reason;
            return this;
        }

        /** Sets the validation status and reason in one call for PASSED outcomes. */
        public LinkFindingBuilder passed(final String note) {
            this.status = STATUS_PASSED;
            this.reason = note;
            return this;
        }

        /** Sets an explicit validation status string. */
        public LinkFindingBuilder status(final String status) {
            this.status = Objects.requireNonNull(status, "status must not be null");
            return this;
        }

        /** Sets the reason / explanation text. */
        public LinkFindingBuilder reason(final String reason) {
            this.reason = reason;
            return this;
        }

        /**
         * Builds and returns the formatted finding string.
         *
         * <p>Format:
         * <pre>
         * {title}
         *   Displayed Text  : {displayText}   ← omitted if null/blank
         *   Destination     : {href}           ← omitted if null/blank
         *   Validation      : {status}
         *   Reason          : {reason}         ← omitted if null/blank
         * </pre>
         */
        public String build() {
            final StringBuilder sb = new StringBuilder();
            sb.append(coalesce(title, "Link Finding")).append('\n');

            if (displayText != null && !displayText.isBlank()) {
                sb.append(String.format(LABEL_DISPLAY, displayText));
            }
            if (href != null && !href.isBlank()) {
                sb.append(String.format(LABEL_DESTINATION, href));
            }

            sb.append(String.format(LABEL_VALIDATION, coalesce(status, STATUS_FAILED)));

            if (reason != null && !reason.isBlank()) {
                sb.append(String.format(LABEL_REASON, reason));
            }

            return sb.toString().stripTrailing();
        }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /** Returns {@code value} when non-null and non-blank, otherwise {@code fallback}. */
    private static String coalesce(final String value, final String fallback) {
        return (value != null && !value.isBlank()) ? value : fallback;
    }

    /**
     * Builds a human-readable CTA element label such as:
     * {@code <a> "Shop Now"} or {@code <button> (empty text)}.
     */
    private static String buildCtaLabel(final String tag, final String text) {
        final String safeTag  = coalesce(tag, "?");
        final String safeText = (text != null && !text.isBlank())
                ? "\"" + text + "\""
                : "(empty text)";
        return String.format("<%s> %s", safeTag, safeText);
    }

    // =========================================================================
// 5. Alt text / image findings
// =========================================================================

    /**
     * Finding for a single image's alt-text validation outcome — PASS or FAIL.
     *
     * <p>Example PASS output:
     * <pre>
     * Alt Text Validation
     *   Image           : hero-banner.png
     *   Validation      : PASSED
     *   Reason          : Alt text is present.
     * </pre>
     *
     * <p>Example FAIL output:
     * <pre>
     * Alt Text Validation
     *   Image           : hero-banner.png
     *   Validation      : FAILED
     *   Reason          : Missing alt attribute.
     * </pre>
     *
     * @param imageRef a human-identifiable reference to the image — typically
     *                 its {@code src} attribute (may be blank/null; falls back
     *                 to "(no src attribute)")
     * @param passed   {@code true} for a PASSED outcome, {@code false} for FAILED
     * @param reason   business-readable explanation of the outcome
     * @return formatted finding string
     */
    public static String altTextFinding(
            final String imageRef,
            final boolean passed,
            final String reason) {
        final LinkFindingBuilder builder = linkFinding()
                .title("Alt Text Validation")
                .displayText(coalesce(imageRef, "(no src attribute)"));

        return passed
                ? builder.passed(reason).build()
                : builder.failed(reason).build();
    }
    // =========================================================================
// 5. View Online / disclosure findings
// =========================================================================

    /**
     * Finding for a "View Online" / browser-version link's validation outcome —
     * PASS or FAIL. Reuses {@link LinkFindingBuilder}; when {@code href} or
     * {@code displayText} are blank/null (e.g. the link could not be located on
     * the page at all), those lines are simply omitted from the output.
     *
     * <p>Example PASS output:
     * <pre>
     * View Online Link
     *   Displayed Text  : View Online
     *   Destination     : https://company.com/view
     *   Validation      : PASSED
     *   Reason          : Link resolved successfully.
     * </pre>
     *
     * <p>Example FAIL output:
     * <pre>
     * View Online Link
     *   Displayed Text  : View Online
     *   Destination     : https://company.com/view
     *   Validation      : FAILED
     *   Reason          : HTTP 404 Not Found
     * </pre>
     *
     * @param displayText visible anchor text (may be blank/null)
     * @param href        the link's destination URL (may be blank/null)
     * @param passed      {@code true} for a PASSED outcome, {@code false} for FAILED
     * @param reason      business-readable explanation of the outcome
     * @return formatted finding string
     */
    public static String viewOnlineFinding(
            final String displayText,
            final String href,
            final boolean passed,
            final String reason) {
        final LinkFindingBuilder builder = linkFinding()
                .title("View Online Link")
                .displayText(displayText)
                .href(href);

        return passed
                ? builder.passed(reason).build()
                : builder.failed(reason).build();
    }

    /**
     * Finding for a disclaimer / reply-to validation outcome — PASS or FAIL.
     * Reuses {@link LinkFindingBuilder} with no display text or destination set,
     * since a disclaimer check has no associated link to cite — only a
     * pass/fail outcome and a reason.
     *
     * <p>Example PASS output:
     * <pre>
     * Disclaimer Validation
     *   Validation      : PASSED
     *   Reason          : Required disclaimer text is present.
     * </pre>
     *
     * <p>Example FAIL output:
     * <pre>
     * Disclaimer Validation
     *   Validation      : FAILED
     *   Reason          : Mandatory legal disclaimer not found.
     * </pre>
     *
     * @param passed {@code true} for a PASSED outcome, {@code false} for FAILED
     * @param reason business-readable explanation of the outcome
     * @return formatted finding string
     */
    public static String disclaimerFinding(final boolean passed, final String reason) {
        final LinkFindingBuilder builder = linkFinding().title("Disclaimer Validation");

        return passed
                ? builder.passed(reason).build()
                : builder.failed(reason).build();
    }
}