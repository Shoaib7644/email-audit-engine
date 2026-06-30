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
    public String passImpact() {
        return "Privacy Link is present.";
    }

    @Override
    public String failImpact() {
        return "Privacy Link is missing or invalid.";
    }

    @Override
    @SuppressWarnings("unchecked")
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
                            href: link.getAttribute('href') || ''
                        };
                    }
                    """);

            // Case 1: Privacy link is entirely missing
            if (privacyLink == null) {
                String finding = FindingFormatter.missingPrivacyLink("privacy policy");
                return RuleResult.builder(this, RuleResult.Status.FAIL, startMs)
                        .withFindings(List.of(finding))
                        .build();
            }

            String text = privacyLink.get("text");
            String href = privacyLink.get("href");

            // Case 2: Link exists but has an empty href target string
            if (href == null || href.isBlank()) {
                String finding = FindingFormatter.linkFinding()
                        .title("Privacy Policy Link")
                        .displayText(text)
                        .href("(empty href)")
                        .failed("Missing href")
                        .build();
                return RuleResult.builder(this, RuleResult.Status.FAIL, startMs)
                        .withFindings(List.of(finding))
                        .build();
            }

            // Case 3: Link exists but remote check invalidates the target destination
            ValidationResult result = LinkHealthChecker.validate(href);
            if (!result.valid()) {
                String finding = FindingFormatter.linkFinding()
                        .title("Privacy Policy Link")
                        .displayText(text)
                        .href(href)
                        .failed(result.message())
                        .build();
                return RuleResult.builder(this, RuleResult.Status.FAIL, startMs)
                        .withFindings(List.of(finding))
                        .build();
            }



            // Case 4: Privacy link found and valid
            String passFinding = FindingFormatter.linkFinding()
                    .title("Privacy Policy Link")
                    .displayText(text)
                    .href(href)
                    .passed(result.message())
                    .build();

            return RuleResult.builder(this, RuleResult.Status.PASS, startMs)
                    .withFindings(List.of(passFinding))
                    .build();



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