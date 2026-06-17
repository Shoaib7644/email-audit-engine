package com.acxiom.emailaudit.reporting.dashboard;

import java.util.Objects;

/**
 * Represents the aggregated result of a dashboard section
 * (Accessibility, Links, Content, CTA, etc.)
 * for a single audited file.
 */
public record SectionCheckResult(
        String sectionName,
        String status,
        String severity,
        int findingCount,
        String businessImpact) {

    public SectionCheckResult {
        Objects.requireNonNull(sectionName, "sectionName must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(severity, "severity must not be null");
        Objects.requireNonNull(businessImpact, "businessImpact must not be null");

        if (findingCount < 0) {
            throw new IllegalArgumentException(
                    "findingCount cannot be negative");
        }
    }
}