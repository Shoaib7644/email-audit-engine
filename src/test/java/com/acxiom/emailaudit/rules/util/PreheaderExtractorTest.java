package com.acxiom.emailaudit.rules.util;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class PreheaderExtractorTest {

    @Test
    public void extractsAuthoredTextFromDisplayNoneDivPreheader() {
        final String html = """
                <body>
                  <div style="display:none;font-size:1px;color:#ffffff;line-height:1px;max-height:0;">
                    Discover its full-size strength, style, and innovation.
                  </div>
                  <div style="display:none;float:left;overflow:hidden;width:0;max-height:0;">
                    &nbsp;&zwnj;&nbsp;&zwnj;&nbsp;&zwnj;
                  </div>
                </body>
                """;

        assertEquals(
                PreheaderExtractor.extractAuthoredText(html).orElseThrow().strip(),
                "Discover its full-size strength, style, and innovation.");
    }

    @Test
    public void ignoresPaddingOnlyHiddenDivs() {
        final String html = """
                <body>
                  <div style="display:none;float:left;overflow:hidden;width:0;max-height:0;">
                    &nbsp;&zwnj;&nbsp;&zwnj;&nbsp;&zwnj;
                  </div>
                </body>
                """;

        assertTrue(PreheaderExtractor.extractAuthoredText(html).isEmpty());
    }
}
