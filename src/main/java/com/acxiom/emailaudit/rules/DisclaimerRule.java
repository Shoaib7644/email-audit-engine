package com.acxiom.emailaudit.rules;

import com.microsoft.playwright.Page;
import com.acxiom.emailaudit.rules.util.LinkHealthChecker;
import com.acxiom.emailaudit.rules.util.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Map;

import java.util.List;
import java.util.Objects;

public final class DisclaimerRule implements AuditRule {

    private static final Logger log =
            LoggerFactory.getLogger(DisclaimerRule.class);

    public static final String RULE_ID =
            "DISCLAIMER_PRESENT";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public String description() {
        return "Verify disclaimer / reply-to information exists";
    }

    @Override
    public RuleCategory category() {
        return RuleCategory.CONTENT;
    }

    @Override
    public RuleSeverity severity() {
        return RuleSeverity.HIGH;
    }

    @Override
    public String passImpact() {
        return "Mandatory compliance messaging is present.";
    }

    @Override
    public String failImpact() {
        return "Mandatory compliance messaging is incomplete.";
    }

    @Override
    @SuppressWarnings("unchecked")
    public RuleResult execute(Page page) {

        Objects.requireNonNull(page);

        long startMs = System.currentTimeMillis();

        try {

            Map<String, Object> result =
                    (Map<String, Object>) page.evaluate("""
                () => {

                    const body =
                        document.body.innerText.toLowerCase();

                    const disclaimerFound =
                        body.includes('do not reply')
                        || body.includes('please do not reply')
                        || body.includes('this mailbox is not monitored')
                        || body.includes('no-reply')
                        || body.includes('unsubscribe');

                    const unsubscribe =
                        [...document.querySelectorAll('a')]
                            .find(a => {

                                const txt =
                                    (a.innerText || '')
                                        .toLowerCase();

                                return txt.includes('unsubscribe');
                            });

                    const preferences =
                        [...document.querySelectorAll('a')]
                            .find(a => {

                                const txt =
                                    (a.innerText || '')
                                        .toLowerCase();

                                return txt.includes('preferences')
                                    || txt.includes('manage preferences');
                            });

                    return {
                        disclaimerFound: disclaimerFound,
                        unsubscribeHref:
                            unsubscribe ? unsubscribe.href : '',
                        preferencesHref:
                            preferences ? preferences.href : ''
                    };
                }
                """);

            Boolean disclaimerFound =
                    (Boolean) result.get("disclaimerFound");

            if (!Boolean.TRUE.equals(disclaimerFound)) {

                String finding = FindingFormatter.disclaimerFinding(
                        false, "Mandatory legal disclaimer not found.");

                return RuleResult.builder(this, RuleResult.Status.FAIL, startMs)
                        .withFindings(List.of(finding))
                        .build();
            }

            List<String> findings =
                    new ArrayList<>();

            String unsubscribeHref =
                    (String) result.get("unsubscribeHref");

            if (unsubscribeHref != null
                    && !unsubscribeHref.isBlank()) {

                ValidationResult unsubscribeCheck =
                        LinkHealthChecker.validate(
                                unsubscribeHref);

                if (!unsubscribeCheck.valid()) {

                    findings.add(FindingFormatter.linkFinding()
                            .title("Unsubscribe Link")
                            .href(unsubscribeHref)
                            .failed(unsubscribeCheck.message())
                            .build());
                }
            }

            String preferencesHref =
                    (String) result.get("preferencesHref");

            if (preferencesHref != null
                    && !preferencesHref.isBlank()) {

                ValidationResult prefCheck =
                        LinkHealthChecker.validate(
                                preferencesHref);

                if (!prefCheck.valid()) {

                    findings.add(FindingFormatter.linkFinding()
                            .title("Manage Preferences Link")
                            .href(preferencesHref)
                            .failed(prefCheck.message())
                            .build());
                }
            }

            if (!findings.isEmpty()) {

                return RuleResult.builder(this, RuleResult.Status.FAIL, startMs)
                        .withFindings(findings)
                        .build();
            }

            String passFinding = FindingFormatter.disclaimerFinding(
                    true, "Required disclaimer text is present.");

            return RuleResult.builder(this, RuleResult.Status.PASS, startMs)
                    .withFindings(List.of(passFinding))
                    .build();

        } catch (Exception ex) {

            log.error("Disclaimer validation failed", ex);

            return RuleResult.error(
                    this,
                    startMs,
                    ex);
        }
    }
}