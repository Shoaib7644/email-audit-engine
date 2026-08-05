package com.acxiom.emailaudit.campaign;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Validates that required tracking parameters exist, regardless of values.
 */
public final class TrackingValidator {

    public TrackingResult validate(
            final List<String> requiredParameters,
            final Map<String, String> actualParameters) {

        final Set<String> expected = normalise(requiredParameters);
        if (expected.isEmpty()) {
            return new TrackingResult("N/A", "No required tracking parameters supplied.");
        }

        final Set<String> actual = actualParameters == null
                ? Set.of()
                : normalise(actualParameters.keySet().stream().toList());

        final Set<String> missing = new LinkedHashSet<>(expected);
        missing.removeAll(actual);

        if (!missing.isEmpty()) {
            return new TrackingResult("FAIL",
                    "Missing tracking parameter(s): " + String.join(", ", missing) + ".");
        }

        return new TrackingResult("PASS", "Required tracking parameters are present.");
    }

    private static Set<String> normalise(final List<String> parameters) {
        final Set<String> values = new LinkedHashSet<>();
        if (parameters == null) {
            return values;
        }
        for (final String parameter : parameters) {
            final String name = parameterName(parameter);
            if (!name.isBlank()) {
                values.add(name);
            }
        }
        return values;
    }

    private static String parameterName(final String value) {
        final String trimmed = value == null ? "" : value.trim();
        final int equals = trimmed.indexOf('=');
        return (equals >= 0 ? trimmed.substring(0, equals) : trimmed)
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    public record TrackingResult(String status, String message) {
    }
}
