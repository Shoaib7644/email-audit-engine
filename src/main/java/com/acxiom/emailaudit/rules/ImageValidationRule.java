package com.acxiom.emailaudit.rules;

import com.acxiom.emailaudit.evidence.ScreenshotService;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.LoadState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Validates every visible image in the rendered email page and captures cropped
 * image screenshots for the dashboard.
 */
public final class ImageValidationRule implements AuditRule {

    private static final Logger log =
            LoggerFactory.getLogger(ImageValidationRule.class);

    public static final String RULE_ID = "IMAGE_VALIDATION";

    private static final String DESCRIPTION =
            "Validates every visible rendered image in the email, including "
                    + "load state, natural size, alt attribute, rendering, and "
                    + "cropped image screenshot capture.";

    private static final String IMAGE_MARKER_ATTR =
            "data-email-audit-image-index";

    private static final int MAX_FINDINGS = 25;
    private static final int MAX_EVIDENCE = 25;

    private static final String EXTRACT_RENDERED_IMAGES_JS = """
            async () => {
                const marker = 'data-email-audit-image-index';
                const items = [];
                const seen = new Set();
                const cssEscape = window.CSS && CSS.escape
                    ? CSS.escape
                    : value => String(value).replace(/"/g, '\\\\"');
                const httpStatus = url => {
                    if (!url || !/^https?:/i.test(url)) return null;
                    const entries = performance.getEntriesByName(url);
                    for (let i = entries.length - 1; i >= 0; i--) {
                        const entry = entries[i];
                        if (typeof entry.responseStatus === 'number' && entry.responseStatus > 0) {
                            return Math.round(entry.responseStatus);
                        }
                    }
                    return null;
                };
                const visibleInfo = el => {
                    const rect = el.getBoundingClientRect();
                    const style = window.getComputedStyle(el);
                    const visible = rect.width > 0 &&
                        rect.height > 0 &&
                        style.display !== 'none' &&
                        style.visibility !== 'hidden' &&
                        style.opacity !== '0';
                    return {
                        visible,
                        x: Math.round(rect.x),
                        y: Math.round(rect.y),
                        width: Math.round(rect.width),
                        height: Math.round(rect.height)
                    };
                };
                const bounds = info => `${info.x},${info.y},${info.width}x${info.height}`;
                const isTrackingPixel = (info, naturalWidth, naturalHeight) =>
                    info.width <= 1 && info.height <= 1 &&
                    (naturalWidth == null || naturalWidth <= 1) &&
                    (naturalHeight == null || naturalHeight <= 1);
                const push = data => {
                    if (!data.info.visible || isTrackingPixel(data.info, data.naturalWidth, data.naturalHeight)) {
                        return;
                    }
                    const key = data.type + '|' + data.url + '|' + data.info.x + '|' + data.info.y + '|' +
                        data.info.width + '|' + data.info.height;
                    if (seen.has(key)) return;
                    seen.add(key);
                    const index = items.length;
                    data.el.setAttribute(marker, String(index));
                    items.push({
                        domIndex: index,
                        imageUrl: data.url || '',
                        altText: data.altText || '',
                        hasAlt: !!data.hasAlt,
                        displayWidth: data.info.width,
                        displayHeight: data.info.height,
                        naturalWidth: data.naturalWidth,
                        naturalHeight: data.naturalHeight,
                        bounds: bounds(data.info),
                        visible: data.info.visible,
                        complete: !!data.complete,
                        imageLoaded: !!data.imageLoaded,
                        broken: !!data.broken,
                        httpStatus: httpStatus(data.url),
                        loadingState: data.loadingState || '',
                        imageType: data.type || 'image'
                    });
                };

                document.querySelectorAll('img').forEach(img => {
                    const info = visibleInfo(img);
                    const url = img.currentSrc || img.src || img.getAttribute('src') || '';
                    const naturalWidth = Number(img.naturalWidth || 0);
                    const naturalHeight = Number(img.naturalHeight || 0);
                    const complete = !!img.complete;
                    push({
                        el: img,
                        type: 'img',
                        url,
                        altText: img.getAttribute('alt') || '',
                        hasAlt: img.hasAttribute('alt'),
                        info,
                        naturalWidth,
                        naturalHeight,
                        complete,
                        imageLoaded: complete && naturalWidth > 0 && naturalHeight > 0,
                        broken: complete && (naturalWidth === 0 || naturalHeight === 0),
                        loadingState: complete ? 'complete' : 'loading'
                    });
                });

                document.querySelectorAll('svg').forEach(svg => {
                    const info = visibleInfo(svg);
                    const title = svg.querySelector('title');
                    const alt = svg.getAttribute('aria-label') ||
                        svg.getAttribute('title') ||
                        (title ? title.textContent : '') ||
                        '';
                    push({
                        el: svg,
                        type: 'svg',
                        url: '',
                        altText: alt,
                        hasAlt: !!alt,
                        info,
                        naturalWidth: info.width,
                        naturalHeight: info.height,
                        complete: true,
                        imageLoaded: true,
                        broken: false,
                        loadingState: 'complete'
                    });
                });

                const bgCandidates = [];
                document.querySelectorAll('body *').forEach(el => {
                    if (el.matches('img, svg, svg *')) return;
                    const style = window.getComputedStyle(el);
                    const bg = style.backgroundImage || '';
                    const match = bg.match(/url\\(["']?([^"')]+)["']?\\)/);
                    if (!match) return;
                    const info = visibleInfo(el);
                    if (!info.visible || info.width <= 1 || info.height <= 1) return;
                    bgCandidates.push({
                        el,
                        url: new URL(match[1], document.baseURI).href,
                        info,
                        altText: el.getAttribute('aria-label') || el.getAttribute('title') || '',
                        hasAlt: !!(el.getAttribute('aria-label') || el.getAttribute('title'))
                    });
                });

                await Promise.all(bgCandidates.map(candidate => new Promise(resolve => {
                    const probe = new Image();
                    const done = loaded => {
                        push({
                            el: candidate.el,
                            type: 'background',
                            url: candidate.url,
                            altText: candidate.altText,
                            hasAlt: candidate.hasAlt,
                            info: candidate.info,
                            naturalWidth: loaded ? Number(probe.naturalWidth || 0) : 0,
                            naturalHeight: loaded ? Number(probe.naturalHeight || 0) : 0,
                            complete: true,
                            imageLoaded: loaded && probe.naturalWidth > 0 && probe.naturalHeight > 0,
                            broken: !loaded || probe.naturalWidth === 0 || probe.naturalHeight === 0,
                            loadingState: loaded ? 'complete' : 'failed'
                        });
                        resolve();
                    };
                    const timer = setTimeout(() => done(false), 1500);
                    probe.onload = () => { clearTimeout(timer); done(true); };
                    probe.onerror = () => { clearTimeout(timer); done(false); };
                    probe.src = candidate.url;
                })));

                return items;
            }
            """;

