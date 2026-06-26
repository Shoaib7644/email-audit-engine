package com.acxiom.emailaudit.reporting;

import java.util.Locale;

/**
 * Converts verbose technical findings into concise,
 * business-friendly summaries suitable for Excel reports.
 */
public final class FindingSummarizer {

    private FindingSummarizer() {
        // Utility class
    }

    /**
     * Converts technical findings into a simplified summary.
     *
     * @param findings Raw technical findings
     * @return Business-friendly summary
     */
    public static String summarize(
            String findings) {

        if (findings == null || findings.isBlank()) {
            return "";
        }

        String normalized =
                findings.toLowerCase(Locale.ROOT);

        // Broken anchor issues
        if (normalized.contains("broken anchor")
                || normalized.contains("has no element with id")) {

            return "Internal navigation link is broken";
        }

        // Missing ALT text
        if (normalized.contains("missing alt")
                || normalized.contains("missing alt attribute")
                || normalized.contains("image-alt")
                || normalized.contains("alternate text")) {

            return "Image accessibility issue detected";
        }

        // Broken links / URL failures
        if (normalized.contains("404")
                || normalized.contains("broken link")
                || normalized.contains("broken url")
                || normalized.contains("unknownhostexception")
                || normalized.contains("http 404")
                || normalized.contains("link(s) detected")) {

            return "One or more hyperlinks are broken";
        }

        // Missing H1
        if (normalized.contains("missing <h1>")
                || normalized.contains("no top-level heading")
                || normalized.contains("page-has-heading-one")) {

            return "Missing primary heading";
        }

        // Missing title
        if (normalized.contains("missing or blank <title>")
                || normalized.contains("missing title")
                || normalized.contains("document-title")
                || normalized.contains("<title> element")) {

            return "Missing email title";
        }

        // Accessibility issues
        if (normalized.contains("accessibility")
                || normalized.contains("axe")
                || normalized.contains("wcag")
                || normalized.contains("color-contrast")
                || normalized.contains("html-has-lang")
                || normalized.contains("landmark-one-main")
                || normalized.contains("region")) {

            return "Accessibility compliance issues detected";
        }

        // CTA issues
        if (normalized.contains("call-to-action")
                || normalized.contains("cta")) {

            return "Call-to-action issue detected";
        }

        // Duplicate IDs
        if (normalized.contains("duplicate id")
                || normalized.contains("duplicate identifier")) {

            return "Duplicate HTML identifiers detected";
        }

        // Link text issues
        if (normalized.contains("generic link text")
                || normalized.contains("descriptive text")) {

            return "Links use unclear or non-descriptive text";
        }

        // Content validation
        if (normalized.contains("content validation")
                || normalized.contains("required content")) {

            return "Required email content elements are missing";
        }

        // Privacy link issues
        /*
         * Privacy Link
         */
        if (normalized.contains("privacy policy")) {

            if (normalized.contains("not found")) {
                return "Privacy policy link is missing";
            }

            if (normalized.contains("unknownhostexception")) {
                return "Privacy policy destination cannot be reached";
            }

            if (normalized.contains("http 404")) {
                return "Privacy policy page not found";
            }

            if (normalized.contains("little or no content")) {
                return "Privacy policy page is blank";
            }

            if (normalized.contains("does not exist")) {
                return "Privacy policy page does not exist";
            }

            if (normalized.contains("broken")) {
                return "Privacy policy link is present but not functional";
            }

            return "Privacy policy link issue detected";
        }

        /*
         * View Online Link
         */
        if (normalized.contains("view online")) {

            if (normalized.contains("not found")) {
                return "View Online link not found";
            }

            if (normalized.contains("unknownhostexception")) {
                return "View Online destination cannot be reached";
            }

            if (normalized.contains("http 404")) {
                return "View Online page not found";
            }

            if (normalized.contains("little or no content")) {
                return "View Online page is blank";
            }

            if (normalized.contains("does not exist")) {
                return "View Online page does not exist";
            }

            if (normalized.contains("broken")) {
                return "View Online link is present but not functional";
            }

            return "View Online link issue detected";
        }


        if (normalized.contains("disclaimer")
                || normalized.contains("do not reply")
                || normalized.contains("mailbox is not monitored")) {

            return "Required disclaimer information missing";
        }

        if (normalized.contains("http 404")) {

            return "Linked page not found";
        }

        if (normalized.contains("little or no content")) {

            return "Linked page is blank or contains insufficient content";
        }

        // Fallback
        return findings;
    }
}