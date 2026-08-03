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
        server.createContext("/missing", exchange -> respond(exchange, 404, "not found"));
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
