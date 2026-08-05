package com.acxiom.emailaudit.rules;

import com.microsoft.playwright.Page;

import java.util.List;

/**
 * Optional extension point for rules that need results from earlier rules.
 */
public interface ContextAwareAuditRule extends AuditRule {

    RuleResult execute(Page page, List<RuleResult> previousResults);
}
