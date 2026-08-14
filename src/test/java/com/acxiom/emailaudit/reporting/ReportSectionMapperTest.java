package com.acxiom.emailaudit.reporting;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

public class ReportSectionMapperTest {

    @Test
    public void headerAndPreheaderRulesMapToHeaderDetails() {
        assertEquals(
                ReportSectionMapper.map("HEADER_EMOJI_ENCODING_VALIDATION"),
                ReportSection.HEADER_DETAILS);
        assertEquals(
                ReportSectionMapper.map("PREHEADER_PUNCTUATION_VALIDATION"),
                ReportSection.HEADER_DETAILS);
        assertEquals(
                ReportSectionMapper.map("PREHEADER_TRIM_VALIDATION"),
                ReportSection.HEADER_DETAILS);
    }

    @Test
    public void imageSourceMapsToImagesWhileAltTextStaysAccessibility() {
        assertEquals(
                ReportSectionMapper.map("IMAGE_SRC_VALIDATION"),
                ReportSection.IMAGES);
        assertEquals(
                ReportSectionMapper.map("ALT_TEXT_VALIDATION"),
                ReportSection.ACCESSIBILITY);
    }

    @Test
    public void linkRulesMapToLinks() {
        assertEquals(
                ReportSectionMapper.map("LINK_VALIDATION"),
                ReportSection.LINKS);
        assertEquals(
                ReportSectionMapper.map("LINK_TEXT_VALIDATION"),
                ReportSection.LINKS);
        assertEquals(
                ReportSectionMapper.map("BROKEN_ANCHOR"),
                ReportSection.LINKS);
    }
}
