package com.acxiom.emailaudit.reporting;

import java.util.Map;

/**
 * Maps technical audit rule IDs to business-friendly impact statements
 * suitable for marketing, QA, and stakeholder audiences.
 *
 * <h2>What changed and why</h2>
 * <p>{@code getImpact(ruleId, findings)} previously took only {@code ruleId}
 * and {@code findings} — it had no way to know whether the rule actually
 * passed or failed, so every mapped rule showed the same failure-oriented
 * sentence regardless of outcome. Confirmed directly from exported reports:
 * a passing {@code CTA_VALIDATION} row and a failing one both showed
 * <em>"Marketing engagement tracking may not function correctly."</em>; a
 * passing {@code ALT_TEXT_VALIDATION} row still showed <em>"Images may not
 * display correctly for recipients."</em>; a passing
 * {@code DISCLAIMER_PRESENT} row (disclaimer text found) still showed
 * <em>"Mandatory compliance messaging is incomplete."</em></p>
 *
 * <p>{@link #getImpact(String, String, String)} now takes the rule's status
 * (e.g. {@code "PASS"}, {@code "FAIL"}) as well, and every entry carries a
 * distinct PASS and FAIL statement via {@link ImpactPair}. The two-argument
 * overload is kept only so any other caller that hasn't been updated yet
 * still compiles — it always resolves to the FAIL-oriented text, so update
 * call sites to the three-argument overload as you find them.</p>
 *
 * <p>PRIVACY_LINK and VIEW_ONLINE_LINK keep their finding-context logic for
 * distinguishing "missing" from "present but broken" on FAIL, and now also
 * return a real PASS statement instead of falling through to one of the
 * failure branches.</p>
 *
 * <p>Stateless and thread-safe.</p>
 */
public final class BusinessImpactMapper {

    private static final String DEFAULT_IMPACT_FAIL =
            "An audit check requires attention before deployment.";

    private static final String DEFAULT_IMPACT_PASS =
            "This audit check passed with no issues found.";

    /**
     * Distinct business-impact statements for a rule's PASS and FAIL
     * outcomes. Rules with context-dependent impacts (PRIVACY_LINK,
     * VIEW_ONLINE_LINK) are handled in {@link #getImpact} directly so the
     * logic stays readable and in one place.
     */
    private record ImpactPair(String passText, String failText) {}

    private static final Map<String, ImpactPair> IMPACT_MAP = Map.ofEntries(

            Map.entry("LINK_VALIDATION", new ImpactPair(
                    "All campaign destination links are reachable.",
                    "Recipients may be unable to access campaign destinations.")),

            Map.entry("LINK_TEXT_VALIDATION", new ImpactPair(
                    "Links use clear, descriptive text.",
                    "Marketing engagement tracking may not function correctly.")),

            Map.entry("CTA_VALIDATION", new ImpactPair(
                    "All call-to-action buttons are visible, labeled, and linked correctly.",
                    "Marketing engagement tracking may not function correctly.")),

            Map.entry("DUPLICATE_ID", new ImpactPair(
                    "No duplicate HTML identifiers were found.",
                    "Email rendering may vary across email clients.")),

            Map.entry("HEADING_HIERARCHY", new ImpactPair(
                    "Heading structure follows a valid, accessible outline.",
                    "Email rendering may vary across email clients.")),

            Map.entry("CONTENT_VALIDATION", new ImpactPair(
                    "Required email content is present and complete.",
                    "Placeholder or draft content may be visible to recipients.")),

            Map.entry("ALT_TEXT_VALIDATION", new ImpactPair(
                    "All images include descriptive alternative text.",
                    "Images may not display correctly for recipients.")),

            Map.entry("IMAGE_SRC_VALIDATION", new ImpactPair(
                    "All images loaded successfully and will render correctly.",
                    "One or more images will render as broken-image icons for recipients.")),

            Map.entry("URL_DEFENSE", new ImpactPair(
                    "No links are wrapped by an unexpected security filter.",
                    "Destination links may not reach the intended page.")),

            Map.entry("CAMPAIGN_VALIDATION", new ImpactPair(
                    "Rendered email matches the uploaded campaign specification.",
                    "Rendered email does not match the uploaded campaign specification.")),

            Map.entry("ACCESSIBILITY_AXE", new ImpactPair(
                    "No accessibility violations were detected.",
                    "Email may not meet accessibility standards and could impact"
                            + " customers using assistive technologies.")),

            Map.entry("BROKEN_ANCHOR", new ImpactPair(
                    "All in-page navigation links resolve correctly.",
                    "Recipients may be unable to unsubscribe correctly.")),

            Map.entry("DISCLAIMER_PRESENT", new ImpactPair(
                    "Mandatory compliance messaging is present.",
                    "Mandatory compliance messaging is incomplete.")),

            Map.entry("PREHEADER_TRIM_VALIDATION", new ImpactPair(
                    "Preheader text displays cleanly in the inbox preview with no stray whitespace.",
                    "Accidental leading/trailing whitespace in the preheader may cause inconsistent "
                            + "or truncated preview rendering across inbox providers.")),

            Map.entry("PREHEADER_PUNCTUATION_VALIDATION", new ImpactPair(
                    "Preheader text reads as a complete, properly punctuated sentence.",
                    "Preheader text lacking terminal punctuation may read as abrupt or unfinished "
                            + "in the inbox preview.")),

            Map.entry("HEADER_EMOJI_ENCODING_VALIDATION", new ImpactPair(
                    "Preheader markup uses properly HTML-encoded characters and will render "
                            + "consistently across email clients.",
                    "Unencoded emoji or special characters in the preheader markup may render "
                            + "incorrectly, as mojibake, or as missing glyphs in some email clients."))
    );

