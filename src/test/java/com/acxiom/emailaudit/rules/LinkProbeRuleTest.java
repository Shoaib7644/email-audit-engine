package com.acxiom.emailaudit.rules;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.WaitUntilState;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class LinkProbeRuleTest {

    private HttpServer server;
    private ExecutorService executor;
    private String baseUrl;
    private Path screenshotDir;

    @BeforeMethod
    public void setUp() throws IOException {
        screenshotDir = Files.createTempDirectory("link-validation-shots");
        executor = Executors.newCachedThreadPool();
        server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.createContext("/ok", exchange -> respond(exchange, 200, "ok"));
        server.createContext("/direct", exchange -> respondHtml(exchange, 200,
                "<!doctype html><title>Direct</title><body>direct</body>"));
        server.createContext("/redirect-one", exchange -> redirect(exchange, "/redirect-final"));
        server.createContext("/redirect-two", exchange -> redirect(exchange, "/redirect-hop"));
        server.createContext("/redirect-hop", exchange -> redirect(exchange, "/redirect-final"));
        server.createContext("/redirect-final", exchange -> respondHtml(exchange, 200,
                "<!doctype html><title>Redirect Final</title><body>final</body>"));
        server.createContext("/history-final", exchange -> respondHtml(exchange, 200,
                "<!doctype html><title>History Final</title>"
                        + "<script>history.replaceState(null, '', '/history-final-canonical');</script>"
                        + "<body>history final</body>"));
        server.createContext("/subresource-page", this::handleSubresourcePage);
        server.createContext("/chat", exchange -> respondHtml(exchange, 200,
                "<!doctype html><title>Chat Widget</title><body>chat</body>"));
        server.createContext("/xhr", exchange -> respond(exchange, 200, "{\"ok\":true}"));
        server.createContext("/pixel", this::handlePixel);
        server.createContext("/missing", exchange -> respond(exchange, 404, "not found"));
        server.createContext("/track", this::handleTrackingRedirect);
        server.createContext("/consent/unsubscribe/", exchange -> respond(exchange, 200, "unsubscribe ok"));
        server.createContext("/preferences", exchange -> respond(exchange, 200, "preferences ok"));
        server.createContext("/browser-only", this::handleBrowserOnly);
        server.createContext("/playwright-only", this::handlePlaywrightOnly);
        server.start();
        baseUrl = "http://" + server.getAddress().getHostString()
                + ":" + server.getAddress().getPort();
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    public void linkValidationStillFailsForLegitimate404() {
        final String href = baseUrl + "/missing";

        final RuleResult result = executeAgainstHtml("""
                <!doctype html>
                <html><body>
                  <a href="{{ADV_UNSUB_LINK}}">Unsubscribe</a>
                  <a href="%s">Request Quote</a>
                </body></html>
                """.formatted(href));

        assertTrue(result.isFailed());
        assertTrue(result.getFindings().stream().anyMatch(f -> f.contains("HTTP 404")),
                String.join("\n", result.getFindings()));

        @SuppressWarnings("unchecked")
        final List<LinkAuditEntry> links =
                (List<LinkAuditEntry>) result.getMetadata().get("links");
        final LinkAuditEntry requestQuote = links.stream()
                .filter(link -> link.visibleText().equals("Request Quote"))
                .findFirst()
                .orElseThrow();
        assertEquals(requestQuote.validationStatus(), "FAIL");
        assertEquals(requestQuote.httpStatus(), Integer.valueOf(404));
    }

    @Test
    public void linkValidationUsesBrowserContextForTrackingLink() {
        final String href = baseUrl + "/playwright-only";

        final RuleResult result = executeAgainstHtml("""
                <!doctype html>
                <html><body>
                  <a href="{{ADV_UNSUB_LINK}}">Unsubscribe</a>
                  <a href="%s">Visit us on Facebook.</a>
                </body></html>
                """.formatted(href));

        assertTrue(result.isFailed(), String.join("\n", result.getFindings()));
        assertTrue(result.getFindings().stream()
                        .noneMatch(f -> f.contains("Broken Link")
                                && f.contains("Visit us on Facebook.")),
                String.join("\n", result.getFindings()));

        @SuppressWarnings("unchecked")
        final List<LinkAuditEntry> links =
                (List<LinkAuditEntry>) result.getMetadata().get("links");
        final LinkAuditEntry facebook = links.stream()
                .filter(link -> link.visibleText().equals("Visit us on Facebook."))
                .findFirst()
                .orElseThrow();
        assertEquals(facebook.validationStatus(), "PASS");
        assertTrue(facebook.screenshotPath() != null && !facebook.screenshotPath().isBlank());
    }

    @Test
    public void unresolvedPlaceholdersFailWhileTelephoneAndEmailLinksAreSkipped() {
        final Page page = mock(Page.class);
        when(page.url()).thenReturn("file:///tmp/email.html");
        when(page.evaluate(anyString())).thenReturn(List.of(
                link("{{ADV_UNSUB_LINK}}", "Unsubscribe"),
                link("%%view_email_url%%", "View in browser"),
                link("${profileUrl}", "Profile"),
                link("[[preference_center]]", "Preferences"),
                link("<%= mirror_page_url %>", "Mirror page"),
                link("tel:+18002221020", "Call us"),
                link("mailto:support@example.com", "Email support")
        ));

        final RuleResult result = new LinkValidationRule().execute(page);

        assertTrue(result.isFailed(), String.join("\n", result.getFindings()));
        assertTrue(result.getFindings().stream()
                        .anyMatch(f -> f.contains("Unresolved placeholder detected")),
                String.join("\n", result.getFindings()));

        @SuppressWarnings("unchecked")
        final List<LinkAuditEntry> links =
                (List<LinkAuditEntry>) result.getMetadata().get("links");

        assertEquals(links.size(), 7);
        assertEquals(links.get(0).linkType(), "TEMPLATE_PLACEHOLDER");
        assertEquals(links.get(0).validationNote(), "Template Placeholder");
        assertEquals(links.get(0).validationStatus(), "FAIL");
        assertEquals(links.get(0).reason(),
                "Unresolved placeholder detected. Final rendered email still contains an ESP merge tag.");
        assertEquals(links.get(0).httpStatus(), null);
        assertEquals(links.get(0).pageTitle(), "Not Available");
        assertEquals(links.get(5).linkType(), "TELEPHONE");
        assertEquals(links.get(5).validationNote(), "Telephone Link");
        assertEquals(links.get(5).validationStatus(), "SKIPPED");
        assertEquals(links.get(6).linkType(), "MAILTO");
        assertEquals(links.get(6).validationNote(), "Email Link");
        assertEquals(links.get(6).validationStatus(), "SKIPPED");
    }

    @Test
    public void missingUnsubscribeFindingUsesUnsubscribeTerminology() {
        final Page page = mock(Page.class);
        when(page.url()).thenReturn("file:///tmp/email.html");
        when(page.evaluate(anyString())).thenReturn(List.of(
                link("mailto:support@example.com", "Email support")
        ));

        final RuleResult result = new LinkValidationRule().execute(page);

        assertTrue(result.isFailed(), String.join("\n", result.getFindings()));
        assertTrue(result.getFindings().stream()
                        .anyMatch(finding -> finding.contains("Missing Unsubscribe Link")),
                String.join("\n", result.getFindings()));
        assertTrue(result.getFindings().stream()
                        .noneMatch(finding -> finding.contains("Missing Privacy Link")),
                String.join("\n", result.getFindings()));
    }

    @Test
    public void unsubscribeMatcherRecognizesLiteralTextHrefAndFinalDestination() {
        assertTrue(LinkValidationRule.isUnsubscribeCandidate(
                "Unsubscribe",
                "https://example.test/profile",
                ""));
        assertTrue(LinkValidationRule.isUnsubscribeCandidate(
                "Manage email",
                "https://example.test/email-preferences",
                ""));
        assertTrue(LinkValidationRule.isUnsubscribeCandidate(
                "Manage settings",
                "https://tracking.example.test/r?id=123",
                "https://www.att.com/consent/unsubscribe/?token=abc"));
        assertTrue(LinkValidationRule.isUnsubscribeCandidate(
                "Opt out",
                "https://example.test/profile",
                ""));
        assertFalse(LinkValidationRule.isUnsubscribeCandidate(
                "Preferences",
                "https://example.test/preferences",
                ""));
    }

    @Test
    public void opaqueTrackingUrlQualifiesAsUnsubscribeWhenFinalUrlIsUnsubscribe() {
        final RuleResult result = executeAgainstHtml("""
                <!doctype html>
                <html><body>
                  <a href="%s/track">Manage settings</a>
                </body></html>
                """.formatted(baseUrl));

        assertTrue(result.getFindings().stream()
                        .noneMatch(finding -> finding.contains("Missing Unsubscribe Link")),
                        String.join("\n", result.getFindings()));
    }

    @Test
    public void redirectChainForDirectUrlContainsOnlyFinalNavigationUrl() {
        final LinkAuditEntry direct = validateSingleTarget("Direct", baseUrl + "/direct");

        assertEquals(direct.validationStatus(), "PASS");
        assertEquals(direct.httpStatus(), Integer.valueOf(200));
        assertEquals(direct.redirectCount(), Integer.valueOf(0));
        assertEquals(direct.redirectChain(), List.of(baseUrl + "/direct"));
        assertEquals(direct.redirectChain().getLast(), direct.finalUrl());
    }

    @Test
    public void redirectChainForOneRedirectContainsOnlyNavigationUrls() {
        final LinkAuditEntry redirected = validateSingleTarget("One Redirect", baseUrl + "/redirect-one");

        assertEquals(redirected.validationStatus(), "PASS");
        assertEquals(redirected.httpStatus(), Integer.valueOf(200));
        assertEquals(redirected.redirectCount(), Integer.valueOf(1));
        assertEquals(redirected.redirectChain(), List.of(
                baseUrl + "/redirect-one",
                baseUrl + "/redirect-final"));
        assertEquals(redirected.redirectChain().getLast(), redirected.finalUrl());
    }

    @Test
    public void redirectChainForMultipleRedirectsContainsOnlyNavigationUrls() {
        final LinkAuditEntry redirected = validateSingleTarget("Two Redirects", baseUrl + "/redirect-two");

        assertEquals(redirected.validationStatus(), "PASS");
        assertEquals(redirected.httpStatus(), Integer.valueOf(200));
        assertEquals(redirected.redirectCount(), Integer.valueOf(2));
        assertEquals(redirected.redirectChain(), List.of(
                baseUrl + "/redirect-two",
                baseUrl + "/redirect-hop",
                baseUrl + "/redirect-final"));
        assertEquals(redirected.redirectChain().getLast(), redirected.finalUrl());
    }

    @Test
    public void redirectChainIgnoresIframeFetchPixelAndChatWidgetRequests() {
        final LinkAuditEntry target = validateSingleTarget("Support Page", baseUrl + "/subresource-page");

        assertEquals(target.validationStatus(), "PASS");
        assertEquals(target.httpStatus(), Integer.valueOf(200));
        assertEquals(target.pageTitle(), "Understand Internet Speeds - AT&T Internet Customer Support");
        assertEquals(target.redirectCount(), Integer.valueOf(0));
        assertEquals(target.redirectChain(), List.of(baseUrl + "/subresource-page"));
        assertEquals(target.redirectChain().getLast(), target.finalUrl());
        assertFalse(target.redirectChain().stream().anyMatch(url ->
                        url.contains("/chat")
                                || url.contains("/xhr")
                                || url.contains("/pixel")),
                String.join("\n", target.redirectChain()));
    }

    @Test
    public void finalUrlCanDifferWithoutManufacturingRedirectCount() {
        final LinkAuditEntry target = validateSingleTarget("History Final", baseUrl + "/history-final");

        assertEquals(target.validationStatus(), "PASS");
        assertEquals(target.httpStatus(), Integer.valueOf(200));
        assertEquals(target.redirectCount(), Integer.valueOf(0));
        assertEquals(target.finalUrl(), baseUrl + "/history-final-canonical");
        assertEquals(target.redirectChain(), List.of(
                baseUrl + "/history-final",
                baseUrl + "/history-final-canonical"));
        assertEquals(target.redirectChain().getLast(), target.finalUrl());
    }

    @Test
    public void privacyLinkPassesWhenServerRequiresBrowserLikeHeaders() {
        final Page page = mock(Page.class);
        when(page.evaluate(anyString())).thenReturn(Map.of(
                "text", "Privacy Policy",
                "href", baseUrl + "/browser-only"
        ));

        final RuleResult result = new PrivacyLinkRule().execute(page);

        assertTrue(result.isPassed(), String.join("\n", result.getFindings()));
    }

    @Test
    public void privacyLinkUsesBrowserFallbackAfterHttp403() {
        final String href = baseUrl + "/playwright-only";
        final Page page = mock(Page.class);
        final BrowserContext context = mock(BrowserContext.class);
        final Page fallbackPage = mock(Page.class);
        final Response response = mock(Response.class);

        when(page.evaluate(anyString())).thenReturn(Map.of(
                "text", "Privacy Policy",
                "href", href
        ));
        when(page.context()).thenReturn(context);
        when(context.newPage()).thenReturn(fallbackPage);
        when(fallbackPage.navigate(anyString(), any(Page.NavigateOptions.class))).thenReturn(response);
        when(response.status()).thenReturn(200);
        when(response.statusText()).thenReturn("OK");

        final RuleResult result = new PrivacyLinkRule().execute(page);

        assertTrue(result.isPassed(), String.join("\n", result.getFindings()));
        assertTrue(result.getFindings().getFirst().contains("browser navigation fallback"));
        verify(context).newPage();
    }

    @Test
    public void privacyLinkStillFailsForLegitimate404() {
        final Page page = mock(Page.class);
        when(page.evaluate(anyString())).thenReturn(Map.of(
                "text", "Privacy Policy",
                "href", baseUrl + "/missing"
        ));

        final RuleResult result = new PrivacyLinkRule().execute(page);

        assertTrue(result.isFailed());
        assertTrue(result.getFindings().getFirst().contains("HTTP 404"),
                String.join("\n", result.getFindings()));
        verify(page, never()).context();
    }

    private static Map<String, Object> link(final String href, final String text) {
        return Map.of(
                "href", href,
                "rawHref", href,
                "text", text
        );
    }

    private LinkAuditEntry validateSingleTarget(
            final String text,
            final String href) {

        final RuleResult result = executeAgainstHtml("""
                <!doctype html>
                <html><body>
                  <a href="mailto:unsubscribe@example.test">Unsubscribe</a>
                  <a href="%s">%s</a>
                </body></html>
                """.formatted(href, text));

        @SuppressWarnings("unchecked")
        final List<LinkAuditEntry> links =
                (List<LinkAuditEntry>) result.getMetadata().get("links");

        return links.stream()
                .filter(link -> link.visibleText().equals(text))
                .findFirst()
                .orElseThrow();
    }

    private RuleResult executeAgainstHtml(final String html) {
        try {
            final Path emailFile = Files.createTempFile("link-journey-email", ".html");
            Files.writeString(emailFile, html, StandardCharsets.UTF_8);

            try (Playwright playwright = Playwright.create();
                 Browser browser = playwright.chromium().launch(
                         new BrowserType.LaunchOptions().setHeadless(true));
                 BrowserContext context = browser.newContext();
                 Page page = context.newPage()) {

                page.navigate(emailFile.toUri().toString(), new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                return new LinkValidationRule(
                        new com.acxiom.emailaudit.evidence.ScreenshotService(screenshotDir))
                        .execute(page);
            }
        } catch (final IOException e) {
            throw new AssertionError("Could not create temporary email fixture", e);
        }
    }

    private void handleBrowserOnly(final HttpExchange exchange) throws IOException {
        final String method = exchange.getRequestMethod();
        final String userAgent = firstHeader(exchange, "User-Agent");
        final String accept = firstHeader(exchange, "Accept");
        final String acceptLanguage = firstHeader(exchange, "Accept-Language");

        final boolean browserLike = "GET".equals(method)
                && userAgent.contains("Mozilla/5.0")
                && userAgent.contains("Chrome/")
                && accept.toLowerCase(Locale.ROOT).contains("text/html")
                && acceptLanguage.toLowerCase(Locale.ROOT).startsWith("en-us");

        respond(exchange, browserLike ? 200 : 403, browserLike ? "ok" : "forbidden");
    }

    private void handlePlaywrightOnly(final HttpExchange exchange) throws IOException {
        final String fetchSite = firstHeader(exchange, "Sec-Fetch-Site");
        respond(exchange, fetchSite.isBlank() ? 403 : 200,
                fetchSite.isBlank() ? "forbidden" : "ok");
    }

    private void handleTrackingRedirect(final HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Location", baseUrl + "/consent/unsubscribe/");
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private void handleSubresourcePage(final HttpExchange exchange) throws IOException {
        respondHtml(exchange, 200,
                "<!doctype html>"
                        + "<title>Understand Internet Speeds - AT&T Internet Customer Support</title>"
                        + "<body>"
                        + "<iframe src=\"" + baseUrl + "/chat\"></iframe>"
                        + "<img src=\"" + baseUrl + "/pixel\" alt=\"tracking pixel\">"
                        + "<script>fetch('" + baseUrl + "/xhr');</script>"
                        + "support content"
                        + "</body>");
    }

    private void handlePixel(final HttpExchange exchange) throws IOException {
        final byte[] bytes = new byte[] {
                (byte) 0x89, 0x50, 0x4E, 0x47,
                0x0D, 0x0A, 0x1A, 0x0A
        };
        exchange.getResponseHeaders().add("Content-Type", "image/png");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void redirect(final HttpExchange exchange, final String path) throws IOException {
        exchange.getResponseHeaders().add("Location", baseUrl + path);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static void respondHtml(
            final HttpExchange exchange,
            final int status,
            final String body) throws IOException {

        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        respond(exchange, status, body);
    }

    private static String firstHeader(final HttpExchange exchange, final String name) {
        final String value = exchange.getRequestHeaders().getFirst(name);
        return value == null ? "" : value;
    }

    private static void respond(
            final HttpExchange exchange,
            final int status,
            final String body) throws IOException {

        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
