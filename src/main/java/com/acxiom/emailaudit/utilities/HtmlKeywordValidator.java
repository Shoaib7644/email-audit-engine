package com.acxiom.emailaudit.utilities;

import com.microsoft.playwright.Page;

import java.util.List;

public final class HtmlKeywordValidator {

    private HtmlKeywordValidator() {}

    public static boolean containsAnyKeyword(
            Page page,
            List<String> keywords) {

        String html =
                page.content().toLowerCase();

        return keywords.stream()
                .map(String::toLowerCase)
                .anyMatch(html::contains);
    }
}