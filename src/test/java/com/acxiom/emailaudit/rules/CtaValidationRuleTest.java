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
//import java.util.HashMap;
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
// * Unit tests for {@link CtaValidationRule}.
// *
// * <p>{@link Page} is mocked with Mockito; {@link Page#evaluate(String)} is
// * stubbed to return the {@code List<Map<String, Object>>} shape
// * {@code {tag, text, href, hidden}} that {@code EXTRACT_CTAS_JS} would
// * produce after Playwright's automatic deserialisation. {@code href} is
// * {@code null} for {@code <button>} entries and a {@code String} (possibly
// * empty) for {@code <a>} entries.</p>
// *
// * <p><strong>Test dependency note:</strong> these tests require
// * {@code org.mockito:mockito-core} as a {@code test}-scoped dependency,
// * consistent with the other rule test classes in this package.</p>
// */
//public class CtaValidationRuleTest {
//
//    private CtaValidationRule rule;
//    private Page page;
//
//    @BeforeMethod
//    public void setUp() {
//        rule = new CtaValidationRule();
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
//        assertEquals(rule.ruleId(), "CTA_VALIDATION");
//        assertEquals(rule.category(), AuditRule.RuleCategory.CONTENT);
//        assertEquals(rule.severity(), AuditRule.RuleSeverity.HIGH);
//        assertTrue(rule.isEnabled());
//        assertFalse(rule.description().isBlank());
//    }
//
//    // -------------------------------------------------------------------------
//    // PASS scenarios
//    // -------------------------------------------------------------------------
//
//    @Test
//    public void passesWhenNoCtaCandidatesPresent() {
//        stub(List.of());
//
//        final RuleResult result = rule.execute(page);
//
//        assertTrue(result.isPassed());
//        assertTrue(result.getFindings().isEmpty());
//    }
//
//    @Test
//    public void passesWhenButtonHasTextAndIsVisible() {
//        stub(List.of(cta("button", "Submit", null, false)));
//
//        final RuleResult result = rule.execute(page);
//
//        assertTrue(result.isPassed());
//        assertTrue(result.getFindings().isEmpty());
//    }
//
//    @Test
//    public void passesWhenAnchorButtonHasTextHrefAndIsVisible() {
//        stub(List.of(cta("a", "Shop Now", "https://example.com/shop", false)));
//
//        final RuleResult result = rule.execute(page);
//
//        assertTrue(result.isPassed());
//        assertTrue(result.getFindings().isEmpty());
//    }
//
//    // -------------------------------------------------------------------------
//    // FAIL scenarios – hidden CTA
//    // -------------------------------------------------------------------------
//
//    @Test
//    public void failsWhenCtaIsHidden() {
//        stub(List.of(cta("a", "Shop Now", "https://example.com/shop", true)));
//
//        final RuleResult result = rule.execute(page);
//
//        assertTrue(result.isFailed());
//        assertEquals(result.getFindings().size(), 1);
//        assertTrue(result.getFindings().get(0).contains("CTA is hidden"));
//        assertTrue(result.getFindings().get(0).contains("\"Shop Now\""));
//    }
//
//    // -------------------------------------------------------------------------
//    // FAIL scenarios – empty text
//    // -------------------------------------------------------------------------
//
//    @Test
//    public void failsWhenButtonHasNoText() {
//        stub(List.of(cta("button", "", null, false)));
//
//        final RuleResult result = rule.execute(page);
//
//        assertTrue(result.isFailed());
//        assertEquals(result.getFindings().size(), 1);
//        assertTrue(result.getFindings().get(0).contains("CTA has no visible text"));
//        assertTrue(result.getFindings().get(0).contains("(empty text)"));
//    }
//
//    @Test
//    public void failsWhenAnchorButtonHasWhitespaceOnlyText() {
//        stub(List.of(cta("a", "   ", "https://example.com", false)));
//
//        final RuleResult result = rule.execute(page);
//
//        assertTrue(result.isFailed());
//        assertTrue(result.getFindings().stream().anyMatch(f -> f.contains("CTA has no visible text")));
//    }
//
//    // -------------------------------------------------------------------------
//    // FAIL scenarios – missing href (links only)
//    // -------------------------------------------------------------------------
//
//    @Test
//    public void failsWhenAnchorButtonHasNoHref() {
//        stub(List.of(cta("a", "Shop Now", "", false)));
//
//        final RuleResult result = rule.execute(page);
//
//        assertTrue(result.isFailed());
//        assertEquals(result.getFindings().size(), 1);
//        assertTrue(result.getFindings().get(0).contains("CTA link has no href"));
//        assertTrue(result.getFindings().get(0).contains("\"Shop Now\""));
//    }
//
//    @Test
//    public void doesNotCheckHrefForButtonElements() {
//        // <button> entries have href == null; this must never trigger the
//        // "no href" finding since the check is link-only.
//        stub(List.of(cta("button", "Submit", null, false)));
//
//        final RuleResult result = rule.execute(page);
//
//        assertTrue(result.isPassed());
//    }
//
//    // -------------------------------------------------------------------------
//    // FAIL scenarios – multiple issues on one CTA
//    // -------------------------------------------------------------------------
//
//    @Test
//    public void hiddenEmptyAnchorWithNoHrefProducesThreeFindings() {
//        stub(List.of(cta("a", "", "", true)));
//
//        final RuleResult result = rule.execute(page);
//
//        assertTrue(result.isFailed());
//        assertEquals(result.getFindings().size(), 3);
//        assertTrue(result.getFindings().stream().anyMatch(f -> f.contains("CTA is hidden")));
//        assertTrue(result.getFindings().stream().anyMatch(f -> f.contains("CTA has no visible text")));
//        assertTrue(result.getFindings().stream().anyMatch(f -> f.contains("CTA link has no href")));
//    }
//
//    // -------------------------------------------------------------------------
//    // FAIL scenarios – mixed valid/invalid CTAs
//    // -------------------------------------------------------------------------
//
//    @Test
//    public void failsWithFindingsOnlyForOffendingCtas() {
//        stub(List.of(
//                cta("a", "Shop Now", "https://example.com/shop", false),
//                cta("button", "", null, false),
//                cta("a", "Learn More", "https://example.com/learn", true)
//        ));
//
//        final RuleResult result = rule.execute(page);
//
//        assertTrue(result.isFailed());
//        assertEquals(result.getFindings().size(), 2);
//        assertTrue(result.getFindings().stream().anyMatch(f -> f.contains("CTA has no visible text")));
//        assertTrue(result.getFindings().stream().anyMatch(f -> f.contains("CTA is hidden")));
//    }
//
//    // -------------------------------------------------------------------------
//    // Finding cap / overflow
//    // -------------------------------------------------------------------------
//
//    @Test
//    public void capsFindingsAndAppendsOverflowSummaryWhenExceedingLimit() {
//        // 30 hidden CTAs -> 30 findings, cap is 25 -> 25 findings + 1 overflow summary = 26.
//        final List<Map<String, Object>> ctas = new ArrayList<>();
//        for (int i = 0; i < 30; i++) {
//            ctas.add(cta("a", "CTA " + i, "https://example.com/" + i, true));
//        }
//        stub(ctas);
//
//        final RuleResult result = rule.execute(page);
//
//        assertTrue(result.isFailed());
//        assertEquals(result.getFindings().size(), 26);
//        assertTrue(result.getFindings().get(25).contains("5 more CTA issue(s)"));
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
//        when(page.evaluate(anyString())).thenReturn("unexpected");
//
//        final RuleResult result = rule.execute(page);
//
//        // No CTAs extracted -> treated as no candidates -> PASS.
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
//    private void stub(final List<Map<String, Object>> ctas) {
//        when(page.evaluate(anyString())).thenReturn(ctas);
//    }
//
//    /**
//     * Builds a {@code Map} matching the shape produced by
//     * {@code EXTRACT_CTAS_JS}: {@code {tag, text, href, hidden}}.
//     * {@code href} may be {@code null} (for {@code "button"} entries).
//     */
//    private static Map<String, Object> cta(
//            final String tag, final String text, final String href, final boolean hidden) {
//        final Map<String, Object> map = new HashMap<>();
//        map.put("tag", tag);
//        map.put("text", text);
//        map.put("href", href); // may legitimately be null
//        map.put("hidden", hidden);
//        return map;
//    }
//}
