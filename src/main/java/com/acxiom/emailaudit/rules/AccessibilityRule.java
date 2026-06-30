package com.acxiom.emailaudit.rules;

import com.acxiom.emailaudit.rules.AuditRule;
import com.acxiom.emailaudit.rules.RuleResult;
import com.deque.html.axecore.playwright.AxeBuilder;
import com.deque.html.axecore.results.AxeResults;
import com.deque.html.axecore.results.CheckedNode;
import com.deque.html.axecore.results.Rule;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * {@link AuditRule} implementation that runs an axe-core accessibility scan
 * against the rendered page and maps every violation to a {@link RuleResult}
 * finding.
 *
 * <h2>axe-core integration</h2>
 * <p>Uses {@link AxeBuilder} from {@code com.deque.html.axe-core:playwright}
 * (declared in {@code pom.xml}).  The builder injects the axe-core JS library
 * into the page and returns structured {@link AxeResults} without any
 * additional browser configuration.</p>
 *
 * <h2>WCAG targeting</h2>
 * <p>By default the rule runs all axe tags that map to
 * <em>WCAG 2.1 Level A and AA</em> ({@code wcag2a}, {@code wcag2aa},
 * {@code wcag21a}, {@code wcag21aa}) plus Deque's best-practice rules
 * ({@code best-practice}).  This set can be overridden at construction time
 * for narrower or broader scans.</p>
 *
 * <h2>Severity mapping</h2>
 * <pre>
 *   axe impact   →  RuleSeverity
 *   critical     →  CRITICAL
 *   serious      →  HIGH
 *   moderate     →  MEDIUM
 *   minor        →  LOW
 *   (unknown)    →  INFO
 * </pre>
 *
 * <h2>Thread safety</h2>
 * <p>All configurable fields are set once in the constructor and never mutated.
 * A new {@link AxeBuilder} is created per {@link #execute(Page)} call because
 * the builder holds page-scoped state.  The class is safe for concurrent use
 * from multiple TestNG threads, each operating on its own {@link Page}.</p>
 */
public final class AccessibilityRule implements AuditRule {

    private static final Logger log = LoggerFactory.getLogger(AccessibilityRule.class);

    // -------------------------------------------------------------------------
    // Rule identity
    // -------------------------------------------------------------------------

    public static final String RULE_ID = "ACCESSIBILITY_AXE";

    private static final String DESCRIPTION =
            "Scans the page with axe-core for WCAG 2.1 A/AA violations and best-practice issues.";

    // -------------------------------------------------------------------------
    // Default axe tags (WCAG 2.1 A + AA + best-practice)
    // -------------------------------------------------------------------------

    private static final List<String> DEFAULT_TAGS = List.of(
            "wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "best-practice"
    );

    // -------------------------------------------------------------------------
    // Configuration (immutable after construction)
    // -------------------------------------------------------------------------

    private final List<String> tags;
    private final RuleSeverity severityOverride;
    private final boolean      enabled;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Creates the rule with the default WCAG 2.1 A/AA + best-practice tag set
     * and {@link RuleSeverity#CRITICAL} as the overall rule severity
     * (accessibility failures are blocking).
     */
    public AccessibilityRule() {
        this(DEFAULT_TAGS, RuleSeverity.CRITICAL, true);
    }

    /**
     * Creates the rule with a custom tag set.
     *
     * @param tags             axe-core tags to include; must not be null or empty
     * @param severityOverride overall rule severity reported in the dashboard
     * @param enabled          whether this rule participates in execution
     */
    public AccessibilityRule(
            final List<String> tags,
            final RuleSeverity severityOverride,
            final boolean enabled) {

        Objects.requireNonNull(tags,             "tags must not be null");
        Objects.requireNonNull(severityOverride, "severityOverride must not be null");

        if (tags.isEmpty()) {
            throw new IllegalArgumentException("tags must contain at least one axe-core tag");
        }

        this.tags             = List.copyOf(tags);
        this.severityOverride = severityOverride;
        this.enabled          = enabled;
    }

    // -------------------------------------------------------------------------
    // AuditRule implementation
    // -------------------------------------------------------------------------

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public String passImpact() {
        return "Accessibility scan completed with no violations.";
    }

    @Override
    public String failImpact() {
        return "Accessibility scan found violations.";
    }

    @Override
    public RuleCategory category() {
        return RuleCategory.ACCESSIBILITY;
    }

    @Override
    public RuleSeverity severity() {
        return severityOverride;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Runs axe-core against {@code page} and returns a {@link RuleResult}
     * containing one finding per violation.
     *
     * @param page live, fully loaded Playwright page; must not be {@code null}
     * @return PASS when no violations are found, FAIL when violations exist,
     *         ERROR when axe-core itself throws
     */
    @Override
    public RuleResult execute(final Page page) {
        Objects.requireNonNull(page, "page must not be null");

        final long startMs = System.currentTimeMillis();
        log.info("[{}] Starting axe-core scan (tags: {}) on: {}",
                RULE_ID, tags, safeUrl(page));

        final AxeResults axeResults;
        try {
            axeResults = buildAxe(page).analyze();
        } catch (final Exception e) {
            log.error("[{}] axe-core analysis threw an exception: {}", RULE_ID, e.getMessage(), e);
            return RuleResult.error(this, startMs, e);
        }

        final List<Rule> violations = safeViolations(axeResults);

        if (violations.isEmpty()) {
            log.info("[{}] No accessibility violations found", RULE_ID);
            return RuleResult.pass(this, startMs);
        }

        log.warn("[{}] {} violation(s) found", RULE_ID, violations.size());

        final List<String> findings = buildFindings(violations);
        return RuleResult.fail(this, startMs, findings);
    }

    // -------------------------------------------------------------------------
    // Internal – axe builder construction
    // -------------------------------------------------------------------------

    /**
     * Builds a fresh {@link AxeBuilder} for the supplied page, scoped to the
     * configured tag set.  A new instance is required per call because
     * {@link AxeBuilder} holds mutable page-level state.
     */
    private AxeBuilder buildAxe(final Page page) {
        return new AxeBuilder(page)
                .withTags(tags);
    }

    // -------------------------------------------------------------------------
    // Internal – result mapping
    // -------------------------------------------------------------------------

    /**
     * Converts each node of every axe {@link Rule} violation into a structured multi-line
     * finding string using {@link FindingFormatter#linkFinding()}.
     */
    private static List<String> buildFindings(final List<Rule> violations) {
        final List<String> findings = new ArrayList<>();

        for (final Rule violation : violations) {
            final String axeRuleId = nullSafe(violation.getId());
            final String desc      = nullSafe(violation.getDescription());
            final String helpUrl   = nullSafe(violation.getHelpUrl());
            final List<CheckedNode> nodes = violation.getNodes();

            if (nodes == null || nodes.isEmpty()) {
                // Keep the information intact if no individual element targets are provided
                String finding = FindingFormatter.linkFinding()
                        .title("Accessibility Violation")
                        .displayText("(no nodes)")
                        .href(helpUrl)
                        .failed(desc)
                        .build();
                findings.add(finding);
                log.debug("Violation: {}", axeRuleId);
            } else {
                for (final CheckedNode node : nodes) {
                    final String elementSelector = extractSelector(node);

                    String finding = FindingFormatter.linkFinding()
                            .title("Accessibility Violation")
                            .displayText(elementSelector)
                            .href(helpUrl)
                            .failed(desc)
                            .build();
                    findings.add(finding);
                }
                log.debug("Violation rule extracted: {}, nodes unrolled: {}", axeRuleId, nodes.size());
            }
        }

        return Collections.unmodifiableList(findings);
    }

    /**
     * Extracts the most useful selector string from a {@link CheckedNode}.
     * axe-core returns a list of CSS selector chains; we take the first one.
     */
    @SuppressWarnings("unchecked")
    private static String extractSelector(final CheckedNode node) {
        if (node == null) return "(unknown)";

        final Object rawTarget = node.getTarget();
        if (rawTarget == null) return "(unknown)";

        // axe-core "target" is polymorphic: List<String> for a simple selector,
        // or List<List<String>> for a cross-iframe selector chain.
        if (rawTarget instanceof List<?> outer) {
            if (outer.isEmpty()) return "(unknown)";

            final Object first = outer.get(0);

            if (first instanceof List<?> inner) {
                // Nested form: List<List<String>>
                return inner.stream()
                        .map(String::valueOf)
                        .collect(java.util.stream.Collectors.joining(" > "));
            }

            // Flat form: List<String>
            return outer.stream()
                    .map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining(" > "));
        }

        return String.valueOf(rawTarget);
    }

    // -------------------------------------------------------------------------
    // Internal – helpers
    // -------------------------------------------------------------------------

    /**
     * Maps axe-core impact strings to their {@link RuleSeverity} equivalents
     * for per-finding annotation (informational only – does not affect the
     * overall result status).
     */
    static RuleSeverity mapImpact(final String impact) {
        if (impact == null) return RuleSeverity.INFO;
        return switch (impact.toLowerCase().trim()) {
            case "critical" -> RuleSeverity.CRITICAL;
            case "serious"  -> RuleSeverity.HIGH;
            case "moderate" -> RuleSeverity.MEDIUM;
            case "minor"    -> RuleSeverity.LOW;
            default         -> RuleSeverity.INFO;
        };
    }

    private static List<Rule> safeViolations(final AxeResults results) {
        if (results == null) {
            log.warn("[{}] axe-core returned null AxeResults", RULE_ID);
            return Collections.emptyList();
        }
        final List<Rule> violations = results.getViolations();
        return violations != null ? violations : Collections.emptyList();
    }

    private static String safeUrl(final Page page) {
        try {
            return page.url();
        } catch (final Exception e) {
            return "<unavailable>";
        }
    }

    private static String nullSafe(final String value) {
        return value != null ? value : "";
    }
}