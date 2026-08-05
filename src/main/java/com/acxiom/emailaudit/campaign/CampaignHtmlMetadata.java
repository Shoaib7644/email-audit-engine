package com.acxiom.emailaudit.campaign;

import com.acxiom.emailaudit.rules.LinkAuditEntry;

import java.util.Map;

/**
 * Clickable campaign metadata extracted from the rendered email HTML.
 */
public record CampaignHtmlMetadata(
        int domIndex,
        String category,
        String label,
        String href,
        String visibleText,
        String imageSrc,
        String type,
        Map<String, String> trackingParams,
        boolean wrapsImage,
        boolean mirrorPage,
        boolean optOut,
        boolean mailto,
        boolean telephone,
        LinkAuditEntry linkAuditEntry) {

    public CampaignHtmlMetadata {
        category = clean(category);
        label = clean(label);
        href = clean(href);
        visibleText = clean(visibleText);
        imageSrc = clean(imageSrc);
        type = clean(type);
        trackingParams = trackingParams == null ? Map.of() : Map.copyOf(trackingParams);
    }

    private static String clean(final String value) {
        return value == null ? "" : value.trim();
    }
}
