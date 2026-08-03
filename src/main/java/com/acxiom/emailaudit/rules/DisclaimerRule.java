package com.acxiom.emailaudit.rules;

import com.acxiom.emailaudit.model.RuleDefinition;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link AuditRule} that validates a page contains the required reply-to /
 * "do not reply" disclaimer text, driven by the {@code replyToDisclaimer}
 * section of the shared validation config (the same config file that
 * supplies {@code privacyLink} and {@code viewOnline} keyword lists).
 *
 * <h2>What changed and why</h2>
 * <p>The previous implementation checked disclaimer text presence
 * correctly, but then went on to re-validate the page's unsubscribe and
 * "manage preferences" link health via {@code LinkHealthChecker} — that is
 * {@link LinkValidationRule}'s responsibility, not this rule's, and doing it
 * here caused two problems: (1) a link that {@code LinkValidationRule}
 * already reports as broken would <em>also</em> fail this unrelated rule
 * under a misleading "Mandatory compliance messaging is incomplete"
 * business-impact message, and (2) low-level exceptions from URI parsing
 * (e.g. {@code IllegalArgumentException: URI has a fragment component})
 * leaked directly into the user-facing finding text instead of a clean
 * business message. This version checks disclaimer text only.</p>
 *
 * <h2>Checks performed</h2>
 * <p>The page body's visible text is scanned (case-insensitive) for any one
 * of the keyword phrases configured under {@code replyToDisclaimer.keywords}
 * — e.g. {@code "do not reply"}, {@code "please do not reply"},
 * {@code "mailbox is not monitored"}, {@code "unmonitored mailbox"}. A
 * single match anywhere in the body is sufficient to PASS.</p>
 *
 * <h2>Configuration</h2>
 * <p>Keywords are loaded from a JSON config resource shaped like:</p>
 * <pre>
 * {
 *   "replyToDisclaimer": {
 *     "enabled": true,
 *     "keywords": [
 *       "do not reply",
 *       "please do not reply",
 *       "mailbox is not monitored",
 *       "unmonitored mailbox"
 *     ]
 *   }
 * }
 * </pre>
 * <p>Each top-level section deserializes directly into the shared
 * {@link RuleDefinition} model (the same {@code enabled}/{@code keywords}
 * shape used for {@code privacyLink} and {@code viewOnline}). If
 * {@code enabled} is {@code false}, the rule short-circuits to PASS. If the
 * config resource or the {@code replyToDisclaimer} key can't be found, a
 * built-in default keyword list (matching the example above) is used
 * instead, so the rule degrades gracefully rather than failing every run —
 * check the log for a warning if this happens, since it likely means
 * {@link #CONFIG_RESOURCE_PATH} doesn't match your actual config location
 * ({@code config/email-rule.json}, loaded from the classpath).</p>
 *
 * <p><strong>Integration note:</strong> if there's already a shared loader
 * that returns a {@code Map<String, RuleDefinition>} (or a typed wrapper)
 * for this whole config file — used by {@code PrivacyLinkRule} /
 * {@code ViewOnlineLinkRule} — point me to it and I'll call that directly
 * instead of the independent classpath read below, so there's exactly one
 * place that parses this file.</p>
 *
 * <h2>Thread safety</h2>
 * <p>The parsed config is cached in a {@code volatile} field after first
 * load, guarded by a class-level {@code synchronized} block, so concurrent
 * TestNG threads share one parse rather than re-reading the resource per
 * page.</p>
 */
public final class DisclaimerRule implements AuditRule {

    private static final Logger log =
            LoggerFactory.getLogger(DisclaimerRule.class);

    public static final String RULE_ID =
            "DISCLAIMER_PRESENT";

    /**
     * Classpath location of the shared validation config. Adjust this to
     * match wherever {@code privacyLink} / {@code viewOnline} are actually
     * loaded from in this project.
     */
    private static final String CONFIG_RESOURCE_PATH = "/config/email-rule.json";

    private static final String CONFIG_SECTION_KEY = "replyToDisclaimer";

    private static final List<String> DEFAULT_KEYWORDS = List.of(
            "do not reply",
            "please do not reply",
            "mailbox is not monitored",
            "unmonitored mailbox"
    );

    private static final RuleDefinition DEFAULT_CONFIG = defaultConfig();

    private static volatile RuleDefinition cachedConfig;

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public String description() {
        return "Verify disclaimer / reply-to information exists";
    }

    @Override
    public RuleCategory category() {
        return RuleCategory.CONTENT;
    }

    @Override
    public RuleSeverity severity() {
        return RuleSeverity.HIGH;
    }

    @Override
    public String passImpact() {
        return "Mandatory compliance messaging is present.";
    }

    @Override
    public String failImpact() {
        return "Mandatory compliance messaging is incomplete.";
    }

    @Override
    public RuleResult execute(final Page page) {

        Objects.requireNonNull(page);

        final long startMs = System.currentTimeMillis();

        try {
            final RuleDefinition config = loadConfig();

            if (!config.isEnabled()) {
                log.info("[{}] Reply-to disclaimer check disabled via config; skipping", RULE_ID);
                return RuleResult.builder(this, RuleResult.Status.PASS, startMs)
                        .withFindings(List.of(FindingFormatter.disclaimerFinding(
                                true, "Reply-to disclaimer check is disabled via configuration.")))
                        .build();
            }

            final String bodyText = (String) page.evaluate(
                    "() => (document.body.innerText || '').toLowerCase()");

            final String matchedKeyword = config.getKeywords().stream()
                    .filter(keyword -> bodyText.contains(keyword.toLowerCase()))
                    .findFirst()
                    .orElse(null);

            if (matchedKeyword == null) {
                final String finding = FindingFormatter.disclaimerFinding(
                        false,
                        "Mandatory reply-to/disclaimer text not found. Expected one of: "
                                + String.join(", ", config.getKeywords()));

                return RuleResult.builder(this, RuleResult.Status.FAIL, startMs)
                        .withFindings(List.of(finding))
                        .build();
            }

            final String passFinding = FindingFormatter.disclaimerFinding(
                    true,
                    "Reply-to/disclaimer text found (matched: \"" + matchedKeyword + "\").");

            return RuleResult.builder(this, RuleResult.Status.PASS, startMs)
                    .withFindings(List.of(passFinding))
                    .build();

        } catch (final Exception ex) {

            log.error("Disclaimer validation failed", ex);

            return RuleResult.error(
                    this,
                    startMs,
                    ex);
        }
    }

    // -------------------------------------------------------------------------
    // Internal – config loading
    // -------------------------------------------------------------------------

    private static RuleDefinition loadConfig() {
        RuleDefinition local = cachedConfig;
        if (local != null) {
            return local;
        }

        synchronized (DisclaimerRule.class) {
            if (cachedConfig != null) {
                return cachedConfig;
            }

            cachedConfig = readConfigFromClasspath();
            return cachedConfig;
        }
    }

    /**
     * Reads the whole validation config file as {@code Map<String,
     * RuleDefinition>} (one entry per top-level section — {@code
     * privacyLink}, {@code viewOnline}, {@code replyToDisclaimer}, etc.) and
     * returns just the {@value #CONFIG_SECTION_KEY} entry, since this rule
     * only cares about its own section. Falls back to
     * {@link #DEFAULT_CONFIG} if the resource, the section, or its keyword
     * list is missing/empty, so a config wiring mismatch degrades this rule
     * to sane defaults instead of failing every audit run.
     */
    private static RuleDefinition readConfigFromClasspath() {
        try (InputStream in = DisclaimerRule.class.getResourceAsStream(CONFIG_RESOURCE_PATH)) {
            if (in == null) {
                log.warn("[{}] Config resource '{}' not found on classpath; "
                                + "falling back to default reply-to keywords",
                        RULE_ID, CONFIG_RESOURCE_PATH);
                return DEFAULT_CONFIG;
            }

            final ObjectMapper mapper = new ObjectMapper();
            final Map<String, RuleDefinition> sections =
                    mapper.readValue(in, new TypeReference<Map<String, RuleDefinition>>() { });

            final RuleDefinition section = sections.get(CONFIG_SECTION_KEY);

            if (section == null) {
                log.warn("[{}] '{}' section missing from '{}'; "
                                + "falling back to default reply-to keywords",
                        RULE_ID, CONFIG_SECTION_KEY, CONFIG_RESOURCE_PATH);
                return DEFAULT_CONFIG;
            }

            if (section.getKeywords() == null || section.getKeywords().isEmpty()) {
                log.warn("[{}] '{}.keywords' was empty in '{}'; "
                                + "falling back to default reply-to keywords",
                        RULE_ID, CONFIG_SECTION_KEY, CONFIG_RESOURCE_PATH);
                final RuleDefinition fallback = new RuleDefinition();
                fallback.setEnabled(section.isEnabled());
                fallback.setKeywords(DEFAULT_KEYWORDS);
                return fallback;
            }

            return section;

        } catch (final IOException e) {
            log.error("[{}] Failed to load '{}', falling back to default reply-to keywords: {}",
                    RULE_ID, CONFIG_RESOURCE_PATH, e.getMessage(), e);
            return DEFAULT_CONFIG;
        }
    }

    private static RuleDefinition defaultConfig() {
        final RuleDefinition definition = new RuleDefinition();
        definition.setEnabled(true);
        definition.setKeywords(DEFAULT_KEYWORDS);
        return definition;
    }
}