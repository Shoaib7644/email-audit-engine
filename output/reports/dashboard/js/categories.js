"use strict";
/* ================================================================
   categories.js  —  Business category definitions
   Maps technical ruleIds → business category keys.
   Maps category keys → human-readable labels and descriptions.
   No DOM.  No state.
   Depends on: (none)
   Load order: 5 of 11
================================================================ */

/*
 * CHANGES
 * -------
 * 1. BROKEN_ANCHOR moved from "UNSUBSCRIBE" → "LINK_VALIDATION".
 *    BROKEN_ANCHOR validates in-page fragment links (href="#section"
 *    resolving to a matching id on the page) — it has nothing to do with
 *    the unsubscribe link. A broken fragment link (e.g. "Jump to
 *    membership details") was showing up under "Legal — Unsubscribe Link"
 *    while that category simultaneously reported "All checks passed" even
 *    when the actual unsubscribe URL (validated separately by
 *    LINK_VALIDATION) was returning HTTP 404 — a direct contradiction on
 *    the same dashboard. Moved so it groups with LINK_VALIDATION under
 *    "Inventory & Inspect Links", where general link-integrity findings
 *    (including the real unsubscribe-link check) already live.
 *
 * 2. LINK_TEXT_VALIDATION moved from "CTA_TRACKING" → "LINK_VALIDATION".
 *    That rule flags generic anchor text (e.g. "click here"); it isn't a
 *    CTA or tracking-parameter check. Same destination category as #1.
 *
 * 3. IMAGE_SRC_VALIDATION added under "IMAGE_AUDIT". This rule (checks
 *    that every <img> src actually loads) was missing from
 *    RULE_TO_CATEGORY entirely, so categoryForRule() returned null for it
 *    and its findings had no sidebar category to land in. Grouped with
 *    ALT_TEXT_VALIDATION, since together they cover both halves of image
 *    quality: accessible label present, and the image actually renders.
 *
 * These three changes bring this file back in sync with
 * BusinessCategoryMapper.java (used by the Excel export) and
 * ReportSectionMapper.java (used for the dashboard JSON "sections"
 * summary) — all three should now agree on where these rule IDs belong.
 *
 * STILL OPEN: the "UNSUBSCRIBE" category (label "Legal — Unsubscribe
 * Link") now has zero ruleIds mapped to it below — nothing was ever
 * removed from ALL_CATEGORIES, only the mapping was corrected, so the nav
 * item will still render but will always show "no checks in this
 * category" rather than a false PASS. Removing the category entirely
 * would require also touching whatever renders the sidebar nav list
 * (sidebar.js / the dashboard-v2.html template) — I don't have those, so
 * I left the category definition in place rather than guess. Let me know
 * if you want it removed and can share those files.
 */

