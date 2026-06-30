//package com.acxiom.emailaudit.rules;
//
//import com.acxiom.emailaudit.rules.AuditRule;
//import com.acxiom.emailaudit.rules.RuleResult;
//import com.microsoft.playwright.Page;
//import com.microsoft.playwright.PlaywrightException;
//import org.testng.annotations.BeforeMethod;
//import org.testng.annotations.Test;
//
//import java.util.ArrayList;
//import java.util.List;
//import java.util.Map;
//
//import static org.mockito.ArgumentMatchers.anyString;
//import static org.mockito.Mockito.mock;
//import static org.mockito.Mockito.when;
//import static org.testng.Assert.assertEquals;
//import static org.testng.Assert.assertFalse;
//import static org.testng.Assert.assertTrue;
//
///**
// * Unit tests for {@link BrokenAnchorRule}.
// *
// * <p>{@link Page} is mocked with Mockito; {@link Page#evaluate(String)} is
// * stubbed to return the {@code Map<String, Object>} shape
// * {@code { ids: List<String>, anchors: List<String> } } that
// * {@code EXTRACT_ANCHORS_AND_IDS_JS} would produce after Playwright's
// * automatic deserialisation.</p>
// *
// * <p><strong>Test dependency note:</strong> these tests require
// * {@code org.mockito:mockito-core} as a {@code test}-scoped dependency,
// * consistent with {@code AltTextValidationRuleTest} and
// * {@code DuplicateIdRuleTest}.</p>
// */
//public class BrokenAnchorRuleTest {
//
//    private BrokenAnchorRule rule;
//    private Page page;
//
//    @BeforeMethod
//    public void setUp() {
//        rule = new BrokenAnchorRule();
//        page = mock(Page.class);
//        when(page.url()).thenReturn("file:///tmp/sample-email.html");
//    }
//
//    // -------------------------------------------------------------------------
//    // Metadata
//    // -------------------------------------------------------------------------
//
//    @Test
//    public void ruleMetadataIsCorrect() {
//        assertEquals(rule.ruleId(), "BROKEN_ANCHOR");
//        assertEquals(rule.category(), AuditRule.RuleCategory.LINKS);
//        assertEquals(rule.severity(), AuditRule.RuleSeverity.MEDIUM);
//        assertTrue(rule.isEnabled());
//        assertFalse(rule.description().isBlank());
//    }
//
//    // -------------------------------------------------------------------------
//    // PASS scenarios
//    // -------------------------------------------------------------------------
//
//    @Test
//    public void passesWhenNoAnchorsPresent() {
//        stub(List.of(), List.of());
//
//        final RuleResult result = rule.execute(page);
//
//        assertTrue(result.isPassed());
//        assertTrue(result.getFindings().isEmpty());
//    }
//
//    @Test
//    public void passesWhenFragmentAnchorMatchesExistingId() {
//        stub(List.of("section-1", "section-2"), List.of("#section-1", "#section-2"));
//
//        final RuleResult result = rule.execute(page);
//
//        assertTrue(result.isPassed());
//        assertTrue(result.getFindings().isEmpty());
//    }
//
//    @Test
//    public void ignoresEmptyFragmentHref() {
//        // href="#" is the conventional "scroll to top" link – no id to validate.
//        stub(List.of(), List.of("#"));
//
//        final RuleResult result = rule.execute(page);
//
//        assertTrue(result.isPassed());
//        assertTrue(result.getFindings().isEmpty());
//    }
//
//    @Test
//    public void ignoresJavascriptVoidHref() {
//        stub(List.of(), List.of("javascript:void(0)"));
//
//        final RuleResult result = rule.execute(page);
//
//        assertTrue(result.isPassed());
//        assertTrue(result.getFindings().isEmpty());
//    }
//
//    @Test
//    public void ignoresJavascriptVoidHrefCaseInsensitive() {
//        stub(List.of(), List.of("JavaScript:void(0)"));
//
//        final RuleResult result = rule.execute(page);
//
//        assertTrue(result.isPassed());
//    }
//
//    @Test
//    public void ignoresNonFragmentHrefs() {
//        // Absolute and relative URLs are not fragment links – out of scope for this rule.
//        stub(List.of("section-1"), List.of("https://example.com/page", "/relative/path", "mailto:test@example.com"));
//
//        final RuleResult result = rule.execute(page);
//
//        assertTrue(result.isPassed());
//    }
//
//    // -------------------------------------------------------------------------
//    // FAIL scenarios – single broken anchor
//    // -------------------------------------------------------------------------
//
//    @Test
//    public void failsWhenFragmentAnchorHasNoMatchingId() {
//        stub(List.of("section-1"), List.of("#missing-section"));
//
//        final RuleResult result = rule.execute(page);
//
//        assertTrue(result.isFailed());
//        assertEquals(result.getFindings().size(), 1);
//        assertTrue(result.getFindings().get(0).contains("href=\"#missing-section\""));
//        assertTrue(result.getFindings().get(0).contains("id=\"missing-section\""));
//    }
//
//    // -------------------------------------------------------------------------
//    // FAIL scenarios – case sensitivity
//    // -------------------------------------------------------------------------
//
//    @Test
//    public void failsWhenFragmentCaseDoesNotMatchId() {
//        // HTML ids are case-sensitive: "#Section" != id="section".
//        stub(List.of("section"), List.of("#Section"));
//
//        final RuleResult result = rule.execute(page);
//
//        assertTrue(result.isFailed());
//        assertEquals(result.getFindings().size(), 1);
//        assertTrue(result.getFindings().get(0).contains("href=\"#Section\""));
//    }
//
//    // -------------------------------------------------------------------------
//    // FAIL scenarios – mixed valid/invalid anchors
//    // -------------------------------------------------------------------------
//
//    @Test
//    public void failsWithOneFindingPerBrokenAnchorOnly() {
//        stub(
//                List.of("top", "contact"),
//                List.of("#top", "#contact", "#unsubscribe", "#", "javascript:void(0)", "https://example.com")
//        );
//
//        final RuleResult result = rule.execute(page);
//
//        assertTrue(result.isFailed());
//        assertEquals(result.getFindings().size(), 1);
//        assertTrue(result.getFindings().get(0).contains("href=\"#unsubscribe\""));
//    }
//
//    @Test
//    public void duplicateBrokenAnchorsEachProduceAFinding() {
//        // Two anchors pointing at the same missing id -> two findings (one per anchor occurrence).
//        stub(List.of(), List.of("#missing", "#missing"));
//
//        final RuleResult result = rule.execute(page);
//
//        assertTrue(result.isFailed());
//        assertEquals(result.getFindings().size(), 2);
//        assertTrue(result.getFindings().stream().allMatch(f -> f.contains("href=\"#missing\"")));
//    }
//
//    // -------------------------------------------------------------------------
//    // Finding cap / overflow
//    // -------------------------------------------------------------------------
//
//    @Test
//    public void capsFindingsAndAppendsOverflowSummaryWhenExceedingLimit() {
//        // 30 broken fragment anchors, cap is 25 -> 25 findings + 1 overflow summary = 26.
//        final List<String> anchors = new ArrayList<>();
//        for (int i = 0; i < 30; i++) {
//            anchors.add("#missing-" + i);
//        }
//        stub(List.of(), anchors);
//
//        final RuleResult result = rule.execute(page);
//
//        assertTrue(result.isFailed());
//        assertEquals(result.getFindings().size(), 26);
//        assertTrue(result.getFindings().get(25).contains("5 more broken anchor(s)"));
//    }
//
//    // -------------------------------------------------------------------------
//    // ERROR scenarios
//    // -------------------------------------------------------------------------
//
//    @Test
//    public void returnsErrorWhenPageEvaluateThrows() {
//        when(page.evaluate(anyString()))
//                .thenThrow(new PlaywrightException("Execution context was destroyed"));
//
//        final RuleResult result = rule.execute(page);
//
//        assertTrue(result.isError());
//        assertTrue(result.getErrorMessage().contains("PlaywrightException"));
//    }
//
//    @Test
//    public void returnsEmptyResultsWhenEvaluateReturnsUnexpectedType() {
//        // Simulate a malformed/unexpected JS evaluation result (e.g. a List
//        // instead of a Map).
//        when(page.evaluate(anyString())).thenReturn(List.of("unexpected"));
//
//        final RuleResult result = rule.execute(page);
//
//        // No ids/anchors extracted -> treated as nothing to validate -> PASS.
//        assertTrue(result.isPassed());
//    }
//
//    @Test
//    public void handlesMissingKeysInEvaluationResultGracefully() {
//        // Map present but missing the "ids"/"anchors" keys entirely.
//        when(page.evaluate(anyString())).thenReturn(Map.of());
//
//        final RuleResult result = rule.execute(page);
//
//        assertTrue(result.isPassed());
//    }
//
//    // -------------------------------------------------------------------------
//    // Null argument handling
//    // -------------------------------------------------------------------------
//
//    @Test(expectedExceptions = NullPointerException.class)
//    public void executeThrowsOnNullPage() {
//        rule.execute(null);
//    }
//
//    // -------------------------------------------------------------------------
//    // Test helpers
//    // -------------------------------------------------------------------------
//
//    /**
//     * Stubs {@code page.evaluate(...)} to return the
//     * {@code { ids, anchors } } map shape produced by
//     * {@code EXTRACT_ANCHORS_AND_IDS_JS}.
//     */
//    private void stub(final List<String> ids, final List<String> anchors) {
//        when(page.evaluate(anyString())).thenReturn(Map.of("ids", ids, "anchors", anchors));
//    }
//}
