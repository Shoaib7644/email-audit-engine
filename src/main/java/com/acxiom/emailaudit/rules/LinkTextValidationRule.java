package com.acxiom.emailaudit.rules;

import com.acxiom.emailaudit.rules.AuditRule;
import com.acxiom.emailaudit.rules.RuleResult;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@link AuditRule} that flags anchor links whose visible text is generic and
 * non-descriptive — text that conveys no information about the link's
 * destination when read out of context (e.g. by a screen reader navigating a
 * "links list", or by a user scanning the page quickly).
 *
 * <h2>Why this matters</h2>
 * <p>Screen readers commonly let users navigate a page via a list of all
 * links, presented out of their surrounding sentence context. A list
 * containing ten entries that all say "click here" is meaningless. WCAG 2.1
 * Success Criterion 2.4.4 (Link Purpose In Context) and 2.4.9 (Link Purpose,
 * Link Only) specifically address this.</p>
 *
 * <h2>Checks performed</h2>
 * <p>For every {@code <a href>} on the page, the link's visible text is
 * trimmed, lower-cased, and compared against a set of banned generic
 * phrases:</p>
 * <ul>
 *   <li>{@code "click here"}</li>
 *   <li>{@code "read more"}</li>
 *   <li>{@code "learn more"}</li>
 *   <li>{@code "here"}</li>
 * </ul>
 * <p>The comparison is an <strong>exact match</strong> on the full trimmed
 * text (not a substring match) — a link whose text is
 * {@code "Where is my order?"} is not flagged just because it contains
 * "here".</p>
 *
 * <h2>Footer unsubscribe exception</h2>
 * <p>Links inside a {@code <footer>} element whose {@code href} or visible
 * text matches an unsubscribe-related pattern (e.g.
 * {@code href="...?action=unsubscribe"} or text {@code "Unsubscribe"}) are
 * <strong>ignored</strong>, even if their text happens to match a banned
 * phrase. This accommodates the common email-footer pattern of
 * "To stop receiving these emails, click here." where "click here" is the
 * unsubscribe mechanism itself and is governed by
 * {@code LinkValidationRule}'s unsubscribe-presence check instead.</p>
 *
 * <h2>PASS evidence</h2>
 * <p>When the rule passes, it now also returns one evidence finding per
 * checked link (excluding ignored footer-unsubscribe links) via
 * {@link FindingFormatter#structuredFinding(String, String, String)}, so the
 * dashboard can show exactly which links were reviewed, e.g.:</p>
 * <pre>
 * Link Text Validation
 *   Element         : "Download Report"
 *   Detail          : Validation: PASSED — Link text is descriptive.
 * </pre>
 * <p>Capped at {@value #MAX_PASS_EVIDENCE} to keep PASS evidence readable;
 * an overflow summary is appended if exceeded.</p>
 *
 * <h2>Static DOM analysis only</h2>
 * <p>All link text, href, and footer-ancestry information is read directly
 * from the live DOM in a single {@link Page#evaluate(String)} round-trip.
 * No navigation or click simulation occurs.</p>
 *
 * <h2>Thread safety</h2>
 * <p>This class is stateless and has no mutable fields. It is safe for
 * concurrent use from multiple TestNG threads, each operating on its own
 * {@link Page}.</p>
 */
public final class LinkTextValidationRule implements AuditRule {

    private static final Logger log = LoggerFactory.getLogger(LinkTextValidationRule.class);

    // -------------------------------------------------------------------------
    // Rule identity
    // -------------------------------------------------------------------------

    public static final String RULE_ID = "LINK_TEXT_VALIDATION";

    private static final String DESCRIPTION =
            "Flags links with generic, non-descriptive text (e.g. \"click here\", "
                    + "\"read more\") that provide no context when read out of order, "
                    + "excluding footer unsubscribe links.";

    /** Caps the number of individual findings to keep report output readable. */
    private static final int MAX_FINDINGS = 25;

    /** Caps the number of PASS evidence findings to keep report output readable. */
    private static final int MAX_PASS_EVIDENCE = 25;

    /** Shared business-friendly title used for evidence findings from this rule. */
    private static final String FINDING_TITLE = "Link Text Validation";

    /**
     * Generic link text values that fail this rule. Compared against the
     * full trimmed, lower-cased anchor text — exact match, not substring.
     */
    private static final Set<String> BANNED_LINK_TEXT = Set.of(
            "click here",
            "read more",
            "learn more",
            "here"
    );

    /**
     * Matches unsubscribe-like hrefs or anchor text, mirroring
     * {@code LinkValidationRule}'s unsubscribe detection so the two rules
     * agree on what counts as an unsubscribe link.
     */
    private static final Pattern UNSUBSCRIBE_PATTERN = Pattern.compile(
            "unsub|opt.?out|remove.?me|email.?pref",
            Pattern.CASE_INSENSITIVE
    );

    // -------------------------------------------------------------------------
    // JavaScript used to extract all anchor data in one round-trip
    // -------------------------------------------------------------------------

    /**
     * Returns a JSON array of objects {@code {href, text, inFooter}} for
     * every {@code <a href>} element on the page, in document order.
     * {@code inFooter} is {@code true} when the anchor has an ancestor
     * {@code <footer>} element.
     */
    private static final String EXTRACT_LINKS_JS = """
            () => Array.from(document.querySelectorAll('a[href]'))
                       .map(a => ({
                           href: a.getAttribute('href') || '',
                           text: (a.innerText || a.textContent || '').trim(),
                           inFooter: a.closest('footer') !== null
                       }))
            """;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /** Creates a {@code LinkTextValidationRule} with default configuration. */
    public LinkTextValidationRule() {
        // stateless
    }

    // -------------------------------------------------------------------------
    // AuditRule implementation
    // -------------------------------------------------------------------------

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public String passImpact() {
        return "Link Validation is Passed";
    }

    @Override
    public String failImpact() {
        return "Link Validation is Failed";
    }
    @Override
    public RuleCategory category() {
        return RuleCategory.LINKS;
    }

    @Override
    public RuleSeverity severity() {
        return RuleSeverity.MEDIUM;
    }

    /**
     * Runs link-text validation against {@code page}.
     *
     * @param page live, fully loaded Playwright page; must not be {@code null}
     * @return PASS (with evidence findings) when no link uses generic text
     *         (outside the footer unsubscribe exception), FAIL when one or
     *         more do, ERROR when DOM extraction itself fails
     */
    @Override
    public RuleResult execute(final Page page) {
        Objects.requireNonNull(page, "page must not be null");

        final long startMs = System.currentTimeMillis();
        log.info("[{}] Starting link text validation on: {}", RULE_ID, safeUrl(page));

        final List<LinkEntry> links;
        try {
            links = extractLinks(page);
        } catch (final Exception e) {
            log.error("[{}] Failed to extract links from page: {}", RULE_ID, e.getMessage(), e);
            return RuleResult.error(this, startMs, e);
        }

        log.info("[{}] {} anchor link(s) found", RULE_ID, links.size());

        final List<String> findings = buildFindings(links);

        if (findings.isEmpty()) {
            log.info("[{}] No generic link text found", RULE_ID);
            return RuleResult.builder(this, RuleResult.Status.PASS, startMs)
                    .withFindings(buildPassEvidence(links))
                    .build();
        }

        log.warn("[{}] {} link(s) with generic text found", RULE_ID, findings.size());
        return RuleResult.fail(this, startMs, findings);
    }

    // -------------------------------------------------------------------------
    // Internal – finding construction
    // -------------------------------------------------------------------------

    /**
     * Builds one finding per offending link, capped at {@value #MAX_FINDINGS}
     * with an overflow summary appended if exceeded.
     */
    private static List<String> buildFindings(final List<LinkEntry> links) {
        final List<String> findings = new ArrayList<>();

        for (final LinkEntry link : links) {
            if (isIgnoredFooterUnsubscribeLink(link)) {
                continue;
            }

            final String normalisedText = link.text().trim().toLowerCase();

            if (BANNED_LINK_TEXT.contains(normalisedText)) {
                findings.add(String.format(
                        "Generic link text \"%s\" for href=\"%s\" – use descriptive text "
                                + "that conveys the link's destination out of context.",
                        link.text().trim(), link.href()));
            }
        }

        if (findings.size() <= MAX_FINDINGS) {
            return findings;
        }

        final List<String> capped = new ArrayList<>(findings.subList(0, MAX_FINDINGS));
        capped.add(String.format("… and %d more link(s) with generic text",
                findings.size() - MAX_FINDINGS));
        return capped;
    }

    /**
     * Builds one PASS evidence finding per checked link (excluding ignored
     * footer-unsubscribe links and links with blank text, since there is
     * nothing meaningful to confirm for either). Capped at
     * {@value #MAX_PASS_EVIDENCE} with an overflow summary appended if
     * exceeded.
     *
     * <p>Example output:
     * <pre>
     * Link Text Validation
     *   Element         : "Download Report"
     *   Detail          : Validation: PASSED — Link text is descriptive.
     * </pre>
     */
    private static List<String> buildPassEvidence(final List<LinkEntry> links) {
        final List<String> evidence = new ArrayList<>();

        for (final LinkEntry link : links) {
            if (isIgnoredFooterUnsubscribeLink(link)) {
                continue;
            }

            final String trimmedText = link.text().trim();
            if (trimmedText.isEmpty()) {
                continue;
            }

            evidence.add(FindingFormatter.structuredFinding(
                    FINDING_TITLE,
                    "\"" + trimmedText + "\"",
                    "Validation: PASSED \u2014 Link text is descriptive."));
        }

        if (evidence.size() <= MAX_PASS_EVIDENCE) {
            return evidence;
        }

        final List<String> capped = new ArrayList<>(evidence.subList(0, MAX_PASS_EVIDENCE));
        capped.add(FindingFormatter.generic(RULE_ID,
                String.format("… and %d more link(s) with descriptive text",
                        evidence.size() - MAX_PASS_EVIDENCE)));
        return capped;
    }

    /**
     * Returns {@code true} if {@code link} is inside a {@code <footer>} and
     * either its {@code href} or visible text matches the unsubscribe pattern
     * — the exception carved out by requirement 3.
     */
    private static boolean isIgnoredFooterUnsubscribeLink(final LinkEntry link) {
        if (!link.inFooter()) {
            return false;
        }
        return UNSUBSCRIBE_PATTERN.matcher(link.href()).find()
                || UNSUBSCRIBE_PATTERN.matcher(link.text()).find();
    }

    // -------------------------------------------------------------------------
    // Internal – page extraction
    // -------------------------------------------------------------------------

    /**
     * Evaluates JavaScript in the page to extract all anchor elements and
     * returns them as typed {@link LinkEntry} records.
     */
    @SuppressWarnings("unchecked")
    private static List<LinkEntry> extractLinks(final Page page) {
        try {
            final Object raw = page.evaluate(EXTRACT_LINKS_JS);

            if (!(raw instanceof List<?> rawList)) {
                log.warn("[{}] Unexpected JS evaluation result type: {}",
                        RULE_ID, raw == null ? "null" : raw.getClass().getSimpleName());
                return List.of();
            }

            final List<LinkEntry> entries = new ArrayList<>(rawList.size());
            for (final Object item : rawList) {
                if (item instanceof Map<?, ?> map) {
                    final String  href     = stringOrEmpty(map.get("href"));
                    final String  text     = stringOrEmpty(map.get("text"));
                    final boolean inFooter = booleanOrFalse(map.get("inFooter"));
                    entries.add(new LinkEntry(href, text, inFooter));
                }
            }

            return entries;

        } catch (final PlaywrightException e) {
            log.error("[{}] Playwright error during link extraction: {}", RULE_ID, e.getMessage(), e);
            throw e;
        }
    }

    // -------------------------------------------------------------------------
    // Internal – helpers
    // -------------------------------------------------------------------------

    private static String safeUrl(final Page page) {
        try {
            return page.url();
        } catch (final Exception e) {
            return "<unavailable>";
        }
    }

    private static String stringOrEmpty(final Object value) {
        return value instanceof String s ? s : "";
    }

    private static boolean booleanOrFalse(final Object value) {
        return value instanceof Boolean b && b;
    }

    // -------------------------------------------------------------------------
    // Internal records
    // -------------------------------------------------------------------------

    /**
     * Typed representation of a single {@code <a href="…">} element extracted
     * from the page.
     *
     * @param href     the raw {@code href} attribute value
     * @param text     the trimmed visible text of the anchor
     * @param inFooter whether the anchor has an ancestor {@code <footer>} element
     */
    private record LinkEntry(String href, String text, boolean inFooter) {}
}
