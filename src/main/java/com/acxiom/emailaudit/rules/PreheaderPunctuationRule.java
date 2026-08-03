package com.acxiom.emailaudit.rules;

import com.acxiom.emailaudit.rules.util.PreheaderExtractor;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link AuditRule} that verifies the authored preheader / preview-text
 * message ends with terminal punctuation ({@code .}, {@code !}, or
 * {@code ?}).
 *
 * <p>The ESP padding suffix is stripped before this check runs — see
 * {@link PreheaderExtractor}. The character immediately preceding the
 * padding (i.e. the last character of the authored message) is what gets
 * evaluated.</p>
 *
 * <h2>Thread safety</h2>
 * <p>Stateless; safe for concurrent use.</p>
 */
public final class PreheaderPunctuationRule implements AuditRule {

    private static final Logger log = LoggerFactory.getLogger(PreheaderPunctuationRule.class);

    public static final String RULE_ID = "PREHEADER_PUNCTUATION_VALIDATION";

    private static final String DESCRIPTION =
            "Validates that the preheader/preview-text message ends with terminal punctuation.";

    private static final String FINDING_TITLE = "Preheader Punctuation Validation";
    private static final String ELEMENT_PREHEADER = "Preview Text / Preheader";

    /** Acceptable terminal punctuation characters. */
    private static final String TERMINAL_PUNCTUATION = ".!?";

    public PreheaderPunctuationRule() {
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
        return RuleCategory.CONTENT;
    }

    @Override
    public RuleSeverity severity() {
        return RuleSeverity.LOW;
    }

    @Override
    public String passImpact() {
        return "Preheader text reads as a complete, properly punctuated sentence.";
    }

    @Override
    public String failImpact() {
        return "Preheader text lacking terminal punctuation may read as abrupt or unfinished "
                + "in the inbox preview.";
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

        final Optional<String> authored = PreheaderExtractor.extractAuthoredText(html);
        if (authored.isEmpty()) {
            return RuleResult.skipped(this, "No preheader/preview-text element found on page");
        }

        final String trimmed = authored.get().strip();
        if (trimmed.isEmpty()) {
            return RuleResult.skipped(this, "Preheader element found but contains no authored text");
        }

        final char lastChar = trimmed.charAt(trimmed.length() - 1);
        if (TERMINAL_PUNCTUATION.indexOf(lastChar) >= 0) {
            log.info("[{}] Preheader ends with terminal punctuation '{}'", RULE_ID, lastChar);
            final String pass = FindingFormatter.structuredFinding(
                    FINDING_TITLE, ELEMENT_PREHEADER,
                    "Validation: PASSED\nReason: Message ends with terminal punctuation.");
            return RuleResult.builder(this, RuleResult.Status.PASS, startMs)
                    .withFindings(List.of(pass))
                    .build();
        }

        final String finding = FindingFormatter.structuredFinding(
                FINDING_TITLE, ELEMENT_PREHEADER,
                "Preheader message does not end with terminal punctuation "
                        + "(found '" + lastChar + "').");

        log.warn("[{}] {}", RULE_ID, finding);
        return RuleResult.builder(this, RuleResult.Status.FAIL, startMs)
                .withFindings(List.of(finding))
                .build();
    }
}