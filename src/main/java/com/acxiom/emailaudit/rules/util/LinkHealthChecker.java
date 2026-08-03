package com.acxiom.emailaudit.rules.util;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LinkHealthChecker {

    private static final Logger log = LoggerFactory.getLogger(LinkHealthChecker.class);

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_REDIRECTS = 5;
    private static final int HTTP_TEMPORARY_REDIRECT = 307;
    private static final int HTTP_PERMANENT_REDIRECT = 308;

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/126.0.0.0 Safari/537.36";
    private static final String ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,"
                    + "image/avif,image/webp,image/apng,*/*;q=0.8";
    private static final String ACCEPT_LANGUAGE = "en-US,en;q=0.9";

    private LinkHealthChecker() {
    }

    /**
     * Validates that {@code url} is reachable using a browser-like GET request.
     *
     * <p>Marketing sites and ESP redirect domains frequently reject bare Java
     * clients or HTTP HEAD probes even though the same link works in a real
     * browser. This checker therefore sends GET, uses realistic browser
     * headers, follows redirects up to a bounded limit, and keeps explicit
     * connect/read timeouts so genuinely broken links still fail promptly.</p>
     *
     * @param url URL to validate; must not be blank
     * @return success for final HTTP 2xx/3xx responses or existing local files;
     *         failure with a human-readable reason otherwise
     */
    public static ValidationResult validate(final String url) {
        return validate(url, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
    }

    static ValidationResult validate(
            final String url,
            final Duration connectTimeout,
            final Duration readTimeout) {

        if (url == null || url.isBlank()) {
            return ValidationResult.failure("URL is empty");
        }

        Objects.requireNonNull(connectTimeout, "connectTimeout must not be null");
        Objects.requireNonNull(readTimeout, "readTimeout must not be null");

        try {

            URL target = URI.create(url).toURL();

            /*
             * Local file links
             */
            if ("file".equalsIgnoreCase(target.getProtocol())) {

                File file =
                        new File(target.toURI());

                if (!file.exists()) {

                    return ValidationResult.failure(
                            "Referenced file does not exist");
                }

                if (file.length() < 50) {

                    return ValidationResult.failure(
                            "Page loaded but contains little or no content");
                }

                return ValidationResult.success();
            }

            /*
             * HTTP / HTTPS links
             */
            if (!"http".equalsIgnoreCase(target.getProtocol())
                    && !"https".equalsIgnoreCase(target.getProtocol())) {
                return ValidationResult.failure(
                        "Unsupported protocol: "
                                + target.getProtocol());
            }

            return validateHttp(target, connectTimeout, readTimeout);

        } catch (Exception ex) {

            return ValidationResult.failure(
                    ex.getClass().getSimpleName()
                            + ": "
                            + ex.getMessage());
        }
    }

    private static ValidationResult validateHttp(
            final URL initialUrl,
            final Duration connectTimeout,
            final Duration readTimeout) throws Exception {

        URL currentUrl = initialUrl;

        for (int redirectCount = 0; redirectCount <= MAX_REDIRECTS; redirectCount++) {
            final HttpURLConnection connection =
                    (HttpURLConnection) currentUrl.openConnection();
            configure(connection, connectTimeout, readTimeout);

            try {
                final int status = connection.getResponseCode();

                if (isRedirect(status)) {
                    final String location = connection.getHeaderField("Location");
                    if (location == null || location.isBlank()) {
                        return ValidationResult.failure(formatHttpStatus(connection, status)
                                + " redirect missing Location");
                    }

                    if (redirectCount == MAX_REDIRECTS) {
                        return ValidationResult.failure(
                                "Too many redirects (more than " + MAX_REDIRECTS + ")");
                    }

                    final URL nextUrl = currentUrl.toURI().resolve(location).toURL();
                    log.debug("Following redirect: {} -> {}", currentUrl, nextUrl);
                    currentUrl = nextUrl;
                    continue;
                }

                if (status < 200 || status >= 400) {
                    return ValidationResult.failure(formatHttpStatus(connection, status));
                }

                return new ValidationResult(true, formatHttpStatus(connection, status));
            } finally {
                connection.disconnect();
            }
        }

        return ValidationResult.failure(
                "Too many redirects (more than " + MAX_REDIRECTS + ")");
    }

    private static void configure(
            final HttpURLConnection connection,
            final Duration connectTimeout,
            final Duration readTimeout) throws Exception {

        connection.setRequestMethod("GET");
        connection.setConnectTimeout(Math.toIntExact(connectTimeout.toMillis()));
        connection.setReadTimeout(Math.toIntExact(readTimeout.toMillis()));
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", ACCEPT);
        connection.setRequestProperty("Accept-Language", ACCEPT_LANGUAGE);
    }

    private static boolean isRedirect(final int status) {
        return status == HttpURLConnection.HTTP_MOVED_PERM
                || status == HttpURLConnection.HTTP_MOVED_TEMP
                || status == HttpURLConnection.HTTP_SEE_OTHER
                || status == HttpURLConnection.HTTP_MULT_CHOICE
                || status == HTTP_TEMPORARY_REDIRECT
                || status == HTTP_PERMANENT_REDIRECT;
    }

    private static String formatHttpStatus(
            final HttpURLConnection connection,
            final int status) {

        try {
            final String message = connection.getResponseMessage();
            if (message != null && !message.isBlank()) {
                return "HTTP " + status + " " + message;
            }
        } catch (final Exception ignored) {
            // Status code alone is enough for validation output.
        }
        final String reason = reasonPhrase(status);
        return reason.isBlank() ? "HTTP " + status : "HTTP " + status + " " + reason;
    }

    private static String reasonPhrase(final int status) {
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
}
