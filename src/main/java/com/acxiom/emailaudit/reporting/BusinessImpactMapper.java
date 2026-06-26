package com.acxiom.emailaudit.reporting;

import java.util.Map;

/**
 * Maps technical audit rule names to business-friendly descriptions
 * suitable for executive and stakeholder reporting.
 *
 * <p>This utility is intentionally stateless and thread-safe.</p>
 */
public final class BusinessImpactMapper {

    private static final Map<String, String> RULE_IMPACT_MAP = Map.ofEntries(
            Map.entry(
                    "ACCESSIBILITY_AXE",
                    "Accessibility compliance issues detected"),

            Map.entry(
                    "ALT_TEXT_VALIDATION",
                    "Images are missing alternative text for screen readers"),

            Map.entry(
                    "BROKEN_ANCHOR",
                    "Internal email navigation links are broken"),

            Map.entry(
                    "CTA_VALIDATION",
                    "Call-to-action validation issues detected"),

            Map.entry(
                    "CONTENT_VALIDATION",
                    "Required email content elements are missing"),

            Map.entry(
                    "DUPLICATE_ID",
                    "Duplicate HTML identifiers detected"),

            Map.entry(
                    "LINK_TEXT_VALIDATION",
                    "Links use unclear or non-descriptive text"),

            Map.entry(
                    "LINK_VALIDATION",
                    "One or more hyperlinks are broken"),

            Map.entry(
                    "HEADING_HIERARCHY",
                    "Heading structure does not follow accessibility standards"),
            Map.entry(
                    "PRIVACY_LINK",
                    "Privacy policy link is missing or not operational"),

            Map.entry(
                    "VIEW_ONLINE_LINK",
                    "Customers may be unable to view email in browser"),

            Map.entry(
                    "DISCLAIMER_PRESENT",
                    "Required email disclaimer information is missing")
    );

    private static final String DEFAULT_IMPACT =
            "Validation issue detected";

    /**
     * Utility class.
     */
    private BusinessImpactMapper() {
        throw new UnsupportedOperationException(
                "BusinessImpactMapper is a utility class and cannot be instantiated");
    }

    /**
     * Returns a business-friendly description for a given audit rule.
     *
     * @param ruleName rule identifier
     * @param findings technical findings (reserved for future use)
     * @return business-friendly impact description
     */
    public static String getImpact(
            final String ruleName,
            final String findings) {

        if (ruleName == null || ruleName.isBlank()) {
            return DEFAULT_IMPACT;
        }

        String normalized =
                findings == null
                        ? ""
                        : findings.toLowerCase();

        switch (ruleName) {

            case "PRIVACY_LINK":

                if (normalized.contains("broken")
                        || normalized.contains("404")
                        || normalized.contains("unknownhost")
                        || normalized.contains("little or no content")
                        || normalized.contains("does not exist")) {

                    return "Privacy policy link is present but not functional";
                }

                return "Privacy policy link is missing";

            case "VIEW_ONLINE_LINK":

                if (normalized.contains("broken")
                        || normalized.contains("404")
                        || normalized.contains("unknownhost")
                        || normalized.contains("little or no content")
                        || normalized.contains("does not exist")) {

                    return "View Online link is present but not functional";
                }

                return "View Online link not found";

            default:

                return RULE_IMPACT_MAP.getOrDefault(
                        ruleName.trim(),
                        DEFAULT_IMPACT);
        }
    }
}