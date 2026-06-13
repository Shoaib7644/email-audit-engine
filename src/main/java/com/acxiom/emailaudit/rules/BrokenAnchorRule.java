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
 *   <li>{@code href="#"} (empty fragment) — <strong>ignored</strong>. This is
 *       the conventional "scroll to top of page" pattern and has no target
 *       {@code id} to validate.</li>
 *   <li>{@code href="javascript:void(0)"} (or any {@code javascript:} scheme,
 *       case-insensitive) — <strong>ignored</strong>. These are script-driven
 *       placeholder links, not fragment navigation, and never start with
 *       {@code #}.</li>
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

    // -------------------------------------------------------------------------
    // JavaScript used to extract anchors and ids in one round-trip
    // -------------------------------------------------------------------------

    /**
     * Returns {@code { ids: string[], anchors: string[] } } where:
     * <ul>
     *   <li>{@code ids} is every non-empty {@code id} attribute value present
     *       on the page (duplicates collapsed via a {@code Set}), and</li>
     *   <li>{@code anchors} is the raw {@code href} attribute value of every
     *       {@code <a href>} element, in document order.</li>
     * </ul>
     * Collecting both in a single call avoids two separate page round-trips.
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
                    .map(a => a.getAttribute('href'))
                    .filter(href => href !== null);

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

        final List<String> findings = buildFindings(extraction);

        if (findings.isEmpty()) {
            log.info("[{}] All fragment links resolve to an existing id", RULE_ID);
            return RuleResult.pass(this, startMs);
        }

        log.warn("[{}] {} broken anchor(s) found", RULE_ID, findings.size());
        return RuleResult.fail(this, startMs, findings);
    }

    // -------------------------------------------------------------------------
    // Internal – finding construction
    // -------------------------------------------------------------------------

    /**
     * Evaluates every fragment anchor against the set of known ids and builds
     * one finding per broken link. Capped at {@value #MAX_FINDINGS} with an
     * overflow summary appended if exceeded.
     */
    private static List<String> buildFindings(final ExtractionResult extraction) {
        final Set<String> ids = new HashSet<>(extraction.ids());
        final List<String> findings = new ArrayList<>();

        for (final String href : extraction.anchors()) {
            final String target = fragmentTarget(href);
            if (target == null) {
                continue; // not a fragment link, or ignored per rule contract
            }

            if (!ids.contains(target)) {
                findings.add(String.format(
                        "Broken anchor: href=\"#%s\" has no element with id=\"%s\"",
                        target, target));
            }
        }

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
     *
     * <p>Returns {@code null} for:</p>
     * <ul>
     *   <li>{@code href} that does not start with {@code #} (including
     *       {@code javascript:void(0)} and absolute/relative URLs),</li>
     *   <li>{@code href="#"} (empty fragment — "scroll to top" convention).</li>
     * </ul>
     */
    private static String fragmentTarget(final String href) {
        if (href == null) return null;

        final String trimmed = href.trim();

        // Explicitly ignore javascript: placeholder links (defensive — these
        // never start with '#', but guarded here per the rule contract).
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
     * Evaluates JavaScript in the page to extract the id set and anchor href
     * list in a single round-trip.
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

            final List<String> ids     = extractStringList(map.get("ids"));
            final List<String> anchors = extractStringList(map.get("anchors"));

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
     * Result of extracting both the page's {@code id} values and its anchor
     * {@code href} values in one evaluation.
     *
     * @param ids     all non-empty {@code id} attribute values on the page
     * @param anchors raw {@code href} attribute values of every {@code <a href>}
     */
    private record ExtractionResult(List<String> ids, List<String> anchors) {}
}
