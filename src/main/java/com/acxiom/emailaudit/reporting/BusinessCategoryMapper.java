package com.acxiom.emailaudit.reporting;

import java.util.Map;

/**
 * Maps technical audit rule IDs to business-friendly category names.
 *
 * <p>This is a pure lookup — no finding analysis, no severity logic,
 * no business wording beyond the category label itself.
 * One responsibility: rule ID → category name.</p>
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
            Map.entry("LINK_TEXT_VALIDATION",  "Tracking Links & CTAs"),
            Map.entry("CTA_VALIDATION",        "Tracking Links & CTAs"),

            Map.entry("DUPLICATE_ID",          "Broken HTML Codes"),
            Map.entry("HEADING_HIERARCHY",     "Broken HTML Codes"),

            Map.entry("ALT_TEXT_VALIDATION",   "Image Inventory & Rendering"),

            Map.entry("URL_DEFENSE",           "URL Defense Wrappers Cleanup"),

            Map.entry("ACCESSIBILITY_AXE",     "Accessibility Violations"),

            Map.entry("PRIVACY_LINK",          "Privacy Link Validations"),

            Map.entry("VIEW_ONLINE_LINK",      "Disclosure / View-in-Browser"),

            Map.entry("DISCLAIMER_PRESENT",    "Reply-to Text / Disclaimer"),

            Map.entry("BROKEN_ANCHOR",         "Legal \u2014 Unsubscribe Link"),

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