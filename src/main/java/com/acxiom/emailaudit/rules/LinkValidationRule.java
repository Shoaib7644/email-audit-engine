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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.SequencedSet;
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
 * <h2>Link extraction</h2>
 * <p>All {@code <a href="…">} elements are collected via a Playwright
 * {@code evaluate()} call, which returns resolved absolute URLs exactly as
 * the browser would compute them.</p>
 *
 * <h2>Broken-link probing</h2>
 * <p>HTTP probing is done with {@link HttpURLConnection} HEAD requests in a
 * fixed-size virtual-thread pool (Java 21-ready but falls back to platform
 * threads on Java 17 via {@link Executors#newFixedThreadPool}).  Each probe
 * has an independent connect/read timeout; failures are recorded per URL
 * without aborting the rest of the batch.</p>
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

    private static final int  CONNECT_TIMEOUT_MS = 5_000;
    private static final int  READ_TIMEOUT_MS    = 5_000;
    private static final int  PROBE_THREADS      = 8;
    private static final int  PROBE_AWAIT_SECS   = 60;
    private static final int  MAX_BROKEN_REPORT  = 20;   // cap findings list

    /** HTTP status codes that are never treated as broken (redirect chain end). */
    private static final int HTTP_OK_MIN    = 200;
    private static final int HTTP_OK_MAX    = 399;

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
     * @return PASS, FAIL, or ERROR result
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

        // ── 2. Check for missing unsubscribe link ────────────────────────────
        checkUnsubscribePresence(links, findings);

        // ── 3. Check for HTTP (non-HTTPS) links ──────────────────────────────
        checkInsecureLinks(links, findings);

        // ── 4. Probe for broken links (HEAD requests) ────────────────────────
        checkBrokenLinks(links, findings);

        if (findings.isEmpty()) {
            log.info("[{}] All link checks passed", RULE_ID);
            return RuleResult.pass(this, startMs);
        }

        log.warn("[{}] {} link issue(s) found", RULE_ID, findings.size());
        return RuleResult.fail(this, startMs, findings);
    }

    // -------------------------------------------------------------------------
    // Check 1 – Missing unsubscribe link
    // -------------------------------------------------------------------------

    private static void checkUnsubscribePresence(
            final List<LinkEntry> links,
            final List<String> findings) {

        final boolean found = links.stream().anyMatch(LinkValidationRule::isUnsubscribeLink);

        if (!found) {
            final String finding =
                    "No unsubscribe link detected – CAN-SPAM and GDPR require a clearly "
                            + "visible mechanism for recipients to opt out of future emails. "
                            + "Add an anchor whose text or href contains 'unsubscribe', "
                            + "'opt-out', or 'email preferences'.";
            findings.add(finding);
            log.debug("[LINKS] {}", finding);
        }
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

        final List<String> insecure = links.stream()
                .map(LinkEntry::href)
                .filter(href -> HTTP_SCHEME_PATTERN.matcher(href).find())
                .distinct()
                .toList();

        if (insecure.isEmpty()) return;

        final String finding = String.format(
                "%d insecure HTTP link(s) detected (should use HTTPS): %s",
                insecure.size(),
                String.join(", ", insecure.size() <= 5
                        ? insecure
                        : insecure.subList(0, 5)));

        findings.add(finding);
        log.debug("[LINKS] {}", finding);
    }

    // -------------------------------------------------------------------------
    // Check 3 – Broken links (parallel HEAD probes)
    // -------------------------------------------------------------------------

    private static void checkBrokenLinks(
            final List<LinkEntry> links,
            final List<String> findings) {

        // Collect unique probeable URLs (HTTP / HTTPS only, deduplicated).
        final SequencedSet<String> probeable = new LinkedHashSet<>();
        for (final LinkEntry link : links) {
            final String href = link.href();
            if (!SKIP_SCHEME_PATTERN.matcher(href).find()
                    && (href.startsWith("http://") || href.startsWith("https://"))) {
                probeable.add(href);
            }
        }

        if (probeable.isEmpty()) {
            log.debug("[{}] No HTTP/HTTPS links to probe", RULE_ID);
            return;
        }

        log.info("[{}] Probing {} unique URL(s) for broken links", RULE_ID, probeable.size());

        final List<ProbeResult> probeResults = probeAll(probeable);

        final List<String> broken = probeResults.stream()
                .filter(r -> !r.ok())
                .map(r -> String.format("%s → %s", r.url(), r.reason()))
                .limit(MAX_BROKEN_REPORT)
                .toList();

        if (broken.isEmpty()) return;

        final long totalBroken = probeResults.stream().filter(r -> !r.ok()).count();
        final String finding = String.format(
                "%d broken link(s) detected%s: %s",
                totalBroken,
                totalBroken > MAX_BROKEN_REPORT
                        ? " (showing first " + MAX_BROKEN_REPORT + ")" : "",
                String.join(" | ", broken));

        findings.add(finding);
        log.debug("[LINKS] {}", finding);
    }

    /**
     * Dispatches HEAD probes for all URLs in parallel and collects results.
     */
    private static List<ProbeResult> probeAll(final SequencedSet<String> urls) {
        final ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(PROBE_THREADS, urls.size()));

        final List<Future<ProbeResult>> futures = new ArrayList<>(urls.size());

        try {
            for (final String url : urls) {
                futures.add(executor.submit(() -> probe(url)));
            }

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
     */
    private static ProbeResult probe(final String url) {
        try {
            final int status = sendRequest(url, "HEAD");

            // Some servers return 405 for HEAD — retry with GET.
            if (status == HttpURLConnection.HTTP_BAD_METHOD) {
                log.debug("[{}] HEAD not allowed for '{}', retrying with GET", RULE_ID, url);
                final int getStatus = sendRequest(url, "GET");
                return evaluateStatus(url, getStatus);
            }

            return evaluateStatus(url, status);

        } catch (final Exception e) {
            final String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.debug("[{}] Probe failed for '{}': {}", RULE_ID, url, reason);
            return new ProbeResult(url, false, reason);
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

    private static ProbeResult evaluateStatus(final String url, final int status) {
        final boolean ok = status >= HTTP_OK_MIN && status <= HTTP_OK_MAX;
        final String reason = ok ? "HTTP " + status : "HTTP " + status + " (broken)";
        return new ProbeResult(url, ok, reason);
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
     */
    private record ProbeResult(String url, boolean ok, String reason) {}
}
