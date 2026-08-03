package com.acxiom.emailaudit.rules;

import com.acxiom.emailaudit.config.ConfigurationManager;
import com.acxiom.emailaudit.evidence.ScreenshotService;
import com.acxiom.emailaudit.utilities.PerformanceMetrics;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.SequencedMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * {@link AuditRule} that validates clickable customer journeys from a rendered
 * email page.
 *
 * <h2>Checks performed</h2>
 * <ol>
 *   <li><strong>Broken links</strong> – each browser-navigable element is clicked
 *       from the rendered email; navigation or render failures are findings.</li>
 *   <li><strong>HTTP (non-HTTPS) links</strong> – any {@code href} using the plain
 *       {@code http://} scheme flags a security/mixed-content concern.</li>
 *   <li><strong>Missing unsubscribe link</strong> – no anchor whose {@code href}
 *       or visible text matches recognised unsubscribe patterns (CAN-SPAM / GDPR
 *       compliance).</li>
 *   <li><strong>Link inventory metadata</strong> – every rendered clickable
 *       element is classified for the dashboard, including HTTP/HTTPS, template
 *       placeholders, telephone links, mailto links, anchors, buttons, and
 *       unsupported schemes.</li>
 * </ol>
 *
 * <h2>Findings format</h2>
 * <p>Every broken hyperlink produces an individual structured finding via
 * {@link FindingFormatter#brokenLink(String, String, String)}, e.g.:</p>
 * <pre>
 * Broken Link
 *   Displayed Text  : Download Report
 *   Destination     : https://example.com/report
 *   Validation      : FAILED
 *   Reason          : HTTP 404 Not Found
 * </pre>
 * <p>Multiple broken links are never aggregated into one string.</p>
 *
 * <h2>PASS evidence</h2>
 * <p>When the rule passes, it now also returns evidence findings via
 * {@link RuleResult.Builder#withFindings(List)} so the dashboard can show
 * exactly what was checked, not just that nothing failed:</p>
 * <ul>
 *   <li>one finding confirming the unsubscribe link was found,</li>
 *   <li>one finding per successfully validated HTTP/HTTPS link, e.g.:
 *       <pre>
 *       Broken Link
 *         Displayed Text  : Download Report
 *         Destination     : https://example.com/report
 *         Validation      : PASSED
 *         Reason          : HTTP 200 OK
 *       </pre>
 *       (the same title is reused for symmetry with the FAIL case — only the
 *       Validation/Reason fields differ).</li>
 * </ul>
 * <p>No insecure-link evidence is produced on PASS, since an empty insecure-link
 * set means there was nothing to validate — there is no individual link to point to.</p>
 *
 * <h2>Clickable extraction</h2>
 * <p>Visible clickable candidates are collected from the rendered DOM via a
 * Playwright {@code evaluate()} call, including anchors, linked images, buttons,
 * role-button elements, and other visible interactive nodes.</p>
 *
 * <h2>Journey probing</h2>
 * <p>Browser-navigable links are validated by clicking the source element in the
 * rendered email, handling same-tab navigation and popups, waiting for the
 * destination to render, and capturing a screenshot.</p>
 *
 * <h2>Thread safety</h2>
 * <p>All {@link Pattern} instances and configuration constants are
 * {@code static final}. The rule does not share Playwright objects across Java
 * worker threads; click journeys run on the caller-owned page.</p>
 */
public final class LinkValidationRule implements AuditRule {

    private static final Logger log = LoggerFactory.getLogger(LinkValidationRule.class);

    // -------------------------------------------------------------------------
    // Rule identity
    // -------------------------------------------------------------------------

    public static final String RULE_ID = "LINK_VALIDATION";

    private static final String DESCRIPTION =
            "Validates all anchor links for broken URLs, insecure HTTP scheme, "
                    + "presence of a mandatory unsubscribe link, and structured "
                    + "link inventory reporting.";

    // -------------------------------------------------------------------------
    // Browser validation configuration
    // -------------------------------------------------------------------------

    private static final double BROWSER_FALLBACK_TIMEOUT_MS = 15_000;
    private static final double BROWSER_STABILIZE_TIMEOUT_MS = 3_000;
    private static final double CLICK_DESTINATION_TIMEOUT_MS = 15_000;
    private static final int MAX_BROKEN_REPORT  = 20;   // cap findings list
    private static final int MAX_PASS_EVIDENCE  = 20;   // cap PASS evidence list

    // ── Placeholder labels for missing display data ───────────────────────────
    private static final String NO_VISIBLE_TEXT = "(no visible text)";
    private static final String EMPTY_HREF      = "(empty href)";
    private static final String UNRESOLVED_PLACEHOLDER_REASON =
            "Unresolved placeholder detected. Final rendered email still contains an ESP merge tag.";
    private final ScreenshotService screenshotService;
    private final int stabilizationDelayMs;

    // -------------------------------------------------------------------------
    // Patterns – compiled once, thread-safe
    // -------------------------------------------------------------------------

    /** Matches any href that starts with plain http:// (not https://). */
    private static final Pattern HTTP_SCHEME_PATTERN =
            Pattern.compile("^http://", Pattern.CASE_INSENSITIVE);

    /**
     * Matches unsubscribe-like hrefs or anchor text.
     * Covers: "unsubscribe", "opt out", "opt-out", "remove me", "email preferences".
     */
    private static final Pattern UNSUBSCRIBE_PATTERN = Pattern.compile(
            "unsub|opt.?out|remove.?me|email.?pref",
            Pattern.CASE_INSENSITIVE
    );

    /** Matches common unresolved ESP/CMS merge tag syntaxes left in a rendered href. */
    private static final List<Pattern> PLACEHOLDER_LINK_PATTERNS = List.of(
            Pattern.compile("\\{\\{\\s*[^{}]+?\\s*\\}\\}"),
            Pattern.compile("%%\\s*[^%]+?\\s*%%"),
            Pattern.compile("\\$\\{\\s*[^{}]+?\\s*\\}"),
            Pattern.compile("\\[\\[\\s*[^\\[\\]]+?\\s*\\]\\]"),
            Pattern.compile("<%=\\s*.+?\\s*%>")
    );

    // -------------------------------------------------------------------------
    // JavaScript used to extract all anchor data in one round-trip
    // -------------------------------------------------------------------------

    /**
     * Returns a JSON array of objects for visible clickable elements. Each
     * element is addressed later via its DOM-order index within the same
     * selector, so the audit clicks the rendered email instead of navigating
     * directly to extracted URLs.
     *
     * <p>Both are needed: {@code href} is used for HTTP probing and
     * display in every other finding from this rule, but the browser
     * percent-encodes characters like {@code {} } when resolving a
     * relative-looking href — {@code href="{{TOKEN}}"} resolves to
     * something like {@code .../%7B%7BTOKEN%7D%7D}. Detecting unresolved
     * merge tags has to inspect {@code rawHref}, since the literal curly
     * braces no longer appear in the resolved form.</p>
     */
    private static final String CLICKABLE_SELECTOR =
            "a[href], area[href], button, [role='button'], [onclick], [tabindex]:not([tabindex='-1'])";

    private static final String EXTRACT_LINKS_JS = """
            () => Array.from(document.querySelectorAll(
                    "a[href], area[href], button, [role='button'], [onclick], [tabindex]:not([tabindex='-1'])"
                ))
                .map((el, index) => {
                    const rect = el.getBoundingClientRect();
                    const style = window.getComputedStyle(el);
                    const visible = rect.width > 0 &&
                        rect.height > 0 &&
                        style.display !== 'none' &&
                        style.visibility !== 'hidden' &&
                        style.pointerEvents !== 'none';
                    const visibleText = (el.innerText || el.textContent || '').trim();
                    const imageAltText = Array.from(el.querySelectorAll('img[alt]'))
                        .map(img => img.getAttribute('alt') || '')
                        .map(alt => alt.trim())
                        .filter(Boolean)
                        .join(' ')
                        .trim();
                    const ariaLabel = (el.getAttribute('aria-label') || '').trim();
                    const title = (el.getAttribute('title') || '').trim();
                    const rawHref = el.getAttribute('href') || '';
                    const href = el.href || rawHref;

                    return {
                        domIndex: index,
                        href: href,
                        rawHref: rawHref,
                        text: visibleText || imageAltText || ariaLabel || title,
                        ariaLabel: ariaLabel,
                        title: title,
                        target: el.getAttribute('target') || '',
                        tagName: el.tagName ? el.tagName.toLowerCase() : '',
                        role: el.getAttribute('role') || '',
                        visible: visible,
                        enabled: !el.disabled,
                        x: Math.round(rect.x),
                        y: Math.round(rect.y),
                        width: Math.round(rect.width),
                        height: Math.round(rect.height)
                    };
                })
                .filter(l => l.visible)
            """;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /** Creates a {@code LinkValidationRule} with default configuration. */
    public LinkValidationRule() {
        this(new ScreenshotService(), ConfigurationManager.getInstance());
    }

    LinkValidationRule(final ScreenshotService screenshotService) {
        this(screenshotService, ConfigurationManager.getInstance());
    }

    LinkValidationRule(
            final ScreenshotService screenshotService,
            final ConfigurationManager config) {
        this.screenshotService = Objects.requireNonNull(
                screenshotService,
                "screenshotService must not be null");
        Objects.requireNonNull(config, "config must not be null");
        this.stabilizationDelayMs = config.getBrowserPageStabilizationDelayMs();
    }

    // -------------------------------------------------------------------------
    // AuditRule implementation
    // -------------------------------------------------------------------------

    @Override
    public String ruleId() {
        return RULE_ID;
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
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public RuleCategory category() {
        return RuleCategory.LINKS;
    }

    @Override
    public RuleSeverity severity() {
        return RuleSeverity.HIGH;
    }

    /**
     * Runs link checks and captures structured link inventory against {@code page}.
     *
     * @param page live, fully loaded Playwright page; must not be {@code null}
     * @return PASS (with evidence findings), FAIL, or ERROR result
     */
    @Override
    public RuleResult execute(final Page page) {
        Objects.requireNonNull(page, "page must not be null");

        final long startMs = System.currentTimeMillis();
        log.info("[{}] Starting link validation on: {}", RULE_ID, safeUrl(page));

        // ── 1. Extract all anchor links from the page ────────────────────────
        final List<LinkEntry> links;
        final long discoveryStartNanos = System.nanoTime();
        try {
            links = extractLinks(page);
            PerformanceMetrics.recordLinkDiscovery(
                    PerformanceMetrics.elapsedMillis(discoveryStartNanos));
        } catch (final Exception e) {
            PerformanceMetrics.recordLinkDiscovery(
                    PerformanceMetrics.elapsedMillis(discoveryStartNanos));
            log.error("[{}] Failed to extract links from page: {}", RULE_ID, e.getMessage(), e);
            return RuleResult.error(this, startMs, e);
        }

        log.info("[{}] {} clickable element(s) found", RULE_ID, links.size());

        final List<String> findings = new ArrayList<>();
        final List<String> passEvidence = new ArrayList<>();

        // ── 2. Check for missing unsubscribe link ────────────────────────────
        checkUnsubscribePresence(links, findings, passEvidence);

        // ── 3. Check for HTTP (non-HTTPS) links ──────────────────────────────
        checkInsecureLinks(links, findings);

        // ── 4. Fail unresolved ESP/template placeholders in final rendered email
        checkUnresolvedPlaceholderLinks(links, findings);

        // ── 5. Validate HTTP/HTTPS links by clicking rendered elements ───────
        final List<ProbeResult> enrichedProbeResults =
                checkBrokenLinks(page, links, findings, passEvidence);

        final List<LinkAuditEntry> linkAuditEntries =
                buildLinkAuditEntries(links, enrichedProbeResults);

        if (findings.isEmpty()) {
            log.info("[{}] All link checks passed", RULE_ID);
            return RuleResult.builder(this, RuleResult.Status.PASS, startMs)
                    .withFindings(capEvidence(passEvidence))
                    .withMetadata("links", linkAuditEntries)
                    .build();
        }

        log.warn("[{}] {} link issue(s) found", RULE_ID, findings.size());
        return RuleResult.builder(this, RuleResult.Status.FAIL, startMs)
                .withFindings(findings)
                .withMetadata("links", linkAuditEntries)
                .build();
    }

    // -------------------------------------------------------------------------
    // Check 1 – Missing unsubscribe link
    // -------------------------------------------------------------------------

    private static void checkUnsubscribePresence(
            final List<LinkEntry> links,
            final List<String> findings,
            final List<String> passEvidence) {

        final LinkEntry unsubscribeLink = links.stream()
                .filter(LinkValidationRule::isUnsubscribeLink)
                .findFirst()
                .orElse(null);

        if (unsubscribeLink == null) {
            final String finding = FindingFormatter.missingPrivacyLink("unsubscribe");
            findings.add(finding);
            log.debug("[LINKS] missing unsubscribe link");
            return;
        }

        final String displayText = unsubscribeLink.text().isBlank()
                ? NO_VISIBLE_TEXT
                : unsubscribeLink.text();

        passEvidence.add(FindingFormatter.linkFinding()
                .title("Unsubscribe Link")
                .displayText(displayText)
                .href(unsubscribeLink.href())
                .passed("Unsubscribe link present")
                .build());
    }

    private static boolean isUnsubscribeLink(final LinkEntry link) {
        return UNSUBSCRIBE_PATTERN.matcher(link.href()).find()
                || UNSUBSCRIBE_PATTERN.matcher(link.text()).find();
    }

    // -------------------------------------------------------------------------
    // Check 2 – HTTP (non-HTTPS) links
    // -------------------------------------------------------------------------

    private static void checkInsecureLinks(
            final List<LinkEntry> links,
            final List<String> findings) {

        links.stream()
                .filter(link -> HTTP_SCHEME_PATTERN.matcher(link.href()).find())
                .map(LinkEntry::href)
                .distinct()
                .map(FindingFormatter::insecureLink)
                .forEach(finding -> {
                    findings.add(finding);
                    log.debug("[LINKS] insecure link detected");
                });

        // No PASS evidence is produced here: an empty insecure-link set means
        // there was nothing to validate, so there is no individual link to cite.
    }

    // -------------------------------------------------------------------------
    // Check 3 – Unresolved ESP/template placeholders
    // -------------------------------------------------------------------------

    private static void checkUnresolvedPlaceholderLinks(
            final List<LinkEntry> links,
            final List<String> findings) {

        links.stream()
                .filter(LinkValidationRule::hasPlaceholderLink)
                .forEach(link -> {
                    final String displayText = link.text().isBlank()
                            ? NO_VISIBLE_TEXT
                            : link.text();
                    findings.add(FindingFormatter.brokenLink(
                            displayText,
                            displayHref(link),
                            UNRESOLVED_PLACEHOLDER_REASON));
                    log.debug("[LINKS] unresolved placeholder detected – href='{}'", displayHref(link));
                });
    }

    // -------------------------------------------------------------------------
    // Check 4 – Customer click journeys
    // -------------------------------------------------------------------------

    /**
     * Validates every browser-navigable clickable element and appends one
     * {@link FindingFormatter#brokenLink} finding per failed URL, or one
     * PASSED link-finding per successfully validated URL.
     *
     * <p>Each element is clicked from the rendered email, preserving the
     * customer journey. At most {@value #MAX_BROKEN_REPORT} individual
     * FAIL findings, and at most {@value #MAX_PASS_EVIDENCE} PASS evidence
     * findings, are added; overflow summaries are appended if either is
     * exceeded.</p>
     */
    private List<ProbeResult> checkBrokenLinks(
            final Page page,
            final List<LinkEntry> links,
            final List<String> findings,
            final List<String> passEvidence) {

        final List<LinkEntry> browserJourneys = links.stream()
                .filter(link -> classify(link).requiresBrowserJourney())
                .toList();

        if (browserJourneys.isEmpty()) {
            log.debug("[{}] No browser-click journeys to validate", RULE_ID);
            return List.of();
        }

        log.info("[{}] Validating {} clickable journey/journeys from rendered email",
                RULE_ID, browserJourneys.size());

        final List<ProbeResult> probeResults =
                validateClickableJourneys(page, browserJourneys);

        int reportedFail = 0;
        int reportedPass = 0;

        for (final ProbeResult result : probeResults) {

            final String displayText = result.displayText().isBlank()
                    ? NO_VISIBLE_TEXT
                    : result.displayText();

            final String href = result.url().isBlank()
                    ? EMPTY_HREF
                    : result.url();

            if (result.ok()) {
                if (reportedPass < MAX_PASS_EVIDENCE) {
                    passEvidence.add(FindingFormatter.linkFinding()
                            .title(result.validationStatus().equals("PROTECTED")
                                    ? "Destination Protected"
                                    : "Link Journey")
                            .displayText(displayText)
                            .href(href)
                            .passed(result.reason())
                            .build());
                    reportedPass++;
                }
                continue;
            }

            if (reportedFail >= MAX_BROKEN_REPORT) {
                final long totalBroken = probeResults.stream().filter(r -> !r.ok()).count();
                findings.add(FindingFormatter.generic(RULE_ID,
                        String.format("… and %d more broken link(s) not shown above",
                                totalBroken - MAX_BROKEN_REPORT)));
                break;
            }

            findings.add(FindingFormatter.brokenLink(displayText, href, result.reason()));
            log.debug("[LINKS] broken link – href='{}' reason='{}'", href, result.reason());
            reportedFail++;
        }

        return probeResults;
    }

    private static List<LinkAuditEntry> buildLinkAuditEntries(
            final List<LinkEntry> links,
            final List<ProbeResult> probeResults) {

        final SequencedMap<Integer, ProbeResult> probeByDomIndex = new LinkedHashMap<>();
        for (final ProbeResult result : probeResults) {
            probeByDomIndex.putIfAbsent(result.link().domIndex(), result);
        }

        final List<LinkAuditEntry> auditEntries = new ArrayList<>(links.size());
        for (final LinkEntry link : links) {
            final LinkClassification classification = classify(link);
            final ProbeResult result = probeByDomIndex.get(link.domIndex());
            final String visibleText = link.text().isBlank()
                    ? NO_VISIBLE_TEXT
                    : link.text();
            final String originalUrl = link.rawHref().isBlank()
                    ? link.href()
                    : link.rawHref();
            final String defaultFinalUrl = classification.requiresBrowserJourney()
                    ? link.href()
                    : "";

            if (result == null) {
                final boolean unresolvedPlaceholder = "TEMPLATE_PLACEHOLDER".equals(classification.type());
                auditEntries.add(new LinkAuditEntry(
                        visibleText,
                        originalUrl,
                        unresolvedPlaceholder ? originalUrl : defaultFinalUrl,
                        classification.type(),
                        classification.note(),
                        unresolvedPlaceholder ? "FAIL" : "SKIPPED",
                        unresolvedPlaceholder ? UNRESOLVED_PLACEHOLDER_REASON : classification.note(),
                        unresolvedPlaceholder ? "Not Available" : "",
                        null,
                        "",
                        null,
                        List.of(),
                        null,
                        null,
                        elementDescriptor(link),
                        link.ariaLabel(),
                        link.title(),
                        link.target(),
                        link.domIndex(),
                        boundsLabel(link.rect())));
                continue;
            }

            auditEntries.add(new LinkAuditEntry(
                    visibleText,
                    originalUrl,
                    result.finalUrl().isBlank() ? defaultFinalUrl : result.finalUrl(),
                    classification.type(),
                    result.validationNote().isBlank()
                            ? classification.note()
                            : result.validationNote(),
                    result.validationStatus(),
                    result.reason(),
                    result.pageTitle(),
                    result.httpStatus(),
                    result.statusText(),
                    result.redirectCount(),
                    result.redirectChain(),
                    result.responseTimeMs(),
                    result.screenshotPath(),
                    elementDescriptor(link),
                    link.ariaLabel(),
                    link.title(),
                    link.target(),
                    link.domIndex(),
                    boundsLabel(link.rect())));
        }

        return auditEntries;
    }

    // -------------------------------------------------------------------------
    // Internal – PASS evidence capping
    // -------------------------------------------------------------------------

    /**
     * Caps the combined PASS evidence list at {@value #MAX_PASS_EVIDENCE}
     * with an overflow summary appended if exceeded. The per-check evidence
     * builders already cap their own contribution, so this is a final
     * safety net for the combined list.
     */
    private static List<String> capEvidence(final List<String> evidence) {
        if (evidence.size() <= MAX_PASS_EVIDENCE) {
            return evidence;
        }
        final List<String> capped = new ArrayList<>(evidence.subList(0, MAX_PASS_EVIDENCE));
        capped.add(FindingFormatter.generic(RULE_ID,
                String.format("… and %d more successfully validated link(s)",
                        evidence.size() - MAX_PASS_EVIDENCE)));
        return capped;
    }

    // -------------------------------------------------------------------------
    // Browser click journey validation
    // -------------------------------------------------------------------------

    private List<ProbeResult> validateClickableJourneys(
            final Page emailPage,
            final List<LinkEntry> links) {

        final List<ProbeResult> results = new ArrayList<>(links.size());
        final String emailUrl = safeUrl(emailPage);
        for (final LinkEntry link : links) {
            closeSecondaryPages(emailPage.context(), emailPage);
            restoreEmailPage(emailPage, emailUrl);
            results.add(validateSingleClickJourney(emailPage, link, emailUrl));
        }
        return results;
    }

    private ProbeResult validateSingleClickJourney(
            final Page emailPage,
            final LinkEntry link,
            final String emailUrl) {

        final BrowserContext context = emailPage.context();
        final AtomicReference<Response> latestNavigationResponse = new AtomicReference<>();
        final long startNanos = System.nanoTime();
        final LinkTiming timing = new LinkTiming();
        Consumer<Response> responseListener = null;
        Page destinationPage = null;
        List<Page> pagesBeforeClick = List.of();

        try {
            if (context == null) {
                return recordAndReturn(
                        ProbeResult.failure(link, "Browser context unavailable"),
                        timing,
                        startNanos);
            }

            responseListener = response -> {
                if (isMainNavigationResponse(response)) {
                    latestNavigationResponse.set(response);
                }
            };
            context.onResponse(responseListener);

            pagesBeforeClick = safePages(context);
            final Locator locator = emailPage.locator(CLICKABLE_SELECTOR).nth(link.domIndex());
            locator.scrollIntoViewIfNeeded(new Locator.ScrollIntoViewIfNeededOptions()
                    .setTimeout(BROWSER_FALLBACK_TIMEOUT_MS));

            if (!locator.isVisible()) {
                return recordAndReturn(
                        ProbeResult.failure(link, "Clickable element is not visible"),
                        timing,
                        startNanos);
            }
            if (!locator.isEnabled()) {
                return recordAndReturn(
                        ProbeResult.failure(link, "Clickable element is not enabled"),
                        timing,
                        startNanos);
            }

            final NavigationResult navigationResult = clickAndWaitForDestination(
                    emailPage,
                    context,
                    locator,
                    link,
                    emailUrl,
                    pagesBeforeClick);
            destinationPage = navigationResult.page();
            timing.clickMs = navigationResult.clickMs();
            timing.navigationMs = navigationResult.navigationMs();
            if (destinationPage == null) {
                return recordAndReturn(
                        ProbeResult.failure(link, "Click did not navigate", elapsedMillis(startNanos)),
                        timing,
                        startNanos);
            }

            final long stabilizationStartNanos = System.nanoTime();
            waitForPageStability(destinationPage);
            timing.stabilizationMs = elapsedMillis(stabilizationStartNanos);
            final long responseTimeMs = elapsedMillis(startNanos);
            final Response browserResponse = latestNavigationResponse.get();

            if (browserResponse == null) {
                if (!isRendered(destinationPage)) {
                    return recordAndReturn(
                            ProbeResult.failure(link,
                                    "Destination page could not render",
                                    responseTimeMs),
                            timing,
                            startNanos);
                }
                return recordAndReturn(
                        buildRenderedResult(link, destinationPage, null, responseTimeMs, timing),
                        timing,
                        startNanos);
            }

            if (!isRendered(destinationPage)) {
                return recordAndReturn(
                        ProbeResult.failure(
                                link,
                                "Destination page could not render",
                                responseTimeMs,
                                browserResponse),
                        timing,
                        startNanos);
            }

            return recordAndReturn(
                    buildRenderedResult(link, destinationPage, browserResponse, responseTimeMs, timing),
                    timing,
                    startNanos);

        } catch (final PlaywrightException e) {
            log.debug("[{}] Browser navigation failed for '{}': {}",
                    RULE_ID, link.href(), e.getMessage(), e);
            return recordAndReturn(
                    ProbeResult.failure(link,
                            friendlyNavigationFailure(e),
                            elapsedMillis(startNanos)),
                    timing,
                    startNanos);
        } catch (final Exception e) {
            log.debug("[{}] Browser validation failed for '{}': {}",
                    RULE_ID, link.href(), e.getMessage(), e);
            return recordAndReturn(
                    ProbeResult.failure(link,
                            e.getClass().getSimpleName() + ": " + e.getMessage(),
                            elapsedMillis(startNanos)),
                    timing,
                    startNanos);
        } finally {
            if (responseListener != null) {
                context.offResponse(responseListener);
            }
            if (destinationPage != null && destinationPage != emailPage) {
                closePopup(destinationPage);
            }
            closeUnexpectedNewPages(context, pagesBeforeClick, destinationPage);
            restoreEmailPage(emailPage, emailUrl);
        }
    }

    private static NavigationResult clickAndWaitForDestination(
            final Page emailPage,
            final BrowserContext context,
            final Locator locator,
            final LinkEntry link,
            final String emailUrl,
            final List<Page> pagesBeforeClick) {

        if (opensInNewTab(link)) {
            final NavigationResult popupResult = clickAndWaitForPopup(emailPage, locator);
            if (popupResult.page() != null) {
                return popupResult;
            }

            final long navigationStartNanos = System.nanoTime();
            final Page page = waitForDestinationPage(context, emailPage, emailUrl, pagesBeforeClick);
            return new NavigationResult(
                    page,
                    popupResult.clickMs(),
                    popupResult.navigationMs() + elapsedMillis(navigationStartNanos));
        }

        final long clickStartNanos = System.nanoTime();
        locator.click(new Locator.ClickOptions().setTimeout(BROWSER_FALLBACK_TIMEOUT_MS));
        final long clickMs = elapsedMillis(clickStartNanos);
        final long navigationStartNanos = System.nanoTime();
        final Page page = waitForDestinationPage(context, emailPage, emailUrl, pagesBeforeClick);
        return new NavigationResult(page, clickMs, elapsedMillis(navigationStartNanos));
    }

    private static NavigationResult clickAndWaitForPopup(
            final Page emailPage,
            final Locator locator) {

        final AtomicReference<Long> clickMs = new AtomicReference<>(0L);
        final long popupStartNanos = System.nanoTime();
        try {
            final Page popup = emailPage.waitForPopup(
                    new Page.WaitForPopupOptions().setTimeout(CLICK_DESTINATION_TIMEOUT_MS),
                    () -> {
                        final long clickStartNanos = System.nanoTime();
                        locator.click(new Locator.ClickOptions()
                                .setTimeout(BROWSER_FALLBACK_TIMEOUT_MS));
                        clickMs.set(elapsedMillis(clickStartNanos));
                    });
            final long totalMs = elapsedMillis(popupStartNanos);
            return new NavigationResult(
                    popup,
                    clickMs.get(),
                    Math.max(0, totalMs - clickMs.get()));
        } catch (final PlaywrightException e) {
            if (isTimeout(e)) {
                final long totalMs = elapsedMillis(popupStartNanos);
                return new NavigationResult(
                        null,
                        clickMs.get(),
                        Math.max(0, totalMs - clickMs.get()));
            }
            throw e;
        }
    }

    private ProbeResult buildRenderedResult(
            final LinkEntry link,
            final Page linkPage,
            final Response response,
            final long responseTimeMs,
            final LinkTiming timing) {

        final Integer status = response == null ? null : response.status();
        final String statusText = normaliseStatusText(
                status,
                response == null ? "" : response.statusText());
        final String finalUrl = currentUrl(linkPage, response);
        final List<String> redirectChain = response == null ? List.of(finalUrl) : redirectChain(response);
        final Integer redirectCount = Math.max(0, redirectChain.size() - 1);
        final String pageTitle = pageTitle(linkPage);

        if (linkPage.isClosed()) {
            return new ProbeResult(
                    link,
                    false,
                    "FAIL",
                    "Destination page closed before screenshot capture",
                    finalUrl,
                    status,
                    statusText,
                    redirectCount,
                    redirectChain,
                    "Destination page closed before screenshot capture",
                    pageTitle,
                    responseTimeMs,
                    null);
        }

        long screenshotStartNanos = 0L;
        try {
            screenshotStartNanos = System.nanoTime();
            final Path screenshotPath = screenshotService.capture(
                    linkPage,
                    screenshotSourceName(link.href().isBlank() ? link.text() : link.href()));
            timing.screenshotMs = elapsedMillis(screenshotStartNanos);

            final boolean failedHttpStatus = isFinalBrokenHttpStatus(status);
            final boolean protectedDestination = isProtectedDestination(status, pageTitle, linkPage);
            final String validationStatus = protectedDestination
                    ? "PROTECTED"
                    : failedHttpStatus ? "FAIL" : "PASS";
            final String reason = protectedDestination
                    ? "Destination Protected"
                    : failedHttpStatus ? formatHttpStatus(status, statusText)
                            : browserPassReason(redirectCount);
            return new ProbeResult(
                    link,
                    !"FAIL".equals(validationStatus),
                    validationStatus,
                    reason,
                    finalUrl,
                    status,
                    statusText,
                    redirectCount,
                    redirectChain,
                    "",
                    pageTitle,
                    responseTimeMs,
                    screenshotPath.toAbsolutePath().toString());
        } catch (final ScreenshotService.ScreenshotException e) {
            timing.screenshotMs = screenshotStartNanos == 0L ? 0 : elapsedMillis(screenshotStartNanos);
            log.debug("[{}] Screenshot capture failed for '{}': {}",
                    RULE_ID, link.href(), e.getMessage(), e);
            return new ProbeResult(
                    link,
                    false,
                    "FAIL",
                    "Screenshot capture failed",
                    finalUrl,
                    status,
                    statusText,
                    redirectCount,
                    redirectChain,
                    "Screenshot capture failed: " + e.getMessage(),
                    pageTitle,
                    responseTimeMs,
                    null);
        }
    }

    private static Page waitForDestinationPage(
            final BrowserContext context,
            final Page emailPage,
            final String emailUrl,
            final List<Page> pagesBeforeClick) {

        try {
            context.waitForCondition(
                    () -> hasNewPage(context, pagesBeforeClick) || pageMoved(emailPage, emailUrl),
                    new BrowserContext.WaitForConditionOptions()
                            .setTimeout(CLICK_DESTINATION_TIMEOUT_MS));
        } catch (final PlaywrightException ignored) {
            // Fall through and inspect the current browser state below.
        }

        final Page popup = newestPage(context, pagesBeforeClick);
        if (popup != null) {
            return popup;
        }
        return pageMoved(emailPage, emailUrl) ? emailPage : null;
    }

    private static boolean hasNewPage(
            final BrowserContext context,
            final List<Page> pagesBeforeClick) {

        return newestPage(context, pagesBeforeClick) != null;
    }

    private static Page newestPage(
            final BrowserContext context,
            final List<Page> pagesBeforeClick) {

        final List<Page> currentPages = safePages(context);
        for (int i = currentPages.size() - 1; i >= 0; i--) {
            final Page page = currentPages.get(i);
            if (!pagesBeforeClick.contains(page)) {
                return page;
            }
        }
        return null;
    }

    private static List<Page> safePages(final BrowserContext context) {
        try {
            return context == null ? List.of() : List.copyOf(context.pages());
        } catch (final Exception e) {
            return List.of();
        }
    }

    private static void closeSecondaryPages(
            final BrowserContext context,
            final Page primaryPage) {

        for (final Page page : safePages(context)) {
            if (page != primaryPage) {
                closePopup(page);
            }
        }
    }

    private static void closeUnexpectedNewPages(
            final BrowserContext context,
            final List<Page> pagesBeforeClick,
            final Page destinationPage) {

        for (final Page page : safePages(context)) {
            if (page != destinationPage && !pagesBeforeClick.contains(page)) {
                closePopup(page);
            }
        }
    }

    private static boolean pageMoved(final Page page, final String emailUrl) {
        final String current = safeUrl(page);
        return !current.isBlank() && !current.equals(emailUrl);
    }

    private static boolean opensInNewTab(final LinkEntry link) {
        return "_blank".equalsIgnoreCase(link.target().trim());
    }

    private static boolean isTimeout(final PlaywrightException e) {
        final String message = e.getMessage();
        return message != null && message.toLowerCase().contains("timeout");
    }

    private void restoreEmailPage(final Page emailPage, final String emailUrl) {
        if (emailUrl == null || emailUrl.isBlank() || !pageMoved(emailPage, emailUrl)) {
            return;
        }

        try {
            emailPage.navigate(emailUrl, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(BROWSER_FALLBACK_TIMEOUT_MS));
            waitForPageStability(emailPage);
        } catch (final PlaywrightException e) {
            log.debug("[{}] Could not restore email page after link journey: {}",
                    RULE_ID, e.getMessage(), e);
        }
    }

    private static void closePopup(final Page page) {
        try {
            if (!page.isClosed()) {
                page.close();
            }
        } catch (final PlaywrightException e) {
            log.debug("[{}] Error closing link journey popup: {}", RULE_ID, e.getMessage(), e);
        }
    }

    private static boolean isProtectedDestination(
            final Integer status,
            final String pageTitle,
            final Page page) {

        if (status == null || !(status == 401 || status == 403 || status == 429)) {
            return false;
        }

        final String evidence = (pageTitle + " " + bodyText(page)).toLowerCase();
        return evidence.contains("access denied")
                || evidence.contains("permission to access")
                || evidence.contains("forbidden")
                || evidence.contains("bot")
                || evidence.contains("akamai")
                || evidence.contains("cloudflare")
                || evidence.contains("imperva")
                || evidence.contains("edgesuite");
    }

    private static String bodyText(final Page page) {
        try {
            final Object result = page.evaluate("""
                    () => document.body
                      ? (document.body.innerText || document.body.textContent || '').slice(0, 2000)
                      : ''
                    """);
            return result instanceof String text ? text : "";
        } catch (final Exception e) {
            return "";
        }
    }

    private static boolean isMainNavigationResponse(final Response response) {
        try {
            return response != null
                    && response.request() != null
                    && response.request().isNavigationRequest();
        } catch (final Exception e) {
            return response != null;
        }
    }

    private static long elapsedMillis(final long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static ProbeResult recordAndReturn(
            final ProbeResult result,
            final LinkTiming timing,
            final long startNanos) {

        PerformanceMetrics.recordLink(
                result.displayText(),
                timing.clickMs,
                timing.navigationMs,
                timing.stabilizationMs,
                timing.screenshotMs,
                elapsedMillis(startNanos));
        return result;
    }

    private static String browserPassReason(final Integer redirectCount) {
        return redirectCount != null && redirectCount > 0
                ? "Redirect completed successfully"
                : "Page loaded successfully";
    }

    private static boolean isFinalBrokenHttpStatus(final Integer status) {
        return status != null && (status == 404 || status >= 500);
    }

    private static String friendlyNavigationFailure(final PlaywrightException e) {
        final String message = e.getMessage() == null ? "" : e.getMessage();
        final String lower = message.toLowerCase();
        if (lower.contains("timeout")) {
            return "Navigation timeout";
        }
        if (lower.contains("name_not_resolved") || lower.contains("dns")) {
            return "DNS resolution failed";
        }
        if (lower.contains("certificate") || lower.contains("ssl")) {
            return "SSL certificate error";
        }
        if (lower.contains("target closed") || lower.contains("crash")) {
            return "Browser crashed";
        }
        return message.isBlank() ? "Browser navigation error" : "Browser navigation error: " + message;
    }

    private void waitForPageStability(final Page page) {
        waitForDomReady(page);
        waitForLoad(page);
        waitForReadyStateComplete(page);
        pauseForStabilization();
    }

    private static void waitForDomReady(final Page page) {
        try {
            page.waitForLoadState(
                    LoadState.DOMCONTENTLOADED,
                    new Page.WaitForLoadStateOptions().setTimeout(BROWSER_FALLBACK_TIMEOUT_MS));
        } catch (final PlaywrightException ignored) {
            // The click may already have settled or the page may keep shifting; render checks decide outcome.
        }
    }

    private static void waitForLoad(final Page page) {
        try {
            page.waitForLoadState(
                    LoadState.LOAD,
                    new Page.WaitForLoadStateOptions().setTimeout(BROWSER_FALLBACK_TIMEOUT_MS));
        } catch (final PlaywrightException ignored) {
            // Some campaign destinations keep resources pending; later checks decide the result.
        }
    }

    private static void waitForReadyStateComplete(final Page page) {
        try {
            page.waitForCondition(() -> {
                final Object state = page.evaluate("() => document.readyState");
                return "complete".equals(state);
            }, new Page.WaitForConditionOptions().setTimeout(BROWSER_STABILIZE_TIMEOUT_MS));
        } catch (final PlaywrightException ignored) {
            // Network-heavy pages may not report complete inside the stabilization window.
        }
    }

    private void pauseForStabilization() {
        if (stabilizationDelayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(stabilizationDelayMs);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isRendered(final Page page) {
        try {
            final Object result = page.evaluate("""
                    () => {
                      const body = document.body;
                      if (!body) return false;
                      const text = (body.innerText || body.textContent || '').trim();
                      const hasVisibleText = text.length > 0;
                      const hasMedia = document.images.length > 0 ||
                                       document.querySelectorAll('video, canvas, iframe, svg').length > 0;
                      return hasVisibleText || hasMedia;
                    }
                    """);
            return Boolean.TRUE.equals(result);
        } catch (final Exception e) {
            return false;
        }
    }

    private static String pageTitle(final Page page) {
        try {
            final String title = page.title();
            return title == null ? "" : title.trim();
        } catch (final Exception e) {
            return "";
        }
    }

    private static String currentUrl(final Page page, final Response response) {
        try {
            final String pageUrl = page.url();
            if (pageUrl != null && !pageUrl.isBlank()) {
                return pageUrl;
            }
        } catch (final Exception ignored) {
            // fall through to response URL
        }

        return response == null || response.url() == null ? "" : response.url();
    }

    private static String elementDescriptor(final LinkEntry link) {
        final String tag = link.tagName().toLowerCase();
        final String role = link.role().toLowerCase();
        if ("button".equals(tag) || "button".equals(role)) {
            return "Button";
        }
        if ("a".equals(tag) && !link.text().isBlank()) {
            return "Text Link";
        }
        if ("a".equals(tag)) {
            return "Image Link";
        }
        if ("area".equals(tag)) {
            return "Image Map Area";
        }
        return "Interactive Element";
    }

    private static String boundsLabel(final Rect rect) {
        return String.format("%d,%d %dx%d", rect.x(), rect.y(), rect.width(), rect.height());
    }

    private static String formatBrowserStatus(final Response response) {
        return formatHttpStatus(
                response.status(),
                normaliseStatusText(response.status(), response.statusText()));
    }

    private static String formatHttpStatus(final int status, final String statusText) {
        if (statusText == null || statusText.isBlank()) {
            return "HTTP " + status;
        }
        return "HTTP " + status + " " + statusText;
    }

    private static String normaliseStatusText(final Integer status, final String statusText) {
        if (statusText != null && !statusText.isBlank()) {
            return statusText.trim();
        }
        if (status == null) {
            return "";
        }
        return switch (status) {
            case 100 -> "Continue";
            case 101 -> "Switching Protocols";
            case 200 -> "OK";
            case 201 -> "Created";
            case 202 -> "Accepted";
            case 204 -> "No Content";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 303 -> "See Other";
            case 304 -> "Not Modified";
            case 307 -> "Temporary Redirect";
            case 308 -> "Permanent Redirect";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 408 -> "Request Timeout";
            case 409 -> "Conflict";
            case 410 -> "Gone";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 501 -> "Not Implemented";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            default -> "";
        };
    }

    private static List<String> redirectChain(final Response response) {
        try {
            final Request request = response.request();
            if (request == null) {
                return response.url() == null || response.url().isBlank()
                        ? List.of()
                        : List.of(response.url());
            }

            final List<String> chain = new ArrayList<>();
            Request current = request;
            while (current != null) {
                chain.add(current.url());
                current = current.redirectedFrom();
            }
            Collections.reverse(chain);

            final String finalUrl = response.url();
            if (finalUrl != null && !finalUrl.isBlank()
                    && (chain.isEmpty() || !finalUrl.equals(chain.getLast()))) {
                chain.add(finalUrl);
            }

            return chain;
        } catch (final Exception e) {
            final String finalUrl = response.url();
            return finalUrl == null || finalUrl.isBlank()
                    ? List.of()
                    : List.of(finalUrl);
        }
    }

    private static String screenshotSourceName(final String url) {
        final int hash = Math.abs(Objects.hashCode(url));
        return "link_destination_" + Integer.toHexString(hash);
    }

    // -------------------------------------------------------------------------
    // Internal – link extraction
    // -------------------------------------------------------------------------

    /**
     * Evaluates JavaScript in the page to extract all anchor elements and
     * returns them as typed {@link LinkEntry} records.
     */
    @SuppressWarnings("unchecked")
    private static List<LinkEntry> extractLinks(final Page page) {
        try {
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);

            final Object raw = page.evaluate(EXTRACT_LINKS_JS);

            if (!(raw instanceof List<?> rawList)) {
                log.warn("[{}] Unexpected JS evaluation result type: {}",
                        RULE_ID, raw == null ? "null" : raw.getClass().getSimpleName());
                return List.of();
            }

            final List<LinkEntry> entries = new ArrayList<>(rawList.size());
            for (final Object item : rawList) {
                if (item instanceof java.util.Map<?, ?> map) {
                    final String href      = stringOrEmpty(map.get("href"));
                    final String rawHref   = stringOrEmpty(map.get("rawHref"));
                    final String text      = stringOrEmpty(map.get("text"));
                    final String ariaLabel = stringOrEmpty(map.get("ariaLabel"));
                    final String title     = stringOrEmpty(map.get("title"));
                    final String target    = stringOrEmpty(map.get("target"));
                    final String tagName   = stringOrEmpty(map.get("tagName"));
                    final String role      = stringOrEmpty(map.get("role"));
                    final int domIndex     = integerOrDefault(map.get("domIndex"), entries.size());
                    final Rect rect = new Rect(
                            integerOrDefault(map.get("x"), 0),
                            integerOrDefault(map.get("y"), 0),
                            integerOrDefault(map.get("width"), 0),
                            integerOrDefault(map.get("height"), 0));

                    entries.add(new LinkEntry(
                            href,
                            rawHref,
                            text,
                            ariaLabel,
                            title,
                            target,
                            domIndex,
                            tagName,
                            role,
                            rect));
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

    private static int integerOrDefault(final Object value, final int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (final NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static LinkClassification classify(final LinkEntry link) {
        final String rawHref = link.rawHref() == null ? "" : link.rawHref().trim();
        final String href = link.href() == null ? "" : link.href().trim();
        final String rawLower = rawHref.toLowerCase();
        final String hrefLower = href.toLowerCase();

        if (rawHref.isBlank()) {
            if (isInteractiveWithoutHref(link)) {
                return new LinkClassification("INTERACTIVE", "Interactive Element", true);
            }
            return new LinkClassification("EMPTY_HREF", "Empty Href", false);
        }
        if (isPlaceholderLink(rawHref) || isPlaceholderLink(href)) {
            return new LinkClassification("TEMPLATE_PLACEHOLDER", "Template Placeholder", false);
        }
        if (rawLower.startsWith("tel:") || hrefLower.startsWith("tel:")) {
            return new LinkClassification("TELEPHONE", "Telephone Link", false);
        }
        if (rawLower.startsWith("mailto:") || hrefLower.startsWith("mailto:")) {
            return new LinkClassification("MAILTO", "Email Link", false);
        }
        if (rawLower.startsWith("#") || hrefLower.startsWith("#")) {
            return new LinkClassification("ANCHOR", "Anchor Link", false);
        }
        if (hrefLower.startsWith("http://") || hrefLower.startsWith("https://")) {
            return new LinkClassification("HTTP", "HTTP/HTTPS Link", true);
        }
        if (!rawLower.matches("^[a-z][a-z0-9+.-]*:.*")) {
            return new LinkClassification("RELATIVE", "Relative URL", false);
        }
        return new LinkClassification("UNSUPPORTED", "Unsupported Link Type", false);
    }

    private static boolean isInteractiveWithoutHref(final LinkEntry link) {
        final String tag = link.tagName() == null ? "" : link.tagName().toLowerCase();
        final String role = link.role() == null ? "" : link.role().toLowerCase();
        return "button".equals(tag) || "button".equals(role);
    }

    static boolean isPlaceholderLink(final String href) {
        if (href == null || href.isBlank()) {
            return false;
        }

        final String trimmed = href.trim();
        if (matchesPlaceholderPattern(trimmed)) {
            return true;
        }

        final String decoded = safeUrlDecode(trimmed);
        return !decoded.equals(trimmed) && matchesPlaceholderPattern(decoded);
    }

    private static boolean hasPlaceholderLink(final LinkEntry link) {
        return isPlaceholderLink(link.rawHref()) || isPlaceholderLink(link.href());
    }

    private static boolean matchesPlaceholderPattern(final String value) {
        return PLACEHOLDER_LINK_PATTERNS.stream()
                .anyMatch(pattern -> pattern.matcher(value).find());
    }

    private static String safeUrlDecode(final String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (final IllegalArgumentException e) {
            return value;
        }
    }

    private static String displayHref(final LinkEntry link) {
        final String rawHref = link.rawHref() == null ? "" : link.rawHref().trim();
        if (!rawHref.isBlank()) {
            return rawHref;
        }
        final String href = link.href() == null ? "" : link.href().trim();
        return href.isBlank() ? EMPTY_HREF : href;
    }

    // -------------------------------------------------------------------------
    // Internal records
    // -------------------------------------------------------------------------

    /**
     * Typed representation of a single {@code <a href="…">} element extracted
     * from the page.
     *
     * @param href    browser-resolved absolute URL (via {@code anchor.href})
     * @param rawHref literal, unresolved {@code href} attribute value (via
     *                {@code getAttribute('href')}) — used for detecting
     *                unresolved merge tags, since {@code href} has already
     *                had characters like {@code {} } percent-encoded away
     * @param text    trimmed visible text of the anchor
     */
    private record LinkEntry(
            String href,
            String rawHref,
            String text,
            String ariaLabel,
            String title,
            String target,
            int domIndex,
            String tagName,
            String role,
            Rect rect) {
        private LinkEntry {
            href = href == null ? "" : href;
            rawHref = rawHref == null ? "" : rawHref;
            text = text == null ? "" : text;
            ariaLabel = ariaLabel == null ? "" : ariaLabel;
            title = title == null ? "" : title;
            target = target == null ? "" : target;
            tagName = tagName == null ? "" : tagName;
            role = role == null ? "" : role;
            rect = rect == null ? new Rect(0, 0, 0, 0) : rect;
        }
    }

    private record Rect(int x, int y, int width, int height) {}

    private record LinkClassification(String type, String note, boolean requiresBrowserJourney) {}

    private record NavigationResult(Page page, long clickMs, long navigationMs) {}

    private static final class LinkTiming {
        private long clickMs;
        private long navigationMs;
        private long stabilizationMs;
        private long screenshotMs;
    }

    /**
     * Result of a single browser click-journey validation.
     *
     * @param link        clicked element metadata
     * @param ok          {@code true} when browser navigation produced a
     *                    successful final status and rendered content
     * @param validationStatus dashboard-facing result status
     * @param reason      human-readable status or error description
     */
    private record ProbeResult(
            LinkEntry link,
            boolean ok,
            String validationStatus,
            String reason,
            String finalUrl,
            Integer httpStatus,
            String statusText,
            Integer redirectCount,
            List<String> redirectChain,
            String validationNote,
            String pageTitle,
            Long responseTimeMs,
            String screenshotPath) {
        private ProbeResult {
            Objects.requireNonNull(link, "link must not be null");
            validationStatus = validationStatus == null || validationStatus.isBlank()
                    ? (ok ? "PASS" : "FAIL")
                    : validationStatus;
            reason = reason == null ? "" : reason;
            finalUrl = finalUrl == null ? "" : finalUrl;
            statusText = statusText == null ? "" : statusText;
            redirectChain = redirectChain == null ? List.of() : List.copyOf(redirectChain);
            validationNote = validationNote == null ? "" : validationNote;
            pageTitle = pageTitle == null || pageTitle.isBlank() ? "Unknown" : pageTitle;
        }

        private String url() {
            return link.href();
        }

        private String displayText() {
            return link.text();
        }

        private static ProbeResult failure(
                final LinkEntry link,
                final String reason) {
            return failure(link, reason, null);
        }

        private static ProbeResult failure(
                final LinkEntry link,
                final String reason,
                final Long responseTimeMs) {
            return new ProbeResult(
                    link,
                    false,
                    "FAIL",
                    reason,
                    link.href(),
                    null,
                    "",
                    null,
                    List.of(),
                    "",
                    "",
                    responseTimeMs,
                    null);
        }

        private static ProbeResult failure(
                final LinkEntry link,
                final String reason,
                final Long responseTimeMs,
                final Response response) {

            final Integer status = response == null ? null : response.status();
            final String statusText = normaliseStatusText(
                    status,
                    response == null ? "" : response.statusText());
            final String finalUrl = response == null || response.url() == null
                    ? link.href()
                    : response.url();

            return new ProbeResult(
                    link,
                    false,
                    "FAIL",
                    reason,
                    finalUrl,
                    status,
                    statusText,
                    null,
                    response == null ? List.of() : LinkValidationRule.redirectChain(response),
                    "",
                    "Unknown",
                    responseTimeMs,
                    null);
        }
    }
}
