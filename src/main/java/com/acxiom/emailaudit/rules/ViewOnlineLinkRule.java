package com.acxiom.emailaudit.rules;

import com.acxiom.emailaudit.model.RuleDefinition;
import com.acxiom.emailaudit.rules.util.LinkHealthChecker;
import com.acxiom.emailaudit.rules.util.ValidationResult;
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
import java.util.stream.Collectors;

/**
 * {@link AuditRule} that verifies a "View Online" / "Browser Version" link
 * exists on the page and, if present, that its destination resolves.
 *
 * <h2>What changed and why</h2>
 * <ul>
 *   <li><strong>Keyword matching was hardcoded and case-sensitive.</strong>
 *       The previous implementation embedded a fixed keyword array directly
 *       in the page-evaluation JavaScript, and one entry —
 *       {@code 'View it in your browser'} — was left in mixed case while
 *       the page's anchor text was lowercased before comparison
 *       ({@code txt.includes(k)}). Since a lowercased string can never
 *       contain a mixed-case substring, that keyword could never match,
 *       even against a page whose link text was that exact phrase. Keywords
 *       are now lowercased once in Java before being passed into the page,
 *       and the page-side comparison lowercases the anchor text the same
 *       way, so matching is reliably case-insensitive regardless of how a
 *       keyword is capitalized in config.</li>
 *   <li><strong>Keywords are now config-driven.</strong> They're loaded
 *       from the {@code viewOnline} section of {@code config/email-rule.json}
 *       (the same {@code enabled}/{@code keywords} shape used for
 *       {@code privacyLink} and {@code replyToDisclaimer}) via the shared
 *       {@link RuleDefinition} model, instead of being fixed in code.</li>
 *   <li><strong>{@code passImpact()}/{@code failImpact()} were copy-pasted
 *       from {@code PrivacyLinkRule}</strong> and described privacy-policy
 *       accessibility rather than the View Online link. Corrected to
 *       describe this rule's own concern.</li>
 * </ul>
 *
 * <h2>Configuration</h2>
 * <p>If {@code config/email-rule.json} or its {@code viewOnline} section
 * can't be found, a built-in default keyword list is used instead, so a
 * config wiring mismatch degrades this rule to sane defaults rather than
 * failing every run — check the log for a warning if this happens.</p>
 *
 * <p><strong>Integration note:</strong> this duplicates the same classpath
 * config-loading logic now present in {@code DisclaimerRule} (one rule, one
 * section each). If there's already a shared loader that returns a
 * {@code Map<String, RuleDefinition>} for the whole config file elsewhere
 * in the project, point me to it and I'll consolidate both rules onto it
 * instead of each parsing the file independently.</p>
 */
public final class ViewOnlineLinkRule implements AuditRule {

    private static final Logger log =
            LoggerFactory.getLogger(ViewOnlineLinkRule.class);

    public static final String RULE_ID = "VIEW_ONLINE_LINK";

    /** Classpath location of the shared validation config. */
    private static final String CONFIG_RESOURCE_PATH = "/config/email-rule.json";

    private static final String CONFIG_SECTION_KEY = "viewOnline";

    private static final List<String> DEFAULT_KEYWORDS = List.of(
            "view online",
            "view in browser",
            "open in browser",
            "web version",
            "view it in your browser",
            "read online"
    );

    private static final RuleDefinition DEFAULT_CONFIG = defaultConfig();

    private static volatile RuleDefinition cachedConfig;

    private static final String EXTRACT_LINK_JS = """
            (keywords) => {

                const found =
                    [...document.querySelectorAll('a')]
                        .find(a => {

                            const txt =
                                (a.innerText || '')
                                    .trim()
                                    .toLowerCase();

                            return keywords.some(k => txt.includes(k));
                        });

                if (!found) {
                    return null;
                }

                return {
                    text: found.innerText || '',
                    href: found.getAttribute('href') || ''
                };
            }
            """;

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public String description() {
        return "Verify View Online / Browser Version link exists";
    }

    @Override
    public RuleCategory category() {
        return RuleCategory.LINKS;
    }

    @Override
    public RuleSeverity severity() {
        return RuleSeverity.MEDIUM;
    }

    @Override
    public String passImpact() {
        return "The View in Browser link is present and functional.";
    }

    @Override
    public String failImpact() {
        return "Recipients may be unable to open the online version of this email.";
    }

