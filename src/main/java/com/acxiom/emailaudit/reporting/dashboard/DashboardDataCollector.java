package com.acxiom.emailaudit.reporting.dashboard;

import com.acxiom.emailaudit.core.AuditContext;
import com.acxiom.emailaudit.core.ClientContext;
import com.acxiom.emailaudit.core.ExecutionContext;
import com.acxiom.emailaudit.campaign.CampaignValidationResult;
import com.acxiom.emailaudit.gmail.GmailMetadata;
import com.acxiom.emailaudit.gmail.GmailMetadataContext;
import com.acxiom.emailaudit.orchestration.AuditOrchestrator;
import com.acxiom.emailaudit.reporting.FindingSummarizer;
import com.acxiom.emailaudit.reporting.ReportSection;
import com.acxiom.emailaudit.reporting.ReportSectionMapper;
import com.acxiom.emailaudit.rules.ImageValidationResult;
import com.acxiom.emailaudit.rules.ImageValidationRule;
import com.acxiom.emailaudit.rules.CampaignValidationRule;
import com.acxiom.emailaudit.rules.LinkAuditEntry;
import com.acxiom.emailaudit.rules.LinkValidationRule;
import com.acxiom.emailaudit.rules.RuleResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Converts audit execution results into dashboard-friendly data models.
 */
public final class DashboardDataCollector {

    private DashboardDataCollector() {
        throw new UnsupportedOperationException(
                "Utility class should not be instantiated");
    }

    /**
     * Builds dashboard data from a completed audit run.
     *
     * @param summary orchestrator run summary
     * @return dashboard data model
     */
    public static RunAuditData collect(
            final AuditOrchestrator.RunSummary summary) {

        if (summary == null) {
            throw new IllegalArgumentException(
                    "RunSummary must not be null");
        }

        final List<FileAuditData> fileAuditDataList =
                new ArrayList<>();

        int passedFiles  = 0;
        int failedFiles  = 0;
        int erroredFiles = 0;
        int skippedFiles = 0;

        for (AuditContext context : summary.auditResults()) {

            final FileAuditData fileData =
                    buildFileAuditData(context);

            fileAuditDataList.add(fileData);

            switch (context.getStatus()) {
                case SUCCESS -> passedFiles++;
                case FAILED  -> failedFiles++;
                case ERROR   -> erroredFiles++;
                case SKIPPED -> skippedFiles++;
            }
        }

        return new RunAuditData(
                ClientContext.selectedClient(),
                ExecutionContext.validationMode().name(),
                ExecutionContext.inputSource(),
                fileAuditDataList.size(),
                passedFiles,
                failedFiles,
                erroredFiles,
                skippedFiles,
                summary.executionTimeMs(),      // ← wired from RunSummary
                Instant.now(),
                buildEmailMetadataData(),
                fileAuditDataList);
    }

    private static EmailMetadataData buildEmailMetadataData() {
        return GmailMetadataContext.current()
                .map(DashboardDataCollector::toEmailMetadataData)
                .orElseGet(EmailMetadataData::notAvailable);
    }

    private static EmailMetadataData toEmailMetadataData(final GmailMetadata metadata) {
        return new EmailMetadataData(
                true,
                metadata.subject(),
                metadata.from(),
                metadata.to(),
                metadata.cc(),
                metadata.bcc(),
                metadata.replyTo(),
                metadata.receivedDate(),
                metadata.messageId());
    }

    private static FileAuditData buildFileAuditData(
            final AuditContext context) {

        final List<RuleResult> ruleResults =
                context.getRuleResults();

        final int totalChecks =
                ruleResults.size();

        final int passedChecks =
                (int) ruleResults.stream()
                        .filter(RuleResult::isPassed)
                        .count();

        final int failedChecks =
                (int) ruleResults.stream()
                        .filter(RuleResult::requiresAttention)
                        .count();

        final String overallStatus = switch (context.getStatus()) {
            case SUCCESS -> "PASS";
            case FAILED  -> "FAIL";
            case ERROR   -> "ERROR";
            case SKIPPED -> "SKIPPED";
            default      -> "UNKNOWN";
        };

        final Map<ReportSection, List<RuleResult>> groupedResults =
                new EnumMap<>(ReportSection.class);

        for (RuleResult ruleResult : ruleResults) {

            final ReportSection section =
                    ReportSectionMapper.map(
                            ruleResult.getRuleId());

            groupedResults
                    .computeIfAbsent(
                            section,
                            ignored -> new ArrayList<>())
                    .add(ruleResult);
        }

        final List<SectionCheckResult> sections =
                new ArrayList<>();

        groupedResults.entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey(
                        Comparator.comparing(Enum::name)))
                .forEach(entry ->
                        sections.add(
                                buildSectionResult(
                                        entry.getKey(),
                                        entry.getValue())));

        final List<RuleAuditData> rules =
                buildRuleAuditDataList(ruleResults);

