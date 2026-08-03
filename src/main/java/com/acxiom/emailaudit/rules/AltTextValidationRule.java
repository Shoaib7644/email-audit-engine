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
 * {@link AuditRule} that validates every {@code <img>} element on a rendered
 * HTML page for the presence of a meaningful {@code alt} attribute.
 *
 * <h2>Checks performed</h2>
 * <p>For each {@code <img>} element found on the page, this rule fails if:</p>
 * <ul>
 *   <li>the {@code alt} attribute is <strong>missing entirely</strong>
 *       (no {@code alt} attribute present on the element), or</li>
 *   <li>the {@code alt} attribute is present but is <strong>empty after
 *       trimming whitespace</strong> (e.g. {@code alt=""} or {@code alt="   "}).</li>
 * </ul>
 *
 * <h2>Findings format</h2>
 * <p>Every image on the page — passing or failing — produces an individual
 * structured finding via {@link FindingFormatter#altTextFinding(String, boolean, String)},
 * e.g.:</p>
 * <pre>
 * Alt Text Validation
 *   Image           : hero-banner.png
 *   Validation      : PASSED
 *   Reason          : Alt text is present.
 * </pre>
 * <pre>
 * Alt Text Validation
 *   Image           : hero-banner.png
 *   Validation      : FAILED
 *   Reason          : Missing alt attribute.
 * </pre>
 * <p>Multiple images are never aggregated into one long string — one finding
 * per image, for both PASS and FAIL outcomes.</p>
 *
 * <h2>Extraction strategy</h2>
 * <p>All {@code <img>} elements are collected via a single
 * {@link Page#evaluate(String)} round-trip, which returns the {@code src}
 * attribute, whether {@code alt} is present, and the raw {@code alt} value
 * for every image in document order — consistent with the extraction
 * approach used by {@link LinkValidationRule}.</p>
 *
 * <h2>Thread safety</h2>
 * <p>This class is stateless and has no mutable fields. It is safe for
 * concurrent use from multiple TestNG threads, each operating on its own
 * {@link Page}.</p>
 */
public final class AltTextValidationRule implements AuditRule {

    private static final Logger log = LoggerFactory.getLogger(AltTextValidationRule.class);

    // -------------------------------------------------------------------------
    // Rule identity
    // -------------------------------------------------------------------------

    public static final String RULE_ID = "ALT_TEXT_VALIDATION";

    private static final String DESCRIPTION =
            "Validates that every <img> element has a non-empty alt attribute "
                    + "for screen-reader accessibility.";

    /** Caps the number of individual FAIL findings to keep report output readable. */
    private static final int MAX_FINDINGS = 25;

    /** Caps the number of individual PASS findings to keep report output readable. */
    private static final int MAX_PASS_EVIDENCE = 25;

    /** Placeholder used in findings when an <img> has no usable src value. */
    private static final String UNKNOWN_SRC = "(no src attribute)";

    /** Reason text used when the alt attribute is missing entirely. */
    private static final String REASON_MISSING_ALT = "Missing alt attribute.";

    /** Reason text used when the alt attribute is present but empty. */
    private static final String REASON_EMPTY_ALT = "Alt attribute is present but empty.";

    /** Reason text used when the alt attribute is present and non-empty. */
    private static final String REASON_ALT_PRESENT = "Alt text is present.";

    // -------------------------------------------------------------------------
    // JavaScript used to extract all <img> data in one round-trip
    // -------------------------------------------------------------------------

    /**
     * Returns a JSON array of objects {@code {src, hasAlt, alt, width, height,
     * renderedWidth, renderedHeight}} for every {@code <img>} element on the
     * page, in document order.
     *
     * <p>{@code hasAlt} distinguishes "no alt attribute" ({@code false}) from
     * "alt attribute present but empty" ({@code true} with {@code alt: ""}),
     * which {@code img.getAttribute('alt')} alone cannot do (it returns
     * {@code null} for both an absent attribute and would otherwise be
     * indistinguishable from an empty string once serialised).</p>
     */
    private static final String EXTRACT_IMAGES_JS = """
            () => Array.from(document.querySelectorAll('img')).map(img => {
                const rect = img.getBoundingClientRect();
                return {
                    src: img.getAttribute('src') || '',
                    hasAlt: img.hasAttribute('alt'),
                    alt: img.getAttribute('alt') || '',
                    width: img.getAttribute('width') || '',
                    height: img.getAttribute('height') || '',
                    renderedWidth: rect.width || 0,
                    renderedHeight: rect.height || 0
                };
            })
            """;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /** Creates an {@code AltTextValidationRule} with default configuration. */
    public AltTextValidationRule() {
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
        return "ALT Text is present.";
    }

    @Override
    public String failImpact() {
        return "ALT Text is missing or empty.";
    }
    @Override
    public RuleCategory category() {
        return RuleCategory.ACCESSIBILITY;
    }

    @Override
    public RuleSeverity severity() {
        return RuleSeverity.HIGH;
    }

    /**
     * Runs the alt-text validation against {@code page}.
     *
     * @param page live, fully loaded Playwright page; must not be {@code null}
     * @return PASS (with one evidence finding per valid image) when every
     *         {@code <img>} has a non-empty {@code alt}, FAIL (with one
     *         finding per offending image) when one or more images are
     *         missing it, ERROR when image extraction itself fails
     */
    @Override
    public RuleResult execute(final Page page) {
        Objects.requireNonNull(page, "page must not be null");

        final long startMs = System.currentTimeMillis();
        log.info("[{}] Starting alt-text validation on: {}", RULE_ID, safeUrl(page));

        final List<ImageEntry> images;
        try {
            images = extractImages(page);
        } catch (final Exception e) {
            log.error("[{}] Failed to extract <img> elements: {}", RULE_ID, e.getMessage(), e);
            return RuleResult.error(this, startMs, e);
        }

        log.info("[{}] {} <img> element(s) found", RULE_ID, images.size());

        final List<String> findings = buildFindings(images);

        if (findings.isEmpty()) {
            log.info("[{}] All images have non-empty alt attributes", RULE_ID);
            return RuleResult.builder(this, RuleResult.Status.PASS, startMs)
                    .withFindings(buildPassEvidence(images))
                    .build();
        }

        log.warn("[{}] {} image(s) with missing or empty alt attribute(s)", RULE_ID, findings.size());
        return RuleResult.builder(this, RuleResult.Status.FAIL, startMs)
                .withFindings(findings)
                .build();
    }

    // -------------------------------------------------------------------------
    // Internal – finding construction
    // -------------------------------------------------------------------------

    /**
     * Builds one finding per offending image via
     * {@link FindingFormatter#altTextFinding(String, boolean, String)},
     * capped at {@value #MAX_FINDINGS} with an overflow summary appended if
     * exceeded.
     */
    private static List<String> buildFindings(final List<ImageEntry> images) {
        final List<String> offending = new ArrayList<>();

        for (final ImageEntry image : images) {
            if (image.isTrackingPixel()) {
                continue;
            }

            final String src = image.src().isBlank() ? UNKNOWN_SRC : image.src();

            if (!image.hasAlt()) {
                offending.add(FindingFormatter.altTextFinding(src, false, REASON_MISSING_ALT));
            } else if (image.alt().trim().isEmpty()) {
                offending.add(FindingFormatter.altTextFinding(src, false, REASON_EMPTY_ALT));
            }
        }

        if (offending.size() <= MAX_FINDINGS) {
            return offending;
        }

        final List<String> capped = new ArrayList<>(offending.subList(0, MAX_FINDINGS));
        capped.add(FindingFormatter.generic(RULE_ID,
                String.format("… and %d more image(s) with missing or empty alt attributes",
                        offending.size() - MAX_FINDINGS)));
        return capped;
    }

    /**
     * Builds one PASS evidence finding per image with a valid (present,
     * non-empty) alt attribute, via
     * {@link FindingFormatter#altTextFinding(String, boolean, String)}.
     * Capped at {@value #MAX_PASS_EVIDENCE} with an overflow summary
     * appended if exceeded.
     */
    private static List<String> buildPassEvidence(final List<ImageEntry> images) {
        final List<String> evidence = new ArrayList<>();

        for (final ImageEntry image : images) {
            if (image.isTrackingPixel()) {
                continue;
            }

            if (image.hasAlt() && !image.alt().trim().isEmpty()) {
                final String src = image.src().isBlank() ? UNKNOWN_SRC : image.src();
                evidence.add(FindingFormatter.altTextFinding(src, true, REASON_ALT_PRESENT));
            }
        }

        if (evidence.size() <= MAX_PASS_EVIDENCE) {
            return evidence;
        }

        final List<String> capped = new ArrayList<>(evidence.subList(0, MAX_PASS_EVIDENCE));
        capped.add(FindingFormatter.generic(RULE_ID,
                String.format("… and %d more image(s) with valid alt text",
                        evidence.size() - MAX_PASS_EVIDENCE)));
        return capped;
    }

    // -------------------------------------------------------------------------
    // Internal – page extraction
    // -------------------------------------------------------------------------

    /**
     * Evaluates JavaScript in the page to extract all {@code <img>} elements
     * and returns them as typed {@link ImageEntry} records.
     */
    @SuppressWarnings("unchecked")
    private static List<ImageEntry> extractImages(final Page page) {
        try {
            final Object raw = page.evaluate(EXTRACT_IMAGES_JS);

            if (!(raw instanceof List<?> rawList)) {
                log.warn("[{}] Unexpected JS evaluation result type: {}",
                        RULE_ID, raw == null ? "null" : raw.getClass().getSimpleName());
                return List.of();
            }

            final List<ImageEntry> entries = new ArrayList<>(rawList.size());
            for (final Object item : rawList) {
                if (item instanceof Map<?, ?> map) {
                    final String  src            = stringOrEmpty(map.get("src"));
                    final boolean hasAlt         = booleanOrFalse(map.get("hasAlt"));
                    final String  alt            = stringOrEmpty(map.get("alt"));
                    final String  width          = stringOrEmpty(map.get("width"));
                    final String  height         = stringOrEmpty(map.get("height"));
                    final double  renderedWidth  = doubleOrZero(map.get("renderedWidth"));
                    final double  renderedHeight = doubleOrZero(map.get("renderedHeight"));
                    entries.add(new ImageEntry(
                            src,
                            hasAlt,
                            alt,
                            width,
                            height,
                            renderedWidth,
                            renderedHeight));
                }
            }

            return entries;

        } catch (final PlaywrightException e) {
            log.error("[{}] Playwright error during <img> extraction: {}", RULE_ID, e.getMessage(), e);
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

    private static double doubleOrZero(final Object value) {
        return value instanceof Number n ? n.doubleValue() : 0;
    }

    private static boolean isZeroDimension(final String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            return Double.parseDouble(value.trim()) == 0;
        } catch (final NumberFormatException e) {
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Internal records
    // -------------------------------------------------------------------------

    /**
     * Typed representation of a single {@code <img>} element extracted from
     * the page.
     *
     * @param src            the raw {@code src} attribute value (empty string if absent)
     * @param hasAlt         whether the {@code alt} attribute is present on the element
     * @param alt            the raw {@code alt} attribute value (empty string if absent)
     * @param width          raw {@code width} attribute value
     * @param height         raw {@code height} attribute value
     * @param renderedWidth  rendered CSS width in pixels
     * @param renderedHeight rendered CSS height in pixels
     */
    private record ImageEntry(
            String src,
            boolean hasAlt,
            String alt,
            String width,
            String height,
            double renderedWidth,
            double renderedHeight) {

        private boolean isTrackingPixel() {
            return (isZeroDimension(width) && isZeroDimension(height))
                    || (renderedWidth <= 1 && renderedHeight <= 1
                    && src.toLowerCase(java.util.Locale.ROOT).contains("/r/?"));
        }
    }
}
