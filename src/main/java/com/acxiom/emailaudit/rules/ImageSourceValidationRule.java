package com.acxiom.emailaudit.rules;

import com.acxiom.emailaudit.rules.AuditRule;
import com.acxiom.emailaudit.rules.RuleResult;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.LoadState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link AuditRule} that validates every {@code <img>} on a rendered HTML
 * page actually resolved and loaded — catching broken relative paths,
 * missing local files, and dead remote image URLs that render as
 * broken-image icons in a recipient's inbox.
 *
 * <h2>Why this matters</h2>
 * <p>{@link com.acxiom.emailaudit.rules.AltTextValidationRule} (or the
 * equivalent alt-text rule in this codebase) only confirms that an
 * {@code alt} attribute is present and non-empty — it says nothing about
 * whether the {@code src} the image points to actually exists. A typo'd
 * folder name (e.g. {@code src="vvvv/logo.png"} instead of
 * {@code src="images/logo.png"}) passes alt-text validation cleanly while
 * rendering as a broken image for every recipient. This rule closes that
 * gap.</p>
 *
 * <h2>Detection approach</h2>
 * <p>Rather than re-implementing scheme-specific probing (HTTP HEAD for
 * {@code http(s)://}, filesystem existence checks for {@code file://}, and
 * so on — the same audit engine may be pointed at either a deployed URL or
 * a local HTML file on disk, per {@code page.url()}), this rule reads the
 * outcome directly from the browser: once an {@code <img>} has finished
 * attempting to load, {@code img.complete === true} and
 * {@code img.naturalWidth === 0} together are the DOM's own signal that the
 * image failed to load, regardless of what scheme or path caused the
 * failure. This works uniformly for {@code http://}, {@code https://},
 * {@code file://}, and relative paths, with no separate network client
 * needed, and reflects exactly what the image looks like in a real
 * rendered page — the same thing a recipient's mail client would show.</p>
 *
 * <h2>Checks performed</h2>
 * <p>For every {@code <img>} with a non-empty {@code src} attribute:</p>
 * <ul>
 *   <li><strong>Broken image</strong> — the image finished loading
 *       ({@code complete === true}) but has {@code naturalWidth === 0},
 *       meaning the browser could not decode any image data from the
 *       resolved {@code src}.</li>
 * </ul>
 * <p>Inline {@code data:} URIs are included in the same check — a malformed
 * base64 payload will also report {@code naturalWidth === 0} once the
 * browser fails to decode it, so no separate handling is needed.</p>
 *
 * <h2>PASS evidence</h2>
 * <p>When the rule passes, it also returns one evidence finding per
 * successfully loaded image via {@link FindingFormatter#linkFinding()}, so
 * the dashboard can show exactly which images were reviewed, e.g.:</p>
 * <pre>
 * Image Source Validation
 *   Displayed Text  : Join now
 *   Destination     : https://example.com/images/join_now_cta.png
 *   Validation      : PASSED
 *   Reason          : Image loaded successfully
 * </pre>
 *
 * <h2>DOM and Playwright usage</h2>
 * <p>Waits for {@link LoadState#LOAD} before evaluating, so every image has
 * had a chance to finish (successfully or not) attempting to load. Image
 * data, resolved {@code src}, and load state are all read in a single
 * {@link Page#evaluate(String)} round-trip.</p>
 *
 * <h2>Thread safety</h2>
 * <p>This class is stateless and has no mutable fields. It is safe for
 * concurrent use from multiple TestNG threads, each operating on its own
 * {@link Page}.</p>
 */
public final class ImageSourceValidationRule implements AuditRule {

    private static final Logger log = LoggerFactory.getLogger(ImageSourceValidationRule.class);

    // -------------------------------------------------------------------------
    // Rule identity
    // -------------------------------------------------------------------------

    public static final String RULE_ID = "IMAGE_SRC_VALIDATION";

    private static final String DESCRIPTION =
            "Validates that every <img> src actually resolves and loads, catching "
                    + "broken relative paths, missing files, and dead remote image URLs "
                    + "that render as broken-image icons in an inbox.";

    /** Caps the number of individual findings to keep report output readable. */
    private static final int MAX_FINDINGS = 25;

    /** Caps the number of PASS evidence findings to keep report output readable. */
    private static final int MAX_PASS_EVIDENCE = 25;

    private static final String NO_ALT_TEXT = "(no alt text)";
    private static final String EMPTY_SRC   = "(empty src)";

    // -------------------------------------------------------------------------
    // JavaScript used to extract image load state in one round-trip
    // -------------------------------------------------------------------------

    /**
     * Returns a JSON array of objects {@code {src, alt, broken}} for every
     * {@code <img>} element with a non-empty {@code src} attribute, in
     * document order.
     *
     * <ul>
     *   <li>{@code src}    — resolved absolute URL, exactly as the browser
     *       computed it (matches {@code http(s)://}, {@code file://}, and
     *       {@code data:} schemes alike)</li>
     *   <li>{@code alt}    — the image's {@code alt} attribute</li>
     *   <li>{@code broken} — {@code true} when the image finished attempting
     *       to load but produced no decodable pixel data</li>
     * </ul>
     */
    private static final String EXTRACT_IMAGES_JS = """
            () => Array.from(document.querySelectorAll('img'))
                       .filter(img => (img.getAttribute('src') || '').length > 0)
                       .map(img => ({
                           src: img.src,
                           alt: img.getAttribute('alt') || '',
                           broken: img.complete && img.naturalWidth === 0
                       }))
            """;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /** Creates an {@code ImageSourceValidationRule} with default configuration. */
    public ImageSourceValidationRule() {
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
        return "All images loaded successfully.";
    }

    @Override
    public String failImpact() {
        return "One or more images failed to load and will render as broken images.";
    }

    @Override
    public RuleCategory category() {
        // NOTE: only RuleCategory.CONTENT and RuleCategory.LINKS were visible
        // in the files shared with me. If your RuleCategory enum has a more
        // specific value used by the alt-text rule (e.g. ACCESSIBILITY or
        // IMAGES), swap it in here so this rule groups with it correctly.
        return RuleCategory.CONTENT;
    }

    @Override
    public RuleSeverity severity() {
        return RuleSeverity.HIGH;
    }

    /**
     * Runs image-load validation against {@code page}.
     *
     * @param page live, fully loaded Playwright page; must not be {@code null}
     * @return PASS (with evidence findings) when every {@code <img>} loaded
     *         successfully; FAIL when one or more images are broken; ERROR
     *         when DOM extraction itself fails
     */
    @Override
    public RuleResult execute(final Page page) {
        Objects.requireNonNull(page, "page must not be null");

        final long startMs = System.currentTimeMillis();
        log.info("[{}] Starting image source validation on: {}", RULE_ID, safeUrl(page));

        final List<ImageEntry> images;
        try {
            images = extractImages(page);
        } catch (final Exception e) {
            log.error("[{}] Failed to extract images from page: {}", RULE_ID, e.getMessage(), e);
            return RuleResult.error(this, startMs, e);
        }

        log.info("[{}] {} image(s) found", RULE_ID, images.size());

        final List<String> findings = buildFindings(images);

        if (findings.isEmpty()) {
            log.info("[{}] All images loaded successfully", RULE_ID);
            return RuleResult.builder(this, RuleResult.Status.PASS, startMs)
                    .withFindings(buildPassEvidence(images))
                    .build();
        }

        log.warn("[{}] {} broken image(s) found", RULE_ID, findings.size());
        return RuleResult.fail(this, startMs, findings);
    }

    // -------------------------------------------------------------------------
    // Internal – finding construction
    // -------------------------------------------------------------------------

    /**
     * Builds one finding per broken image, capped at {@value #MAX_FINDINGS}
     * with an overflow summary appended if exceeded.
     */
    private static List<String> buildFindings(final List<ImageEntry> images) {
        final List<String> findings = new ArrayList<>();

        for (final ImageEntry image : images) {
            if (!image.broken()) {
                continue;
            }

            final String altValue = image.alt().isBlank() ? NO_ALT_TEXT : image.alt();
            final String srcValue = image.src().isBlank() ? EMPTY_SRC : image.src();

            findings.add(FindingFormatter.linkFinding()
                    .title("Image Source Validation")
                    .displayText(altValue)
                    .href(srcValue)
                    .failed("Image failed to load (0-width natural size) — src does not resolve to a valid image")
                    .build());
        }

        if (findings.size() <= MAX_FINDINGS) {
            return findings;
        }

        final List<String> capped = new ArrayList<>(findings.subList(0, MAX_FINDINGS));
        capped.add(String.format("… and %d more broken image(s)",
                findings.size() - MAX_FINDINGS));
        return capped;
    }

    /**
     * Builds one PASS evidence finding per successfully loaded image,
     * capped at {@value #MAX_PASS_EVIDENCE} with an overflow summary
     * appended if exceeded.
     */
    private static List<String> buildPassEvidence(final List<ImageEntry> images) {
        final List<String> evidence = new ArrayList<>();

        for (final ImageEntry image : images) {
            final String altValue = image.alt().isBlank() ? NO_ALT_TEXT : image.alt();
            final String srcValue = image.src().isBlank() ? EMPTY_SRC : image.src();

            evidence.add(FindingFormatter.linkFinding()
                    .title("Image Source Validation")
                    .displayText(altValue)
                    .href(srcValue)
                    .passed("Image loaded successfully")
                    .build());
        }

        if (evidence.size() <= MAX_PASS_EVIDENCE) {
            return evidence;
        }

        final List<String> capped = new ArrayList<>(evidence.subList(0, MAX_PASS_EVIDENCE));
        capped.add(FindingFormatter.generic(RULE_ID,
                String.format("… and %d more successfully loaded image(s)",
                        evidence.size() - MAX_PASS_EVIDENCE)));
        return capped;
    }

    // -------------------------------------------------------------------------
    // Internal – page extraction
    // -------------------------------------------------------------------------

    /**
     * Evaluates JavaScript in the page to extract all image load states and
     * returns them as typed {@link ImageEntry} records, in document order.
     */
    @SuppressWarnings("unchecked")
    private static List<ImageEntry> extractImages(final Page page) {
        try {
            // Ensure every <img> has had a chance to finish (successfully or
            // not) attempting to load before we read naturalWidth/complete.
            page.waitForLoadState(LoadState.LOAD);

            final Object raw = page.evaluate(EXTRACT_IMAGES_JS);

            if (!(raw instanceof List<?> rawList)) {
                log.warn("[{}] Unexpected JS evaluation result type: {}",
                        RULE_ID, raw == null ? "null" : raw.getClass().getSimpleName());
                return List.of();
            }

            final List<ImageEntry> entries = new ArrayList<>(rawList.size());
            for (final Object item : rawList) {
                if (item instanceof Map<?, ?> map) {
                    final String  src    = stringOrEmpty(map.get("src"));
                    final String  alt    = stringOrEmpty(map.get("alt"));
                    final boolean broken = booleanOrFalse(map.get("broken"));
                    entries.add(new ImageEntry(src, alt, broken));
                }
            }

            return entries;

        } catch (final PlaywrightException e) {
            log.error("[{}] Playwright error during image extraction: {}", RULE_ID, e.getMessage(), e);
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
     * Typed representation of a single {@code <img>} element extracted from
     * the page.
     *
     * @param src    resolved absolute URL of the image's {@code src}
     * @param alt    the image's {@code alt} attribute
     * @param broken whether the image finished loading but has no decodable
     *               pixel data
     */
    private record ImageEntry(String src, String alt, boolean broken) {}
}