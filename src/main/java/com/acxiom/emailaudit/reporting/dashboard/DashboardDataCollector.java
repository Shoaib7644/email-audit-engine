package com.acxiom.emailaudit.reporting.dashboard;

import com.acxiom.emailaudit.core.AuditContext;
import com.acxiom.emailaudit.orchestration.AuditOrchestrator;
import com.acxiom.emailaudit.reporting.BusinessImpactMapper;
import com.acxiom.emailaudit.reporting.FindingSummarizer;
import com.acxiom.emailaudit.reporting.ReportSection;
import com.acxiom.emailaudit.reporting.ReportSectionMapper;
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

        int passedFiles = 0;
        int failedFiles = 0;

        for (AuditContext context : summary.auditResults()) {

            final FileAuditData fileData =
                    buildFileAuditData(context);

            fileAuditDataList.add(fileData);

            if ("PASS".equalsIgnoreCase(
                    fileData.overallStatus())) {
                passedFiles++;
            } else {
                failedFiles++;
            }
        }

        return new RunAuditData(
                fileAuditDataList.size(),
                passedFiles,
                failedFiles,
                Instant.now(),
                fileAuditDataList);
    }

    private static FileAuditData buildFileAuditData(
            final AuditContext context) {

        final List<RuleResult> ruleResults =
                context.getRuleResults();

        final int totalChecks =
                ruleResults.size();

        final int passedChecks =
                (int) ruleResults.stream()
                        .filter(rule ->
                                "PASS".equalsIgnoreCase(
                                        rule.getStatus().name()))
                        .count();

        final int failedChecks =
                totalChecks - passedChecks;

        final String overallStatus =
                failedChecks > 0 ? "FAIL" : "PASS";

        // ── Existing: section grouping (unchanged) ───────────────────────────

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

        // ── New: per-rule detail list ─────────────────────────────────────────

        final List<RuleAuditData> rules =
                buildRuleAuditDataList(ruleResults);

        // ── Screenshot path (null when no screenshot was captured) ────────────

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
                screenshotPath);
    }

    private static SectionCheckResult buildSectionResult(
            final ReportSection section,
            final List<RuleResult> rules) {

        int findingCount = 0;
        String status = "PASS";
        String severity = "INFO";
        String businessImpact =
                section.getDescription();

        for (RuleResult rule : rules) {

            if (!"PASS".equalsIgnoreCase(
                    rule.getStatus().name())) {

                status = "FAIL";

                severity =
                        rule.getSeverity().name();

                findingCount +=
                        rule.getFindings().size();

                businessImpact =
                        BusinessImpactMapper.getImpact(
                                rule.getRuleId(),
                                String.join(
                                        "; ",
                                        rule.getFindings()));

                if (!rule.getFindings().isEmpty()) {

                    businessImpact =
                            FindingSummarizer.summarize(
                                    rule.getFindings()
                                            .getFirst());
                }

                break;
            }
        }

        return new SectionCheckResult(
                section.getDisplayName(),
                status,
                severity,
                findingCount,
                businessImpact);
    }

    // ── New private helper ────────────────────────────────────────────────────

    /**
     * Builds one {@link RuleAuditData} per {@link RuleResult}, populating
     * all fields from the result and {@link BusinessImpactMapper}.
     *
     * <p>Rule name falls back to {@code ruleId} when {@code getDescription()}
     * is blank or absent, as {@link RuleResult} exposes no separate
     * {@code getRuleName()} method.</p>
     */
    private static List<RuleAuditData> buildRuleAuditDataList(
            final List<RuleResult> ruleResults) {

        final List<RuleAuditData> list =
                new ArrayList<>(ruleResults.size());

        for (final RuleResult rule : ruleResults) {

            final String ruleId = rule.getRuleId();

            final String description = rule.getDescription();
            final String ruleName =
                    (description != null && !description.isBlank())
                            ? description
                            : ruleId;

            final List<String> findings =
                    new ArrayList<>(rule.getFindings());

            final String businessImpact =
                    BusinessImpactMapper.getImpact(
                            ruleId,
                            String.join("; ", findings));

            list.add(new RuleAuditData(
                    ruleName,
                    ruleId,
                    rule.getStatus().name(),
                    rule.getSeverity().name(),
                    findings,
                    businessImpact));
        }

        return list;
    }
}