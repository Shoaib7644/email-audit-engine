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

/**
 * {@link AuditRule} that validates the heading hierarchy ({@code <h1>}
 * through {@code <h6>}) of a rendered HTML page.
 *
 * <h2>Why this matters</h2>
 * <p>Screen reader users frequently navigate documents by jumping between
 * headings. A well-formed heading outline — exactly one {@code <h1>} as the
 * page title, with subsequent levels introduced sequentially without
 * skipping — gives that navigation a predictable document structure.
 * WCAG 2.1 Success Criterion 1.3.1 (Info and Relationships) and 2.4.6
 * (Headings and Labels) both depend on a correct heading outline.</p>
 *
 * <h2>Checks performed</h2>
 * <ol>
 *   <li><strong>Missing H1</strong> — the page has no {@code <h1>} element.</li>
 *   <li><strong>Multiple H1s</strong> — the page has more than one
 *       {@code <h1>} element; every {@code <h1>} after the first is reported,
 *       along with its text.</li>
 *   <li><strong>Skipped heading levels</strong> — a heading's level jumps by
 *       more than one from the previous heading in document order (e.g. an
 *       {@code <h1>} immediately followed by an {@code <h3>}, or an
 *       {@code <h2>} immediately followed by an {@code <h4>}). Moving back up
 *       to a shallower level (e.g. {@code <h3>} followed by {@code <h1>}) is
 *       always valid and is not flagged.</li>
 * </ol>
 *
 * <p>Every finding includes the exact, trimmed text of the heading element
 * that causes the violation.</p>
 *
 * <h2>Skipped-level findings format</h2>
 * <p>Every skipped heading level produces an individual structured finding
 * via {@link FindingFormatter#structuredFinding(String, String, String)},
 * e.g.:</p>
 * <pre>
 * Heading Hierarchy
 *   Element         : Products
 *   Detail          : Found: H3 — Expected: H2 — Heading levels should increase sequentially.
 * </pre>
 * <p>Multiple skipped-level violations are never aggregated into one long
 * string.</p>
 *
 * <h2>Static DOM parsing only</h2>
 * <p>All heading levels and text are read directly from the live DOM in a
 * single {@link Page#evaluate(String)} round-trip, in document order. No
 * navigation, scrolling, click simulation, or screenshot capture occurs.</p>
 *
 * <h2>Thread safety</h2>
 * <p>This class is stateless and has no mutable fields. It is safe for
 * concurrent use from multiple TestNG threads, each operating on its own
 * {@link Page}.</p>
 */
public final class HeadingHierarchyRule implements AuditRule {

    private static final Logger log = LoggerFactory.getLogger(HeadingHierarchyRule.class);

    // -------------------------------------------------------------------------
    // Rule identity
    // -------------------------------------------------------------------------

    public static final String RULE_ID = "HEADING_HIERARCHY";

    private static final String DESCRIPTION =
            "Validates the page's heading outline: exactly one <h1>, and no "
                    + "skipped heading levels (e.g. <h1> directly followed by <h3>).";

    /** Caps the number of individual findings to keep report output readable. */
    private static final int MAX_FINDINGS = 25;

    private static final String EMPTY_HEADING_TEXT_PLACEHOLDER = "(empty heading text)";

    /** Shared business-friendly explanation appended to every skipped-level finding. */
    private static final String SKIPPED_LEVEL_REASON =
            "Heading levels should increase sequentially.";

    // -------------------------------------------------------------------------
    // JavaScript used to extract all headings in one round-trip
    // -------------------------------------------------------------------------

    /**
     * Returns a JSON array of objects {@code {level, text}} for every
     * {@code <h1>}–{@code <h6>} element on the page, in document order.
     * {@code level} is the numeric heading level (1–6) parsed from the tag
     * name; {@code text} is the trimmed visible text of the heading.
     */
    private static final String EXTRACT_HEADINGS_JS = """
            () => Array.from(document.querySelectorAll('h1, h2, h3, h4, h5, h6'))
                       .map(h => ({
                           level: parseInt(h.tagName.substring(1), 10),
                           text: (h.innerText || h.textContent || '').trim()
                       }))
            """;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /** Creates a {@code HeadingHierarchyRule} with default configuration. */
    public HeadingHierarchyRule() {
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
        return "Heading Hierarchy Is Passed.";
    }

    @Override
    public String failImpact() {
        return "Heading Hierarchy Is Failed.";
    }

    @Override
    public RuleCategory category() {
        return RuleCategory.ACCESSIBILITY;
    }

    @Override
    public RuleSeverity severity() {
        return RuleSeverity.MEDIUM;
    }

    /**
     * Runs heading hierarchy validation against {@code page}.
     *
     * @param page live, fully loaded Playwright page; must not be {@code null}
     * @return PASS when exactly one {@code <h1>} exists and no heading level
     *         is skipped, FAIL when any violation is found,
     *         ERROR when DOM extraction itself fails
     */
    @Override
    public RuleResult execute(final Page page) {
        Objects.requireNonNull(page, "page must not be null");

        final long startMs = System.currentTimeMillis();
        log.info("[{}] Starting heading hierarchy validation on: {}", RULE_ID, safeUrl(page));

        final List<HeadingEntry> headings;
        try {
            headings = extractHeadings(page);
        } catch (final Exception e) {
            log.error("[{}] Failed to extract headings: {}", RULE_ID, e.getMessage(), e);
            return RuleResult.error(this, startMs, e);
        }

        log.info("[{}] {} heading element(s) found", RULE_ID, headings.size());

        final List<String> findings = new ArrayList<>();
        checkH1Presence(headings, findings);
        checkSkippedLevels(headings, findings);

        final List<String> cappedFindings = capFindings(findings);

        if (cappedFindings.isEmpty()) {
            log.info("[{}] Heading hierarchy is valid", RULE_ID);
            return RuleResult.pass(this, startMs);
        }

        log.warn("[{}] {} heading hierarchy issue(s) found", RULE_ID, cappedFindings.size());
        return RuleResult.fail(this, startMs, cappedFindings);
    }

