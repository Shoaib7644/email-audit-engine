package com.acxiom.emailaudit.campaign;

import com.acxiom.emailaudit.rules.ImageValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Performs business-rule comparisons for one spreadsheet row and matched HTML
 * metadata object.
 */
public final class CampaignComparisonEngine {

    private static final Logger log = LoggerFactory.getLogger(CampaignComparisonEngine.class);

    private final UrlTemplateComparator urlComparator = new UrlTemplateComparator();
    private final TrackingValidator trackingValidator = new TrackingValidator();
    private final TypeValidator typeValidator = new TypeValidator();
    private final CampaignResultCalculator resultCalculator = new CampaignResultCalculator();

    public CampaignComparisonResult compare(
            final int rowIndex,
            final CampaignSpecificationRow specRow,
            final CampaignHtmlMetadata metadata,
            final ImageValidationResult image) {

        final List<String> notes = new ArrayList<>();
        final String elementStatus = metadata == null ? "FAIL" : "PASS";
        if (metadata == null) {
            notes.add("Missing campaign element for _category " + specRow.expectedCategory() + ".");
        }

        final UrlTemplateComparator.ComparisonResult urlResult =
                urlComparator.compare(specRow.expectedHref(), metadata == null ? "" : metadata.href());
        addIssue(notes, urlResult);

        final TrackingValidator.TrackingResult trackingResult =
                trackingValidator.validate(
                        specRow.requiredTrackingParameters(),
                        metadata == null ? Map.of() : metadata.trackingParams());
        addIssue(notes, trackingResult);

        final String labelStatus = validateLabel(specRow, metadata, notes);
        final String categoryStatus = validateCategory(specRow, metadata, notes);

        final TypeValidator.TypeResult typeResult = typeValidator.validate(specRow, metadata);
        if ("FAIL".equals(typeResult.status())) {
            notes.add(typeResult.message());
        }

        final String screenshotPath = screenshotPath(metadata, image);
        final String screenshotStatus = screenshotPath.isBlank() ? "WARNING" : "PASS";
        if (screenshotPath.isBlank()) {
            notes.add("Screenshot not captured for this campaign asset.");
        }

        final String linkValidationStatus = linkValidationStatus(metadata);
        final String overall = resultCalculator.calculate(
                elementStatus,
                urlResult.status(),
                trackingResult.status(),
                labelStatus,
                categoryStatus,
                typeResult.status());

        if (notes.isEmpty()) {
            notes.add("Generated email HTML matches the campaign specification.");
        }

        log.debug("""
                ====================================
                Campaign Validation
                Category: {}
                Expected Label: {}
                Expected URL: {}
                HTML Category: {}
                HTML Label: {}
                HTML URL: {}
                URL Comparison: {}
                Tracking Comparison: {}
                Type Comparison: {}
                Overall Result: {}
                ====================================""",
                specRow.expectedCategory(),
                specRow.expectedLabel(),
                specRow.expectedHref(),
                metadata == null ? "" : metadata.category(),
                metadata == null ? "" : metadata.label(),
                metadata == null ? "" : metadata.href(),
                urlResult.status() + " - " + urlResult.message(),
                trackingResult.status() + " - " + trackingResult.message(),
                typeResult.status() + " - " + typeResult.message(),
                overall);

        final CampaignValidationRow row = new CampaignValidationRow(
                rowIndex,
                specRow.displayName(),
                typeResult.expectedType(),
                typeResult.actualType(),
                specRow.expectedHref(),
                metadata == null ? "" : metadata.href(),
                metadata == null ? "" : metadata.visibleText(),
                specRow.expectedLabel(),
                metadata == null ? "" : metadata.label(),
                specRow.expectedCategory(),
                metadata == null ? "" : metadata.category(),
                specRow.requiredTrackingParameters(),
                metadata == null ? Map.of() : metadata.trackingParams(),
                urlResult.status(),
                trackingResult.status(),
                labelStatus,
                categoryStatus,
                elementStatus,
                typeResult.status(),
                screenshotStatus,
                screenshotPath,
                linkValidationStatus,
                finalDestinationUrl(metadata),
                httpStatus(metadata),
                overall,
                String.join(" ", notes),
                specRow.rawColumns());

        return new CampaignComparisonResult(row, metadata == null, overall);
    }

    private static void addIssue(
            final List<String> notes,
            final UrlTemplateComparator.ComparisonResult result) {
        if ("FAIL".equals(result.status())) {
            notes.add(result.message());
        }
    }

    private static void addIssue(
            final List<String> notes,
            final TrackingValidator.TrackingResult result) {
        if ("FAIL".equals(result.status())) {
            notes.add(result.message());
        }
    }

    private static String validateLabel(
            final CampaignSpecificationRow specRow,
            final CampaignHtmlMetadata metadata,
            final List<String> notes) {

        if (specRow.expectedLabel().isBlank()) {
            return "N/A";
        }
        if (metadata == null) {
            return "FAIL";
        }
        final boolean match = specRow.expectedLabel().equalsIgnoreCase(metadata.label());
        if (!match) {
            notes.add("Adobe _label mismatch.");
        }
        return match ? "PASS" : "FAIL";
    }

    private static String validateCategory(
            final CampaignSpecificationRow specRow,
            final CampaignHtmlMetadata metadata,
            final List<String> notes) {

        if (specRow.expectedCategory().isBlank()) {
            notes.add("Spreadsheet row does not define an expected _category.");
            return "FAIL";
        }
        if (metadata == null) {
            return "FAIL";
        }
        final boolean match = specRow.expectedCategory().equalsIgnoreCase(metadata.category());
        if (!match) {
            notes.add("Adobe _category mismatch.");
        }
        return match ? "PASS" : "FAIL";
    }

    private static String screenshotPath(
            final CampaignHtmlMetadata metadata,
            final ImageValidationResult image) {

        if (metadata != null && metadata.linkAuditEntry() != null
                && metadata.linkAuditEntry().screenshotPath() != null
                && !metadata.linkAuditEntry().screenshotPath().isBlank()) {
            return metadata.linkAuditEntry().screenshotPath();
        }
        if (image != null && image.screenshotPath() != null && !image.screenshotPath().isBlank()) {
            return image.screenshotPath();
        }
        return "";
    }

    private static String linkValidationStatus(final CampaignHtmlMetadata metadata) {
        if (metadata == null || metadata.linkAuditEntry() == null) {
            return "N/A";
        }
        final String status = metadata.linkAuditEntry().validationStatus();
        return status == null || status.isBlank() ? "N/A" : status.toUpperCase();
    }

    private static String finalDestinationUrl(final CampaignHtmlMetadata metadata) {
        if (metadata == null || metadata.linkAuditEntry() == null) {
            return "";
        }
        return firstNonBlank(metadata.linkAuditEntry().finalUrl(), metadata.linkAuditEntry().originalUrl());
    }

    private static String httpStatus(final CampaignHtmlMetadata metadata) {
        if (metadata == null || metadata.linkAuditEntry() == null || metadata.linkAuditEntry().httpStatus() == null) {
            return "";
        }
        final String text = metadata.linkAuditEntry().statusText();
        return metadata.linkAuditEntry().httpStatus()
                + (text == null || text.isBlank() ? "" : " " + text);
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

    public record CampaignComparisonResult(
            CampaignValidationRow row,
            boolean missing,
            String overallStatus) {
    }
}
