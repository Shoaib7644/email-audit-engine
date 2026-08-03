package com.acxiom.emailaudit.rules;

import com.microsoft.playwright.Page;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class AltTextValidationRuleActiveTest {

    @Test
    public void ignoresZeroSizedTrackingPixelsWithEmptyAlt() {
        final Page page = mock(Page.class);
        when(page.url()).thenReturn("file:///tmp/email.html");
        when(page.evaluate(anyString())).thenReturn(List.of(
                image("https://t.delivery.generalmotors.com/r/?id=abc,def,1", true, "", "0", "0", 0, 0),
                image("https://cdn.example.com/logo.png", true, "Logo", "100", "50", 100, 50)
        ));

        final RuleResult result = new AltTextValidationRule().execute(page);

        assertTrue(result.isPassed(), String.join("\n", result.getFindings()));
        assertFalse(String.join("\n", result.getFindings()).contains("generalmotors.com/r/?id="));
    }

    @Test
    public void stillFailsVisibleImageWithEmptyAlt() {
        final Page page = mock(Page.class);
        when(page.url()).thenReturn("file:///tmp/email.html");
        when(page.evaluate(anyString())).thenReturn(List.of(
                image("https://cdn.example.com/arrow.png", true, "", "24", "24", 24, 24)
        ));

        final RuleResult result = new AltTextValidationRule().execute(page);

        assertTrue(result.isFailed());
        assertEquals(result.getFindings().size(), 1);
        assertTrue(result.getFindings().getFirst().contains("arrow.png"));
    }

    private static Map<String, Object> image(
            final String src,
            final boolean hasAlt,
            final String alt,
            final String width,
            final String height,
            final double renderedWidth,
            final double renderedHeight) {

        return Map.of(
                "src", src,
                "hasAlt", hasAlt,
                "alt", alt,
                "width", width,
                "height", height,
                "renderedWidth", renderedWidth,
                "renderedHeight", renderedHeight
        );
    }
}
