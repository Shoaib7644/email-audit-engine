package com.acxiom.emailaudit.reporting;

import java.util.Map;

/**
 * Maps technical audit rule IDs to business-friendly impact statements
 * suitable for marketing, QA, and stakeholder audiences.
 *
 * <p>Changes from previous version:</p>
 * <ul>
 *   <li>All impact strings rewritten in business language — no technical
 *       terms (no WCAG, no 404, no exception class names, no HTML tags).</li>
 *   <li>PRIVACY_LINK and VIEW_ONLINE_LINK contextual logic preserved but
 *       impact strings now use business wording throughout.</li>
 *   <li>Default impact updated to a neutral business statement.</li>
 *   <li>URL_DEFENSE added (was absent from previous version).</li>
 * </ul>
 *
 * <p>Stateless and thread-safe.</p>
 */
public final class BusinessImpactMapper {

    private static final String DEFAULT_IMPACT =
            "An audit check requires attention before deployment.";

    /**
     * Immutable base impact map — used for rules that do not need
     * finding-context to produce their statement.
     *
     * Rules with context-dependent impacts (PRIVACY_LINK,
     * VIEW_ONLINE_LINK) are handled in {@link #getImpact} directly
     * so the logic stays readable and in one place.
     */
    private static final Map<String, String> IMPACT_MAP = Map.ofEntries(

            Map.entry(
                    "LINK_VALIDATION",
                    "Recipients may be unable to access campaign destinations."),

            Map.entry(
                    "LINK_TEXT_VALIDATION",
                    "Marketing engagement tracking may not function correctly."),

            Map.entry(
                    "CTA_VALIDATION",
                    "Marketing engagement tracking may not function correctly."),

            Map.entry(
                    "DUPLICATE_ID",
                    "Email rendering may vary across email clients."),

            Map.entry(
                    "HEADING_HIERARCHY",
                    "Email rendering may vary across email clients."),

            Map.entry(
                    "CONTENT_VALIDATION",
                    "Placeholder or draft content may be visible to recipients."),

            Map.entry(
                    "ALT_TEXT_VALIDATION",
                    "Images may not display correctly for recipients."),

            Map.entry(
                    "URL_DEFENSE",
                    "Destination links may not reach the intended page."),

            Map.entry(
                    "ACCESSIBILITY_AXE",
                    "Email may not meet accessibility standards and could impact"
                            + " customers using assistive technologies."),

            Map.entry(
                    "BROKEN_ANCHOR",
                    "Recipients may be unable to unsubscribe correctly."),

            Map.entry(
                    "DISCLAIMER_PRESENT",
                    "Mandatory compliance messaging is incomplete.")
    );

    private BusinessImpactMapper() {
        throw new UnsupportedOperationException(
                "BusinessImpactMapper is a utility class and cannot be instantiated");
    }

    /**
     * Returns a business-friendly impact statement for the given rule.
     *
     * <p>For most rules the statement is fixed regardless of findings.
     * PRIVACY_LINK and VIEW_ONLINE_LINK produce slightly different
     * statements depending on whether the link was missing entirely
     * or was present but broken.</p>
     *
     * @param ruleId   technical rule identifier (e.g. {@code "ACCESSIBILITY_AXE"})
     * @param findings raw technical findings string (used only for contextual rules)
     * @return business-friendly impact statement; never {@code null}
     */
    public static String getImpact(
            final String ruleId,
            final String findings) {

        if (ruleId == null || ruleId.isBlank()) {
            return DEFAULT_IMPACT;
        }

        final String normalizedFindings =
                findings == null ? "" : findings.toLowerCase();

        switch (ruleId.trim()) {

            case "PRIVACY_LINK":
                return _privacyLinkImpact(normalizedFindings);

            case "VIEW_ONLINE_LINK":
                return _viewOnlineLinkImpact(normalizedFindings);

            default:
                return IMPACT_MAP.getOrDefault(
                        ruleId.trim(),
                        DEFAULT_IMPACT);
        }
    }

    /* ── Contextual impact helpers ───────────────────────────────── */

    /**
     * PRIVACY_LINK: distinguish "missing" from "present but broken".
     * Both are compliance risks but the business action differs.
     */
    private static String _privacyLinkImpact(final String findings) {

        if (_indicatesBrokenLink(findings)) {
            return "Required privacy information may be inaccessible to recipients.";
        }

        return "Required privacy information is missing from this email.";
    }

    /**
     * VIEW_ONLINE_LINK: distinguish "missing" from "present but broken".
     */
    private static String _viewOnlineLinkImpact(final String findings) {

        if (_indicatesBrokenLink(findings)) {
            return "Recipients may be unable to open the online version of this email.";
        }

        return "The View in Browser link is missing from this email.";
    }

    /**
     * Returns {@code true} when the findings text suggests the link
     * exists but the destination is unreachable or returns an error.
     * Used to differentiate "missing" from "broken" impact statements.
     */
    private static boolean _indicatesBrokenLink(final String findings) {
        return findings.contains("broken")
                || findings.contains("404")
                || findings.contains("unknownhost")
                || findings.contains("little or no content")
                || findings.contains("does not exist");
    }
}