    private BusinessImpactMapper() {
        throw new UnsupportedOperationException(
                "BusinessImpactMapper is a utility class and cannot be instantiated");
    }

    /**
     * Returns a business-friendly, status-aware impact statement for the
     * given rule.
     *
     * @param ruleId   technical rule identifier (e.g. {@code "ACCESSIBILITY_AXE"})
     * @param status   the rule's outcome, e.g. {@code "PASS"} or {@code "FAIL"}
     *                 (case-insensitive; anything other than {@code "PASS"}
     *                 is treated as a failure for the purpose of choosing
     *                 wording)
     * @param findings raw technical findings string (used only for contextual rules)
     * @return business-friendly impact statement; never {@code null}
     */
    public static String getImpact(
            final String ruleId,
            final String status,
            final String findings) {

        if (ruleId == null || ruleId.isBlank()) {
            return isPass(status) ? DEFAULT_IMPACT_PASS : DEFAULT_IMPACT_FAIL;
        }

        final boolean pass = isPass(status);
        final String normalizedFindings =
                findings == null ? "" : findings.toLowerCase();

        switch (ruleId.trim()) {

            case "PRIVACY_LINK":
                return _privacyLinkImpact(pass, normalizedFindings);

            case "VIEW_ONLINE_LINK":
                return _viewOnlineLinkImpact(pass, normalizedFindings);

            default:
                final ImpactPair pair = IMPACT_MAP.get(ruleId.trim());
                if (pair == null) {
                    return pass ? DEFAULT_IMPACT_PASS : DEFAULT_IMPACT_FAIL;
                }
                return pass ? pair.passText() : pair.failText();
        }
    }

    /**
     * @deprecated status-blind overload retained only so pre-existing
     *             callers still compile; it always resolves to the
     *             FAIL-oriented text since no status is available. Update
     *             call sites to {@link #getImpact(String, String, String)}.
     */
    @Deprecated
    public static String getImpact(
            final String ruleId,
            final String findings) {
        return getImpact(ruleId, "FAIL", findings);
    }

    private static boolean isPass(final String status) {
        return status != null && "PASS".equalsIgnoreCase(status.trim());
    }

    /* ── Contextual impact helpers ───────────────────────────────── */

    /**
     * PRIVACY_LINK: real PASS statement, or on FAIL distinguish "missing"
     * from "present but broken". Both FAIL cases are compliance risks but
     * the business action differs.
     */
    private static String _privacyLinkImpact(final boolean pass, final String findings) {

        if (pass) {
            return "Required privacy information is available to recipients.";
        }

        if (_indicatesBrokenLink(findings)) {
            return "Required privacy information may be inaccessible to recipients.";
        }

        return "Required privacy information is missing from this email.";
    }

    /**
     * VIEW_ONLINE_LINK: real PASS statement, or on FAIL distinguish
     * "missing" from "present but broken".
     */
    private static String _viewOnlineLinkImpact(final boolean pass, final String findings) {

        if (pass) {
            return "The View in Browser link is present and functional.";
        }

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
