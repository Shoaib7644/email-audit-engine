package com.acxiom.emailaudit.reporting;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

public class BusinessCategoryMapperTest {

    @Test
    public void affectedRulesUseBusinessCategoriesThatMatchDashboardSections() {
        assertEquals(
                BusinessCategoryMapper.getCategory("IMAGE_SRC_VALIDATION"),
                "Images");
        assertEquals(
                BusinessCategoryMapper.getCategory("ALT_TEXT_VALIDATION"),
                "Accessibility Violations");
        assertEquals(
                BusinessCategoryMapper.getCategory("HEADER_EMOJI_ENCODING_VALIDATION"),
                "Header / Sender Details");
        assertEquals(
                BusinessCategoryMapper.getCategory("PREHEADER_PUNCTUATION_VALIDATION"),
                "Header / Sender Details");
        assertEquals(
                BusinessCategoryMapper.getCategory("PREHEADER_TRIM_VALIDATION"),
                "Header / Sender Details");
        assertEquals(
                BusinessCategoryMapper.getCategory("LINK_TEXT_VALIDATION"),
                "Inventory & Inspect Links");
    }
}