    // -------------------------------------------------------------------------
    // Check 1 & 2 – H1 presence and uniqueness
    // -------------------------------------------------------------------------

    /**
     * Checks for a missing {@code <h1>} (Check 1) or multiple {@code <h1>}
     * elements (Check 2).
     *
     * <p>Unchanged by this enhancement — only skipped-level findings
     * (Check 3, below) were converted to {@link FindingFormatter}.</p>
     */
    private static void checkH1Presence(
            final List<HeadingEntry> headings,
            final List<String> findings) {

        final List<HeadingEntry> h1s = headings.stream()
                .filter(h -> h.level() == 1)
                .toList();

        if (h1s.isEmpty()) {
            findings.add("Missing <h1>: the page has no top-level heading.");
            return;
        }

        if (h1s.size() > 1) {
            final String firstText = displayText(h1s.get(0).text());
            for (int i = 1; i < h1s.size(); i++) {
                final String extraText = displayText(h1s.get(i).text());
                findings.add(String.format(
                        "Multiple <h1> elements found: \"%s\" is an additional <h1> "
                                + "(first <h1> was \"%s\")",
                        extraText, firstText));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Check 3 – skipped heading levels
    // -------------------------------------------------------------------------

    /**
     * Walks the heading list in document order and flags any transition where
     * the heading level increases by more than one (e.g. h1 → h3, h2 → h4).
     * Transitions that decrease or increase by exactly one are valid.
     *
     * <p>Each violation is reported via
     * {@link FindingFormatter#structuredFinding(String, String, String)},
     * e.g.:</p>
     * <pre>
     * Heading Hierarchy
     *   Element         : Products
     *   Detail          : Found: H3 — Expected: H2 — Heading levels should increase sequentially.
     * </pre>
     */
    private static void checkSkippedLevels(
            final List<HeadingEntry> headings,
            final List<String> findings) {

        for (int i = 1; i < headings.size(); i++) {
            final HeadingEntry previous = headings.get(i - 1);
            final HeadingEntry current  = headings.get(i);

            if (current.level() > previous.level() + 1) {
                final int expectedLevel = previous.level() + 1;

                findings.add(FindingFormatter.structuredFinding(
                        "Heading Hierarchy",
                        displayText(current.text()),
                        String.format(
                                "Found: H%d \u2014 Expected: H%d \u2014 %s",
                                current.level(), expectedLevel, SKIPPED_LEVEL_REASON)));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Internal – finding capping
    // -------------------------------------------------------------------------

    /**
     * Caps the combined findings list at {@value #MAX_FINDINGS} with an
     * overflow summary appended if exceeded.
     */
    private static List<String> capFindings(final List<String> findings) {
        if (findings.size() <= MAX_FINDINGS) {
            return findings;
        }

        final List<String> capped = new ArrayList<>(findings.subList(0, MAX_FINDINGS));
        capped.add(FindingFormatter.generic(RULE_ID,
                String.format("… and %d more heading hierarchy issue(s)",
                        findings.size() - MAX_FINDINGS)));
        return capped;
    }

    /**
     * Returns {@code text} for display in a finding, substituting a
     * placeholder when the heading element has no visible text (e.g. an
     * icon-only heading).
     */
    private static String displayText(final String text) {
        return text.isBlank() ? EMPTY_HEADING_TEXT_PLACEHOLDER : text;
    }

    // -------------------------------------------------------------------------
    // Internal – page extraction
    // -------------------------------------------------------------------------

    /**
     * Evaluates JavaScript in the page to extract all headings and returns
     * them as typed {@link HeadingEntry} records, in document order.
     */
    @SuppressWarnings("unchecked")
    private static List<HeadingEntry> extractHeadings(final Page page) {
        try {
            final Object raw = page.evaluate(EXTRACT_HEADINGS_JS);

            if (!(raw instanceof List<?> rawList)) {
                log.warn("[{}] Unexpected JS evaluation result type: {}",
                        RULE_ID, raw == null ? "null" : raw.getClass().getSimpleName());
                return List.of();
            }

            final List<HeadingEntry> entries = new ArrayList<>(rawList.size());
            for (final Object item : rawList) {
                if (item instanceof Map<?, ?> map) {
                    final int    level = intOrZero(map.get("level"));
                    final String text  = stringOrEmpty(map.get("text"));
                    if (level >= 1 && level <= 6) {
                        entries.add(new HeadingEntry(level, text));
                    }
                }
            }

            return entries;

        } catch (final PlaywrightException e) {
            log.error("[{}] Playwright error during heading extraction: {}", RULE_ID, e.getMessage(), e);
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

    private static int intOrZero(final Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        return 0;
    }

    // -------------------------------------------------------------------------
    // Internal records
    // -------------------------------------------------------------------------

    /**
     * Typed representation of a single heading element extracted from the page.
     *
     * @param level numeric heading level (1–6, from {@code h1}–{@code h6})
     * @param text  trimmed visible text of the heading
     */
    private record HeadingEntry(int level, String text) {}
}