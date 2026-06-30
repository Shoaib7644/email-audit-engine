package com.acxiom.emailaudit.rules;

import com.acxiom.emailaudit.rules.util.LinkHealthChecker;
import com.acxiom.emailaudit.rules.util.ValidationResult;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ViewOnlineLinkRule implements AuditRule {

    private static final Logger log =
            LoggerFactory.getLogger(ViewOnlineLinkRule.class);

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
    public String passImpact() {
        return "Required privacy information is available to recipients.";
    }

    @Override
    public String failImpact() {
        return "Required privacy information may be inaccessible to recipients.";
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
                        href: found.getAttribute('href') || ''
                    };
                }
                """);

            // Case 1: No View Online link exists at all
            if (link == null) {
                String finding = FindingFormatter.viewOnlineFinding(
                        null, null, false, "View Online link not found on page.");
                return RuleResult.builder(this, RuleResult.Status.FAIL, startMs)
                        .withFindings(List.of(finding))
                        .build();
            }

            String text = link.get("text");
            String href = link.get("href");

            // Case 2: Link exists but its href attribute is missing or empty
            if (href == null || href.isBlank()) {
                String finding = FindingFormatter.viewOnlineFinding(
                        text, "(empty href)", false, "Missing href");
                return RuleResult.builder(this, RuleResult.Status.FAIL, startMs)
                        .withFindings(List.of(finding))
                        .build();
            }

            // Case 3: Link exists and has an href, but fails remote validation
            ValidationResult result = LinkHealthChecker.validate(href);
            if (!result.valid()) {
                String finding = FindingFormatter.viewOnlineFinding(
                        text, href, false, result.message());
                return RuleResult.builder(this, RuleResult.Status.FAIL, startMs)
                        .withFindings(List.of(finding))
                        .build();
            }

            // Case 4: Link found, has a valid href, and resolved successfully
            String passFinding = FindingFormatter.viewOnlineFinding(
                    text, href, true, "Link resolved successfully.");

            return RuleResult.builder(this, RuleResult.Status.PASS, startMs)
                    .withFindings(List.of(passFinding))
                    .build();

        } catch (Exception ex) {

            log.error("View Online link validation failed", ex);

            return RuleResult.error(
                    this,
                    startMs,
                    ex);
        }
    }
}