    private final ScreenshotService screenshotService;

    public ImageValidationRule() {
        this(new ScreenshotService());
    }

    ImageValidationRule(final ScreenshotService screenshotService) {
        this.screenshotService = Objects.requireNonNull(
                screenshotService,
                "screenshotService must not be null");
    }

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
        return RuleCategory.IMAGES;
    }

    @Override
    public RuleSeverity severity() {
        return RuleSeverity.HIGH;
    }

    @Override
    public String passImpact() {
        return "All rendered images loaded and captured successfully.";
    }

    @Override
    public String failImpact() {
        return "One or more rendered images may appear broken or missing to recipients.";
    }

    @Override
    public RuleResult execute(final Page page) {
        Objects.requireNonNull(page, "page must not be null");

        final long startMs = System.currentTimeMillis();
        log.info("[{}] Starting rendered image validation on: {}", RULE_ID, safeUrl(page));

        final List<RenderedImage> renderedImages;
        try {
            page.waitForLoadState(LoadState.LOAD);
            renderedImages = extractRenderedImages(page);
        } catch (final Exception e) {
            log.error("[{}] Failed to extract rendered images: {}", RULE_ID, e.getMessage(), e);
            return RuleResult.error(this, startMs, e);
        }

        final List<ImageValidationResult> results = new ArrayList<>(renderedImages.size());
        final List<String> findings = new ArrayList<>();
        final List<String> evidence = new ArrayList<>();

        for (final RenderedImage image : renderedImages) {
            final ImageValidationResult result = validateAndCapture(page, image);
            results.add(result);

            if ("FAIL".equals(result.validationStatus())) {
                findings.add(imageFinding(result));
            } else if (evidence.size() < MAX_EVIDENCE) {
                evidence.add(imageFinding(result));
            }
        }

        final RuleResult.Builder builder = findings.isEmpty()
                ? RuleResult.builder(this, RuleResult.Status.PASS, startMs)
                : RuleResult.builder(this, RuleResult.Status.FAIL, startMs);

        return builder
                .withFindings(findings.isEmpty() ? evidence : cap(findings))
                .withMetadata("images", results)
                .build();
    }

    @SuppressWarnings("unchecked")
    private static List<RenderedImage> extractRenderedImages(final Page page) {
        final Object raw = page.evaluate(EXTRACT_RENDERED_IMAGES_JS);
        if (!(raw instanceof List<?> rawList)) {
            return List.of();
        }

        final List<RenderedImage> images = new ArrayList<>(rawList.size());
        for (final Object item : rawList) {
            if (item instanceof Map<?, ?> map) {
                images.add(new RenderedImage(
                        integerOrZero(map.get("domIndex")),
                        stringOrEmpty(map.get("imageUrl")),
                        stringOrEmpty(map.get("altText")),
                        booleanOrFalse(map.get("hasAlt")),
                        integerOrNull(map.get("httpStatus")),
                        integerOrNull(map.get("naturalWidth")),
                        integerOrNull(map.get("naturalHeight")),
                        integerOrZero(map.get("displayWidth")),
                        integerOrZero(map.get("displayHeight")),
                        booleanOrFalse(map.get("imageLoaded")),
                        booleanOrFalse(map.get("visible")),
                        booleanOrFalse(map.get("complete")),
                        booleanOrFalse(map.get("broken")),
                        stringOrEmpty(map.get("loadingState")),
                        stringOrEmpty(map.get("bounds")),
                        stringOrEmpty(map.get("imageType"))));
            }
        }
        return images;
    }

    private ImageValidationResult validateAndCapture(
            final Page page,
            final RenderedImage image) {

        String screenshotPath = null;
        String screenshotFailure = "";

        try {
            final Locator locator = page.locator(
                    "[" + IMAGE_MARKER_ATTR + "=\"" + image.domIndex() + "\"]").first();
            final Path path = screenshotService.capture(
                    locator,
                    "image_" + (image.domIndex() + 1));
            screenshotPath = path.toAbsolutePath().toString();
        } catch (final ScreenshotService.ScreenshotException | PlaywrightException e) {
            screenshotFailure = "Screenshot failed";
            log.warn("[{}] Image screenshot failed for index {}: {}",
                    RULE_ID, image.domIndex(), e.getMessage());
        }

        final boolean httpFailed = image.httpStatus() != null && image.httpStatus() >= 400;
        final boolean loaded = image.imageLoaded()
                && Boolean.TRUE.equals(isPositive(image.naturalWidth()))
                && Boolean.TRUE.equals(isPositive(image.naturalHeight()));
        final boolean requiredPassed = image.rendered()
                && image.complete()
                && loaded
                && !image.broken()
                && !httpFailed
                && screenshotPath != null;

        final boolean warning = requiredPassed && !image.hasAlt();
        final String status = requiredPassed
                ? warning ? "WARNING" : "PASS"
                : "FAIL";

        return new ImageValidationResult(
                image.imageUrl(),
                image.altText(),
                image.httpStatus(),
                status,
                warning,
                image.naturalWidth(),
                image.naturalHeight(),
                image.displayWidth(),
                image.displayHeight(),
                image.imageLoaded(),
                image.rendered(),
                screenshotPath,
                screenshotPath,
                notes(image, status, screenshotFailure),
                image.bounds(),
                image.imageType());
    }

    private static String notes(
            final RenderedImage image,
            final String status,
            final String screenshotFailure) {

        if (!screenshotFailure.isBlank()) {
            return screenshotFailure;
        }
        if (!image.rendered()) {
            return "Image is hidden or not rendered";
        }
        if (image.httpStatus() != null && image.httpStatus() >= 400) {
            return "HTTP " + image.httpStatus();
        }
        if (!image.complete()) {
            return "Image did not finish loading";
        }
        if (image.broken() || !image.imageLoaded()) {
            return "Image failed to load";
        }
        if ("WARNING".equals(status)) {
            return "Missing alt attribute";
        }
        return "Rendered correctly";
    }

    private static String imageFinding(final ImageValidationResult result) {
        final String label = result.altText().isBlank()
                ? "(No alt text)"
                : result.altText();
        return FindingFormatter.linkFinding()
                .title("Image Validation")
                .displayText(label)
                .href(result.imageUrl().isBlank() ? "(inline image)" : result.imageUrl())
                .status(result.validationStatus())
                .reason(result.notes())
                .build();
    }

    private static List<String> cap(final List<String> findings) {
        if (findings.size() <= MAX_FINDINGS) {
            return findings;
        }
        final List<String> capped = new ArrayList<>(findings.subList(0, MAX_FINDINGS));
        capped.add("... and " + (findings.size() - MAX_FINDINGS) + " more image issue(s)");
        return capped;
    }

    private static Boolean isPositive(final Integer value) {
        return value != null && value > 0;
    }

    private static String safeUrl(final Page page) {
        try {
            return page.url();
        } catch (final Exception e) {
            return "<unavailable>";
        }
    }

    private static String stringOrEmpty(final Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean booleanOrFalse(final Object value) {
        return value instanceof Boolean b && b;
    }

    private static Integer integerOrNull(final Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    private static int integerOrZero(final Object value) {
        final Integer number = integerOrNull(value);
        return number == null ? 0 : number;
    }

    private record RenderedImage(
            int domIndex,
            String imageUrl,
            String altText,
            boolean hasAlt,
            Integer httpStatus,
            Integer naturalWidth,
            Integer naturalHeight,
            int displayWidth,
            int displayHeight,
            boolean imageLoaded,
            boolean rendered,
            boolean complete,
            boolean broken,
            String loadingState,
            String bounds,
            String imageType) {}
}
