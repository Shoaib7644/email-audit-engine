package com.acxiom.emailaudit.campaign;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Business-facing validation result for one specification row or unexpected
 * rendered email link.
 */
public record CampaignValidationRow(
        int index,
        String identifier,
        String type,
        String actualType,
        String expectedUrl,
        String actualUrl,
        String visibleText,
        String expectedLabel,
        String actualLabel,
        String expectedCategory,
        String actualCategory,
        List<String> expectedTracking,
        Map<String, String> actualTrackingParameters,
        String urlStatus,
        String trackingStatus,
        String labelStatus,
        String categoryStatus,
        String elementStatus,
        String typeStatus,
        String screenshotStatus,
        String screenshotPath,
        String linkValidationStatus,
        String finalDestinationUrl,
        String httpStatus,
        String validation,
        String notes,
        Map<String, String> rawColumns) {

    public CampaignValidationRow {
        identifier = clean(identifier);
        type = clean(type);
        actualType = clean(actualType);
        expectedUrl = clean(expectedUrl);
        actualUrl = clean(actualUrl);
        visibleText = clean(visibleText);
        expectedLabel = clean(expectedLabel);
        actualLabel = clean(actualLabel);
        expectedCategory = clean(expectedCategory);
        actualCategory = clean(actualCategory);
        expectedTracking = expectedTracking == null
                ? List.of()
                : expectedTracking.stream()
                .map(CampaignValidationRow::clean)
                .filter(value -> !value.isBlank())
                .toList();
        actualTrackingParameters = actualTrackingParameters == null
                ? Map.of()
                : new LinkedHashMap<>(actualTrackingParameters);
        urlStatus = clean(urlStatus);
        trackingStatus = clean(trackingStatus);
        labelStatus = clean(labelStatus);
        categoryStatus = clean(categoryStatus);
        elementStatus = clean(elementStatus);
        typeStatus = clean(typeStatus);
        screenshotStatus = clean(screenshotStatus);
        screenshotPath = clean(screenshotPath);
        linkValidationStatus = clean(linkValidationStatus);
        finalDestinationUrl = clean(finalDestinationUrl);
        httpStatus = clean(httpStatus);
        validation = clean(validation);
        notes = clean(notes);
        rawColumns = rawColumns == null ? Map.of() : new LinkedHashMap<>(rawColumns);
    }

    public CampaignValidationRow(
            final int index,
            final String identifier,
            final String type,
            final String expectedUrl,
            final String actualUrl,
            final String validation,
            final String notes,
            final Map<String, String> rawColumns) {

        this(index,
                identifier,
                type,
                "",
                expectedUrl,
                actualUrl,
                "",
                "",
                "",
                "",
                "",
                List.of(),
                Map.of(),
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                validation,
                notes,
                rawColumns);
    }

    private static String clean(final String value) {
        return value == null ? "" : value.trim();
    }
}
