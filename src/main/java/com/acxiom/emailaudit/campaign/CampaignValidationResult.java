package com.acxiom.emailaudit.campaign;

import java.util.List;

/**
 * Complete result payload consumed by the dashboard and Excel export.
 */
public record CampaignValidationResult(
        boolean specificationSelected,
        String message,
        int expectedEntries,
        int matched,
        int missing,
        int unexpected,
        int trackingErrors,
        int urlErrors,
        int passed,
        int failed,
        int warnings,
        List<String> originalHeaders,
        List<CampaignValidationRow> rows) {

    public CampaignValidationResult {
        message = message == null ? "" : message;
        originalHeaders = originalHeaders == null ? List.of() : List.copyOf(originalHeaders);
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    public static CampaignValidationResult noSpecification() {
        return new CampaignValidationResult(
                false,
                "No campaign specification selected.",
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                List.of(),
                List.of());
    }

    public static CampaignValidationResult notExecuted(final String message) {
        return new CampaignValidationResult(
                true,
                message == null || message.isBlank()
                        ? "Campaign validation was not executed."
                        : message,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                List.of(),
                List.of());
    }
}
