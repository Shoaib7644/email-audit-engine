package com.acxiom.emailaudit.rules.util;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class LinkHealthCheckerTest {

    private HttpServer server;
    private ExecutorService executor;
    private String baseUrl;

    @BeforeMethod
    public void setUp() throws IOException {
        executor = Executors.newCachedThreadPool();
        server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.createContext("/missing", exchange -> respond(exchange, 404, "not found"));
        server.createContext("/browser-only", this::handleBrowserOnly);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/browser-only");
            respond(exchange, 302, "");
        });
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(500);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "slow response");
        });
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
    public void failsForLegitimate404() {
        final ValidationResult result = LinkHealthChecker.validate(baseUrl + "/missing");

        assertFalse(result.valid());
        assertTrue(result.message().contains("HTTP 404"));
    }

    @Test
    public void passesWhenServerRequiresBrowserLikeHeaders() {
        final ValidationResult result = LinkHealthChecker.validate(baseUrl + "/browser-only");

        assertTrue(result.valid(), result.message());
        assertTrue(result.message().contains("HTTP 200"));
    }

    @Test
    public void followsRedirectsBeforeEvaluatingFinalStatus() {
        final ValidationResult result = LinkHealthChecker.validate(baseUrl + "/redirect");

        assertTrue(result.valid(), result.message());
        assertTrue(result.message().contains("HTTP 200"));
    }

    @Test
    public void failsWhenReadTimesOut() {
        final long startMs = System.currentTimeMillis();

        final ValidationResult result = LinkHealthChecker.validate(
                baseUrl + "/slow",
                Duration.ofMillis(100),
                Duration.ofMillis(100));

        final long durationMs = System.currentTimeMillis() - startMs;
        assertFalse(result.valid());
        assertTrue(result.message().contains("SocketTimeoutException"), result.message());
        assertTrue(durationMs < 2_000, "timeout test should fail promptly");
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