        final List<LinkAuditData> links =
                buildLinkAuditDataList(ruleResults);

        final List<ImageAuditData> images =
                buildImageAuditDataList(ruleResults);

        final CampaignValidationResult campaignValidation =
                buildCampaignValidationResult(ruleResults);

        final String screenshotPath =
                context.getScreenshotPath() != null
                        ? context.getScreenshotPath()
                          .toAbsolutePath()
                          .toString()
                        : null;

        return new FileAuditData(
                context.getFileName(),
                overallStatus,
                totalChecks,
                passedChecks,
                failedChecks,
                sections,
                rules,
                links,
                images,
                campaignValidation,
                screenshotPath);
    }

    private static CampaignValidationResult buildCampaignValidationResult(
            final List<RuleResult> ruleResults) {

        for (final RuleResult rule : ruleResults) {
            if (!CampaignValidationRule.RULE_ID.equals(rule.getRuleId())) {
                continue;
            }

            final Object rawResult = rule.getMetadata().get("campaignValidation");
            if (rawResult instanceof CampaignValidationResult result) {
                return result;
            }
        }

        return CampaignValidationResult.noSpecification();
    }

    private static List<ImageAuditData> buildImageAuditDataList(
            final List<RuleResult> ruleResults) {

        for (final RuleResult rule : ruleResults) {
            if (!ImageValidationRule.RULE_ID.equals(rule.getRuleId())) {
                continue;
            }

            final Object rawImages = rule.getMetadata().get("images");
            if (!(rawImages instanceof List<?> rawList)) {
                return List.of();
            }

            final List<ImageAuditData> images = new ArrayList<>(rawList.size());
            for (final Object rawImage : rawList) {
                final ImageAuditData data = toImageAuditData(rawImage);
                if (data != null) {
                    images.add(data);
                }
            }
            return images;
        }

        return List.of();
    }

    private static ImageAuditData toImageAuditData(final Object rawImage) {
        if (rawImage instanceof ImageValidationResult result) {
            return new ImageAuditData(
                    result.imageUrl(),
                    result.altText(),
                    result.httpStatus(),
                    result.validationStatus(),
                    result.warning(),
                    result.naturalWidth(),
                    result.naturalHeight(),
                    result.displayWidth(),
                    result.displayHeight(),
                    result.imageLoaded(),
                    result.rendered(),
                    result.screenshotPath(),
                    result.thumbnailPath(),
                    result.notes(),
                    result.bounds(),
                    result.imageType());
        }

        if (rawImage instanceof Map<?, ?> map) {
            return new ImageAuditData(
                    stringOrEmpty(map.get("imageUrl")),
                    stringOrEmpty(map.get("altText")),
                    integerOrNull(map.get("httpStatus")),
                    stringOrEmpty(map.get("validationStatus")),
                    booleanOrFalse(map.get("warning")),
                    integerOrNull(map.get("naturalWidth")),
                    integerOrNull(map.get("naturalHeight")),
                    integerOrNull(map.get("displayWidth")),
                    integerOrNull(map.get("displayHeight")),
                    booleanOrFalse(map.get("imageLoaded")),
                    booleanOrFalse(map.get("rendered")),
                    stringOrNull(map.get("screenshotPath")),
                    stringOrNull(map.get("thumbnailPath")),
                    stringOrEmpty(map.get("notes")),
                    stringOrEmpty(map.get("bounds")),
                    stringOrEmpty(map.get("imageType")));
        }

        return null;
    }

    private static List<LinkAuditData> buildLinkAuditDataList(
            final List<RuleResult> ruleResults) {

        for (final RuleResult rule : ruleResults) {
            if (!LinkValidationRule.RULE_ID.equals(rule.getRuleId())) {
                continue;
            }

            final Object rawLinks = rule.getMetadata().get("links");
            if (!(rawLinks instanceof List<?> rawList)) {
                return List.of();
            }

            final List<LinkAuditData> links = new ArrayList<>(rawList.size());
            for (final Object rawLink : rawList) {
                final LinkAuditData data = toLinkAuditData(rawLink);
                if (data != null) {
                    links.add(data);
                }
            }
            return links;
        }

        return List.of();
    }

    private static LinkAuditData toLinkAuditData(final Object rawLink) {
        if (rawLink instanceof LinkAuditEntry entry) {
            return new LinkAuditData(
                    entry.visibleText(),
                    entry.originalUrl(),
                    entry.finalUrl(),
                    entry.linkType(),
                    entry.validationNote(),
                    entry.validationStatus(),
                    entry.reason(),
                    entry.pageTitle(),
                    entry.httpStatus(),
                    entry.statusText(),
                    entry.redirectCount(),
                    entry.redirectChain(),
                    entry.responseTimeMs(),
                    entry.screenshotPath(),
                    entry.element(),
                    entry.ariaLabel(),
                    entry.title(),
                    entry.target(),
                    entry.domIndex(),
                    entry.bounds());
        }

        if (rawLink instanceof Map<?, ?> map) {
            final String status = stringOrEmpty(map.get("validationStatus"));
            final String note = stringOrEmpty(map.get("validationNote"));
            return new LinkAuditData(
                    stringOrEmpty(map.get("visibleText")),
                    stringOrEmpty(map.get("originalUrl")),
                    stringOrEmpty(map.get("finalUrl")),
                    stringOrEmpty(map.get("linkType")),
                    note,
                    status.isBlank() ? legacyValidationStatus(map) : status,
                    stringOrEmpty(map.get("reason")),
                    stringOrEmpty(map.get("pageTitle")),
                    integerOrNull(map.get("httpStatus")),
                    stringOrEmpty(map.get("statusText")),
                    integerOrNull(map.get("redirectCount")),
                    stringList(map.get("redirectChain")),
                    longOrNull(map.get("responseTimeMs")),
                    stringOrNull(map.get("screenshotPath")),
                    stringOrEmpty(map.get("element")),
                    stringOrEmpty(map.get("ariaLabel")),
                    stringOrEmpty(map.get("title")),
                    stringOrEmpty(map.get("target")),
                    integerOrNull(map.get("domIndex")),
                    stringOrEmpty(map.get("bounds")));
        }

        return null;
    }

    private static String stringOrEmpty(final Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String stringOrNull(final Object value) {
        if (value == null) {
            return null;
        }
        final String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private static Integer integerOrNull(final Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.valueOf(String.valueOf(value));
            } catch (final NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Long longOrNull(final Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.valueOf(String.valueOf(value));
            } catch (final NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean booleanOrFalse(final Object value) {
        return value instanceof Boolean bool && bool;
    }

    private static String legacyValidationStatus(final Map<?, ?> map) {
        final String linkType = stringOrEmpty(map.get("linkType"));
        if ("TEMPLATE_PLACEHOLDER".equalsIgnoreCase(linkType)) {
            return "FAIL";
        }
        if (!"HTTP".equalsIgnoreCase(linkType)) {
            return "SKIPPED";
        }

        final Integer status = integerOrNull(map.get("httpStatus"));
        if (status != null && status >= 400) {
            return "FAIL";
        }
        if (stringOrNull(map.get("screenshotPath")) != null) {
            return "PASS";
        }
        return "";
    }

    private static List<String> stringList(final Object value) {
        if (!(value instanceof List<?> rawList)) {
            return List.of();
        }

        return rawList.stream()
                .map(String::valueOf)
                .toList();
    }

    private static SectionCheckResult buildSectionResult(
            final ReportSection section,
            final List<RuleResult> rules) {

        int    findingCount   = 0;
        String status         = "PASS";
        String severity       = "INFO";
        String businessImpact = section.getDescription();
        RuleResult firstAttentionRule = null;
        RuleResult firstSkippedRule   = null;

        for (RuleResult rule : rules) {

            if (rule.requiresAttention()) {
                if (firstAttentionRule == null) {
                    firstAttentionRule = rule;
                }
                findingCount += rule.getFindings().size();
            } else if (rule.isSkipped() && firstSkippedRule == null) {
                firstSkippedRule = rule;
            }
        }

        if (firstAttentionRule != null) {
            status = "FAIL";
            severity = firstAttentionRule.getSeverity().name();
            businessImpact = firstAttentionRule.getBusinessImpact();

            if (!firstAttentionRule.getFindings().isEmpty()) {
                businessImpact =
                        FindingSummarizer.summarize(
                                firstAttentionRule.getFindings().getFirst());
            }
        } else if (firstSkippedRule != null) {
            status = "SKIPPED";
            severity = firstSkippedRule.getSeverity().name();
            businessImpact = firstSkippedRule.getBusinessImpact();
        }

        return new SectionCheckResult(
                section.getDisplayName(),
                status,
                severity,
                findingCount,
                businessImpact);
    }

    private static List<RuleAuditData> buildRuleAuditDataList(
            final List<RuleResult> ruleResults) {

        final List<RuleAuditData> list =
                new ArrayList<>(ruleResults.size());

        for (final RuleResult rule : ruleResults) {

            final String ruleId      = rule.getRuleId();
            final String description = rule.getDescription();
            final String ruleName    =
                    (description != null && !description.isBlank())
                            ? description
                            : ruleId;

            final List<String> findings =
                    new ArrayList<>(rule.getFindings());

            // Status-aware impact: PASS results carry the rule's
            // passImpact() text, FAIL/ERROR/SKIPPED carry failImpact() —
            // both already resolved on RuleResult at construction time.
            final String businessImpact = rule.getBusinessImpact();

            list.add(new RuleAuditData(
                    ruleName,
                    ruleId,
                    rule.getStatus().name(),
                    rule.getSeverity().name(),
                    findings,
                    businessImpact,
                    stringOrEmpty(rule.getErrorMessage())));
        }

        return list;
    }
}
