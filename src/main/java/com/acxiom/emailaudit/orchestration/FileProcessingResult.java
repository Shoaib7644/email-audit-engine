package com.acxiom.emailaudit.orchestration;

import com.acxiom.emailaudit.core.AuditContext;

public record FileProcessingResult(
        AuditContext context,
        AuditOrchestrator.FileOutcome outcome) {
}