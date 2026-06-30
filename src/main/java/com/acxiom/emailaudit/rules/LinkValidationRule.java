package com.acxiom.emailaudit.rules;

import com.acxiom.emailaudit.rules.AuditRule;
import com.acxiom.emailaudit.rules.RuleResult;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.LoadState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.SequencedMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * {@link AuditRule} that validates all anchor links found on a rendered HTML page.
 *
 * <h2>Checks performed</h2>
 * <ol>
 *   <li><strong>Broken links</strong> – HTTP HEAD request per unique {@code href};
 *       any response outside 2xx or 3xx, or a connection failure, is a finding.</li>
 *   <li><strong>HTTP (non-HTTPS) links</strong> – any {@code href} using the plain
 *       {@code http://} scheme flags a security/mixed-content concern.</li>
 *   <li><strong>Missing unsubscribe link</strong> – no anchor whose {@code href}
 *       or visible text matches recognised unsubscribe patterns (CAN-SPAM / GDPR
 *       compliance).</li>
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
 *   <li>one finding per successfully probed HTTP/HTTPS link, e.g.:
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
 * set means there was nothing to probe — there is no individual link to point to.</p>
 *
 * <h2>Link extraction</h2>
 * <p>All {@code <a href="…">} elements are collected via a Playwright
 * {@code evaluate()} call, which returns resolved absolute URLs exactly as
 * the browser would compute them.</p>
 *
 * <h2>Broken-link probing</h2>
 * <p>HTTP probing is done with {@link HttpURLConnection} HEAD requests in a
 * fixed-size thread pool.  Each probe has an independent connect/read timeout;
 * failures are recorded per URL without aborting the rest of the batch.</p>
 *
 * <h2>Thread safety</h2>
 * <p>All {@link Pattern} instances and configuration constants are
 * {@code static final}.  An {@link ExecutorService} is created and shut down
 * within each {@link #execute(Page)} call — no shared mutable state escapes
 * the method.  The class is safe for concurrent use from multiple TestNG
 * threads.</p>
 */
public final class LinkValidationRule implements AuditRule {

    private static final Logger log = LoggerFactory.getLogger(LinkValidationRule.class);

    // -------------------------------------------------------------------------
    // Rule identity
    // -------------------------------------------------------------------------

    public static final String RULE_ID = "LINK_VALIDATION";

    private static final String DESCRIPTION =
            "Validates all anchor links for broken URLs, insecure HTTP scheme, "
                    + "and presence of a mandatory unsubscribe link.";

    // -------------------------------------------------------------------------
    // HTTP probe configuration
    // -------------------------------------------------------------------------

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS    = 5_000;
    private static final int PROBE_THREADS      = 8;
    private static final int PROBE_AWAIT_SECS   = 60;
    private static final int MAX_BROKEN_REPORT  = 20;   // cap findings list
    private static final int MAX_PASS_EVIDENCE  = 20;   // cap PASS evidence list

    /** HTTP status codes that are never treated as broken (redirect chain end). */
    private static final int HTTP_OK_MIN = 200;
    private static final int HTTP_OK_MAX = 399;

    // ── Placeholder labels for missing display data ───────────────────────────
    private static final String NO_VISIBLE_TEXT = "(no visible text)";
    private static final String EMPTY_HREF      = "(empty href)";

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

    /** Href schemes that should be skipped during validation (not HTTP/HTTPS URLs). */
    private static final Pattern SKIP_SCHEME_PATTERN =
            Pattern.compile("^(mailto:|tel:|sms:|#|javascript:|data:)",
                    Pattern.CASE_INSENSITIVE);

    // -------------------------------------------------------------------------
    // JavaScript used to extract all anchor data in one round-trip
    // -------------------------------------------------------------------------

    /**
     * Returns a JSON array of objects {@code {href, text}} for every
     * {@code <a>} element whose {@code href} attribute is non-empty.
     * The browser resolves relative URLs to absolute form automatically
     * via the {@code anchor.href} property.
     */
    private static final String EXTRACT_LINKS_JS = """
            () => Array.from(document.querySelectorAll('a[href]'))
                       .map(a => ({
                           href: a.href,
                           text: (a.innerText || a.textContent || '').trim()
                       }))
                       .filter(l => l.href && l.href.length > 0)
            """;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /** Creates a {@code LinkValidationRule} with default configuration. */
    public LinkValidationRule() {
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
     * Runs all three link checks against {@code page}.
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
        try {
            links = extractLinks(page);
        } catch (final Exception e) {
            log.error("[{}] Failed to extract links from page: {}", RULE_ID, e.getMessage(), e);
            return RuleResult.error(this, startMs, e);
        }

        log.info("[{}] {} anchor link(s) found", RULE_ID, links.size());

        final List<String> findings = new ArrayList<>();
        final List<String> passEvidence = new ArrayList<>();

        // ── 2. Check for missing unsubscribe link ────────────────────────────
        checkUnsubscribePresence(links, findings, passEvidence);

        // ── 3. Check for HTTP (non-HTTPS) links ──────────────────────────────
        checkInsecureLinks(links, findings);

        // ── 4. Probe for broken links (HEAD requests) ────────────────────────
        checkBrokenLinks(links, findings, passEvidence);

        if (findings.isEmpty()) {
            log.info("[{}] All link checks passed", RULE_ID);
            return RuleResult.builder(this, RuleResult.Status.PASS, startMs)
                    .withFindings(capEvidence(passEvidence))
                    .build();
        }

        log.warn("[{}] {} link issue(s) found", RULE_ID, findings.size());
        return RuleResult.fail(this, startMs, findings);
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
        // there was nothing to probe, so there is no individual link to cite.
    }

    // -------------------------------------------------------------------------
    // Check 3 – Broken links (parallel HEAD probes)
    // -------------------------------------------------------------------------

    /**
     * Probes every unique HTTP/HTTPS href in parallel and appends one
     * {@link FindingFormatter#brokenLink} finding per failed URL, or one
     * PASSED link-finding per successfully probed URL.
     *
     * <p>Links that share the same {@code href} are deduplicated before probing
     * (only one HTTP request is made); the first seen display text is used as
     * the label in the finding.  At most {@value #MAX_BROKEN_REPORT} individual
     * FAIL findings, and at most {@value #MAX_PASS_EVIDENCE} PASS evidence
     * findings, are added; overflow summaries are appended if either is
     * exceeded.</p>
     */
    private static void checkBrokenLinks(
            final List<LinkEntry> links,
            final List<String> findings,
            final List<String> passEvidence) {

        // ── Deduplicate by href, preserving the first display text seen ───────
        // SequencedMap keeps insertion order so findings appear in document order.
        final SequencedMap<String, String> hrefToDisplayText = new LinkedHashMap<>();
        for (final LinkEntry link : links) {
            final String href = link.href();
            if (!SKIP_SCHEME_PATTERN.matcher(href).find()
                    && (href.startsWith("http://") || href.startsWith("https://"))) {
                // putIfAbsent — first display text wins for duplicate hrefs
                hrefToDisplayText.putIfAbsent(href, link.text());
            }
        }

        if (hrefToDisplayText.isEmpty()) {
            log.debug("[{}] No HTTP/HTTPS links to probe", RULE_ID);
            return;
        }

        log.info("[{}] Probing {} unique URL(s) for broken links",
                RULE_ID, hrefToDisplayText.size());

        final List<ProbeResult> probeResults = probeAll(hrefToDisplayText);

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
                            .title("Broken Link")
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
    // Parallel probe dispatcher
    // -------------------------------------------------------------------------

    /**
     * Dispatches HEAD probes for all entries in {@code hrefToDisplayText} in
     * parallel and collects {@link ProbeResult}s, each carrying the href, its
     * resolved display text, the ok flag, and the reason string.
     *
     * @param hrefToDisplayText ordered map of href → first-seen display text
     * @return list of probe results, in the same order as the input map
     */
    private static List<ProbeResult> probeAll(
            final SequencedMap<String, String> hrefToDisplayText) {

        final ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(PROBE_THREADS, hrefToDisplayText.size()));

        final List<Future<ProbeResult>> futures = new ArrayList<>(hrefToDisplayText.size());

        try {
            hrefToDisplayText.forEach((href, displayText) ->
                    futures.add(executor.submit(() -> probe(href, displayText))));

            executor.shutdown();

            final boolean finished = executor.awaitTermination(PROBE_AWAIT_SECS, TimeUnit.SECONDS);
            if (!finished) {
                log.warn("[{}] Link probe timed out – some URLs may not have been checked", RULE_ID);
                executor.shutdownNow();
            }

        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[{}] Link probe interrupted", RULE_ID);
            executor.shutdownNow();
        }

        final List<ProbeResult> results = new ArrayList<>(futures.size());
        for (final Future<ProbeResult> f : futures) {
            try {
                if (f.isDone() && !f.isCancelled()) {
                    results.add(f.get());
                }
            } catch (final Exception e) {
                log.debug("[{}] Could not retrieve probe future result: {}", RULE_ID, e.getMessage());
            }
        }

        return results;
    }

    /**
     * Sends a HEAD request to {@code url} and returns a {@link ProbeResult}.
     * Falls back to GET if the server rejects HEAD (405 Method Not Allowed).
     *
     * @param url         the absolute URL to probe
     * @param displayText the visible link text associated with this URL
     */
    private static ProbeResult probe(final String url, final String displayText) {
        try {
            final int status = sendRequest(url, "HEAD");

            // Some servers return 405 for HEAD — retry with GET.
            if (status == HttpURLConnection.HTTP_BAD_METHOD) {
                log.debug("[{}] HEAD not allowed for '{}', retrying with GET", RULE_ID, url);
                final int getStatus = sendRequest(url, "GET");
                return evaluateStatus(url, displayText, getStatus);
            }

            return evaluateStatus(url, displayText, status);

        } catch (final Exception e) {
            final String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.debug("[{}] Probe failed for '{}': {}", RULE_ID, url, reason);
            return new ProbeResult(url, displayText, false, reason);
        }
    }

    private static int sendRequest(final String urlString, final String method) throws Exception {
        final URL url = URI.create(urlString).toURL();
        final HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent",
                "Mozilla/5.0 AuditEngine/1.0 LinkChecker");
        try {
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }

    private static ProbeResult evaluateStatus(
            final String url, final String displayText, final int status) {
        final boolean ok = status >= HTTP_OK_MIN && status <= HTTP_OK_MAX;
        final String reason = "HTTP " + status + (ok ? "" : " (broken)");
        return new ProbeResult(url, displayText, ok, reason);
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
                    final String href = stringOrEmpty(map.get("href"));
                    final String text = stringOrEmpty(map.get("text"));
                    if (!href.isBlank()) {
                        entries.add(new LinkEntry(href, text));
                    }
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

    // -------------------------------------------------------------------------
    // Internal records
    // -------------------------------------------------------------------------

    /**
     * Typed representation of a single {@code <a href="…">} element extracted
     * from the page.
     */
    private record LinkEntry(String href, String text) {}

    /**
     * Result of a single HTTP HEAD/GET probe.
     *
     * @param url         the probed URL
     * @param displayText visible anchor text associated with this URL
     * @param ok          {@code true} when the response was 2xx or 3xx
     * @param reason      human-readable status or error description
     */
    private record ProbeResult(String url, String displayText, boolean ok, String reason) {}
}