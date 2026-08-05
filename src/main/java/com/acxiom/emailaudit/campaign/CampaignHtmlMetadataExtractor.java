package com.acxiom.emailaudit.campaign;

import com.acxiom.emailaudit.rules.LinkAuditEntry;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Extracts campaign metadata from the final rendered email DOM.
 */
public final class CampaignHtmlMetadataExtractor {

    private static final Logger log = LoggerFactory.getLogger(CampaignHtmlMetadataExtractor.class);

    private static final String EXTRACT_EMAIL_METADATA_JS = """
            () => Array.from(document.querySelectorAll('a[href]'))
                .map((el, index) => {
                    const rect = el.getBoundingClientRect();
                    const style = window.getComputedStyle(el);
                    const visibleText = (el.innerText || el.textContent || '').trim();
                    const img = el.querySelector('img');
                    const imageAlt = img ? (img.getAttribute('alt') || '').trim() : '';
                    const href = el.getAttribute('href') || '';
                    const resolvedHref = el.href || href;
                    const label = el.getAttribute('_label') ||
                        el.getAttribute('data-label') ||
                        el.getAttribute('data-adobe-label') ||
                        '';
                    const category = el.getAttribute('_category') ||
                        el.getAttribute('data-category') ||
                        el.getAttribute('data-adobe-category') ||
                        '';
                    const classes = (el.getAttribute('class') || '').toLowerCase();
                    const role = (el.getAttribute('role') || '').toLowerCase();
                    const combined = (href + ' ' + resolvedHref + ' ' + visibleText + ' ' + label + ' ' + category).toLowerCase();
                    return {
                        domIndex: index,
                        label: label.trim(),
                        category: category.trim(),
                        href: resolvedHref,
                        rawHref: href,
                        visibleText: visibleText || imageAlt,
                        imageSrc: img ? (img.currentSrc || img.src || img.getAttribute('src') || '') : '',
                        wrapsImage: !!img,
                        ctaButton: role === 'button' || classes.indexOf('cta') >= 0 || classes.indexOf('button') >= 0,
                        textLink: !img,
                        mirrorPage: combined.indexOf('viewinbrowser') >= 0 || combined.indexOf('view in browser') >= 0 || combined.indexOf('mirror') >= 0,
                        optOut: combined.indexOf('unsubscribe') >= 0 || combined.indexOf('optout') >= 0 || combined.indexOf('opt out') >= 0 || combined.indexOf('adv_unsub') >= 0,
                        mailto: resolvedHref.toLowerCase().indexOf('mailto:') === 0,
                        telephone: resolvedHref.toLowerCase().indexOf('tel:') === 0,
                        visible: rect.width > 0 &&
                            rect.height > 0 &&
                            style.display !== 'none' &&
                            style.visibility !== 'hidden'
                    };
                })
                .filter(item => item.visible)
            """;

    public List<CampaignHtmlMetadata> extract(
            final Page page,
            final List<LinkAuditEntry> linkEvidence) {

        final Object raw = page.evaluate(EXTRACT_EMAIL_METADATA_JS);
        if (!(raw instanceof List<?> rawList)) {
            return List.of();
        }

        final List<CampaignHtmlMetadata> metadata = new ArrayList<>();
        for (final Object item : rawList) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }

            final int domIndex = integerOrZero(map.get("domIndex"));
            final String href = firstNonBlank(string(map.get("href")), string(map.get("rawHref")));
            final CampaignHtmlMetadata entry = new CampaignHtmlMetadata(
                    domIndex,
                    string(map.get("category")),
                    string(map.get("label")),
                    href,
                    string(map.get("visibleText")),
                    string(map.get("imageSrc")),
                    elementType(map),
                    queryParamsMap(href),
                    booleanOrFalse(map.get("wrapsImage")),
                    booleanOrFalse(map.get("mirrorPage")),
                    booleanOrFalse(map.get("optOut")),
                    booleanOrFalse(map.get("mailto")),
                    booleanOrFalse(map.get("telephone")),
                    findLinkEvidence(domIndex, href, linkEvidence));

            log.info("========== HTML CAMPAIGN METADATA ==========");
            log.info("Category: {}", entry.category());
            log.info("Label: {}", entry.label());
            log.info("Href: {}", entry.href());
            log.info("Type: {}", entry.type());

            metadata.add(entry);
        }
        return metadata;
    }

    private static String elementType(final Map<?, ?> map) {
        if (booleanOrFalse(map.get("mirrorPage"))) {
            return "Mirror Page";
        }
        if (booleanOrFalse(map.get("optOut"))) {
            return "Opt-Out";
        }
        if (booleanOrFalse(map.get("mailto"))) {
            return "Mailto";
        }
        if (booleanOrFalse(map.get("telephone"))) {
            return "Telephone";
        }
        if (booleanOrFalse(map.get("wrapsImage"))) {
            return "Image";
        }
        if (booleanOrFalse(map.get("ctaButton"))) {
            return "CTA";
        }
        return "Text";
    }

    private static LinkAuditEntry findLinkEvidence(
            final int domIndex,
            final String href,
            final List<LinkAuditEntry> links) {

        for (final LinkAuditEntry link : safeLinks(links)) {
            if (link.domIndex() != null && link.domIndex() == domIndex) {
                return link;
            }
        }
        for (final LinkAuditEntry link : safeLinks(links)) {
            if (href.equals(link.originalUrl()) || href.equals(link.finalUrl())) {
                return link;
            }
        }
        return null;
    }

    private static List<LinkAuditEntry> safeLinks(final List<LinkAuditEntry> links) {
        return links == null ? List.of() : links;
    }

    private static Map<String, String> queryParamsMap(final String rawUrl) {
        try {
            final URI uri = URI.create(rawUrl == null ? "" : rawUrl.trim());
            final String rawQuery = uri.getRawQuery();
            if (rawQuery == null || rawQuery.isBlank()) {
                return Map.of();
            }

            final Map<String, String> params = new LinkedHashMap<>();
            for (final String pair : rawQuery.split("&")) {
                final int equals = pair.indexOf('=');
                final String key = decode(equals >= 0 ? pair.substring(0, equals) : pair)
                        .toLowerCase(Locale.ROOT);
                final String value = decode(equals >= 0 ? pair.substring(equals + 1) : "");
                params.putIfAbsent(key, value);
            }
            return params;
        } catch (final Exception ignored) {
            return Map.of();
        }
    }

    private static String decode(final String value) {
        return URLDecoder.decode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String firstNonBlank(final String... values) {
        if (values == null) {
            return "";
        }
        for (final String value : values) {
            if (value != null && !value.trim().isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String string(final Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int integerOrZero(final Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static boolean booleanOrFalse(final Object value) {
        return value instanceof Boolean bool && bool;
    }
}
