package com.acxiom.emailaudit.rules;

import com.acxiom.emailaudit.rules.util.LinkHealthChecker;
import com.acxiom.emailaudit.rules.util.ValidationResult;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class PrivacyLinkRule implements AuditRule {

    private static final Logger log =
            LoggerFactory.getLogger(PrivacyLinkRule.class);

    public static final String RULE_ID = "PRIVACY_LINK";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public String description() {
        return "Verify privacy policy link exists and is functional";
    }

    @Override
    public RuleCategory category() {
        return RuleCategory.LINKS;
    }

    @Override
    public RuleSeverity severity() {
        return RuleSeverity.HIGH;
    }

    @Override
    public RuleResult execute(Page page) {

        Objects.requireNonNull(page);

        long startMs = System.currentTimeMillis();

        try {

            Map<String, String> privacyLink =
                    (Map<String, String>) page.evaluate("""
                    () => {
                        const link =
                            [...document.querySelectorAll('a')]
                                .find(a => {
                                    const text =
                                        (a.innerText || '').toLowerCase();

                                    const href =
                                        (a.getAttribute('href') || '')
                                            .toLowerCase();

                                    return text.includes('privacy')
                                        || href.includes('privacy');
                                });

                        if (!link) {
                            return null;
                        }

                        return {
                            text: link.innerText || '',
                            href: link.href || ''
                        };
                    }
                    """);

            if (privacyLink == null) {

                return RuleResult.fail(
                        this,
                        startMs,
                        List.of(
                                "Privacy Policy link not found"));
            }

            String href = privacyLink.get("href");

            if (href == null || href.isBlank()) {

                return RuleResult.fail(
                        this,
                        startMs,
                        List.of(
                                "Privacy Policy link found but href is empty"));
            }

            ValidationResult result =
                    LinkHealthChecker.validate(href);

            if (!result.valid()) {

                return RuleResult.fail(
                        this,
                        startMs,
                        List.of(
                                "Privacy Policy link present but broken: "
                                        + result.message()));
            }

            return RuleResult.pass(
                    this,
                    startMs);

        } catch (Exception ex) {

            log.error(
                    "Privacy validation failed",
                    ex);

            return RuleResult.error(
                    this,
                    startMs,
                    ex);
        }
    }
}