package com.acxiom.emailaudit.rules;

import com.acxiom.emailaudit.rules.util.PreheaderExtractor;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@link AuditRule} that scans the preheader / preview-text markup for
 * literal (unencoded) characters above U+00FF — e.g. emoji, smart quotes, em
 * dashes typed directly rather than expressed as HTML entities — which some
 * email clients and ESP transport layers render inconsistently or mangle.
 *
 * <h2>Detection strategy</h2>
 * <p>Operates on the <strong>raw</strong> markup (before entity decoding).
 * All valid HTML entities ({@code &name;}, {@code &#NNN;}, {@code &#xHHH;})
 * are stripped first; any remaining character with a code point greater than
 * {@code 0x00FF} is considered unencoded and is reported.</p>
 *
 * <h2>Scope</h2>
 * <p>Limited to the preheader/preview-text block for this iteration — see
 * {@link PreheaderExtractor}. Subject Line is not checked here because the
 * sample templates this rule was validated against do not embed Subject
 * Line in the rendered HTML at all.</p>
 *
 * <h2>Thread safety</h2>
 * <p>Stateless; all {@link Pattern}s are compiled once at class-load time.</p>
 */
public final class HeaderEmojiEncodingRule implements AuditRule {

    private static final Logger log = LoggerFactory.getLogger(HeaderEmojiEncodingRule.class);

    public static final String RULE_ID = "HEADER_EMOJI_ENCODING_VALIDATION";

    private static final String DESCRIPTION =
            "Scans the preheader/preview-text markup for unencoded emoji or special "
                    + "characters (code point > U+00FF) that should be HTML-encoded.";

    private static final String FINDING_TITLE = "Header Emoji/Special Character Encoding";
    private static final String ELEMENT_PREHEADER = "Preview Text / Preheader";

    /** Matches any valid HTML entity — named, decimal, or hex. */
    private static final Pattern ENTITY_PATTERN = Pattern.compile(
            "&(?:#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);");

    /** Cap on distinct offending characters reported per finding, to keep output readable. */
    private static final int MAX_REPORTED_CHARS = 10;

    public HeaderEmojiEncodingRule() {
        // stateless
    }

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public RuleCategory category() {
        return RuleCategory.HEADER_DETAILS;
    }

    @Override
    public RuleSeverity severity() {
        return RuleSeverity.MEDIUM;
    }

    @Override
    public String passImpact() {
        return "Preheader markup uses properly HTML-encoded characters and will render "
                + "consistently across email clients.";
    }

    @Override
    public String failImpact() {
        return "Unencoded emoji or special characters in the preheader markup may render "
                + "incorrectly, as mojibake, or as missing glyphs in some email clients.";
    }

    @Override
    public RuleResult execute(final Page page) {
        Objects.requireNonNull(page, "page must not be null");
        final long startMs = System.currentTimeMillis();

        final String html;
        try {
            html = page.content();
        } catch (final Exception e) {
            log.error("[{}] Failed to read page content: {}", RULE_ID, e.getMessage(), e);
            return RuleResult.error(this, startMs, e);
        }

        final Optional<String> rawInner = PreheaderExtractor.extractRawHtml(html);
        if (rawInner.isEmpty()) {
            return RuleResult.skipped(this, "No preheader/preview-text element found on page");
        }

        final String withoutEntities = ENTITY_PATTERN.matcher(rawInner.get()).replaceAll("");
        final Set<String> offenders = new LinkedHashSet<>();

        withoutEntities.codePoints().forEach(cp -> {
            if (cp > 0x00FF && offenders.size() < MAX_REPORTED_CHARS) {
                offenders.add(String.format("'%s' (U+%04X)", new String(Character.toChars(cp)), cp));
            }
        });

        if (offenders.isEmpty()) {
            log.info("[{}] No unencoded special characters found in preheader markup", RULE_ID);
            final String pass = FindingFormatter.structuredFinding(
                    FINDING_TITLE, ELEMENT_PREHEADER,
                    "Validation: PASSED\nReason: All characters are properly HTML-encoded.");
            return RuleResult.builder(this, RuleResult.Status.PASS, startMs)
                    .withFindings(List.of(pass))
                    .build();
        }

        final List<String> findings = new ArrayList<>();
        final String detail = "Unencoded character(s) detected: " + String.join(", ", offenders);
        findings.add(FindingFormatter.structuredFinding(FINDING_TITLE, ELEMENT_PREHEADER, detail));

        log.warn("[{}] {}", RULE_ID, detail);
        return RuleResult.builder(this, RuleResult.Status.FAIL, startMs)
                .withFindings(findings)
                .build();
    }
}
