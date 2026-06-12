package com.acxiom.emailaudit.rules;

import com.acxiom.emailaudit.rules.AuditRule;
import com.acxiom.emailaudit.rules.RuleResult;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * Unit tests for {@link AltTextValidationRule}.
 *
 * <p>{@link Page} is mocked with Mockito; {@link Page#evaluate(String)} is
 * stubbed to return the JSON-equivalent {@code List<Map<String, Object>>}
 * structure that the real browser-side JavaScript in
 * {@code EXTRACT_IMAGES_JS} would produce after Playwright's automatic
 * deserialisation.</p>
 *
 * <p><strong>Test dependency note:</strong> these tests require
 * {@code org.mockito:mockito-core} as a {@code test}-scoped dependency.
 * It is not currently declared in {@code pom.xml} and must be added
 * separately; per task constraints, {@code pom.xml} is not modified here.</p>
 */
public class AltTextValidationRuleTest {

    private AltTextValidationRule rule;
    private Page page;

    @BeforeMethod
    public void setUp() {
        rule = new AltTextValidationRule();
        page = mock(Page.class);
        when(page.url()).thenReturn("file:///tmp/sample-email.html");
    }

    // -------------------------------------------------------------------------
    // Metadata
    // -------------------------------------------------------------------------

    @Test
    public void ruleMetadataIsCorrect() {
        assertEquals(rule.ruleId(), "ALT_TEXT_VALIDATION");
        assertEquals(rule.category(), AuditRule.RuleCategory.ACCESSIBILITY);
        assertEquals(rule.severity(), AuditRule.RuleSeverity.HIGH);
        assertTrue(rule.isEnabled());
        assertFalse(rule.description().isBlank());
    }

    // -------------------------------------------------------------------------
    // PASS scenarios
    // -------------------------------------------------------------------------

    @Test
    public void passesWhenNoImagesPresent() {
        when(page.evaluate(anyString())).thenReturn(List.of());

        final RuleResult result = rule.execute(page);

        assertTrue(result.isPassed());
        assertTrue(result.getFindings().isEmpty());
    }

    @Test
    public void passesWhenAllImagesHaveNonEmptyAlt() {
        final List<Map<String, Object>> images = List.of(
                imageEntry("/images/logo.png", true, "Company logo"),
                imageEntry("/images/banner.png", true, "Seasonal promotion banner")
        );
        when(page.evaluate(anyString())).thenReturn(images);

        final RuleResult result = rule.execute(page);

        assertTrue(result.isPassed());
        assertTrue(result.getFindings().isEmpty());
    }

    @Test
    public void passesWhenAltContainsOnlyWhitespaceButTrimsToNonEmpty() {
        // alt = " Logo " trims to "Logo" – non-empty, should pass.
        final List<Map<String, Object>> images = List.of(
                imageEntry("/images/logo.png", true, " Logo ")
        );
        when(page.evaluate(anyString())).thenReturn(images);

        final RuleResult result = rule.execute(page);

        assertTrue(result.isPassed());
    }

    // -------------------------------------------------------------------------
    // FAIL scenarios – missing alt attribute
    // -------------------------------------------------------------------------

    @Test
    public void failsWhenAltAttributeIsMissingEntirely() {
        final List<Map<String, Object>> images = List.of(
                imageEntry("/images/hero.png", false, "")
        );
        when(page.evaluate(anyString())).thenReturn(images);

        final RuleResult result = rule.execute(page);

        assertTrue(result.isFailed());
        assertEquals(result.getFindings().size(), 1);
        assertTrue(result.getFindings().get(0).contains("Missing alt attribute"));
        assertTrue(result.getFindings().get(0).contains("/images/hero.png"));
    }

    // -------------------------------------------------------------------------
    // FAIL scenarios – empty alt attribute
    // -------------------------------------------------------------------------

    @Test
    public void failsWhenAltAttributeIsEmptyString() {
        final List<Map<String, Object>> images = List.of(
                imageEntry("/images/spacer.gif", true, "")
        );
        when(page.evaluate(anyString())).thenReturn(images);

        final RuleResult result = rule.execute(page);

        assertTrue(result.isFailed());
        assertEquals(result.getFindings().size(), 1);
        assertTrue(result.getFindings().get(0).contains("Empty alt attribute"));
        assertTrue(result.getFindings().get(0).contains("/images/spacer.gif"));
    }

    @Test
    public void failsWhenAltAttributeIsWhitespaceOnly() {
        final List<Map<String, Object>> images = List.of(
                imageEntry("/images/spacer.gif", true, "   ")
        );
        when(page.evaluate(anyString())).thenReturn(images);

        final RuleResult result = rule.execute(page);

        assertTrue(result.isFailed());
        assertEquals(result.getFindings().size(), 1);
        assertTrue(result.getFindings().get(0).contains("Empty alt attribute"));
    }

    // -------------------------------------------------------------------------
    // FAIL scenarios – missing src
    // -------------------------------------------------------------------------

    @Test
    public void reportsPlaceholderWhenSrcIsMissing() {
        final List<Map<String, Object>> images = List.of(
                imageEntry("", false, "")
        );
        when(page.evaluate(anyString())).thenReturn(images);

        final RuleResult result = rule.execute(page);

        assertTrue(result.isFailed());
        assertTrue(result.getFindings().get(0).contains("(no src attribute)"));
    }

    // -------------------------------------------------------------------------
    // FAIL scenarios – mixed pass/fail images
    // -------------------------------------------------------------------------

    @Test
    public void failsWithOneFindingPerOffendingImageOnly() {
        final List<Map<String, Object>> images = List.of(
                imageEntry("/images/ok.png", true, "Valid description"),
                imageEntry("/images/missing-alt.png", false, ""),
                imageEntry("/images/empty-alt.png", true, ""),
                imageEntry("/images/also-ok.png", true, "Another valid description")
        );
        when(page.evaluate(anyString())).thenReturn(images);

        final RuleResult result = rule.execute(page);

        assertTrue(result.isFailed());
        assertEquals(result.getFindings().size(), 2);
        assertTrue(result.getFindings().stream()
                .anyMatch(f -> f.contains("/images/missing-alt.png") && f.contains("Missing alt attribute")));
        assertTrue(result.getFindings().stream()
                .anyMatch(f -> f.contains("/images/empty-alt.png") && f.contains("Empty alt attribute")));
    }

    // -------------------------------------------------------------------------
    // Finding cap / overflow
    // -------------------------------------------------------------------------

    @Test
    public void capsFindingsAndAppendsOverflowSummaryWhenExceedingLimit() {
        // 30 offending images, cap is 25 -> 25 findings + 1 overflow summary = 26.
        final List<Map<String, Object>> images = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            images.add(imageEntry("/images/img-" + i + ".png", false, ""));
        }
        when(page.evaluate(anyString())).thenReturn(images);

        final RuleResult result = rule.execute(page);

        assertTrue(result.isFailed());
        assertEquals(result.getFindings().size(), 26);
        assertTrue(result.getFindings().get(25).contains("5 more image(s)"));
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
        // Simulate a malformed/unexpected JS evaluation result (e.g. a String
        // instead of a List).
        when(page.evaluate(anyString())).thenReturn("unexpected");

        final RuleResult result = rule.execute(page);

        // No images extracted -> treated as no <img> elements -> PASS.
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

    /**
     * Builds a {@code Map} matching the shape produced by
     * {@code EXTRACT_IMAGES_JS}: {@code {src, hasAlt, alt}}.
     */
    private static Map<String, Object> imageEntry(
            final String src, final boolean hasAlt, final String alt) {
        return Map.of(
                "src", src,
                "hasAlt", hasAlt,
                "alt", alt
        );
    }
}
