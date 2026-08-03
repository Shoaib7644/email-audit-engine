package com.acxiom.emailaudit.reporting;

/**
 * Dashboard-level reporting categories used to group
 * audit rule results into business-friendly sections.
 */
public enum ReportSection {

    ACCESSIBILITY(
            "Accessibility",
            "Accessibility compliance, usability, screen reader support, and WCAG-related checks."
    ),

    CONTENT(
            "Content",
            "Validation of required email content, metadata, titles, and messaging elements."
    ),

    LINKS(
            "Links",
            "Validation of hyperlinks, anchor references, and link text quality."
    ),

    IMAGES(
            "Images",
            "Validation of rendered email images, load state, alt attributes, and image screenshots."
    ),

    CTA(
            "Call To Action",
            "Validation of call-to-action elements and user engagement pathways."
    ),

    HTML_STRUCTURE(
            "HTML Structure",
            "Validation of HTML markup quality, uniqueness, and document structure."
    ),

    BEST_PRACTICES(
            "Best Practices",
            "General quality, maintainability, and uncategorized audit validations."
    );

    private final String displayName;
    private final String description;

    ReportSection(
            final String displayName,
            final String description) {

        this.displayName = displayName;
        this.description = description;
    }

    /**
     * Human-readable section name displayed on dashboards.
     *
     * @return display name
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Description displayed in dashboards and reports.
     *
     * @return section description
     */
    public String getDescription() {
        return description;
    }
}
