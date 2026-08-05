package com.acxiom.emailaudit.campaign;

import com.acxiom.emailaudit.rules.ImageValidationResult;
import com.acxiom.emailaudit.rules.ImageValidationRule;
import com.acxiom.emailaudit.rules.LinkAuditEntry;
import com.acxiom.emailaudit.rules.LinkValidationRule;
import com.acxiom.emailaudit.rules.RuleResult;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Compares the client campaign implementation specification with the rendered
 * email HTML metadata.
 */
public final class CampaignValidator {

    private static final Logger log = LoggerFactory.getLogger(CampaignValidator.class);

    private final CampaignHtmlMetadataExtractor metadataExtractor = new CampaignHtmlMetadataExtractor();
    private final CampaignComparisonEngine comparisonEngine = new CampaignComparisonEngine();

    public CampaignValidator() {
        this(true);
    }

    CampaignValidator(final boolean ignoreAnchors) {
        // Kept for package-level test compatibility. URL comparison always ignores anchors.
    }

    public CampaignValidationResult validate(final CampaignSpecification specification, final Page page) {
        return validate(specification, page, List.of());
    }

    public CampaignValidationResult validate(
            final CampaignSpecification specification,
            final Page page,
            final List<RuleResult> previousResults) {

        Objects.requireNonNull(specification, "specification must not be null");
        Objects.requireNonNull(page, "page must not be null");

        final CampaignEvidence evidence = CampaignEvidence.from(previousResults);
        final List<CampaignHtmlMetadata> inventory = metadataExtractor.extract(page, evidence.links());
        final Map<String, CampaignHtmlMetadata> metadataByCategory = categoryMap(inventory);
        final Set<String> specificationCategories = new HashSet<>();
        final List<CampaignValidationRow> rows = new ArrayList<>();

        int matched = 0;
        int missing = 0;
        int unexpected = 0;
        int trackingErrors = 0;
        int urlErrors = 0;
        int passed = 0;
        int failed = 0;
        int warnings = 0;

        for (final CampaignSpecificationRow specRow : specification.entries()) {
            final String expectedCategoryKey = categoryKey(specRow.expectedCategory());
            if (!expectedCategoryKey.isBlank()) {
                specificationCategories.add(expectedCategoryKey);
            }

            final CampaignHtmlMetadata metadata = expectedCategoryKey.isBlank()
                    ? null
                    : metadataByCategory.get(expectedCategoryKey);
            final ImageValidationResult image = findImageEvidence(specRow, metadata, evidence.images());
            final CampaignComparisonEngine.CampaignComparisonResult comparison =
                    comparisonEngine.compare(rows.size() + 1, specRow, metadata, image);
            final CampaignValidationRow validationRow = comparison.row();
            final String overall = validationRow.validation();

            if (comparison.missing()) {
                missing++;
            }

            if ("PASS".equals(overall)) {
                matched++;
            }

            if ("FAIL".equals(validationRow.urlStatus())) {
                urlErrors++;
            }
            if ("FAIL".equals(validationRow.trackingStatus())) {
                trackingErrors++;
            }

            if ("PASS".equals(overall)) {
                passed++;
            } else if ("WARNING".equals(overall)) {
                warnings++;
            } else {
                failed++;
            }

            rows.add(validationRow);
        }

        for (final Map.Entry<String, CampaignHtmlMetadata> entry : metadataByCategory.entrySet()) {
            if (specificationCategories.contains(entry.getKey())) {
                continue;
            }
            unexpected++;
            warnings++;
            rows.add(unexpectedRow(rows.size() + 1, entry.getValue()));
        }

        return new CampaignValidationResult(
                true,
                "Campaign specification compared with rendered email HTML metadata.",
                specification.entries().size(),
                matched,
                missing,
                unexpected,
                trackingErrors,
                urlErrors,
                passed,
                failed,
                warnings,
                specification.headers(),
                rows);
    }

