package com.acxiom.emailaudit.rules;

import com.acxiom.emailaudit.rules.util.LinkHealthChecker;
import com.acxiom.emailaudit.rules.util.ValidationResult;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.WaitUntilState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class PrivacyLinkRule implements AuditRule {

    private static final Logger log =
            LoggerFactory.getLogger(PrivacyLinkRule.class);

    public static final String RULE_ID = "PRIVACY_LINK";

    private static final double BROWSER_FALLBACK_TIMEOUT_MS = 15_000;

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
            ValidationResult result = validatePrivacyDestination(page, href);
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

    private static ValidationResult validatePrivacyDestination(
            final Page emailPage,
            final String href) {

        final ValidationResult httpResult = LinkHealthChecker.validate(href);
        if (httpResult.valid() || !shouldTryBrowserFallback(httpResult)) {
            return httpResult;
        }

        log.info("[{}] HTTP validation returned '{}'; retrying '{}' in browser context",
                RULE_ID, httpResult.message(), href);

        final ValidationResult browserResult =
                validateWithBrowserNavigation(emailPage, href, httpResult);

        if (browserResult.valid()) {
            return browserResult;
        }

        log.debug("[{}] Browser fallback did not validate '{}': {}",
                RULE_ID, href, browserResult.message());
        return httpResult;
    }

    private static boolean shouldTryBrowserFallback(final ValidationResult result) {
        final String message = result.message() == null ? "" : result.message();
        return message.startsWith("HTTP 403")
                || message.startsWith("HTTP 429")
                || message.contains("SocketTimeoutException")
                || message.contains("Read timed out");
    }

    private static ValidationResult validateWithBrowserNavigation(
            final Page emailPage,
            final String href,
            final ValidationResult originalResult) {

        Page linkPage = null;

        try {
            final BrowserContext context = emailPage.context();
            linkPage = context.newPage();
            linkPage.setDefaultTimeout(BROWSER_FALLBACK_TIMEOUT_MS);
            linkPage.setDefaultNavigationTimeout(BROWSER_FALLBACK_TIMEOUT_MS);

            final Response response = linkPage.navigate(href, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(BROWSER_FALLBACK_TIMEOUT_MS));

            if (response == null) {
                return ValidationResult.failure(
                        "Browser navigation produced no HTTP response after "
                                + originalResult.message());
            }

            final int status = response.status();
            if (status >= 200 && status < 400) {
                return new ValidationResult(true,
                        formatBrowserStatus(response)
                                + " (browser navigation fallback after "
                                + originalResult.message() + ")");
            }

            return ValidationResult.failure(formatBrowserStatus(response));

        } catch (final PlaywrightException e) {
            log.debug("[{}] Browser navigation fallback failed for '{}': {}",
                    RULE_ID, href, e.getMessage(), e);
            return ValidationResult.failure(
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (linkPage != null) {
                try {
                    linkPage.close();
                } catch (final PlaywrightException e) {
                    log.debug("[{}] Error closing privacy-link fallback page: {}",
                            RULE_ID, e.getMessage(), e);
                }
            }
        }
    }

    private static String formatBrowserStatus(final Response response) {
        final String statusText = response.statusText();
        if (statusText == null || statusText.isBlank()) {
            return "HTTP " + response.status();
        }
        return "HTTP " + response.status() + " " + statusText;
    }
}
