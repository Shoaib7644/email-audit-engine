package com.acxiom.emailaudit.campaign;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One generic row from an uploaded campaign specification.
 */
public record CampaignEntry(
        int rowNumber,
        String identifier,
        String taxonomy,
        String type,
        String expectedUrl,
        String trackingLabel,
        String trackingCategory,
        List<String> trackingParameters,
        Map<String, String> rawColumns) {

    public CampaignEntry {
        identifier = clean(identifier);
        taxonomy = clean(taxonomy);
        type = clean(type);
        expectedUrl = clean(expectedUrl);
        trackingLabel = clean(trackingLabel);
        trackingCategory = clean(trackingCategory);
        trackingParameters = trackingParameters == null
                ? List.of()
                : trackingParameters.stream()
                .map(CampaignEntry::clean)
                .filter(value -> !value.isBlank())
                .toList();
        rawColumns = rawColumns == null ? Map.of() : new LinkedHashMap<>(rawColumns);
    }

    public String displayName() {
        if (!taxonomy.isBlank()) {
            return taxonomy;
        }
        if (!identifier.isBlank()) {
            return identifier;
        }
        if (!trackingLabel.isBlank()) {
            return trackingLabel;
        }
        return "Specification row " + rowNumber;
    }

    private static String clean(final String value) {
        return value == null ? "" : value.trim();
    }
}
