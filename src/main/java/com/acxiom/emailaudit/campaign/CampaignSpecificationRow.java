package com.acxiom.emailaudit.campaign;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One expected campaign asset from the uploaded implementation specification.
 */
public record CampaignSpecificationRow(
        int rowNumber,
        String itemNumber,
        String taxonomy,
        String shortLabel,
        String expectedLabel,
        String expectedCategory,
        String expectedHref,
        List<String> requiredTrackingParameters,
        String expectedElementType,
        String finalCode,
        Map<String, String> rawColumns) {

    public CampaignSpecificationRow {
        itemNumber = clean(itemNumber);
        taxonomy = clean(taxonomy);
        shortLabel = clean(shortLabel);
        expectedLabel = clean(expectedLabel);
        expectedCategory = clean(expectedCategory);
        expectedHref = clean(expectedHref);
        expectedElementType = clean(expectedElementType);
        finalCode = clean(finalCode);
        requiredTrackingParameters = requiredTrackingParameters == null
                ? List.of()
                : requiredTrackingParameters.stream()
                .map(CampaignSpecificationRow::clean)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        rawColumns = rawColumns == null ? Map.of() : new LinkedHashMap<>(rawColumns);
    }

    public String displayName() {
        if (!taxonomy.isBlank()) {
            return taxonomy;
        }
        if (!shortLabel.isBlank()) {
            return shortLabel;
        }
        if (!expectedLabel.isBlank()) {
            return expectedLabel;
        }
        return "Specification row " + rowNumber;
    }

    private static String clean(final String value) {
        return value == null ? "" : value.trim();
    }
}
