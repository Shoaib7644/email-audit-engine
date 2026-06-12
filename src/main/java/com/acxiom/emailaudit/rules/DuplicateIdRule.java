package com.acxiom.emailaudit.rules;

import com.acxiom.emailaudit.rules.AuditRule;
import com.acxiom.emailaudit.rules.RuleResult;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link AuditRule} that detects duplicate {@code id} attribute values across
 * a rendered HTML page.
 *
 * <h2>Why this matters</h2>
 * <p>Per the HTML specification, every {@code id} value within a document
 * must be unique. Duplicate IDs break {@code label[for]} associations,
 * fragment links ({@code #anchor}), {@code getElementById} lookups, and
 * accessibility tooling that relies on {@code aria-labelledby} /
 * {@code aria-describedby} references — all common failure modes in
 * email templates assembled from repeated component blocks.</p>
 *
 * <h2>Checks performed</h2>
 * <p>Every element with a non-empty {@code id} attribute is collected in
 * document order. IDs occurring more than once are reported as findings,
 * each including the duplicated {@code id} value and its total occurrence
 * count.</p>
 *
 * <h2>Extraction strategy</h2>
 * <p>This rule is <strong>DOM-parsing only</strong>: all {@code id} values are
 * read directly from the live DOM via a single {@link Page#evaluate(String)}
 * round-trip (consistent with {@link LinkValidationRule} and
 * {@link AltTextValidationRule}). No HTML source text is parsed separately —
 * the rendered DOM is the single source of truth, so duplicates introduced by
 * dynamic markup are caught the same as duplicates present in the raw source.</p>
 *
 * <h2>Thread safety</h2>
 * <p>This class is stateless and has no mutable fields. It is safe for
 * concurrent use from multiple TestNG threads, each operating on its own
 * {@link Page}.</p>
 */
public final class DuplicateIdRule implements AuditRule {

    private static final Logger log = LoggerFactory.getLogger(DuplicateIdRule.class);

    // -------------------------------------------------------------------------
    // Rule identity
    // -------------------------------------------------------------------------

    public static final String RULE_ID = "DUPLICATE_ID";

    private static final String DESCRIPTION =
            "Detects duplicate id attribute values across the rendered page, "
                    + "which break label associations, fragment links, and "
                    + "accessibility references.";

    /** Caps the number of individual findings to keep report output readable. */
    private static final int MAX_FINDINGS = 25;

    // -------------------------------------------------------------------------
    // JavaScript used to extract all id values in one round-trip
    // -------------------------------------------------------------------------

    /**
     * Returns a JSON array of every non-empty {@code id} attribute value found
     * on the page, in document order. Duplicate values appear multiple times
     * in the array — one entry per element — so the Java side can simply
     * count occurrences.
     */
    private static final String EXTRACT_IDS_JS = """
            () => Array.from(document.querySelectorAll('[id]'))
                       .map(el => el.getAttribute('id'))
                       .filter(id => id !== null && id.trim().length > 0)
            """;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /** Creates a {@code DuplicateIdRule} with default configuration. */
    public DuplicateIdRule() {
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
        return RuleCategory.HTML;
    }

    @Override
    public RuleSeverity severity() {
        return RuleSeverity.MEDIUM;
    }

    /**
     * Runs duplicate-ID detection against {@code page}.
     *
     * @param page live, fully loaded Playwright page; must not be {@code null}
     * @return PASS when every {@code id} value is unique,
     *         FAIL when one or more duplicate {@code id} values are found,
     *         ERROR when DOM extraction itself fails
     */
    @Override
    public RuleResult execute(final Page page) {
        Objects.requireNonNull(page, "page must not be null");

        final long startMs = System.currentTimeMillis();
        log.info("[{}] Starting duplicate id detection on: {}", RULE_ID, safeUrl(page));

        final List<String> ids;
        try {
            ids = extractIds(page);
        } catch (final Exception e) {
            log.error("[{}] Failed to extract id attributes: {}", RULE_ID, e.getMessage(), e);
            return RuleResult.error(this, startMs, e);
        }

        log.info("[{}] {} element(s) with an id attribute found", RULE_ID, ids.size());

        final Map<String, Integer> occurrenceCounts = countOccurrences(ids);
        final List<String> findings = buildFindings(occurrenceCounts);

        if (findings.isEmpty()) {
            log.info("[{}] No duplicate id values found", RULE_ID);
            return RuleResult.pass(this, startMs);
        }

        log.warn("[{}] {} duplicate id value(s) found", RULE_ID, findings.size());
        return RuleResult.fail(this, startMs, findings);
    }

    // -------------------------------------------------------------------------
    // Internal – counting and finding construction
    // -------------------------------------------------------------------------

    /**
     * Counts occurrences of each {@code id} value, preserving first-seen order
     * so findings are reported deterministically.
     */
    private static Map<String, Integer> countOccurrences(final List<String> ids) {
        final Map<String, Integer> counts = new LinkedHashMap<>();
        for (final String id : ids) {
            counts.merge(id, 1, Integer::sum);
        }
        return counts;
    }

    /**
     * Builds one finding per duplicated {@code id} value, including the value
     * and its total occurrence count. Capped at {@value #MAX_FINDINGS} with an
     * overflow summary appended if exceeded.
     */
    private static List<String> buildFindings(final Map<String, Integer> occurrenceCounts) {
        final List<String> duplicates = new ArrayList<>();

        for (final Map.Entry<String, Integer> entry : occurrenceCounts.entrySet()) {
            final int count = entry.getValue();
            if (count > 1) {
                duplicates.add(String.format(
                        "Duplicate id '%s' found %d times", entry.getKey(), count));
            }
        }

        if (duplicates.size() <= MAX_FINDINGS) {
            return duplicates;
        }

        final List<String> capped = new ArrayList<>(duplicates.subList(0, MAX_FINDINGS));
        capped.add(String.format("… and %d more duplicate id value(s)",
                duplicates.size() - MAX_FINDINGS));
        return capped;
    }

    // -------------------------------------------------------------------------
    // Internal – page extraction
    // -------------------------------------------------------------------------

    /**
     * Evaluates JavaScript in the page to extract every non-empty {@code id}
     * attribute value, in document order.
     */
    private static List<String> extractIds(final Page page) {
        try {
            final Object raw = page.evaluate(EXTRACT_IDS_JS);

            if (!(raw instanceof List<?> rawList)) {
                log.warn("[{}] Unexpected JS evaluation result type: {}",
                        RULE_ID, raw == null ? "null" : raw.getClass().getSimpleName());
                return List.of();
            }

            final List<String> ids = new ArrayList<>(rawList.size());
            for (final Object item : rawList) {
                if (item instanceof String s && !s.isBlank()) {
                    ids.add(s);
                }
            }
            return ids;

        } catch (final PlaywrightException e) {
            log.error("[{}] Playwright error during id extraction: {}", RULE_ID, e.getMessage(), e);
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
}
