package com.acxiom.emailaudit.campaign;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Compares expected campaign URL templates against rendered URLs while
 * ignoring dynamic ESP values, parameter order, trailing slashes, and anchors.
 */
public final class UrlTemplateComparator {

    public ComparisonResult compare(final String expectedUrl, final String actualUrl) {
        final String expected = clean(expectedUrl);
        final String actual = clean(actualUrl);

        if (expected.isBlank()) {
            return ComparisonResult.notApplicable("No expected URL supplied.");
        }
        if (actual.isBlank()) {
            return ComparisonResult.fail("HTML href is missing.");
        }

        if (isMailto(expected) || isMailto(actual)) {
            return mailAddress(expected).equalsIgnoreCase(mailAddress(actual))
                    ? ComparisonResult.pass("Mailto address matches.")
                    : ComparisonResult.fail("Mailto address does not match.");
        }

        if (isTelephone(expected) || isTelephone(actual)) {
            return digits(expected).equals(digits(actual))
                    ? ComparisonResult.pass("Telephone number matches.")
                    : ComparisonResult.fail("Telephone number does not match.");
        }

        final ParsedUrl expectedParsed = ParsedUrl.parse(expected);
        final ParsedUrl actualParsed = ParsedUrl.parse(actual);

        if (!expectedParsed.scheme().isBlank()) {
            if (actualParsed.scheme().isBlank()
                    || !expectedParsed.scheme().equalsIgnoreCase(actualParsed.scheme())) {
                return ComparisonResult.fail("URL protocol mismatch.");
            }
        }

        if (!expectedParsed.host().isBlank()) {
            if (actualParsed.host().isBlank()
                    || !expectedParsed.host().equalsIgnoreCase(actualParsed.host())) {
                return ComparisonResult.fail("URL host mismatch.");
            }
        }

        if (!expectedParsed.path().equals(actualParsed.path())) {
            return ComparisonResult.fail("URL path mismatch.");
        }

        final Set<String> missingParams = new LinkedHashSet<>(expectedParsed.queryParameterNames());
        missingParams.removeAll(actualParsed.queryParameterNames());
        if (!missingParams.isEmpty()) {
            return ComparisonResult.fail("Missing URL parameter(s): " + String.join(", ", missingParams) + ".");
        }

        return ComparisonResult.pass("URL structure matches.");
    }

    private static boolean isMailto(final String value) {
        return value.toLowerCase(Locale.ROOT).startsWith("mailto:");
    }

    private static boolean isTelephone(final String value) {
        return value.toLowerCase(Locale.ROOT).startsWith("tel:");
    }

    private static String mailAddress(final String url) {
        final String value = clean(url).replaceFirst("(?i)^mailto:", "");
        final int query = value.indexOf('?');
        return query >= 0 ? value.substring(0, query).trim() : value.trim();
    }

    private static String digits(final String value) {
        return clean(value).replaceAll("\\D+", "");
    }

    private static String clean(final String value) {
        return value == null ? "" : value.trim();
    }

    public record ComparisonResult(String status, String message) {
        static ComparisonResult pass(final String message) {
            return new ComparisonResult("PASS", message);
        }

        static ComparisonResult fail(final String message) {
            return new ComparisonResult("FAIL", message);
        }

        static ComparisonResult notApplicable(final String message) {
            return new ComparisonResult("N/A", message);
        }
    }

    private record ParsedUrl(
            String scheme,
            String host,
            String path,
            Set<String> queryParameterNames) {

        private static ParsedUrl parse(final String rawUrl) {
            final String withoutFragment = stripFragment(clean(rawUrl));
            final int queryIndex = withoutFragment.indexOf('?');
            final String base = queryIndex >= 0 ? withoutFragment.substring(0, queryIndex) : withoutFragment;
            final String query = queryIndex >= 0 ? withoutFragment.substring(queryIndex + 1) : "";

            String scheme = "";
            String host = "";
            String path = base;

            final int schemeIndex = base.indexOf("://");
            if (schemeIndex > 0) {
                scheme = base.substring(0, schemeIndex).toLowerCase(Locale.ROOT);
                final String remainder = base.substring(schemeIndex + 3);
                final int slashIndex = remainder.indexOf('/');
                host = slashIndex >= 0
                        ? remainder.substring(0, slashIndex).toLowerCase(Locale.ROOT)
                        : remainder.toLowerCase(Locale.ROOT);
                path = slashIndex >= 0 ? remainder.substring(slashIndex) : "";
            }

            return new ParsedUrl(
                    scheme,
                    host,
                    normalisePath(path),
                    queryParameterNames(query));
        }

        private static String stripFragment(final String value) {
            final int fragment = value.indexOf('#');
            return fragment >= 0 ? value.substring(0, fragment) : value;
        }

        private static String normalisePath(final String path) {
            if (path == null || path.isBlank() || "/".equals(path)) {
                return "";
            }
            return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        }

        private static Set<String> queryParameterNames(final String query) {
            final Set<String> names = new LinkedHashSet<>();
            if (query == null || query.isBlank()) {
                return names;
            }
            for (final String pair : query.split("&")) {
                if (pair.isBlank()) {
                    continue;
                }
                final int equals = pair.indexOf('=');
                final String rawName = equals >= 0 ? pair.substring(0, equals) : pair;
                final String name = decode(rawName).toLowerCase(Locale.ROOT);
                if (!name.isBlank()) {
                    names.add(name);
                }
            }
            return names;
        }

        private static String decode(final String value) {
            try {
                return URLDecoder.decode(value == null ? "" : value, StandardCharsets.UTF_8);
            } catch (final IllegalArgumentException ignored) {
                return value == null ? "" : value;
            }
        }
    }
}
