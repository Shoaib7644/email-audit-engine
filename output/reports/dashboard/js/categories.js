"use strict";
/* ================================================================
   categories.js  —  Business category definitions
   Maps technical ruleIds → business category keys.
   Maps category keys → human-readable labels and descriptions.
   No DOM.  No state.
   Depends on: (none)
   Load order: 5 of 11
================================================================ */

var Categories = (function () {

    /* ── Rule-ID → category-key mapping ────────────────────────── */
    var RULE_TO_CATEGORY = {
        "LINK_VALIDATION":       "LINK_VALIDATION",
        "LINK_TEXT_VALIDATION":  "CTA_TRACKING",
        "CTA_VALIDATION":        "CTA_TRACKING",
        "CONTENT_VALIDATION":    "HTML_QUALITY",
        "HEADING_HIERARCHY":     "HTML_QUALITY",
        "DUPLICATE_ID":          "HTML_QUALITY",
        "ALT_TEXT_VALIDATION":   "IMAGE_AUDIT",
        "ACCESSIBILITY_AXE":     "ACCESSIBILITY",
        "PRIVACY_LINK":          "PRIVACY",
        "VIEW_ONLINE_LINK":      "DISCLOSURE",
        "DISCLAIMER_PRESENT":    "DISCLAIMER",
        "BROKEN_ANCHOR":         "UNSUBSCRIBE"
    };

    /* ── Category metadata ──────────────────────────────────────── */
    var ALL_CATEGORIES = [
        {
            key:         "LINK_VALIDATION",
            label:       "Inventory & Inspect Links",
            icon:        "🔗",
            badgeId:     "badge-LINK_VALIDATION",
            description: "Verifies every hyperlink in the email resolves correctly. " +
                "Broken or redirecting links reduce click-through rates and erode subscriber trust.",
            recommendation: "Review each flagged link in your ESP before deployment. " +
                "Ensure all URLs are live, correctly formatted, and point to the intended destination."
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
            description: "Checks every image for a descriptive alt-text attribute. " +
                "Missing alt text breaks the email experience when images are blocked " +
                "and fails accessibility standards.",
            recommendation: "Add descriptive alt text to every image. For decorative images use an empty alt attribute (alt=\"\"). " +
                "Avoid generic labels like 'image' or 'photo'."
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
            key:         "UNSUBSCRIBE",
            label:       "Legal — Unsubscribe Link",
            icon:        "📧",
            badgeId:     "badge-UNSUBSCRIBE",
            description: "Confirms the presence of a functional unsubscribe link in the email footer. " +
                "Required by CAN-SPAM, GDPR, and CASL. Missing or broken unsubscribe links " +
                "expose the organisation to regulatory risk.",
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