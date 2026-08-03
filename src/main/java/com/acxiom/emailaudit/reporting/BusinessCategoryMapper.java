package com.acxiom.emailaudit.reporting;

import java.util.Map;

/**
 * Maps technical audit rule IDs to business-friendly category names.
 *
 * <p>This is a pure lookup — no finding analysis, no severity logic,
 * no business wording beyond the category label itself.
 * One responsibility: rule ID → category name.</p>
 *
 * <h2>What changed and why</h2>
 * <ul>
 *   <li><strong>{@code BROKEN_ANCHOR}</strong> was mapped to
 *       {@code "Legal — Unsubscribe Link"}. That rule validates in-page
 *       fragment links (e.g. {@code href="#section"} resolving to a
 *       matching {@code id} on the page) — it has nothing to do with
 *       unsubscribe compliance. A finding like "Jump to membership
 *       details → #membership-details does not exist" showing up under a
 *       "Legal — Unsubscribe Link" category is actively misleading. Now
 *       mapped to {@code "Inventory & Inspect Links"}, alongside
 *       {@code LINK_VALIDATION} — both are link-integrity checks.</li>
 *   <li><strong>{@code LINK_TEXT_VALIDATION}</strong> was mapped to
 *       {@code "Tracking Links & CTAs"}. That rule flags generic anchor
 *       text (e.g. "click here"); it isn't a CTA or tracking-parameter
 *       check. Moved to {@code "Inventory & Inspect Links"} for the same
 *       reason as above.</li>
 *   <li>Both moves also bring the Excel category scheme in line with
 *       {@link ReportSectionMapper}, which already groups both of these
 *       rule IDs under {@code ReportSection.LINKS} for the dashboard — so
 *       the two reports no longer disagree on where these findings belong.</li>
 * </ul>
 *
 * <p>Stateless and thread-safe.</p>
 */
public final class BusinessCategoryMapper {

    private static final String DEFAULT_CATEGORY = "Other Validation";

    /**
     * Immutable rule-ID → business category map.
     * Mirrors the category groupings used in the dashboard sidebar.
     */
    private static final Map<String, String> CATEGORY_MAP = Map.ofEntries(

            Map.entry("LINK_VALIDATION",      "Inventory & Inspect Links"),
            // Moved from "Tracking Links & CTAs" — this rule flags generic
            // link text (e.g. "click here"), not CTA or tracking issues.
            Map.entry("LINK_TEXT_VALIDATION",  "Inventory & Inspect Links"),
            Map.entry("CTA_VALIDATION",        "Tracking Links & CTAs"),

            Map.entry("DUPLICATE_ID",          "Broken HTML Codes"),
            Map.entry("HEADING_HIERARCHY",     "Broken HTML Codes"),

            Map.entry("ALT_TEXT_VALIDATION",   "Image Inventory & Rendering"),
            Map.entry("IMAGE_VALIDATION",      "Images"),
            // Groups with ALT_TEXT_VALIDATION under the same "Image
            // Inventory & Rendering" sidebar bucket — together they cover
            // both halves of image quality (accessible label present, and
            // the image actually renders).
            Map.entry("IMAGE_SRC_VALIDATION",  "Image Inventory & Rendering"),

            Map.entry("URL_DEFENSE",           "URL Defense Wrappers Cleanup"),

            Map.entry("ACCESSIBILITY_AXE",     "Accessibility Violations"),

            Map.entry("PRIVACY_LINK",          "Privacy Link Validations"),

            Map.entry("VIEW_ONLINE_LINK",      "Disclosure / View-in-Browser"),

            Map.entry("DISCLAIMER_PRESENT",    "Reply-to Text / Disclaimer"),
            Map.entry("PREHEADER_TRIM_VALIDATION",        "Header / Sender Details"),
            Map.entry("PREHEADER_PUNCTUATION_VALIDATION", "Header / Sender Details"),
            Map.entry("HEADER_EMOJI_ENCODING_VALIDATION", "Header / Sender Details"),

            // BROKEN_ANCHOR validates in-page fragment links (href="#id"),
            // not the unsubscribe link — grouped here with LINK_VALIDATION
            // rather than under a standalone "Legal — Unsubscribe Link"
            // category. (Previous version of this file described this move
            // in a comment but never actually added the entry below —
            // BROKEN_ANCHOR was silently falling through to the
            // DEFAULT_CATEGORY "Other Validation" fallback as a result.)
            Map.entry("BROKEN_ANCHOR",         "Inventory & Inspect Links"),

            Map.entry("CONTENT_VALIDATION",    "Macro Verification")
    );

    private BusinessCategoryMapper() {
        throw new UnsupportedOperationException(
                "BusinessCategoryMapper is a utility class and cannot be instantiated");
    }

    /**
     * Returns the business-friendly category name for the given rule ID.
     *
     * @param ruleId technical rule identifier (e.g. {@code "ACCESSIBILITY_AXE"})
     * @return business category name, or {@value DEFAULT_CATEGORY} if unknown
     */
    public static String getCategory(final String ruleId) {

        if (ruleId == null || ruleId.isBlank()) {
            return DEFAULT_CATEGORY;
        }

        return CATEGORY_MAP.getOrDefault(
                ruleId.trim(),
                DEFAULT_CATEGORY);
    }
}
