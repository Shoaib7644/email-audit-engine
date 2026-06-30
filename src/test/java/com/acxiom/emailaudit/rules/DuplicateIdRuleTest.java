//package com.acxiom.emailaudit.rules;
//
//import com.microsoft.playwright.Page;
//import com.microsoft.playwright.PlaywrightException;
//import org.testng.annotations.BeforeMethod;
//import org.testng.annotations.Test;
//
//import java.util.ArrayList;
//import java.util.List;
//
//import static org.mockito.ArgumentMatchers.anyString;
//import static org.mockito.Mockito.mock;
//import static org.mockito.Mockito.when;
//import static org.testng.Assert.assertEquals;
//import static org.testng.Assert.assertFalse;
//import static org.testng.Assert.assertTrue;
//
///**
// * Unit tests for {@link DuplicateIdRule}.
// *
// * <p>{@link Page} is mocked with Mockito; {@link Page#evaluate(String)} is
// * stubbed to return the {@code List<String>} of {@code id} attribute values
// * that {@code EXTRACT_IDS_JS} would produce after Playwright's automatic
// * deserialisation — one entry per element, in document order, with duplicate
// * values appearing multiple times.</p>
// *
// * <p><strong>Test dependency note:</strong> these tests require
// * {@code org.mockito:mockito-core} as a {@code test}-scoped dependency,
// * consistent with {@code AltTextValidationRuleTest}.</p>
// */
//public class DuplicateIdRuleTest {
//
//    private DuplicateIdRule rule;
//    private Page page;
//
//    @BeforeMethod
//    public void setUp() {
//        rule = new DuplicateIdRule();
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
//        assertEquals(rule.ruleId(), "DUPLICATE_ID");
//        assertEquals(rule.category(), AuditRule.RuleCategory.HTML);
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
//    public void passesWhenNoElementsHaveIds() {
//        when(page.evaluate(anyString())).thenReturn(List.of());
//
//        final RuleResult result = rule.execute(page);
//
//        assertTrue(result.isPassed());
//        assertTrue(result.getFindings().isEmpty());
//    }
//
//    @Test
//    public void passesWhenAllIdsAreUnique() {
//        when(page.evaluate(anyString())).thenReturn(List.of("header", "main", "footer"));
//
//        final RuleResult result = rule.execute(page);
//
//        assertTrue(result.isPassed());
//        assertTrue(result.getFindings().isEmpty());
//    }
//
//    // -------------------------------------------------------------------------
//    // FAIL scenarios – single duplicate
//    // -------------------------------------------------------------------------
//
//    @Test
//    public void failsWhenOneIdIsDuplicated() {
//        when(page.evaluate(anyString()))
//                .thenReturn(List.of("header", "content", "content", "footer"));
//
//        final RuleResult result = rule.execute(page);
//
//        assertTrue(result.isFailed());
//        assertEquals(result.getFindings().size(), 1);
//        assertTrue(result.getFindings().get(0).contains("Duplicate id 'content'"));
//        assertTrue(result.getFindings().get(0).contains("found 2 times"));
//    }
//
//    // -------------------------------------------------------------------------
//    // FAIL scenarios – occurrence count beyond two
//    // -------------------------------------------------------------------------
//
//    @Test
//    public void reportsCorrectOccurrenceCountForTripleDuplicate() {
//        when(page.evaluate(anyString()))
//                .thenReturn(List.of("row", "row", "row", "summary"));
//
//        final RuleResult result = rule.execute(page);
//
//        assertTrue(result.isFailed());
//        assertEquals(result.getFindings().size(), 1);
//        assertTrue(result.getFindings().get(0).contains("Duplicate id 'row'"));
//        assertTrue(result.getFindings().get(0).contains("found 3 times"));
//    }
//
//    // -------------------------------------------------------------------------
//    // FAIL scenarios – multiple distinct duplicates
//    // -------------------------------------------------------------------------
//
//    @Test
//    public void failsWithOneFindingPerDuplicatedIdValue() {
//        when(page.evaluate(anyString()))
//                .thenReturn(List.of("header", "cta", "cta", "footer", "footer"));
//
//        final RuleResult result = rule.execute(page);
//
//        assertTrue(result.isFailed());
//        assertEquals(result.getFindings().size(), 2);
//        assertTrue(result.getFindings().stream()
//                .anyMatch(f -> f.contains("Duplicate id 'cta'") && f.contains("found 2 times")));
//        assertTrue(result.getFindings().stream()
//                .anyMatch(f -> f.contains("Duplicate id 'footer'") && f.contains("found 2 times")));
//    }
//
//    // -------------------------------------------------------------------------
//    // Finding cap / overflow
//    // -------------------------------------------------------------------------
//
//    @Test
//    public void capsFindingsAndAppendsOverflowSummaryWhenExceedingLimit() {
//        // 30 distinct ids, each duplicated -> 30 duplicate findings, cap is 25
//        // -> 25 findings + 1 overflow summary = 26.
//        final List<String> ids = new ArrayList<>();
//        for (int i = 0; i < 30; i++) {
//            ids.add("dup-" + i);
//            ids.add("dup-" + i);
//        }
//        when(page.evaluate(anyString())).thenReturn(ids);
//
//        final RuleResult result = rule.execute(page);
//
//        assertTrue(result.isFailed());
//        assertEquals(result.getFindings().size(), 26);
//        assertTrue(result.getFindings().get(25).contains("5 more duplicate id value(s)"));
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
//        // Simulate a malformed/unexpected JS evaluation result (e.g. a String
//        // instead of a List).
//        when(page.evaluate(anyString())).thenReturn("unexpected");
//
//        final RuleResult result = rule.execute(page);
//
//        // No ids extracted -> treated as no elements with ids -> PASS.
//        assertTrue(result.isPassed());
//    }
//
//    @Test
//    public void ignoresBlankIdValuesInResult() {
//        // Defensive: even if the JS filter were bypassed, blank strings must
//        // not be counted as duplicates of each other.
//        when(page.evaluate(anyString()))
//                .thenReturn(List.of("header", "", "  ", "footer"));
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
//}