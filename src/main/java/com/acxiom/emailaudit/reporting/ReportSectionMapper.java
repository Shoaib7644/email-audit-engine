package com.acxiom.emailaudit.reporting;

import java.util.Locale;
import java.util.Map;

/**
 * Maps audit rule identifiers to dashboard reporting sections.
 *
 * <p>This utility centralizes section assignment logic used by
 * dashboard generation and reporting components.</p>
 */
public final class ReportSectionMapper {

    private static final Map<String, ReportSection> RULE_SECTION_MAP =
            Map.ofEntries(
                    Map.entry("ACCESSIBILITY_AXE", ReportSection.ACCESSIBILITY),
                    Map.entry("ALT_TEXT_VALIDATION", ReportSection.ACCESSIBILITY),
                    Map.entry("HEADING_HIERARCHY", ReportSection.ACCESSIBILITY),
                    // New: groups with ALT_TEXT_VALIDATION — both are
                    // image-quality checks (accessible label present vs.
                    // image actually renders) and should land in the same
                    // ACCESSIBILITY section summary bucket.
                    Map.entry("IMAGE_SRC_VALIDATION", ReportSection.ACCESSIBILITY),
                    Map.entry("IMAGE_VALIDATION", ReportSection.IMAGES),

                    Map.entry("CAMPAIGN_VALIDATION", ReportSection.CAMPAIGN_VALIDATION),

                    Map.entry("LINK_VALIDATION", ReportSection.LINKS),
                    Map.entry("LINK_TEXT_VALIDATION", ReportSection.LINKS),
                    Map.entry("BROKEN_ANCHOR", ReportSection.LINKS),

                    Map.entry("CTA_VALIDATION", ReportSection.CTA),

                    Map.entry("CONTENT_VALIDATION", ReportSection.CONTENT),

                    Map.entry("DUPLICATE_ID", ReportSection.HTML_STRUCTURE),

                    Map.entry("PRIVACY_LINK", ReportSection.LINKS),

                    Map.entry("VIEW_ONLINE_LINK", ReportSection.LINKS),

                    Map.entry("DISCLAIMER_PRESENT", ReportSection.CONTENT),
                    Map.entry("PREHEADER_TRIM_VALIDATION", ReportSection.CONTENT),
                    Map.entry("PREHEADER_PUNCTUATION_VALIDATION", ReportSection.CONTENT),
                    Map.entry("HEADER_EMOJI_ENCODING_VALIDATION", ReportSection.CONTENT)
            );

    private ReportSectionMapper() {
        throw new UnsupportedOperationException(
                "Utility class should not be instantiated");
    }

    /**
     * Maps a rule identifier to a dashboard reporting section.
     *
     * @param ruleId audit rule identifier
     * @return matching {@link ReportSection}, or
     *         {@link ReportSection#BEST_PRACTICES} when no mapping exists
     */
    public static ReportSection map(final String ruleId) {

        if (ruleId == null || ruleId.isBlank()) {
            return ReportSection.BEST_PRACTICES;
        }

        return RULE_SECTION_MAP.getOrDefault(
                ruleId.trim().toUpperCase(Locale.ROOT),
                ReportSection.BEST_PRACTICES);
    }
}
