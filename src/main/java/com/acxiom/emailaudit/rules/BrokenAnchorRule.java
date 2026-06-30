package com.acxiom.emailaudit.rules;


import com.acxiom.emailaudit.rules.AuditRule;
import com.acxiom.emailaudit.rules.RuleResult;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@link AuditRule} that validates in-page fragment anchors — links of the
 * form {@code href="#section"} — against the set of {@code id} attribute
 * values present on the page.
 *
 * <h2>Why this matters</h2>
 * <p>A fragment link whose target {@code id} does not exist on the page is a
 * silent failure: clicking it does nothing in most browsers, with no error
 * surfaced to the user. This is a common defect in email templates that use
 * a "back to top" or table-of-contents pattern where a section was renamed
 * or removed but the linking anchor was not updated.</p>
 *
 * <h2>Checks performed</h2>
 * <p>For every {@code <a href="#...">} on the page:</p>
 * <ul>
 *   <li>{@code href="#section"} — <strong>FAIL</strong> if no element with
 *       {@code id="section"} exists anywhere on the page.</li>
 *   <li>{@code href=""} (empty/blank href) — <strong>FAIL</strong>; reported
 *       with destination {@code (empty href)}.</li>
 *   <li>{@code href="#"} (empty fragment) — <strong>ignored</strong>. This is
 *       the conventional "scroll to top of page" pattern and has no target
 *       {@code id} to validate.</li>
 *   <li>{@code href="javascript:void(0)"} (or any {@code javascript:} scheme,
 *       case-insensitive) — <strong>ignored</strong>. These are script-driven
 *       placeholder links, not fragment navigation, and never start with
 *       {@code #}.</li>
 *   <li>{@code href="#section"} where {@code id="section"} exists —
 *       <strong>PASS</strong>; reported as a structured pass finding.</li>
 * </ul>
 *
 * <h2>Static DOM validation</h2>
 * <p>This rule performs <strong>static DOM validation only</strong>: both the
 * set of {@code href="#..."} anchors and the set of existing {@code id}
 * values are read directly from the live DOM in a single
 * {@link Page#evaluate(String)} round-trip. No navigation, scrolling, or
 * click simulation occurs — {@code id} matching is a pure set-membership
 * check against the document as rendered.</p>
 *
 * <p>Per the HTML specification, {@code id} matching is
 * <strong>case-sensitive</strong>; {@code href="#Section"} does not match
 * {@code id="section"} and is reported as broken.</p>
 *
 * <h2>Thread safety</h2>
 * <p>This class is stateless and has no mutable fields. It is safe for
 * concurrent use from multiple TestNG threads, each operating on its own
 * {@link Page}.</p>
 */
public final class BrokenAnchorRule implements AuditRule {

    private static final Logger log = LoggerFactory.getLogger(BrokenAnchorRule.class);

    // -------------------------------------------------------------------------
    // Rule identity
    // -------------------------------------------------------------------------

    public static final String RULE_ID = "BROKEN_ANCHOR";

    private static final String DESCRIPTION =
            "Validates that every in-page fragment link (href=\"#section\") "
                    + "resolves to an element with a matching id on the page.";

    /** Caps the number of individual findings to keep report output readable. */
    private static final int MAX_FINDINGS = 25;

    /** Empty-fragment href, conventionally used as a "scroll to top" link. */
    private static final String EMPTY_FRAGMENT = "#";

    /** Prefix used to detect (and ignore) script-driven placeholder links. */
    private static final String JAVASCRIPT_SCHEME_PREFIX = "javascript:";

    /** Title used for every finding produced by this rule. */
    private static final String FINDING_TITLE = "Broken Anchor";

    /** Placeholder used when an anchor's href attribute is empty or blank. */
    private static final String EMPTY_HREF_PLACEHOLDER = "(empty href)";

    /** Placeholder used when an anchor has no readable display text. */
    private static final String NO_TEXT_PLACEHOLDER = "(no visible text)";

    // -------------------------------------------------------------------------
    // JavaScript used to extract anchors and ids in one round-trip
    // -------------------------------------------------------------------------

    /**
     * Returns { ids: string[], anchors: Array<{text: string, href: string}> } where:
     * <ul>
     *   <li>{@code ids} is every non-empty {@code id} attribute value present
     *       on the page (duplicates collapsed via a {@code Set}), and</li>
     *   <li>{@code anchors} captures objects containing the {@code text} and {@code href}
     *       attributes of every {@code <a href>} element, in document order.</li>
     * </ul>
     */
    private static final String EXTRACT_ANCHORS_AND_IDS_JS = """
            () => {
                const ids = Array.from(
                    new Set(
                        Array.from(document.querySelectorAll('[id]'))
                             .map(el => el.getAttribute('id'))
                             .filter(id => id !== null && id.trim().length > 0)
                    )
                );

                const anchors = Array.from(document.querySelectorAll('a[href]'))
                    .map(a => ({
                        text: a.innerText || '',
                        href: a.getAttribute('href')
                    }))
                    .filter(a => a.href !== null);

                return { ids, anchors };
            }
            """;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /** Creates a {@code BrokenAnchorRule} with default configuration. */
    public BrokenAnchorRule() {
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
        return "Broken Anchor Not Found";
    }

    @Override
    public String failImpact() {
        return "Broken Anchor Found";
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
     * Runs broken-anchor validation against {@code page}.
     *
     * @param page live, fully loaded Playwright page; must not be {@code null}
     * @return PASS when every fragment link resolves to an existing id,
     *         FAIL when one or more fragment links have no matching target,
     *         ERROR when DOM extraction itself fails
     */
    @Override
    public RuleResult execute(final Page page) {
        Objects.requireNonNull(page, "page must not be null");

        final long startMs = System.currentTimeMillis();
        log.info("[{}] Starting broken anchor validation on: {}", RULE_ID, safeUrl(page));

        final ExtractionResult extraction;
        try {
            extraction = extractAnchorsAndIds(page);
        } catch (final Exception e) {
            log.error("[{}] Failed to extract anchors/ids: {}", RULE_ID, e.getMessage(), e);
            return RuleResult.error(this, startMs, e);
        }

        log.info("[{}] {} id(s) and {} anchor(s) found",
                RULE_ID, extraction.ids().size(), extraction.anchors().size());

        final AnchorFindings results = buildFindings(extraction);

        if (results.brokenFindings().isEmpty()) {
            log.info("[{}] All fragment links resolve to an existing id", RULE_ID);
            return RuleResult.builder(this, RuleResult.Status.PASS, startMs)
                    .withFindings(results.passFindings())
                    .build();
        }

        log.warn("[{}] {} broken anchor(s) found", RULE_ID, results.brokenFindings().size());
        return RuleResult.builder(this, RuleResult.Status.FAIL, startMs)
                .withFindings(results.brokenFindings())
                .build();
    }

    // -------------------------------------------------------------------------
    // Internal – finding construction
    // -------------------------------------------------------------------------

    /**
     * Evaluates every fragment anchor against the set of known ids and builds
     * one structured finding per anchor (PASS or FAIL) using
     * {@link FindingFormatter#linkFinding()}. Broken findings are capped at
     * {@value #MAX_FINDINGS} with an overflow summary appended if exceeded.
     */
    private static AnchorFindings buildFindings(final ExtractionResult extraction) {
        final Set<String> ids = new HashSet<>(extraction.ids());
        final List<String> brokenFindings = new ArrayList<>();
        final List<String> passFindings = new ArrayList<>();

        for (final Map<String, String> anchorMap : extraction.anchors()) {
            final String href = anchorMap.get("href");
            final String text = anchorMap.get("text");
            final String displayText = (text != null && !text.isBlank())
                    ? text
                    : NO_TEXT_PLACEHOLDER;

            // Empty/blank href is always reported as broken, independent of
            // fragment-link detection below.
            if (href == null || href.isBlank()) {
                brokenFindings.add(
                        FindingFormatter.linkFinding()
                                .title(FINDING_TITLE)
                                .displayText(displayText)
                                .href(EMPTY_HREF_PLACEHOLDER)
                                .failed("Missing href")
                                .build());
                continue;
            }

            final String target = fragmentTarget(href);
            if (target == null) {
                continue; // not a fragment link, or ignored per rule contract
            }

            if (ids.contains(target)) {
                passFindings.add(
                        FindingFormatter.linkFinding()
                                .title(FINDING_TITLE)
                                .displayText(displayText)
                                .href(href)
                                .passed("Anchor target exists.")
                                .build());
            } else {
                brokenFindings.add(
                        FindingFormatter.linkFinding()
                                .title(FINDING_TITLE)
                                .displayText(displayText)
                                .href(href)
                                .failed(String.format(
                                        "Target element with id=\"%s\" does not exist.", target))
                                .build());
            }
        }

        return new AnchorFindings(capFindings(brokenFindings), passFindings);
    }

    /** Caps {@code findings} at {@value #MAX_FINDINGS}, appending an overflow summary if exceeded. */
    private static List<String> capFindings(final List<String> findings) {
        if (findings.size() <= MAX_FINDINGS) {
            return findings;
        }

        final List<String> capped = new ArrayList<>(findings.subList(0, MAX_FINDINGS));
        capped.add(String.format("… and %d more broken anchor(s)",
                findings.size() - MAX_FINDINGS));
        return capped;
    }

    /**
     * Returns the fragment identifier (text after {@code #}) that {@code href}
     * should resolve to, or {@code null} if {@code href} is not a fragment
     * link that requires validation.
     */
    private static String fragmentTarget(final String href) {
        if (href == null) return null;

        final String trimmed = href.trim();

        // Explicitly ignore javascript: placeholder links
        if (trimmed.toLowerCase().startsWith(JAVASCRIPT_SCHEME_PREFIX)) {
            return null;
        }

        if (!trimmed.startsWith(EMPTY_FRAGMENT)) {
            return null; // not a fragment link
        }

        if (trimmed.equals(EMPTY_FRAGMENT)) {
            return null; // "#" – scroll-to-top convention, nothing to validate
        }

        return trimmed.substring(1);
    }

    // -------------------------------------------------------------------------
    // Internal – page extraction
    // -------------------------------------------------------------------------

    /**
     * Evaluates JavaScript in the page to extract the id set and anchor data map list.
     */
    @SuppressWarnings("unchecked")
    private static ExtractionResult extractAnchorsAndIds(final Page page) {
        try {
            final Object raw = page.evaluate(EXTRACT_ANCHORS_AND_IDS_JS);

            if (!(raw instanceof Map<?, ?> map)) {
                log.warn("[{}] Unexpected JS evaluation result type: {}",
                        RULE_ID, raw == null ? "null" : raw.getClass().getSimpleName());
                return new ExtractionResult(List.of(), List.of());
            }

            final List<String> ids = extractStringList(map.get("ids"));

            // Extract the anchor list structures safely
            final List<Map<String, String>> anchors = new ArrayList<>();
            if (map.get("anchors") instanceof List<?> rawList) {
                for (final Object item : rawList) {
                    if (item instanceof Map<?, ?> anchorMap) {
                        anchors.add((Map<String, String>) anchorMap);
                    }
                }
            }

            return new ExtractionResult(ids, anchors);

        } catch (final PlaywrightException e) {
            log.error("[{}] Playwright error during anchor/id extraction: {}", RULE_ID, e.getMessage(), e);
            throw e;
        }
    }

    private static List<String> extractStringList(final Object value) {
        if (!(value instanceof List<?> rawList)) {
            return List.of();
        }
        final List<String> result = new ArrayList<>(rawList.size());
        for (final Object item : rawList) {
            if (item instanceof String s) {
                result.add(s);
            }
        }
        return result;
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

    // -------------------------------------------------------------------------
    // Internal records
    // -------------------------------------------------------------------------

    /**
     * Result of extracting both the page's {@code id} values and its complex anchor properties.
     *
     * @param ids     all non-empty {@code id} attribute values on the page
     * @param anchors maps containing raw text and href attributes for every anchor
     */
    private record ExtractionResult(List<String> ids, List<Map<String, String>> anchors) {}

    /**
     * Structured findings produced while evaluating every anchor on the page.
     *
     * @param brokenFindings FAIL findings, one per anchor whose target id is missing
     *                       (or whose href is empty), capped at {@value #MAX_FINDINGS}
     * @param passFindings   PASS findings, one per anchor that resolved successfully
     */
    private record AnchorFindings(List<String> brokenFindings, List<String> passFindings) {}
}