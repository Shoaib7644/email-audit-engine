package com.acxiom.emailaudit.rules;

import com.acxiom.emailaudit.rules.AuditRule;
import com.acxiom.emailaudit.rules.RuleResult;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * {@link AuditRule} that validates the textual content quality of a rendered
 * HTML page, catching the most common pre-production content defects:
 *
 * <ol>
 *   <li><strong>Missing title</strong> – {@code <title>} element absent or blank.</li>
 *   <li><strong>Empty body content</strong> – visible text below a minimum
 *       threshold after stripping whitespace.</li>
 *   <li><strong>Lorem Ipsum</strong> – any variant of the classic filler text.</li>
 *   <li><strong>Placeholder tokens</strong> – common template substitution
 *       markers left un-replaced (e.g. {@code {{firstName}}},
 *       {@code [FIRST_NAME]}, {@code %7BFIRST_NAME%7D}, {@code $$TOKEN$$}).</li>
 * </ol>
 *
 * <h2>Findings format</h2>
 * <p>Every finding produced by this rule is built via
 * {@link FindingFormatter#structuredFinding(String, String, String)}, giving
 * each violation a consistent {@code Content Validation} title, an
 * {@code Element} field identifying where the issue was found (e.g.
 * {@code "Document Head"}, {@code "Body"}, {@code "Paragraph"}), and a
 * {@code Detail} field with a concise, business-readable reason. For
 * example:</p>
 * <pre>
 * Content Validation
 *   Element         : Paragraph
 *   Detail          : Lorem Ipsum placeholder detected.
 * </pre>
 * <p>Multiple violations are never aggregated into one long string — each
 * check produces at most one finding, and the placeholder-token check still
 * reports all distinct tokens within its single finding's detail text.</p>
 *
 * <p>When every check passes, a single PASS finding is reported, also via
 * {@link FindingFormatter#structuredFinding(String, String, String)}:</p>
 * <pre>
 * Content Validation
 *   Detail          : Validation: PASSED
 *                      Reason: Required content present.
 * </pre>
 *
 * <h2>Extraction strategy</h2>
 * <p>All text is extracted via {@link Page#innerText(String, Page.InnerTextOptions)}
 * so only <em>visible</em> text participates in checks — hidden elements,
 * script blocks, and style blocks are ignored automatically by Playwright's
 * inner-text algorithm.</p>
 *
 * <h2>Thread safety</h2>
 * <p>All fields are {@code final} and all {@link Pattern} instances are
 * compiled once at class-load time. The class is safe for concurrent use
 * from multiple TestNG threads, each operating on its own {@link Page}.</p>
 */
public final class ContentValidationRule implements AuditRule {

    private static final Logger log = LoggerFactory.getLogger(ContentValidationRule.class);

    // -------------------------------------------------------------------------
    // Rule identity
    // -------------------------------------------------------------------------

    public static final String RULE_ID = "CONTENT_VALIDATION";

    private static final String DESCRIPTION =
            "Validates page content for missing title, empty body, lorem ipsum filler, "
                    + "and un-replaced placeholder tokens.";

    // -------------------------------------------------------------------------
    // Findings format constants
    // -------------------------------------------------------------------------

    /** Shared business-friendly title used for every finding from this rule. */
    private static final String FINDING_TITLE = "Content Validation";

    /** Element label used when the issue concerns the document's <title>/<head>. */
    private static final String ELEMENT_DOCUMENT_HEAD = "Document Head";

    /** Element label used when the issue concerns the overall <body> content. */
    private static final String ELEMENT_BODY = "Body";

    /** Element label used when the issue is filler text within a paragraph-level block. */
    private static final String ELEMENT_PARAGRAPH = "Paragraph";

    /** Detail text reported on the single PASS finding when all checks succeed. */
    private static final String PASS_DETAIL =
            "Validation: PASSED\nReason: Required content present.";

    // -------------------------------------------------------------------------
    // Thresholds
    // -------------------------------------------------------------------------

    /**
     * Minimum number of non-whitespace characters required in the body before
     * the page is considered to have meaningful content.
     */
    private static final int MIN_CONTENT_LENGTH = 50;

    // -------------------------------------------------------------------------
    // Patterns – compiled once, immutable, thread-safe
    // -------------------------------------------------------------------------

    /**
     * Matches any recognisable Lorem Ipsum fragment, case-insensitive.
     * Handles the classic opening phrase and standalone "lorem" or "ipsum" as
     * isolated words to avoid false positives on words like "lorenz".
     */
    private static final Pattern LOREM_IPSUM_PATTERN = Pattern.compile(
            "lorem\\s+ipsum"                         // classic two-word opener
                    + "|\\blorem\\b"                 // standalone "lorem"
                    + "|\\bipsum\\b"                 // standalone "ipsum"
                    + "|dolor\\s+sit\\s+amet"        // continuation phrase
                    + "|consectetur\\s+adipiscing",  // further continuation
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Matches common un-replaced template placeholder token styles:
     * <ul>
     *   <li>{@code {{token}}} or {@code {token}} – Handlebars / Mustache / Jinja</li>
     *   <li>{@code [TOKEN]} or {@code [token]} – square-bracket tokens</li>
     *   <li>{@code %7BTOKEN%7D} – URL-encoded curly braces (sent in raw email src)</li>
     *   <li>{@code $$TOKEN$$} – double-dollar delimiters</li>
     *   <li>{@code ${token}} or {@code #{token}} – Spring / FreeMarker style</li>
     *   <li>{@code <TOKEN>} – angle-bracket tokens often used in Word templates</li>
     * </ul>
     */
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile(
            "\\{\\{[^}]{1,100}\\}\\}"              // {{token}}
                    + "|\\{[^{}]{1,100}\\}"         // {token}  (single brace)
                    + "|\\[[A-Z_]{2,60}\\]"         // [UPPER_TOKEN]
                    + "|%7B[^%]{1,100}%7D"          // %7Btoken%7D (URL-encoded)
                    + "|\\$\\$[^$]{1,100}\\$\\$"    // $$TOKEN$$
                    + "|\\$\\{[^}]{1,100}\\}"       // ${token}
                    + "|#\\{[^}]{1,100}\\}"         // #{token}
                    + "|<[A-Z][A-Z_]{1,59}>",       // <UPPER_TOKEN>
            Pattern.CASE_INSENSITIVE
    );

    /** CSS selector used to read the page's visible body text. */
    private static final String BODY_SELECTOR  = "body";
    private static final String TITLE_SELECTOR = "title";

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Creates a {@code ContentValidationRule} with default settings.
     * No external configuration is required.
     */
    public ContentValidationRule() {
        // stateless – nothing to initialise
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
        return "Content Validation is Passed.";
    }

    @Override
    public String failImpact() {
        return "Content Validation is Failed.";
    }

    @Override
    public RuleCategory category() {
        return RuleCategory.CONTENT;
    }

    @Override
    public RuleSeverity severity() {
        return RuleSeverity.HIGH;
    }

    /**
     * Runs all four content checks against the supplied page.
     *
     * @param page live, fully loaded Playwright page; must not be {@code null}
     * @return PASS when all checks pass, FAIL when any finding is detected,
     *         ERROR when page extraction itself fails
     */
    @Override
    public RuleResult execute(final Page page) {
        Objects.requireNonNull(page, "page must not be null");

        final long startMs = System.currentTimeMillis();
        log.info("[{}] Starting content validation on: {}", RULE_ID, safeUrl(page));

        final String titleText;
        final String bodyText;

        try {
            titleText = extractTitle(page);
            bodyText  = extractBodyText(page);
        } catch (final Exception e) {
            log.error("[{}] Failed to extract page content: {}", RULE_ID, e.getMessage(), e);
            return RuleResult.error(this, startMs, e);
        }

        final List<String> findings = new ArrayList<>();

        checkMissingTitle(titleText, findings);
        checkEmptyContent(bodyText,  findings);
        checkLoremIpsum(bodyText,    findings);
        checkPlaceholders(bodyText,  findings);

        if (findings.isEmpty()) {
            log.info("[{}] All content checks passed", RULE_ID);
            final String passFinding = FindingFormatter.structuredFinding(
                    FINDING_TITLE,
                    null,
                    PASS_DETAIL);
            return RuleResult.builder(this, RuleResult.Status.PASS, startMs)
                    .withFindings(List.of(passFinding))
                    .build();
        }

        log.warn("[{}] {} content issue(s) found", RULE_ID, findings.size());
        return RuleResult.builder(this, RuleResult.Status.FAIL, startMs)
                .withFindings(findings)
                .build();
    }

    // -------------------------------------------------------------------------
    // Individual checks
    // -------------------------------------------------------------------------

    /**
     * Check 1 – Missing or blank {@code <title>}.
     *
     * <p>Example output:
     * <pre>
     * Content Validation
     *   Element         : Document Head
     *   Detail          : Page title is missing or blank.
     * </pre>
     */
    private static void checkMissingTitle(
            final String titleText,
            final List<String> findings) {

        if (titleText == null || titleText.isBlank()) {
            final String finding = FindingFormatter.structuredFinding(
                    FINDING_TITLE,
                    ELEMENT_DOCUMENT_HEAD,
                    "Page title is missing or blank.");
            findings.add(finding);
            log.debug("[CONTENT] {}", finding);
        }
    }

    /**
     * Check 2 – Body with insufficient visible text content.
     *
     * <p>Example output:
     * <pre>
     * Content Validation
     *   Element         : Body
     *   Detail          : Page content appears empty or failed to render.
     * </pre>
     */
    private static void checkEmptyContent(
            final String bodyText,
            final List<String> findings) {

        final String stripped = bodyText != null
                ? bodyText.replaceAll("\\s+", "")
                : "";

        if (stripped.length() < MIN_CONTENT_LENGTH) {
            final String finding = FindingFormatter.structuredFinding(
                    FINDING_TITLE,
                    ELEMENT_BODY,
                    "Page content appears empty or failed to render.");
            findings.add(finding);
            log.debug("[CONTENT] {}", finding);
        }
    }

    /**
     * Check 3 – Lorem Ipsum filler text.
     *
     * <p>Example output:
     * <pre>
     * Content Validation
     *   Element         : Paragraph
     *   Detail          : Lorem Ipsum placeholder detected.
     * </pre>
     */
    private static void checkLoremIpsum(
            final String bodyText,
            final List<String> findings) {

        if (bodyText == null || bodyText.isBlank()) return;

        final var matcher = LOREM_IPSUM_PATTERN.matcher(bodyText);
        if (matcher.find()) {
            final String finding = FindingFormatter.structuredFinding(
                    FINDING_TITLE,
                    ELEMENT_PARAGRAPH,
                    "Lorem Ipsum placeholder detected.");
            findings.add(finding);
            log.debug("[CONTENT] {}", finding);
        }
    }

    /**
     * Check 4 – Un-replaced template placeholder tokens.
     * Collects all distinct matches (up to a cap) and reports them together
     * in a single finding's detail text.
     *
     * <p>Example output:
     * <pre>
     * Content Validation
     *   Element         : Body
     *   Detail          : Un-replaced placeholder token(s) detected: {{firstName}}, [LAST_NAME]
     * </pre>
     */
    private static void checkPlaceholders(
            final String bodyText,
            final List<String> findings) {

        if (bodyText == null || bodyText.isBlank()) return;

        final var matcher       = PLACEHOLDER_PATTERN.matcher(bodyText);
        final List<String> hits = new ArrayList<>();
        final int cap           = 10;

        while (matcher.find() && hits.size() < cap) {
            final String token = matcher.group().trim();
            if (!hits.contains(token)) {
                hits.add(token);
            }
        }

        if (hits.isEmpty()) return;

        final String detail = String.format(
                "Un-replaced placeholder token(s) detected: %s%s",
                String.join(", ", hits),
                hits.size() >= cap ? " …" : "");

        final String finding = FindingFormatter.structuredFinding(
                FINDING_TITLE,
                ELEMENT_BODY,
                detail);
        findings.add(finding);
        log.debug("[CONTENT] {}", finding);
    }

    // -------------------------------------------------------------------------
    // Internal – page extraction
    // -------------------------------------------------------------------------

    /**
     * Extracts the text content of the {@code <title>} element.
     * Returns an empty string if the element is absent.
     */
    private static String extractTitle(final Page page) {
        try {
            final var titleEl = page.querySelector(TITLE_SELECTOR);
            if (titleEl == null) return "";
            return titleEl.innerText().trim();
        } catch (final Exception e) {
            log.debug("[{}] Could not extract title element: {}", RULE_ID, e.getMessage());
            return "";
        }
    }

    /**
     * Extracts all visible text from the {@code <body>} using Playwright's
     * {@code innerText} algorithm, which excludes hidden nodes, scripts,
     * and style blocks automatically.
     */
    private static String extractBodyText(final Page page) {
        try {
            final var bodyEl = page.querySelector(BODY_SELECTOR);
            if (bodyEl == null) {
                log.warn("[{}] No <body> element found on page", RULE_ID);
                return "";
            }
            return bodyEl.innerText();
        } catch (final Exception e) {
            log.warn("[{}] Could not extract body text: {}", RULE_ID, e.getMessage());
            return "";
        }
    }

    // -------------------------------------------------------------------------
    // Internal – helpers
    // -------------------------------------------------------------------------

    private static String safeUrl(final Page page) {
        try {
            return page.url();
        } catch (final Exception e) {
            return "<unavailable>";
        }
    }
}