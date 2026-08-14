package com.acxiom.emailaudit.rules;

import com.microsoft.playwright.Page;

public interface AuditRule {

    String ruleId();

    String description();

    RuleCategory category();

    default RuleSeverity severity() {
        return RuleSeverity.MEDIUM;
    }

    /**
     * Returns the business-readable impact statement to display when this
     * rule's result is PASS — i.e. what is true for recipients/the business
     * because this check succeeded.
     *
     * <p>Example: {@code "Marketing engagement tracking is configured correctly."}</p>
     *
     * @return PASS impact statement; never {@code null} or blank
     */
    String passImpact();

    /**
     * Returns the business-readable impact statement to display when this
     * rule's result is FAIL or ERROR — i.e. what risk or consequence this
     * failure poses to recipients/the business.
     *
     * <p>Example: {@code "Marketing engagement tracking may not function correctly."}</p>
     *
     * @return FAIL impact statement; never {@code null} or blank
     */
    String failImpact();

    RuleResult execute(Page page);

    default boolean isEnabled() {
        return true;
    }

    enum RuleCategory {
        ACCESSIBILITY,
        CONTENT,
        HEADER_DETAILS,
        LINKS,
        IMAGES,
        PERFORMANCE,
        SEO,
        SPELLING,
        STRUCTURE,
        HTML,
        CUSTOM
    }

    enum RuleSeverity {
        INFO,
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }
}
