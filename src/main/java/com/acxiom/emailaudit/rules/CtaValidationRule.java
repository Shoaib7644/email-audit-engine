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
 * {@link AuditRule} that validates primary call-to-action (CTA) elements —
 * {@code <button>} elements and {@code <a>} elements styled to look like
 * buttons — on a rendered HTML page.
 *
 * <h2>Why this matters</h2>
 * <p>A CTA is the single most important interactive element in most marketing
 * emails. A CTA that is invisible, has no destination, or has no readable
 * label is a silent conversion-killer: it looks correct to a developer
 * reviewing markup but fails completely for the recipient.</p>
 *
 * <h2>CTA detection</h2>
 * <p>An element is treated as a CTA candidate if it is:</p>
 * <ul>
 *   <li>any {@code <button>} element, or</li>
 *   <li>any {@code <a>} element that is <strong>styled as a button</strong>,
 *       determined by any of:
 *       <ul>
 *         <li>{@code role="button"}, or</li>
 *         <li>a {@code class} attribute containing {@code "btn"} or
 *             {@code "button"} (case-insensitive), or</li>
 *         <li>a computed style with both a non-transparent
 *             {@code background-color} <em>and</em> non-zero padding —
 *             the visual hallmark of a button in HTML email markup, where
 *             {@code <a>} tags are styled with inline padding/background
 *             rather than using native {@code <button>} elements.</li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * <h2>Checks performed</h2>
 * <p>For every detected CTA candidate:</p>
 * <ul>
 *   <li><strong>Empty text</strong> — the element's trimmed visible text is empty.</li>
 *   <li><strong>Missing href</strong> — applies only to {@code <a>} elements:
 *       the {@code href} attribute is absent or empty. {@code <button>}
 *       elements are not required to have an {@code href}.</li>
 *   <li><strong>Hidden</strong> — the element's computed {@code display} is
 *       {@code none}, {@code visibility} is {@code hidden}, or
 *       {@code opacity} is {@code 0}.</li>
 * </ul>
 * <p>A single CTA may produce more than one finding if it has multiple issues
 * (e.g. a hidden button with no text).</p>
 *
 * <h2>DOM and Playwright usage</h2>
 * <p>CTA detection, text extraction, and computed-style checks are all
 * performed via a single {@link Page#evaluate(String)} round-trip using
 * {@code window.getComputedStyle}, leveraging Playwright's rendered page —
 * not the raw HTML source — so styles applied via {@code <style>} blocks or
 * external stylesheets are correctly reflected.</p>
 *
 * <h2>Thread safety</h2>
 * <p>This class is stateless and has no mutable fields. It is safe for
 * concurrent use from multiple TestNG threads, each operating on its own
 * {@link Page}.</p>
 */
public final class CtaValidationRule implements AuditRule {

    private static final Logger log = LoggerFactory.getLogger(CtaValidationRule.class);

    // -------------------------------------------------------------------------
    // Rule identity
    // -------------------------------------------------------------------------

    public static final String RULE_ID = "CTA_VALIDATION";

    private static final String DESCRIPTION =
            "Validates primary call-to-action buttons and button-styled links "
                    + "for visible text, a valid href, and visibility.";

    /** Caps the number of individual findings to keep report output readable. */
    private static final int MAX_FINDINGS = 25;

    private static final String EMPTY_TEXT_PLACEHOLDER = "(empty text)";

    // -------------------------------------------------------------------------
    // JavaScript used to detect CTA candidates and their state in one round-trip
    // -------------------------------------------------------------------------

    /**
     * Returns a JSON array of objects {@code {tag, text, href, hidden}} for
     * every detected CTA candidate, in document order.
     *
     * <ul>
     *   <li>{@code tag} — {@code "button"} or {@code "a"}</li>
     *   <li>{@code text} — trimmed visible text</li>
     *   <li>{@code href} — raw {@code href} attribute for {@code <a>}
     *       elements, or {@code null} for {@code <button>} elements
     *       (the href check does not apply to buttons)</li>
     *   <li>{@code hidden} — {@code true} if {@code display: none},
     *       {@code visibility: hidden}, or {@code opacity: 0}</li>
     * </ul>
     */
    private static final String EXTRACT_CTAS_JS = """
            () => {
                const isButtonStyled = (el) => {
                    const role = (el.getAttribute('role') || '').toLowerCase();
                    if (role === 'button') return true;

                    const cls = (el.className || '').toString().toLowerCase();
                    if (/\\bbtn\\b|\\bbutton\\b/.test(cls)) return true;

                    const style = window.getComputedStyle(el);
                    const bg = style.backgroundColor;
                    const hasBackground = bg
                        && bg !== 'transparent'
                        && bg !== 'rgba(0, 0, 0, 0)';
                    const hasPadding = parseFloat(style.paddingTop) > 0
                        || parseFloat(style.paddingBottom) > 0
                        || parseFloat(style.paddingLeft) > 0
                        || parseFloat(style.paddingRight) > 0;

                    return hasBackground && hasPadding;
                };

                const isHidden = (el) => {
                    const style = window.getComputedStyle(el);
                    return style.display === 'none'
                        || style.visibility === 'hidden'
                        || parseFloat(style.opacity) === 0;
                };

                const candidates = [];

                document.querySelectorAll('button').forEach(el => candidates.push(el));
                document.querySelectorAll('a').forEach(el => {
                    if (isButtonStyled(el)) candidates.push(el);
                });

                return candidates.map(el => {
                    const tag = el.tagName.toLowerCase();
                    return {
                        tag: tag,
                        text: (el.innerText || el.textContent || '').trim(),
                        href: tag === 'a' ? (el.getAttribute('href') || '') : null,
                        hidden: isHidden(el)
                    };
                });
            }
            """;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /** Creates a {@code CtaValidationRule} with default configuration. */
    public CtaValidationRule() {
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
        return RuleCategory.CONTENT;
    }

    @Override
    public RuleSeverity severity() {
        return RuleSeverity.HIGH;
    }

    /**
     * Runs CTA validation against {@code page}.
     *
     * @param page live, fully loaded Playwright page; must not be {@code null}
     * @return PASS when every detected CTA has visible text, a valid href
     *         (for links), and is not hidden; FAIL when one or more CTAs
     *         violate these checks; ERROR when DOM extraction itself fails
     */
    @Override
    public RuleResult execute(final Page page) {
        Objects.requireNonNull(page, "page must not be null");

        final long startMs = System.currentTimeMillis();
        log.info("[{}] Starting CTA validation on: {}", RULE_ID, safeUrl(page));

        final List<CtaEntry> ctas;
        try {
            ctas = extractCtas(page);
        } catch (final Exception e) {
            log.error("[{}] Failed to extract CTA candidates: {}", RULE_ID, e.getMessage(), e);
            return RuleResult.error(this, startMs, e);
        }

        log.info("[{}] {} CTA candidate(s) found", RULE_ID, ctas.size());

        final List<String> findings = buildFindings(ctas);

        if (findings.isEmpty()) {
            log.info("[{}] All CTAs are valid", RULE_ID);
            return RuleResult.pass(this, startMs);
        }

        log.warn("[{}] {} CTA issue(s) found", RULE_ID, findings.size());
        return RuleResult.fail(this, startMs, findings);
    }

    // -------------------------------------------------------------------------
    // Internal – finding construction
    // -------------------------------------------------------------------------

    /**
     * Builds findings for every CTA that violates one or more checks. A single
     * CTA may produce multiple findings. Capped at {@value #MAX_FINDINGS} with
     * an overflow summary appended if exceeded.
     */
    private static List<String> buildFindings(final List<CtaEntry> ctas) {
        final List<String> findings = new ArrayList<>();

        for (final CtaEntry cta : ctas) {
            final String label = describeCta(cta);

            if (cta.hidden()) {
                findings.add(String.format(
                        "CTA is hidden: %s (display:none, visibility:hidden, or opacity:0)",
                        label));
            }

            if (cta.text().isBlank()) {
                findings.add(String.format("CTA has no visible text: %s", label));
            }

            if ("a".equals(cta.tag()) && (cta.href() == null || cta.href().isBlank())) {
                findings.add(String.format("CTA link has no href: %s", label));
            }
        }

        if (findings.size() <= MAX_FINDINGS) {
            return findings;
        }

        final List<String> capped = new ArrayList<>(findings.subList(0, MAX_FINDINGS));
        capped.add(String.format("… and %d more CTA issue(s)",
                findings.size() - MAX_FINDINGS));
        return capped;
    }

    /**
     * Builds a human-readable label identifying a CTA in findings, e.g.
     * {@code "<a> \"Shop Now\""} or {@code "<button> (empty text)"}.
     */
    private static String describeCta(final CtaEntry cta) {
        final String text = cta.text().isBlank() ? EMPTY_TEXT_PLACEHOLDER : "\"" + cta.text() + "\"";
        return String.format("<%s> %s", cta.tag(), text);
    }

    // -------------------------------------------------------------------------
    // Internal – page extraction
    // -------------------------------------------------------------------------

    /**
     * Evaluates JavaScript in the page to extract all CTA candidates and
     * returns them as typed {@link CtaEntry} records, in document order.
     */
    @SuppressWarnings("unchecked")
    private static List<CtaEntry> extractCtas(final Page page) {
        try {
            final Object raw = page.evaluate(EXTRACT_CTAS_JS);

            if (!(raw instanceof List<?> rawList)) {
                log.warn("[{}] Unexpected JS evaluation result type: {}",
                        RULE_ID, raw == null ? "null" : raw.getClass().getSimpleName());
                return List.of();
            }

            final List<CtaEntry> entries = new ArrayList<>(rawList.size());
            for (final Object item : rawList) {
                if (item instanceof Map<?, ?> map) {
                    final String  tag    = stringOrEmpty(map.get("tag"));
                    final String  text   = stringOrEmpty(map.get("text"));
                    final String  href   = stringOrNull(map.get("href"));
                    final boolean hidden = booleanOrFalse(map.get("hidden"));
                    entries.add(new CtaEntry(tag, text, href, hidden));
                }
            }

            return entries;

        } catch (final PlaywrightException e) {
            log.error("[{}] Playwright error during CTA extraction: {}", RULE_ID, e.getMessage(), e);
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

    /** Preserves {@code null} (used to represent "not applicable" for href on buttons). */
    private static String stringOrNull(final Object value) {
        return value instanceof String s ? s : null;
    }

    private static boolean booleanOrFalse(final Object value) {
        return value instanceof Boolean b && b;
    }

    // -------------------------------------------------------------------------
    // Internal records
    // -------------------------------------------------------------------------

    /**
     * Typed representation of a single CTA candidate extracted from the page.
     *
     * @param tag    {@code "button"} or {@code "a"}
     * @param text   trimmed visible text
     * @param href   raw {@code href} attribute for {@code <a>} elements, or
     *               {@code null} for {@code <button>} elements (href check
     *               not applicable)
     * @param hidden whether the element is hidden via display/visibility/opacity
     */
    private record CtaEntry(String tag, String text, String href, boolean hidden) {}
}
