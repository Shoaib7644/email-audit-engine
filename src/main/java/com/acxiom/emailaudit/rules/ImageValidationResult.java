package com.acxiom.emailaudit.rules;

/**
 * Structured image validation detail captured by {@link ImageValidationRule}.
 */
public record ImageValidationResult(
        String imageUrl,
        String altText,
        Integer httpStatus,
        String validationStatus,
        boolean warning,
        Integer naturalWidth,
        Integer naturalHeight,
        Integer displayWidth,
        Integer displayHeight,
        boolean imageLoaded,
        boolean rendered,
        String screenshotPath,
        String thumbnailPath,
        String notes,
        String bounds,
        String imageType) {

    public ImageValidationResult {
        imageUrl = imageUrl == null ? "" : imageUrl;
        altText = altText == null ? "" : altText;
        validationStatus = validationStatus == null ? "" : validationStatus;
        screenshotPath = screenshotPath == null || screenshotPath.isBlank() ? null : screenshotPath;
        thumbnailPath = thumbnailPath == null || thumbnailPath.isBlank() ? null : thumbnailPath;
        notes = notes == null ? "" : notes;
        bounds = bounds == null ? "" : bounds;
        imageType = imageType == null ? "" : imageType;
        // httpStatus and dimensions are intentionally nullable for inline SVG,
        // data URLs, and other non-network rendered image sources.
    }
}
