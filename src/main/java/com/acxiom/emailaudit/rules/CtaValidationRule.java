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
 * <h2>PASS evidence</h2>
 * <p>When the rule passes, it now also returns one evidence finding per
 * valid CTA candidate via {@link FindingFormatter#linkFinding()}, so the
 * dashboard can show exactly which CTAs were reviewed and confirmed
 * functional, e.g.:</p>
 * <pre>
 * CTA Validation
 *   Displayed Text  : Shop Now
 *   Destination     : https://brand.com/shop
 *   Validation      : PASSED
 *   Reason          : CTA is visible with valid text and href
 * </pre>
 * <p>{@code <button>} elements (which have no href to validate) report
 * {@code "(not applicable)"} as the destination, mirroring the FAIL-path
 * convention already used in {@link #buildFindings(List)}.</p>
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

    /** Caps the number of PASS evidence findings to keep report output readable. */
    private static final int MAX_PASS_EVIDENCE = 25;

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
    public String passImpact() {
        return "CTA Validation is Present";
    }

    @Override
    public String failImpact() {
        return "CTA Validation is Failed or Incomplete.";
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
     * @return PASS (with evidence findings) when every detected CTA has
     *         visible text, a valid href (for links), and is not hidden;
     *         FAIL when one or more CTAs violate these checks; ERROR when
     *         DOM extraction itself fails
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
            return RuleResult.builder(this, RuleResult.Status.PASS, startMs)
                    .withFindings(buildPassEvidence(ctas))
                    .build();
        }

        log.warn("[{}] {} CTA issue(s) found", RULE_ID, findings.size());
        return RuleResult.fail(this, startMs, findings);
    }

    // -------------------------------------------------------------------------
    // Internal – finding construction
    // -------------------------------------------------------------------------

    /**
     * Builds individual non-aggregated findings for every CTA that violates structural validation.
     * Each failure generates its own finding formatted via FindingFormatter.linkFinding().
     * Capped at {@value #MAX_FINDINGS} with an overflow marker if exceeded.
     */
    private static List<String> buildFindings(final List<CtaEntry> ctas) {
        final List<String> findings = new ArrayList<>();

        for (final CtaEntry cta : ctas) {
            final String textValue = cta.text().isBlank() ? "(empty text)" : cta.text();

            // Map the display parameters for the destination field
            final String destinationValue;
            if ("button".equals(cta.tag())) {
                destinationValue = "(not applicable)";
            } else {
                destinationValue = (cta.href() == null || cta.href().isBlank()) ? "(empty href)" : cta.href();
            }

            // Check 1: CTA Visibility Rule Violation
            if (cta.hidden()) {
                String finding = FindingFormatter.linkFinding()
                        .title("CTA Validation")
                        .displayText(textValue)
                        .href(destinationValue)
                        .failed("CTA hidden using display:none")
                        .build();
                findings.add(finding);
            }

            // Check 2: Empty Content Rule Violation
            if (cta.text().isBlank()) {
                String finding = FindingFormatter.linkFinding()
                        .title("CTA Validation")
                        .displayText(textValue)
                        .href(destinationValue)
                        .failed("CTA has no visible text")
                        .build();
                findings.add(finding);
            }

            // Check 3: Missing Target Href Navigation Rule Violation (Only applies to anchor tags)
            if ("a".equals(cta.tag()) && (cta.href() == null || cta.href().isBlank())) {
                String finding = FindingFormatter.linkFinding()
                        .title("CTA Validation")
                        .displayText(textValue)
                        .href("(empty href)")
                        .failed("CTA has no href")
                        .build();
                findings.add(finding);
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
     * Builds one PASS evidence finding per valid CTA candidate via
     * {@link FindingFormatter#linkFinding()}, capped at
     * {@value #MAX_PASS_EVIDENCE} with an overflow summary appended if
     * exceeded.
     *
     * <p>Example output for an {@code <a>} CTA:
     * <pre>
     * CTA Validation
     *   Displayed Text  : Shop Now
     *   Destination     : https://brand.com/shop
     *   Validation      : PASSED
     *   Reason          : CTA is visible with valid text and href
     * </pre>
     * <p>Example output for a {@code <button>} CTA (no href to validate):
     * <pre>
     * CTA Validation
     *   Displayed Text  : Submit
     *   Destination     : (not applicable)
     *   Validation      : PASSED
     *   Reason          : CTA is visible with valid text
     * </pre>
     */
    private static List<String> buildPassEvidence(final List<CtaEntry> ctas) {
        final List<String> evidence = new ArrayList<>();

        for (final CtaEntry cta : ctas) {
            final String textValue = cta.text().isBlank() ? "(empty text)" : cta.text();

            final boolean isButton = "button".equals(cta.tag());
            final String destinationValue = isButton
                    ? "(not applicable)"
                    : (cta.href() == null || cta.href().isBlank() ? "(empty href)" : cta.href());

            final String reason = isButton
                    ? "CTA is visible with valid text"
                    : "CTA is visible with valid text and href";

            evidence.add(FindingFormatter.linkFinding()
                    .title("CTA Validation")
                    .displayText(textValue)
                    .href(destinationValue)
                    .passed(reason)
                    .build());
        }

        if (evidence.size() <= MAX_PASS_EVIDENCE) {
            return evidence;
        }

        final List<String> capped = new ArrayList<>(evidence.subList(0, MAX_PASS_EVIDENCE));
        capped.add(FindingFormatter.generic(RULE_ID,
                String.format("… and %d more valid CTA(s)",
                        evidence.size() - MAX_PASS_EVIDENCE)));
        return capped;
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