var Categories = (function () {

    /* ── Rule-ID → category-key mapping ────────────────────────── */
    var RULE_TO_CATEGORY = {
        "LINK_VALIDATION":       "LINK_VALIDATION",
        "LINK_TEXT_VALIDATION":  "LINK_VALIDATION",
        "BROKEN_ANCHOR":         "LINK_VALIDATION",
        "CTA_VALIDATION":        "CTA_TRACKING",
        "CONTENT_VALIDATION":    "HTML_QUALITY",
        "HEADING_HIERARCHY":     "HTML_QUALITY",
        "DUPLICATE_ID":          "HTML_QUALITY",
        "ALT_TEXT_VALIDATION":   "IMAGE_AUDIT",
        "IMAGE_VALIDATION":      "IMAGES",
        "IMAGE_SRC_VALIDATION":  "IMAGE_AUDIT",
        "ACCESSIBILITY_AXE":     "ACCESSIBILITY",
        "PRIVACY_LINK":          "PRIVACY",
        "VIEW_ONLINE_LINK":      "DISCLOSURE",
        "DISCLAIMER_PRESENT":    "DISCLAIMER",
        "PREHEADER_TRIM_VALIDATION":        "HEADER_DETAILS",
        "PREHEADER_PUNCTUATION_VALIDATION": "HEADER_DETAILS",
        "HEADER_EMOJI_ENCODING_VALIDATION": "HEADER_DETAILS"
    };

    /* ── Category metadata ──────────────────────────────────────── */
    var ALL_CATEGORIES = [
        {
            key:         "LINKS",
            label:       "Links",
            icon:        "🔗",
            badgeId:     "badge-LINKS",
            description: "Shows every clickable journey captured from the rendered email along with the validation data captured during the audit run.",
            recommendation: "Review any 4xx or 5xx responses, confirm redirects land on the intended destination, and inspect screenshots when available."
        },
        {
            key:         "IMAGES",
            label:       "Images",
            icon:        "🖼",
            badgeId:     "badge-IMAGES",
            description: "Shows every visible image captured from the rendered email, including load status, alt text warnings, and cropped image screenshots.",
            recommendation: "Review failed or warning images, confirm each creative renders correctly, and verify missing alt attributes before launch."
        },
        {
            key:         "LINK_VALIDATION",
            label:       "Inventory & Inspect Links",
            icon:        "🔗",
            badgeId:     "badge-LINK_VALIDATION",
            description: "Verifies every hyperlink in the email resolves correctly, including " +
                "in-page fragment links, generic/non-descriptive link text, and the mandatory " +
                "unsubscribe link. Broken or unclear links reduce click-through rates, erode " +
                "subscriber trust, and carry compliance risk.",
            recommendation: "Review each flagged link in your ESP before deployment. " +
                "Ensure all URLs are live, correctly formatted, point to the intended " +
                "destination, and that in-page jump links resolve to a real section."
        },
        {
            key:         "CTA_TRACKING",
            label:       "Tracking Links & CTAs",
            icon:        "👆",
            badgeId:     "badge-CTA_TRACKING",
            description: "Audits call-to-action buttons and tracked links for proper UTM parameters, " +
                "meaningful anchor text, and campaign attribution codes.",
            recommendation: "Ensure every CTA has a clear action label (e.g. 'Shop Now') " +
                "and carries the correct campaign tracking parameters for attribution reporting."
        },
        {
            key:         "HTML_QUALITY",
            label:       "Broken HTML Codes",
            icon:        "📋",
            badgeId:     "badge-HTML_QUALITY",
            description: "Scans the email HTML for structural issues such as duplicate element IDs, " +
                "broken heading sequences, and malformed content that can cause rendering failures " +
                "across email clients.",
            recommendation: "Fix duplicate IDs and heading hierarchy issues in your email template. " +
                "Test across Gmail, Outlook, and Apple Mail before sending."
        },
        {
            key:         "IMAGE_AUDIT",
            label:       "Image Inventory & Rendering",
            icon:        "🖼",
            badgeId:     "badge-IMAGE_AUDIT",
            description: "Checks every image for a descriptive alt-text attribute and confirms every " +
                "image src actually resolves and loads. Missing alt text breaks the email experience " +
                "when images are blocked and fails accessibility standards; a broken src renders as a " +
                "missing-image icon for every recipient regardless of alt text.",
            recommendation: "Add descriptive alt text to every image. For decorative images use an empty alt attribute (alt=\"\"). " +
                "Avoid generic labels like 'image' or 'photo'. Verify every image path resolves before sending."
        },
        {
            key:         "URL_DEFENSE",
            label:       "URL Defense Wrappers",
            icon:        "🛡",
            badgeId:     "badge-URL_DEFENSE",
            description: "Detects links that have been double-wrapped by URL-defense security tools " +
                "(e.g. Proofpoint, Mimecast). Double-wrapped URLs produce broken links " +
                "and skew click reporting.",
            recommendation: "Co-ordinate with your IT security team to whitelist campaign domains " +
                "so URL-defense tools do not re-wrap already-tracked links."
        },
        {
            key:         "ACCESSIBILITY",
            label:       "Accessibility Violations",
            icon:        "♿",
            badgeId:     "badge-ACCESSIBILITY",
            description: "Runs an automated accessibility scan (axe-core) against the rendered email. " +
                "Violations include insufficient colour contrast, missing ARIA roles, " +
                "and unlabelled interactive elements.",
            recommendation: "Address WCAG 2.1 AA violations before deployment. " +
                "Pay special attention to colour contrast ratios and keyboard-navigable links."
        },
        {
            key:         "PRIVACY",
            label:       "Privacy Link Validations",
            icon:        "🔒",
            badgeId:     "badge-PRIVACY",
            description: "Confirms the email contains a visible, working link to your organisation's " +
                "privacy policy. Required under GDPR, CASL, and CAN-SPAM regulations.",
            recommendation: "Include a clearly labelled 'Privacy Policy' link in every commercial email. " +
                "Ensure the linked page is publicly accessible and up-to-date."
        },
        {
            key:         "DISCLOSURE",
            label:       "Disclosure / View-in-Browser",
            icon:        "📺",
            badgeId:     "badge-DISCLOSURE",
            description: "Checks for a 'View in browser' or 'View online' link that allows recipients " +
                "to see the email if it fails to render in their client.",
            recommendation: "Add a 'View in browser' link at the top of every email. " +
                "Confirm the hosted version loads correctly and matches the sent email."
        },
        {
            key:         "DISCLAIMER",
            label:       "Reply-to Text / Disclaimer",
            icon:        "📝",
            badgeId:     "badge-DISCLAIMER",
            description: "Validates the presence of a reply-to disclaimer or footer notice " +
                "advising recipients not to reply directly to automated send addresses.",
            recommendation: "Include a disclaimer such as 'This is an automated message – please do not reply.' " +
                "Provide an alternative contact address for support queries."
        },
        {
            key:         "HEADER_DETAILS",
            label:       "Header / Sender Details",
            icon:        "✉",
            badgeId:     "badge-HEADER_DETAILS",
            description: "Validates the preheader/preview-text message shown in the inbox preview: " +
                "no accidental leading/trailing whitespace, proper terminal punctuation, and no " +
                "unencoded emoji or special characters in the underlying markup.",
            recommendation: "Review the preheader text for accidental whitespace or missing " +
                "punctuation, and ensure any emoji or special characters are properly HTML-encoded " +
                "so they render consistently across inbox providers."
        },
        {
            key:         "UNSUBSCRIBE",
            label:       "Legal — Unsubscribe Link",
            icon:        "📧",
            badgeId:     "badge-UNSUBSCRIBE",
            description: "Confirms the presence of a functional unsubscribe link in the email footer. " +
                "Required by CAN-SPAM, GDPR, and CASL. Missing or broken unsubscribe links " +
                "expose the organisation to regulatory risk. NOTE: unsubscribe-link validation is " +
                "actually performed by LINK_VALIDATION and its findings appear under " +
                "\"Inventory & Inspect Links\" — no rule currently maps to this category directly, " +
                "so it will always show as empty rather than reflecting real unsubscribe-link status. " +
                "See the file-level comment above for why this wasn't removed outright.",
            recommendation: "Verify the unsubscribe link is present, visible, and functional. " +
                "Process unsubscribe requests within 10 business days as required by law."
        }
    ];

    /* ── Index for fast lookup ──────────────────────────────────── */
    var _byKey = {};
    ALL_CATEGORIES.forEach(function (cat) { _byKey[cat.key] = cat; });

    /* ── Return ruleIds belonging to a category ─────────────────── */
    function ruleIdsForCategory(categoryKey) {
        var ids = [];
        Object.keys(RULE_TO_CATEGORY).forEach(function (ruleId) {
            if (RULE_TO_CATEGORY[ruleId] === categoryKey) {
                ids.push(ruleId);
            }
        });
        return ids;
    }

    /* ── Map a ruleId to its category key ───────────────────────── */
    function categoryForRule(ruleId) {
        return RULE_TO_CATEGORY[String(ruleId).toUpperCase()] || null;
    }

    /* ── Get category metadata by key ──────────────────────────── */
    function getCategory(key) {
        return _byKey[key] || null;
    }

    /* ── Public API ─────────────────────────────────────────────── */
    return {
        ALL_CATEGORIES:   ALL_CATEGORIES,
        RULE_TO_CATEGORY: RULE_TO_CATEGORY,
        ruleIdsForCategory: ruleIdsForCategory,
        categoryForRule:    categoryForRule,
        getCategory:        getCategory
    };

}());
