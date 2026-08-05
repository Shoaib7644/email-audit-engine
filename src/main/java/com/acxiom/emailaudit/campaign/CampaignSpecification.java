package com.acxiom.emailaudit.campaign;

import java.nio.file.Path;
import java.util.List;

/**
 * Parsed, generic campaign specification independent of source format.
 */
public record CampaignSpecification(
        Path sourceFile,
        String worksheetName,
        List<String> headers,
        List<CampaignSpecificationRow> entries) {

    public CampaignSpecification {
        if (sourceFile == null) {
            throw new IllegalArgumentException("sourceFile must not be null");
        }
        worksheetName = worksheetName == null ? "" : worksheetName.trim();
        headers = headers == null ? List.of() : List.copyOf(headers);
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
