package com.acxiom.emailaudit.reporting.dashboard;

import java.util.List;
import java.util.Objects;

/**
 * Dashboard representation of a single audit rule execution result.
 */
public record RuleAuditData(
        String ruleName,
        String ruleId,
        String status,
        String severity,
        List<String> findings,
        String businessImpact) {

    public RuleAuditData {
        Objects.requireNonNull(ruleName,       "ruleName must not be null");
        Objects.requireNonNull(ruleId,         "ruleId must not be null");
        Objects.requireNonNull(status,         "status must not be null");
        Objects.requireNonNull(severity,       "severity must not be null");
        Objects.requireNonNull(findings,       "findings must not be null");
        Objects.requireNonNull(businessImpact, "businessImpact must not be null");

        findings = List.copyOf(findings);
    }
}