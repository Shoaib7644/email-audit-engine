package com.acxiom.emailaudit.rules;

import com.acxiom.emailaudit.rules.util.PreheaderExtractor;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;

/**
 * {@link AuditRule} that verifies the authored preheader / preview-text
 * message has no accidental leading or trailing whitespace.
 *
 * <p>Deliberate ESP padding (the trailing {@code &nbsp;&zwnj;} chain used to
 * control inbox preview length) is excluded from this check — see
 * {@link PreheaderExtractor} for details. Only whitespace within the
 * authored message itself is evaluated.</p>
 *
 * <h2>Scope</h2>
 * <p>Subject Line is checked here only when it is present in the rendered
 * page (e.g. via {@code <title>}); the sample templates this rule was built
 * against do not embed Subject Line in HTML at all, so that portion of the
 * check is skipped rather than failed when absent.</p>
 *
 * <h2>Thread safety</h2>
 * <p>Stateless; safe for concurrent use across multiple {@link Page}s.</p>
 */
public final class PreheaderTrimRule implements AuditRule {

    private static final Logger log = LoggerFactory.getLogger(PreheaderTrimRule.class);

    public static final String RULE_ID = "PREHEADER_TRIM_VALIDATION";

    private static final String DESCRIPTION =
            "Validates that the preheader/preview-text message has no accidental "
                    + "leading or trailing whitespace.";

    private static final String FINDING_TITLE = "Preheader Trim Validation";
    private static final String ELEMENT_PREHEADER = "Preview Text / Preheader";

    public PreheaderTrimRule() {
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
        return "Preheader text displays cleanly in the inbox preview with no stray whitespace.";
    }

    @Override
    public String failImpact() {
        return "Accidental leading/trailing whitespace in the preheader may cause inconsistent "
                + "or truncated preview rendering across inbox providers.";
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
            log.debug("[{}] No preheader element found on page", RULE_ID);
            return RuleResult.skipped(this, "No preheader/preview-text element found on page");
        }

        final String text = authored.get();
        if (text.isEmpty()) {
            return RuleResult.skipped(this, "Preheader element found but contains no authored text");
        }

        final String trimmed = text.strip();
        if (trimmed.equals(text)) {
            log.info("[{}] Preheader text has no leading/trailing whitespace", RULE_ID);
            final String pass = FindingFormatter.structuredFinding(
                    FINDING_TITLE, ELEMENT_PREHEADER,
                    "Validation: PASSED\nReason: No leading or trailing whitespace detected.");
            return RuleResult.builder(this, RuleResult.Status.PASS, startMs)
                    .withFindings(java.util.List.of(pass))
                    .build();
        }

        final boolean leading  = !text.equals(text.stripLeading());
        final boolean trailing = !text.equals(text.stripTrailing());
        final String side = leading && trailing ? "leading and trailing" : leading ? "leading" : "trailing";

        final String finding = FindingFormatter.structuredFinding(
                FINDING_TITLE, ELEMENT_PREHEADER,
                "Preheader message has accidental " + side + " whitespace.");

        log.warn("[{}] {}", RULE_ID, finding);
        return RuleResult.builder(this, RuleResult.Status.FAIL, startMs)
                .withFindings(java.util.List.of(finding))
                .build();
    }
}