package com.acxiom.emailaudit.rules;

import com.acxiom.emailaudit.rules.util.LinkHealthChecker;
import com.acxiom.emailaudit.rules.util.ValidationResult;

import java.util.ArrayList;
import java.util.Map;
import com.microsoft.playwright.Page;

import java.util.List;
import java.util.Objects;

public final class ViewOnlineLinkRule implements AuditRule {

    public static final String RULE_ID = "VIEW_ONLINE_LINK";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public String description() {
        return "Verify View Online / Browser Version link exists";
    }

    @Override
    public RuleCategory category() {
        return RuleCategory.LINKS;
    }

    @Override
    public RuleSeverity severity() {
        return RuleSeverity.MEDIUM;
    }

    @Override
    @SuppressWarnings("unchecked")
    public RuleResult execute(Page page) {

        Objects.requireNonNull(page);

        long startMs = System.currentTimeMillis();

        try {

            Map<String, String> link =
                    (Map<String, String>) page.evaluate("""
                () => {

                    const keywords = [
                        'view online',
                        'view in browser',
                        'browser version',
                        'web version',
                        'read online'
                    ];

                    const found =
                        [...document.querySelectorAll('a')]
                            .find(a => {

                                const txt =
                                    (a.innerText || '')
                                        .trim()
                                        .toLowerCase();

                                return keywords.some(k =>
                                    txt.includes(k));
                            });

                    if (!found) {
                        return null;
                    }

                    return {
                        text: found.innerText || '',
                        href: found.href || ''
                    };
                }
                """);

            if (link == null) {

                return RuleResult.fail(
                        this,
                        startMs,
                        List.of(
                                "View Online link not found"));
            }

            String href = link.get("href");
            ValidationResult result =
                    LinkHealthChecker.validate(href);

            if (!result.valid()) {

                return RuleResult.fail(
                        this,
                        startMs,
                        List.of(
                                "View Online link present but broken: "
                                        + result.message()));
            }

            if (href == null || href.isBlank()) {

                return RuleResult.fail(
                        this,
                        startMs,
                        List.of(
                                "View Online link found but href is empty"));
            }

            ValidationResult unsubscribeCheck =
                    LinkHealthChecker.validate(
                            href);

            if (!unsubscribeCheck.valid()) {

                return RuleResult.fail(
                        this,
                        startMs,
                        List.of(
                                "View Online link present but broken: "
                                        + result.message()));
            }

            return RuleResult.pass(
                    this,
                    startMs);

        } catch (Exception ex) {

            return RuleResult.error(
                    this,
                    startMs,
                    ex);
        }
    }
}