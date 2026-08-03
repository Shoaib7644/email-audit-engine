package com.acxiom.emailaudit.reporting.dashboard;

/**
 * Dashboard representation of one rendered image from an audited email.
 */
public record ImageAuditData(
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

    public ImageAuditData {
        imageUrl = imageUrl == null ? "" : imageUrl;
        altText = altText == null ? "" : altText;
        validationStatus = validationStatus == null ? "" : validationStatus;
        screenshotPath = screenshotPath == null || screenshotPath.isBlank() ? null : screenshotPath;
        thumbnailPath = thumbnailPath == null || thumbnailPath.isBlank() ? null : thumbnailPath;
        notes = notes == null ? "" : notes;
        bounds = bounds == null ? "" : bounds;
        imageType = imageType == null ? "" : imageType;
    }
}