    private Map<String, CampaignHtmlMetadata> categoryMap(final List<CampaignHtmlMetadata> inventory) {
        final Map<String, CampaignHtmlMetadata> byCategory = new LinkedHashMap<>();
        for (final CampaignHtmlMetadata metadata : inventory) {
            final String key = categoryKey(metadata.category());
            if (key.isBlank()) {
                continue;
            }
            final CampaignHtmlMetadata previous = byCategory.putIfAbsent(key, metadata);
            if (previous != null) {
                log.warn("Duplicate campaign _category found in rendered HTML: {}. Keeping first occurrence.", metadata.category());
            }
        }
        return byCategory;
    }

    private ImageValidationResult findImageEvidence(
            final CampaignSpecificationRow specRow,
            final CampaignHtmlMetadata metadata,
            final List<ImageValidationResult> images) {

        if (metadata == null || !metadata.wrapsImage()) {
            return null;
        }
        for (final ImageValidationResult image : images) {
            if (!metadata.imageSrc().isBlank()
                    && "PASS".equals(new UrlTemplateComparator()
                    .compare(metadata.imageSrc(), image.imageUrl()).status())) {
                return image;
            }
        }
        return null;
    }

    private CampaignValidationRow unexpectedRow(final int index, final CampaignHtmlMetadata metadata) {
        return new CampaignValidationRow(
                index,
                firstNonBlank(metadata.category(), metadata.label(), metadata.visibleText(), "Unexpected element"),
                "",
                metadata.type(),
                "",
                metadata.href(),
                metadata.visibleText(),
                "",
                metadata.label(),
                "",
                metadata.category(),
                List.of(),
                metadata.trackingParams(),
                "WARNING",
                "N/A",
                "N/A",
                "N/A",
                "PASS",
                "WARNING",
                hasScreenshot(metadata) ? "PASS" : "N/A",
                screenshotPath(metadata, null),
                linkValidationStatus(metadata),
                finalDestinationUrl(metadata),
                httpStatus(metadata),
                "WARNING",
                "Unexpected HTML element not found in campaign specification.",
                Map.of());
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

    private static boolean hasScreenshot(final CampaignHtmlMetadata metadata) {
        return metadata != null && !screenshotPath(metadata, null).isBlank();
    }

    private static String linkValidationStatus(final CampaignHtmlMetadata metadata) {
        if (metadata == null || metadata.linkAuditEntry() == null) {
            return "N/A";
        }
        final String status = metadata.linkAuditEntry().validationStatus();
        return status == null || status.isBlank() ? "N/A" : status.toUpperCase(Locale.ROOT);
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

    private static String categoryKey(final String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
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

    private record CampaignEvidence(
            List<LinkAuditEntry> links,
            List<ImageValidationResult> images) {

        private static CampaignEvidence from(final List<RuleResult> previousResults) {
            final List<LinkAuditEntry> links = new ArrayList<>();
            final List<ImageValidationResult> images = new ArrayList<>();

            for (final RuleResult result : previousResults == null ? List.<RuleResult>of() : previousResults) {
                if (LinkValidationRule.RULE_ID.equals(result.getRuleId())) {
                    collectMetadataList(result, "links", LinkAuditEntry.class, links);
                } else if (ImageValidationRule.RULE_ID.equals(result.getRuleId())) {
                    collectMetadataList(result, "images", ImageValidationResult.class, images);
                }
            }
            return new CampaignEvidence(List.copyOf(links), List.copyOf(images));
        }

        private static <T> void collectMetadataList(
                final RuleResult result,
                final String key,
                final Class<T> type,
                final List<T> target) {

            final Object raw = result.getMetadata().get(key);
            if (!(raw instanceof List<?> rawList)) {
                return;
            }
            for (final Object item : rawList) {
                if (type.isInstance(item)) {
                    target.add(type.cast(item));
                }
            }
        }
    }
}
