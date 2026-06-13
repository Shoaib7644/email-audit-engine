package com.acxiom.emailaudit.rules;


import com.acxiom.emailaudit.rules.AuditRule;
import com.acxiom.emailaudit.rules.RuleResult;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * Unit tests for {@link LinkTextValidationRule}.
 *
 * <p>{@link Page} is mocked with Mockito; {@link Page#evaluate(String)} is
 * stubbed to return the {@code List<Map<String, Object>>} shape
 * {@code {href, text, inFooter}} that {@code EXTRACT_LINKS_JS} would produce
 * after Playwright's automatic deserialisation.</p>
 *
 * <p><strong>Test dependency note:</strong> these tests require
 * {@code org.mockito:mockito-core} as a {@code test}-scoped dependency,
 * consistent with the other rule test classes in this package.</p>
 */
public class LinkTextValidationRuleTest {

    private LinkTextValidationRule rule;
    private Page page;

    @BeforeMethod
    public void setUp() {
        rule = new LinkTextValidationRule();
        page = mock(Page.class);
        when(page.url()).thenReturn("file:///tmp/sample-email.html");
    }

    // -------------------------------------------------------------------------
    // Metadata
    // -------------------------------------------------------------------------

    @Test
    public void ruleMetadataIsCorrect() {
        assertEquals(rule.ruleId(), "LINK_TEXT_VALIDATION");
        assertEquals(rule.category(), AuditRule.RuleCategory.CONTENT);
        assertEquals(rule.severity(), AuditRule.RuleSeverity.MEDIUM);
        assertTrue(rule.isEnabled());
        assertFalse(rule.description().isBlank());
    }

    // -------------------------------------------------------------------------
    // PASS scenarios
    // -------------------------------------------------------------------------

    @Test
    public void passesWhenNoLinksPresent() {
        stub(List.of());

        final RuleResult result = rule.execute(page);

        assertTrue(result.isPassed());
        assertTrue(result.getFindings().isEmpty());
    }

    @Test
    public void passesWhenAllLinksHaveDescriptiveText() {
        stub(List.of(
                linkEntry("/products/widget", "View the Widget product page", false),
                linkEntry("/account/settings", "Manage your account settings", false)
        ));

        final RuleResult result = rule.execute(page);

        assertTrue(result.isPassed());
        assertTrue(result.getFindings().isEmpty());
    }

    @Test
    public void passesWhenTextContainsButDoesNotEqualBannedPhrase() {
        // "here" appears as a substring but the full trimmed text is not banned.
        stub(List.of(
                linkEntry("/faq", "Find out more about where to go from here", false)
        ));

        final RuleResult result = rule.execute(page);

        assertTrue(result.isPassed());
    }

    @Test
    public void matchingIsCaseInsensitive() {
        // "Click Here" should still pass if not banned... actually it IS banned,
        // verifying case-insensitive matching works for the FAIL case too.
        stub(List.of(
                linkEntry("/promo", "Learn More", false)
        ));

        final RuleResult result = rule.execute(page);

        assertTrue(result.isFailed());
        assertEquals(result.getFindings().size(), 1);
    }

    // -------------------------------------------------------------------------
    // FAIL scenarios – each banned phrase
    // -------------------------------------------------------------------------

    @Test
    public void failsOnClickHere() {
        stub(List.of(linkEntry("/promo", "Click here", false)));

        final RuleResult result = rule.execute(page);

        assertTrue(result.isFailed());
        assertEquals(result.getFindings().size(), 1);
        assertTrue(result.getFindings().get(0).contains("\"Click here\""));
        assertTrue(result.getFindings().get(0).contains("href=\"/promo\""));
    }

    @Test
    public void failsOnReadMore() {
        stub(List.of(linkEntry("/blog/post-1", "Read more", false)));

        final RuleResult result = rule.execute(page);

        assertTrue(result.isFailed());
        assertTrue(result.getFindings().get(0).contains("\"Read more\""));
    }

    @Test
    public void failsOnLearnMore() {
        stub(List.of(linkEntry("/features", "learn more", false)));

        final RuleResult result = rule.execute(page);

        assertTrue(result.isFailed());
        assertTrue(result.getFindings().get(0).contains("\"learn more\""));
    }

    @Test
    public void failsOnHere() {
        stub(List.of(linkEntry("/terms", "here", false)));

        final RuleResult result = rule.execute(page);

        assertTrue(result.isFailed());
        assertTrue(result.getFindings().get(0).contains("\"here\""));
    }

    @Test
    public void failsOnBannedTextWithSurroundingWhitespace() {
        stub(List.of(linkEntry("/promo", "  Click here  ", false)));

        final RuleResult result = rule.execute(page);

        assertTrue(result.isFailed());
        assertEquals(result.getFindings().size(), 1);
        assertTrue(result.getFindings().get(0).contains("\"Click here\""));
    }

    // -------------------------------------------------------------------------
    // Footer unsubscribe exception
    // -------------------------------------------------------------------------

    @Test
    public void ignoresFooterLinkWithUnsubscribeHrefEvenIfTextIsBanned() {
        stub(List.of(
                linkEntry("https://example.com/email/unsubscribe?id=123", "Click here", true)
        ));

        final RuleResult result = rule.execute(page);

        assertTrue(result.isPassed());
    }

    @Test
    public void ignoresFooterLinkWithUnsubscribeTextEvenIfBanned() {
        stub(List.of(
                linkEntry("/email/optout", "click here", true)
        ));

        final RuleResult result = rule.execute(page);

        assertTrue(result.isPassed());
    }

    @Test
    public void doesNotIgnoreNonFooterUnsubscribeLinkWithBannedText() {
        // Unsubscribe-related href, but NOT inside a <footer> -> still flagged.
        stub(List.of(
                linkEntry("/email/unsubscribe?id=123", "click here", false)
        ));

        final RuleResult result = rule.execute(page);

        assertTrue(result.isFailed());
        assertEquals(result.getFindings().size(), 1);
    }

    @Test
    public void doesNotIgnoreFooterLinkWithBannedTextThatIsNotUnsubscribeRelated() {
        // In the footer, but unrelated to unsubscribe -> still flagged.
        stub(List.of(
                linkEntry("/privacy-policy", "click here", true)
        ));

        final RuleResult result = rule.execute(page);

        assertTrue(result.isFailed());
        assertEquals(result.getFindings().size(), 1);
    }

    // -------------------------------------------------------------------------
    // FAIL scenarios – multiple offending links
    // -------------------------------------------------------------------------

    @Test
    public void failsWithOneFindingPerOffendingLinkOnly() {
        stub(List.of(
                linkEntry("/products", "Browse our full catalog", false),
                linkEntry("/promo-1", "Click here", false),
                linkEntry("/promo-2", "Read more", false),
                linkEntry("https://example.com/unsubscribe", "Unsubscribe", true)
        ));

        final RuleResult result = rule.execute(page);

        assertTrue(result.isFailed());
        assertEquals(result.getFindings().size(), 2);
        assertTrue(result.getFindings().stream().anyMatch(f -> f.contains("href=\"/promo-1\"")));
        assertTrue(result.getFindings().stream().anyMatch(f -> f.contains("href=\"/promo-2\"")));
    }

    // -------------------------------------------------------------------------
    // Finding cap / overflow
    // -------------------------------------------------------------------------

    @Test
    public void capsFindingsAndAppendsOverflowSummaryWhenExceedingLimit() {
        // 30 offending links, cap is 25 -> 25 findings + 1 overflow summary = 26.
        final List<Map<String, Object>> links = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            links.add(linkEntry("/promo-" + i, "Click here", false));
        }
        stub(links);

        final RuleResult result = rule.execute(page);

        assertTrue(result.isFailed());
        assertEquals(result.getFindings().size(), 26);
        assertTrue(result.getFindings().get(25).contains("5 more link(s)"));
    }

    // -------------------------------------------------------------------------
    // ERROR scenarios
    // -------------------------------------------------------------------------

    @Test
    public void returnsErrorWhenPageEvaluateThrows() {
        when(page.evaluate(anyString()))
                .thenThrow(new PlaywrightException("Execution context was destroyed"));

        final RuleResult result = rule.execute(page);

        assertTrue(result.isError());
        assertTrue(result.getErrorMessage().contains("PlaywrightException"));
    }

    @Test
    public void returnsEmptyResultsWhenEvaluateReturnsUnexpectedType() {
        when(page.evaluate(anyString())).thenReturn("unexpected");

        final RuleResult result = rule.execute(page);

        // No links extracted -> treated as no anchors -> PASS.
        assertTrue(result.isPassed());
    }

    // -------------------------------------------------------------------------
    // Null argument handling
    // -------------------------------------------------------------------------

    @Test(expectedExceptions = NullPointerException.class)
    public void executeThrowsOnNullPage() {
        rule.execute(null);
    }

    // -------------------------------------------------------------------------
    // Test helpers
    // -------------------------------------------------------------------------

    private void stub(final List<Map<String, Object>> links) {
        when(page.evaluate(anyString())).thenReturn(links);
    }

    /**
     * Builds a {@code Map} matching the shape produced by
     * {@code EXTRACT_LINKS_JS}: {@code {href, text, inFooter}}.
     */
    private static Map<String, Object> linkEntry(
            final String href, final String text, final boolean inFooter) {
        return Map.of(
                "href", href,
                "text", text,
                "inFooter", inFooter
        );
    }
}
