package com.acxiom.emailaudit.rules;

import java.util.List;

/**
 * Structured link validation detail captured by {@link LinkValidationRule}.
 */
public record LinkAuditEntry(
        String visibleText,
        String originalUrl,
        String finalUrl,
        String linkType,
        String validationNote,
        String validationStatus,
        String reason,
        String pageTitle,
        Integer httpStatus,
        String statusText,
        Integer redirectCount,
        List<String> redirectChain,
        Long responseTimeMs,
        String screenshotPath,
        String element,
        String ariaLabel,
        String title,
        String target,
        Integer domIndex,
        String bounds) {

    public LinkAuditEntry {
        visibleText = visibleText == null ? "" : visibleText;
        originalUrl = originalUrl == null ? "" : originalUrl;
        finalUrl = finalUrl == null ? "" : finalUrl;
        linkType = linkType == null ? "" : linkType;
        validationNote = validationNote == null ? "" : validationNote;
        validationStatus = validationStatus == null ? "" : validationStatus;
        reason = reason == null ? "" : reason;
        pageTitle = pageTitle == null || pageTitle.isBlank() ? "Unknown" : pageTitle;
        statusText = statusText == null ? "" : statusText;
        redirectChain = redirectChain == null ? List.of() : List.copyOf(redirectChain);
        element = element == null ? "" : element;
        ariaLabel = ariaLabel == null ? "" : ariaLabel;
        title = title == null ? "" : title;
        target = target == null ? "" : target;
        bounds = bounds == null ? "" : bounds;
        // redirectCount, responseTimeMs, and screenshotPath are intentionally nullable.
    }
}
