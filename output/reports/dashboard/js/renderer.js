"use strict";
/* ================================================================
   renderer.js  —  HTML string assembly for all views
   ================================================================
   Renders:
     • buildDetailHeader(file)
         Sticky email name + status badge + pass-rate gauge.
         Always shown at top of center panel.

     • buildCategoryView(file, cat, generatedAt, showPreview)
         cat === 'overview'  → email stats tiles + email preview + issues
         cat !== 'overview'  → category title + description + 3 stat tiles
                               + issue list.  NO screenshot / NO preview.

   The Execution Summary (all-run totals) is NO LONGER rendered here.
   It is rendered once by integration.js into #exec-summary-bar
   which lives in the page header above the sidebar + main panel.
================================================================ */

var Renderer = (function () {

    /* ============================================================
       CATEGORY META
    ============================================================ */
    var CATEGORY_META = {
        overview:        { icon: '&#9783;',  title: 'Overview',                            desc: 'Full audit summary for this email campaign.' },
        LINK_VALIDATION: { icon: '&#128279;',title: 'Inventory & Inspect Links',            desc: 'Validates all hyperlinks in the email. Broken or missing links prevent recipients from reaching intended destinations.' },
        CTA_TRACKING:    { icon: '&#128073;',title: 'Tracking Links & CTAs',               desc: 'Checks CTA button destinations and link-level tracking parameters for analytics accuracy.' },
        HTML_QUALITY:    { icon: '&#128203;',title: 'Broken HTML Codes',                   desc: 'Scans the email HTML for structural issues such as duplicate element IDs, broken heading sequences, and malformed content that can cause rendering failures across email clients.' },
        IMAGE_AUDIT:     { icon: '&#128444;',title: 'Image Inventory & Rendering Accuracy', desc: 'Verifies image availability, alt text presence, and rendering dimensions to ensure images display correctly in all clients.' },
        URL_DEFENSE:     { icon: '&#128737;',title: 'URL Defense Wrappers Cleanup',         desc: 'Detects URL-defense rewriting wrappers that may break destination links or interfere with click tracking.' },
        ACCESSIBILITY:   { icon: '&#9855;',  title: 'Accessibility Violations',             desc: 'Runs axe-core checks to identify WCAG 2.1 AA violations that affect screen reader users and keyboard navigation.' },
        PRIVACY:         { icon: '&#128274;',title: 'Privacy Link Validation',              desc: 'Confirms the Privacy Policy link is present, points to a live destination, and uses the correct macro.' },
        DISCLOSURE:      { icon: '&#128250;',title: 'Disclosure / View-in-Browser',         desc: 'Checks that the View Online / View in Browser link is present and resolves correctly.' },
        DISCLAIMER:      { icon: '&#128221;',title: 'Reply-to Text / Disclaimer',           desc: 'Validates that the legal disclaimer and reply-to text are present and match the approved copy.' },
        UNSUBSCRIBE:     { icon: '&#128231;',title: 'Legal \u2014 Unsubscribe Link',        desc: 'Ensures the unsubscribe mechanism is present, functional, and compliant with CAN-SPAM / GDPR requirements.' }
    };

    /* ============================================================
       CATEGORY → RULE ID MAP
    ============================================================ */
    var CATEGORY_RULES = {
        overview:        null,
        LINK_VALIDATION: ['LINK_VALIDATION'],
        CTA_TRACKING:    ['CTA_VALIDATION', 'LINK_TEXT_VALIDATION'],
        HTML_QUALITY:    ['DUPLICATE_ID', 'HEADING_HIERARCHY', 'CONTENT_VALIDATION'],
        IMAGE_AUDIT:     ['ALT_TEXT_VALIDATION'],
        URL_DEFENSE:     ['URL_DEFENSE'],
        ACCESSIBILITY:   ['ACCESSIBILITY_AXE'],
        PRIVACY:         ['PRIVACY_LINK'],
        DISCLOSURE:      ['VIEW_ONLINE_LINK'],
        DISCLAIMER:      ['DISCLAIMER_PRESENT'],
        UNSUBSCRIBE:     ['BROKEN_ANCHOR', 'CTA_VALIDATION']
    };

    /* ============================================================
       RECOMMENDATION MAP
    ============================================================ */
    var RECOMMENDATIONS = {
        PRIVACY_LINK:         'Verify that the Privacy Policy destination URL is correct and accessible.',
        VIEW_ONLINE_LINK:     'Verify the View Online macro and confirm the hosted URL resolves correctly.',
        LINK_VALIDATION:      'Correct the broken hyperlink and ensure the destination URL is live.',
        CTA_VALIDATION:       'Verify the CTA destination URL and review button wording for clarity.',
        LINK_TEXT_VALIDATION: 'Update link text to be descriptive and meaningful for all readers.',
        ALT_TEXT_VALIDATION:  'Add descriptive ALT text to all images for accessibility and deliverability.',
        ACCESSIBILITY_AXE:    'Resolve the flagged accessibility violations to meet WCAG 2.1 AA standards.',
        BROKEN_ANCHOR:        'Correct the broken internal anchor so unsubscribe navigation works reliably.',
        HEADING_HIERARCHY:    'Fix the heading structure so H1\u2192H2\u2192H3 levels are used in the correct order.',
        DUPLICATE_ID:         'Remove or rename duplicate HTML element IDs to prevent rendering issues.',
        CONTENT_VALIDATION:   'Correct all content placeholders before deployment.',
        DISCLAIMER_PRESENT:   'Verify the legal disclaimer is present and matches the approved copy.',
        URL_DEFENSE:          'Remove or update URL defense wrappers that may be breaking destination links.'
    };

    /* ============================================================
       PUBLIC: buildDetailHeader
       Sticky email name + status + pass-rate gauge.
       Rendered above every category view.
    ============================================================ */
    function buildDetailHeader(file) {
        var status = _normStatus(file.overallStatus);
        var rules  = Array.isArray(file.rules) ? file.rules : [];
        var passed = rules.filter(function (r) { return _normStatus(r.status) === 'PASS'; }).length;
        var total  = rules.length;
        var issues = rules.filter(function (r) { var s = _normStatus(r.status); return s === 'FAIL' || s === 'ERROR'; }).length;

        var fileMs = null;
        if      (file.executionTimeMs  != null) { fileMs = Number(file.executionTimeMs); }
        else if (file.durationMs       != null) { fileMs = Number(file.durationMs); }
        else if (file.executionTimeSec != null) { fileMs = Number(file.executionTimeSec) * 1000; }
        else if (file.executionTime    != null) { var rf = Number(file.executionTime); fileMs = rf > 1000 ? rf : rf * 1000; }

        var timePill = (fileMs !== null && !isNaN(fileMs))
            ? '<span class="detail-meta-item">&#9201; ' + _fmtDuration(fileMs) + '</span>'
            : '';

        return (
            '<div class="detail-header">' +
            '<div class="detail-header-left">' +
            '<div class="detail-filename">' + _esc(file.fileName) + '</div>' +
            '<div class="detail-meta">' +
            '<span class="status-badge ' + _badgeCls(status) + '">' + _esc(status) + '</span>' +
            '<span class="detail-meta-item">&#9635; ' + total + ' checks</span>' +
            (issues > 0 ? '<span class="detail-meta-item" style="color:var(--red)">' + issues + ' issues found</span>' : '') +
            timePill +
            '</div>' +
            '</div>' +
            '<div class="detail-header-right">' +
            _buildGauge(passed, total) +
            '</div>' +
            '</div>'
        );
    }

    /* ============================================================
       PUBLIC: buildCategoryView
       showPreview = true only when categoryKey === 'overview'
    ============================================================ */
    function buildCategoryView(file, categoryKey, generatedAt, showPreview) {
        if (categoryKey === 'overview') {
            return _buildOverview(file, showPreview);
        }
        return _buildCategory(file, categoryKey);
    }

    /* ============================================================
       OVERVIEW
       NO Execution Summary here — that lives in the page header.
       Order: Category header → Email stats tiles → Email Preview → Issues
    ============================================================ */
    function _buildOverview(file, showPreview) {
        var rules        = Array.isArray(file.rules) ? file.rules : [];
        var passedCount  = rules.filter(function (r) { return _normStatus(r.status) === 'PASS'; }).length;
        var failedCount  = rules.filter(function (r) { var s = _normStatus(r.status); return s === 'FAIL' || s === 'ERROR'; }).length;
        var skippedCount = rules.filter(function (r) { return _normStatus(r.status) === 'SKIPPED'; }).length;
        var totalRules   = rules.length;

        /* Per-email stats strip */
        var statsHtml =
            '<div class="section-label">Email Audit Statistics</div>' +
            '<div class="summary-strip">' +
            _tile('Total Checks',  totalRules,  'rules evaluated', 'tile-total',   '&#9635;') +
            _tile('Passed',        passedCount, 'no issues',       'tile-passed',  '&#10003;') +
            _tile('Issues Found',  failedCount, 'need attention',  'tile-failed',  '&#10007;') +
            (skippedCount > 0 ? _tile('Skipped', skippedCount, 'not evaluated', 'tile-skipped', '&#9675;') : '') +
            '</div>';

        /* Email preview — overview only */
        var previewHtml = showPreview ? buildScreenshotSection(file) : '';

        /* All rule results */
        var issuesHtml = _buildIssueList(rules, null);

        return (
            '<div class="category-header">' +
            '<div class="category-eyebrow">AUDIT CATEGORY</div>' +
            '<h1 class="category-title">&#9783; Overview</h1>' +
            '<p class="category-description">Full audit summary for this email campaign. Select a category in the left sidebar to drill into specific findings.</p>' +
            '</div>' +
            statsHtml +
            previewHtml +
            issuesHtml
        );
    }

    /* ============================================================
       CATEGORY VIEW  (non-overview)
       NO screenshot. NO email preview.
       Order: Title → Description → 3-tile stats → Issue list
    ============================================================ */
    function _buildCategory(file, categoryKey) {
        var meta     = CATEGORY_META[categoryKey] || { icon: '&#128203;', title: categoryKey, desc: '' };
        var ruleIds  = CATEGORY_RULES[categoryKey] || [];
        var allRules = Array.isArray(file.rules) ? file.rules : [];

        var catRules = allRules.filter(function (r) { return ruleIds.indexOf(r.ruleId) >= 0; });

        var passed = catRules.filter(function (r) { return _normStatus(r.status) === 'PASS'; }).length;
        var failed = catRules.filter(function (r) { var s = _normStatus(r.status); return s === 'FAIL' || s === 'ERROR'; }).length;
        var total  = catRules.length;

        var statsHtml = total > 0
            ? '<div class="summary-strip strip-3">' +
            _tile('Total Checks', total,  'rules evaluated', 'tile-total',  '&#9635;') +
            _tile('Passed',       passed, 'no issues',       'tile-passed', '&#10003;') +
            _tile('Issues Found', failed, 'need attention',  'tile-failed', '&#10007;') +
            '</div>'
            : '';

        var issuesHtml = _buildIssueList(allRules, ruleIds);

        return (
            '<div class="category-header">' +
            '<div class="category-eyebrow">AUDIT CATEGORY</div>' +
            '<h1 class="category-title">' + meta.icon + ' ' + _esc(meta.title) + '</h1>' +
            (meta.desc ? '<p class="category-description">' + _esc(meta.desc) + '</p>' : '') +
            '</div>' +
            statsHtml +
            issuesHtml
        );
    }

    /* ============================================================
       ISSUE LIST
    ============================================================ */
    function _buildIssueList(rules, ruleFilter) {
        var filtered = ruleFilter
            ? rules.filter(function (r) { return ruleFilter.indexOf(r.ruleId) >= 0; })
            : rules;

        if (filtered.length === 0) {
            return (
                '<div class="no-results">' +
                '<div class="no-results-icon">&#10003;</div>' +
                '<div class="no-results-title">No checks found for this category</div>' +
                '<div class="no-results-sub">This email may not include rules mapped to this section.</div>' +
                '</div>'
            );
        }

        var sorted = filtered.slice().sort(function (a, b) {
            return _sortWeight(_normStatus(a.status)) - _sortWeight(_normStatus(b.status));
        });

        var anyFail = sorted.some(function (r) { var s = _normStatus(r.status); return s === 'FAIL' || s === 'ERROR'; });
        var banner  = !anyFail
            ? '<div class="pass-all-banner">All checks passed — no issues found in this category.</div>'
            : '';

        var cards = sorted.map(function (rule) { return _buildIssueCard(rule); }).join('');

        return (
            '<div class="section-label">Validation Results</div>' +
            banner +
            '<div class="validation-list">' + cards + '</div>'
        );
    }

    function _sortWeight(status) {
        if (status === 'FAIL' || status === 'ERROR') { return 0; }
        if (status === 'PASS')                       { return 1; }
        return 2;
    }

    /* ============================================================
       ISSUE CARD
    ============================================================ */
    function _buildIssueCard(rule) {
        var ruleId   = rule.ruleId        || '';
        var ruleName = rule.ruleName      || ruleId;
        var status   = _normStatus(rule.status);
        var severity = rule.severity      || '';
        var findings = Array.isArray(rule.findings) ? rule.findings : [];
        var impact   = rule.businessImpact || '';
        var isFail   = (status === 'FAIL' || status === 'ERROR');
        var rec      = RECOMMENDATIONS[ruleId] || null;

        // A card has evidence to show whenever findings exist (PASS or FAIL) or
        // there's a businessImpact note — independent of pass/fail status.
        var hasFindings = findings.length > 0;
        var shouldExpand = isFail || (status === 'PASS' && hasFindings);

        var sevHtml = severity
            ? '<span class="severity-chip ' + _sevCls(severity) + '">' + _esc(severity) + '</span>'
            : '';

        var headerHtml =
            '<button class="val-card-header" aria-expanded="' + shouldExpand + '">' +
            '<span class="val-card-chevron">&#9654;</span>' +
            '<span class="val-card-name">' + _esc(ruleName) + '</span>' +
            '<div class="val-card-right">' +
            (function () {

                if (findings.length === 0) return '';

                if (isFail) {
                    return '<span class="finding-count-pill">' +
                        findings.length +
                        ' finding' +
                        (findings.length !== 1 ? 's' : '') +
                        '</span>';
                }

                return '<span class="finding-count-pill finding-count-pill--pass">' +
                    findings.length +
                    ' validation' +
                    (findings.length !== 1 ? 's' : '') +
                    '</span>';

            })()+
            sevHtml +
            '<span class="status-badge ' + _badgeCls(status) + '">' + _esc(status) + '</span>' +
            '</div>' +
            '</button>';

        var bodyHtml;
        if (isFail) {
            // existing FAIL rendering
            // DO NOT CHANGE
            bodyHtml = '<div class="findings-list">';

            if (impact) {
                bodyHtml +=
                    '<div class="finding-item">' +
                    '<div class="finding-index">#&#9889;</div>' +
                    '<div class="finding-content">' +
                    '<div class="finding-impact">' + _esc(impact) + '</div>' +
                    '</div>' +
                    '</div>';
            }

            findings.forEach(function (text, i) {
                bodyHtml +=
                    '<div class="finding-item">' +
                    '<div class="finding-index">#' + (i + 1) + '</div>' +
                    '<div class="finding-content">' +
                    '<div class="finding-text">' + _esc(text) + '</div>' +
                    '</div>' +
                    '</div>';
            });

            if (rec) {
                bodyHtml +=
                    '<div class="finding-recommendation">' +
                    '<span class="finding-rec-label">&#128161; Recommendation</span>' +
                    '<span class="finding-rec-text">' + _esc(rec) + '</span>' +
                    '</div>';
            }

            bodyHtml += '</div>';

        } else if (status === 'PASS' && findings.length > 0) {
            // PASS rendering (Fix 3, Fix 4, and Fix 5 applied: No impact, no recommendation)
            bodyHtml =
                '<div class="findings-list">' +
                '<div class="section-label" style="margin:0 0 8px">Validation Details</div>';

            findings.forEach(function(text, i){
                bodyHtml +=
                    '<div class="finding-item finding-item--pass">' +
                    '<div class="finding-index">V' + (i+1) + '</div>' +
                    '<div class="finding-content">' +
                    '<div class="finding-text">' +
                    _esc(text) +
                    '</div>' +
                    '</div>' +
                    '</div>';
            });

            bodyHtml += '</div>';

        } else {
            // Only reached when a PASS/SKIPPED rule has zero findings — i.e. genuinely nothing to show.
            bodyHtml = '<div class="no-findings">All checks passed for this rule.</div>';
        }

        return (
            '<div class="val-card' + (shouldExpand ? ' expanded' : '') + '">' +
            headerHtml +
            '<div class="val-card-body">' + bodyHtml + '</div>' +
            '</div>'
        );
    }

    /* ============================================================
       SCREENSHOT SECTION  (overview only — called from _buildOverview)
    ============================================================ */
    function buildScreenshotSection(file) {
        var rawPath = file.screenshotPath;
        var path    = (rawPath && String(rawPath).trim()) ? String(rawPath).trim() : null;
        var content;

        if (path) {
            var src = path.indexOf('://') >= 0 ? path : 'file://' + path.replace(/\\/g, '/');
            content =
                '<div class="screenshot-img-wrap" style="max-height:480px;overflow:hidden;position:relative">' +
                '<img class="screenshot-img" src="' + _esc(src) + '" alt="Email preview: ' + _esc(file.fileName) + '" onerror="Renderer.handleScreenshotError(this)"/>' +
                '<div class="screenshot-expand-btn">' +
                '<button class="screenshot-expand-trigger">View full preview &#8595;</button>' +
                '</div>' +
                '</div>';
        } else {
            content = _screenshotUnavailable();
        }

        return (
            '<div class="screenshot-section">' +
            '<div class="section-label">Email Preview</div>' +
            '<div class="screenshot-frame">' +
            '<div class="screenshot-toolbar">' +
            '<span class="screenshot-dot" style="background:#f85149"></span>' +
            '<span class="screenshot-dot" style="background:#d29922"></span>' +
            '<span class="screenshot-dot" style="background:#3fb950"></span>' +
            '<span class="screenshot-url">' + _esc(file.fileName) + '</span>' +
            '</div>' +
            content +
            '</div>' +
            '</div>'
        );
    }

    function _screenshotUnavailable() {
        return (
            '<div class="screenshot-unavailable">' +
            '<div class="screenshot-unavailable-icon">&#9635;</div>' +
            '<div class="screenshot-unavailable-msg">Screenshot unavailable</div>' +
            '<div class="screenshot-unavailable-sub">No screenshot was captured for this email.</div>' +
            '</div>'
        );
    }

    function handleScreenshotError(img) {
        var wrap = img.closest('.screenshot-img-wrap');
        if (wrap) { wrap.innerHTML = _screenshotUnavailable(); }
    }

    /* ============================================================
       WIRE SCREENSHOT EXPAND
    ============================================================ */
    function wireScreenshot(container) {
        var btn = container.querySelector('.screenshot-expand-trigger');
        if (!btn) { return; }
        btn.addEventListener('click', function () {
            var wrap    = container.querySelector('.screenshot-img-wrap');
            var overlay = container.querySelector('.screenshot-expand-btn');
            if (wrap)    { wrap.style.maxHeight = 'none'; wrap.style.overflow = 'visible'; }
            if (overlay) { overlay.style.display = 'none'; }
        });
    }

    /* ============================================================
       GAUGE
    ============================================================ */
    function _buildGauge(passed, total) {
        var pct    = total > 0 ? Math.round((passed / total) * 100) : 0;
        var r      = 31;
        var circ   = 2 * Math.PI * r;
        var offset = circ - (pct / 100) * circ;
        var color  = pct >= 80 ? 'var(--green)' : (pct >= 50 ? 'var(--amber)' : 'var(--red)');

        return (
            '<div class="gauge-wrap">' +
            '<svg class="gauge-svg" width="80" height="80" viewBox="0 0 80 80" aria-hidden="true">' +
            '<circle class="gauge-track" cx="40" cy="40" r="' + r + '"/>' +
            '<circle class="gauge-arc" cx="40" cy="40" r="' + r + '" stroke="' + color + '" stroke-dasharray="' + circ.toFixed(3) + '" stroke-dashoffset="' + offset.toFixed(3) + '"/>' +
            '</svg>' +
            '<div class="gauge-label">' +
            '<div class="gauge-pct" style="color:' + color + '">' + pct + '%</div>' +
            '<div class="gauge-sub">pass rate</div>' +
            '</div>' +
            '</div>'
        );
    }

    /* ============================================================
       TILE helper
    ============================================================ */
    function _tile(label, value, sub, modifier, icon) {
        return (
            '<div class="summary-tile ' + modifier + '">' +
            '<div class="summary-tile-icon" aria-hidden="true">' + icon + '</div>' +
            '<div class="summary-tile-value">' + value + '</div>' +
            '<div class="summary-tile-label">' + _esc(label) + '</div>' +
            '<div class="summary-tile-sub">' + _esc(sub) + '</div>' +
            '</div>'
        );
    }

    /* ============================================================
       UTILITIES
    ============================================================ */
    function _normStatus(status) {
        if (!status) { return 'SKIPPED'; }
        switch (String(status).toUpperCase()) {
            case 'PASS': case 'PASSED': case 'SUCCESS': return 'PASS';
            case 'FAIL': case 'FAILED':                 return 'FAIL';
            case 'ERROR':                               return 'ERROR';
            default:                                    return 'SKIPPED';
        }
    }

    function _badgeCls(status) {
        switch (_normStatus(status)) {
            case 'PASS':  return 'badge-pass';
            case 'FAIL':  return 'badge-fail';
            case 'ERROR': return 'badge-error';
            default:      return 'badge-skipped';
        }
    }

    function _sevCls(sev) {
        if (!sev) { return 'sev-info'; }
        switch (String(sev).toUpperCase()) {
            case 'CRITICAL': return 'sev-critical';
            case 'HIGH':     return 'sev-high';
            case 'MEDIUM':   return 'sev-medium';
            case 'LOW':      return 'sev-low';
            default:         return 'sev-info';
        }
    }

    function _fmtDuration(ms) {
        ms = Math.round(ms);
        if (ms < 1000) { return ms + ' ms'; }
        var s = Math.floor(ms / 1000), rem = ms % 1000;
        if (s < 60) { return s + 's' + (rem > 0 ? ' ' + rem + 'ms' : ''); }
        var m = Math.floor(s / 60), rs = s % 60;
        if (m < 60) { return m + 'm' + (rs > 0 ? ' ' + rs + 's' : ''); }
        var h = Math.floor(m / 60), rm = m % 60;
        return h + 'h' + (rm > 0 ? ' ' + rm + 'm' : '') + (rs > 0 ? ' ' + rs + 's' : '');
    }

    function _esc(v) {
        if (v == null) { return ''; }
        return String(v)
            .replace(/&/g,  '&amp;')
            .replace(/</g,  '&lt;')
            .replace(/>/g,  '&gt;')
            .replace(/"/g,  '&quot;')
            .replace(/'/g,  '&#039;');
    }

    /* ============================================================
       PUBLIC API
    ============================================================ */
    return {
        buildDetailHeader:      buildDetailHeader,
        buildCategoryView:      buildCategoryView,
        buildScreenshotSection: buildScreenshotSection,
        handleScreenshotError:  handleScreenshotError,
        wireScreenshot:         wireScreenshot
    };

}());