    @Override
    @SuppressWarnings("unchecked")
    public RuleResult execute(final Page page) {

        Objects.requireNonNull(page);

        final long startMs = System.currentTimeMillis();

        try {
            final RuleDefinition config = loadConfig();

            if (!config.isEnabled()) {
                log.info("[{}] View Online link check disabled via config; skipping", RULE_ID);
                final String skipFinding = FindingFormatter.viewOnlineFinding(
                        null, null, true, "View Online link check is disabled via configuration.");
                return RuleResult.builder(this, RuleResult.Status.PASS, startMs)
                        .withFindings(List.of(skipFinding))
                        .build();
            }

            // Keywords are lowercased once here; the page-side comparison
            // lowercases anchor text the same way, so matching is reliably
            // case-insensitive regardless of how a keyword is capitalized
            // in config (this is what fixes the "View it in your browser"
            // false negative).
            final List<String> lowerKeywords = config.getKeywords().stream()
                    .map(String::toLowerCase)
                    .collect(Collectors.toList());

            final Map<String, String> link =
                    (Map<String, String>) page.evaluate(EXTRACT_LINK_JS, lowerKeywords);

            // Case 1: No View Online link exists at all
            if (link == null) {
                final String finding = FindingFormatter.viewOnlineFinding(
                        null, null, false, "View Online link not found on page.");
                return RuleResult.builder(this, RuleResult.Status.FAIL, startMs)
                        .withFindings(List.of(finding))
                        .build();
            }

            final String text = link.get("text");
            final String href = link.get("href");

            // Case 2: Link exists but its href attribute is missing or empty
            if (href == null || href.isBlank()) {
                final String finding = FindingFormatter.viewOnlineFinding(
                        text, "(empty href)", false, "Missing href");
                return RuleResult.builder(this, RuleResult.Status.FAIL, startMs)
                        .withFindings(List.of(finding))
                        .build();
            }

            // Case 3: Link exists and has an href, but fails remote validation
            final ValidationResult result = LinkHealthChecker.validate(href);
            if (!result.valid()) {
                final String finding = FindingFormatter.viewOnlineFinding(
                        text, href, false, result.message());
                return RuleResult.builder(this, RuleResult.Status.FAIL, startMs)
                        .withFindings(List.of(finding))
                        .build();
            }

            // Case 4: Link found, has a valid href, and resolved successfully
            final String passFinding = FindingFormatter.viewOnlineFinding(
                    text, href, true, "Link resolved successfully.");

            return RuleResult.builder(this, RuleResult.Status.PASS, startMs)
                    .withFindings(List.of(passFinding))
                    .build();

        } catch (final Exception ex) {

            log.error("View Online link validation failed", ex);

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

        synchronized (ViewOnlineLinkRule.class) {
            if (cachedConfig != null) {
                return cachedConfig;
            }

            cachedConfig = readConfigFromClasspath();
            return cachedConfig;
        }
    }

    private static RuleDefinition readConfigFromClasspath() {
        try (InputStream in = ViewOnlineLinkRule.class.getResourceAsStream(CONFIG_RESOURCE_PATH)) {
            if (in == null) {
                log.warn("[{}] Config resource '{}' not found on classpath; "
                                + "falling back to default view-online keywords",
                        RULE_ID, CONFIG_RESOURCE_PATH);
                return DEFAULT_CONFIG;
            }

            final ObjectMapper mapper = new ObjectMapper();
            final Map<String, RuleDefinition> sections =
                    mapper.readValue(in, new TypeReference<Map<String, RuleDefinition>>() { });

            final RuleDefinition section = sections.get(CONFIG_SECTION_KEY);

            if (section == null) {
                log.warn("[{}] '{}' section missing from '{}'; "
                                + "falling back to default view-online keywords",
                        RULE_ID, CONFIG_SECTION_KEY, CONFIG_RESOURCE_PATH);
                return DEFAULT_CONFIG;
            }

            if (section.getKeywords() == null || section.getKeywords().isEmpty()) {
                log.warn("[{}] '{}.keywords' was empty in '{}'; "
                                + "falling back to default view-online keywords",
                        RULE_ID, CONFIG_SECTION_KEY, CONFIG_RESOURCE_PATH);
                final RuleDefinition fallback = new RuleDefinition();
                fallback.setEnabled(section.isEnabled());
                fallback.setKeywords(DEFAULT_KEYWORDS);
                return fallback;
            }

            return section;

        } catch (final IOException e) {
            log.error("[{}] Failed to load '{}', falling back to default view-online keywords: {}",
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