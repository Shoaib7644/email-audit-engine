package com.acxiom.emailaudit.campaign;

import java.util.Locale;

/**
 * Infers and compares business-facing campaign asset types.
 */
public final class TypeValidator {

    public TypeResult validate(final CampaignSpecificationRow specRow, final CampaignHtmlMetadata metadata) {
        final String expectedType = expectedType(specRow);
        if (metadata == null) {
            return new TypeResult(expectedType, "", "FAIL", "Element type cannot be checked because the element is missing.");
        }

        final String actualType = metadata.type();
        if (typesCompatible(expectedType, actualType)) {
            return new TypeResult(expectedType, actualType, "PASS", "Element type matches.");
        }

        return new TypeResult(expectedType, actualType, "FAIL",
                "Element type mismatch. Expected " + expectedType + " but found " + actualType + ".");
    }

    public String expectedType(final CampaignSpecificationRow row) {
        final String taxonomy = row == null ? "" : row.taxonomy();
        final String category = row == null ? "" : row.expectedCategory();
        final String combined = firstNonBlank(category, taxonomy).toUpperCase(Locale.ROOT);

        if (combined.endsWith("_IMAGE")) {
            return "Image";
        }
        if (combined.endsWith("_CTA")) {
            return "CTA";
        }
        if (combined.endsWith("_TEXT")) {
            return "Text";
        }

        return row == null || row.expectedElementType().isBlank()
                ? "Text"
                : row.expectedElementType();
    }

    private static boolean typesCompatible(final String expected, final String actual) {
        final String expectedType = typeKey(expected);
        final String actualType = typeKey(actual);
        if (expectedType.equals(actualType)) {
            return true;
        }
        return ("cta".equals(expectedType) && "button".equals(actualType))
                || ("button".equals(expectedType) && "cta".equals(actualType));
    }

    private static String typeKey(final String value) {
        final String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        if (lower.contains("mirror")) {
            return "mirror";
        }
        if (lower.contains("opt")) {
            return "optout";
        }
        if (lower.contains("mail")) {
            return "mailto";
        }
        if (lower.contains("tel") || lower.contains("phone")) {
            return "telephone";
        }
        if (lower.contains("image")) {
            return "image";
        }
        if (lower.contains("button")) {
            return "button";
        }
        if (lower.contains("cta")) {
            return "cta";
        }
        return "text";
    }

    private static String firstNonBlank(final String... values) {
        if (values == null) {
            return "";
        }
        for (final String value : values) {
            if (value != null && !value.trim().isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    public record TypeResult(String expectedType, String actualType, String status, String message) {
    }
}
