package com.acxiom.emailaudit.reporting.dashboard;

import java.util.List;
import java.util.Objects;

/**
 * Dashboard representation of a single audited file.
 */
public record FileAuditData(
        String fileName,
        String overallStatus,
        int totalChecks,
        int passedChecks,
        int failedChecks,
        List<SectionCheckResult> sections,
        List<RuleAuditData> rules,
        String screenshotPath) {

    public FileAuditData {
        Objects.requireNonNull(fileName,      "fileName must not be null");
        Objects.requireNonNull(overallStatus, "overallStatus must not be null");
        Objects.requireNonNull(sections,      "sections must not be null");
        Objects.requireNonNull(rules,         "rules must not be null");
        // screenshotPath is intentionally nullable — absent when no screenshot was captured.

        if (totalChecks < 0) {
            throw new IllegalArgumentException("totalChecks cannot be negative");
        }
        if (passedChecks < 0) {
            throw new IllegalArgumentException("passedChecks cannot be negative");
        }
        if (failedChecks < 0) {
            throw new IllegalArgumentException("failedChecks cannot be negative");
        }

        sections = List.copyOf(sections);
        rules    = List.copyOf(rules);
    }
}