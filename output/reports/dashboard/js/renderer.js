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
        LINKS:           { icon: '&#128279;',title: 'Links',                               desc: 'Shows every clickable journey captured from the rendered email during the audit run.' },
        IMAGES:          { icon: '&#128444;',title: 'Images',                              desc: 'Shows every visible rendered image captured from the email, including load status, alt text warnings, and cropped screenshots.' },
        LINK_VALIDATION: { icon: '&#128279;',title: 'Inventory & Inspect Links',            desc: 'Validates all hyperlinks in the email. Broken or missing links prevent recipients from reaching intended destinations.' },
        CTA_TRACKING:    { icon: '&#128073;',title: 'Tracking Links & CTAs',               desc: 'Checks CTA button destinations and link-level tracking parameters for analytics accuracy.' },
        HTML_QUALITY:    { icon: '&#128203;',title: 'Broken HTML Codes',                   desc: 'Scans the email HTML for structural issues such as duplicate element IDs, broken heading sequences, and malformed content that can cause rendering failures across email clients.' },
        IMAGE_AUDIT:     { icon: '&#128444;',title: 'Image Inventory & Rendering Accuracy', desc: 'Verifies image availability, alt text presence, and rendering dimensions to ensure images display correctly in all clients.' },
        URL_DEFENSE:     { icon: '&#128737;',title: 'URL Defense Wrappers Cleanup',         desc: 'Detects URL-defense rewriting wrappers that may break destination links or interfere with click tracking.' },
        ACCESSIBILITY:   { icon: '&#9855;',  title: 'Accessibility',                        desc: 'Summarizes accessibility issues that may affect screen reader users, keyboard navigation, and inclusive campaign quality.' },
        PRIVACY:         { icon: '&#128274;',title: 'Privacy Link Validation',              desc: 'Confirms the Privacy Policy link is present, points to a live destination, and uses the correct macro.' },
        DISCLOSURE:      { icon: '&#128250;',title: 'Disclosure / View-in-Browser',         desc: 'Checks that the View Online / View in Browser link is present and resolves correctly.' },
        DISCLAIMER:      { icon: '&#128221;',title: 'Reply-to Text / Disclaimer',           desc: 'Validates that the legal disclaimer and reply-to text are present and match the approved copy.' },
        UNSUBSCRIBE:     { icon: '&#128231;',title: 'Legal \u2014 Unsubscribe Link',        desc: 'Ensures the unsubscribe mechanism is present, functional, and compliant with CAN-SPAM / GDPR requirements.' },
        HEADER_DETAILS:  { icon: '&#9993;',  title: 'Header / Sender Details',              desc: 'Validates the preheader/preview-text message shown in the inbox preview: no accidental leading/trailing whitespace, proper terminal punctuation, and no unencoded emoji or special characters in the underlying markup.' }
    };

    /* ============================================================
       CATEGORY → RULE ID MAP
    ============================================================ */
    var CATEGORY_RULES = {
        overview:        null,
        LINKS:           null,
        IMAGES:          null,
        LINK_VALIDATION: ['LINK_VALIDATION'],
        CTA_TRACKING:    ['CTA_VALIDATION', 'LINK_TEXT_VALIDATION'],
        HTML_QUALITY:    ['DUPLICATE_ID', 'HEADING_HIERARCHY', 'CONTENT_VALIDATION'],
        IMAGE_AUDIT:     ['ALT_TEXT_VALIDATION'],
        URL_DEFENSE:     ['URL_DEFENSE'],
        ACCESSIBILITY:   ['ACCESSIBILITY_AXE'],
        PRIVACY:         ['PRIVACY_LINK'],
        DISCLOSURE:      ['VIEW_ONLINE_LINK'],
        DISCLAIMER:      ['DISCLAIMER_PRESENT'],
        UNSUBSCRIBE:     ['BROKEN_ANCHOR', 'CTA_VALIDATION'],
        HEADER_DETAILS:  ['PREHEADER_TRIM_VALIDATION', 'PREHEADER_PUNCTUATION_VALIDATION', 'HEADER_EMOJI_ENCODING_VALIDATION']
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
        URL_DEFENSE:          'Remove or update URL defense wrappers that may be breaking destination links.',
        PREHEADER_TRIM_VALIDATION:        'Remove the accidental leading or trailing whitespace from the preheader text in your ESP template.',
        PREHEADER_PUNCTUATION_VALIDATION: 'Add terminal punctuation (a period, exclamation mark, or question mark) to the end of the preheader text.',
        HEADER_EMOJI_ENCODING_VALIDATION: 'Replace the unencoded character(s) in the preheader markup with the correct HTML entity (e.g. &amp;zwnj; instead of a literal zero-width non-joiner).'
    };

    var ACCESSIBILITY_RULE_GUIDANCE = {
        'landmark-one-main': {
            name: 'Missing Main Landmark',
            severity: 'moderate',
            explanation: 'The email does not clearly identify its main content area for assistive technology.',
            recommendation: 'Add one main landmark so screen reader users can quickly find the primary email content.'
        },
        'page-has-heading-one': {
            name: 'Missing Primary Heading',
            severity: 'moderate',
            explanation: 'The email does not include a clear top-level heading.',
            recommendation: 'Add one clear H1 that describes the main message or campaign offer.'
        },
        'region': {
            name: 'Content Outside Landmark',
            severity: 'moderate',
            explanation: 'Some content is outside accessibility landmarks.',
            recommendation: 'Wrap major content sections in meaningful landmarks such as header, main, footer, or nav.'
        },
        'image-alt': {
            name: 'Missing Image Alt Text',
            severity: 'critical',
            explanation: 'Some images do not have text alternatives for screen readers.',
            recommendation: 'Add descriptive alt text so screen readers can describe the image.'
        },
        'color-contrast': {
            name: 'Low Color Contrast',
            severity: 'serious',
            explanation: 'Some text may be difficult to read because foreground and background colors are too similar.',
            recommendation: 'Increase text contrast so copy remains readable for low-vision users.'
        },
        'link-name': {
            name: 'Link Missing Accessible Name',
            severity: 'serious',
            explanation: 'Some links do not expose meaningful names to assistive technology.',
            recommendation: 'Add descriptive link text, aria-label text, or image alt text for linked images.'
        }
    };

    var ACCESSIBILITY_SEVERITY_BY_RULE = {
        'aria-allowed-attr': 'serious',
        'aria-command-name': 'serious',
        'aria-hidden-body': 'critical',
        'aria-hidden-focus': 'serious',
        'aria-input-field-name': 'serious',
        'aria-meter-name': 'serious',
        'aria-progressbar-name': 'serious',
        'aria-required-attr': 'critical',
        'aria-required-children': 'critical',
        'aria-required-parent': 'critical',
        'aria-roles': 'critical',
        'aria-toggle-field-name': 'serious',
        'aria-tooltip-name': 'serious',
        'aria-valid-attr-value': 'critical',
        'aria-valid-attr': 'critical',
        'button-name': 'critical',
        'color-contrast': 'serious',
        'document-title': 'serious',
        'frame-title': 'serious',
        'html-has-lang': 'serious',
        'html-lang-valid': 'serious',
        'image-alt': 'critical',
        'input-button-name': 'critical',
        'input-image-alt': 'critical',
        'label': 'critical',
        'link-name': 'serious',
        'list': 'serious',
        'listitem': 'serious',
        'meta-refresh': 'critical',
        'object-alt': 'serious',
        'role-img-alt': 'serious',
        'svg-img-alt': 'serious',
        'td-headers-attr': 'serious',
        'th-has-data-cells': 'serious',
        'valid-lang': 'serious',
        'video-caption': 'critical'
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
        if (categoryKey === 'LINKS') {
            return _buildLinksAudit(file);
        }
        if (categoryKey === 'IMAGES') {
            return _buildImagesAudit(file);
        }
        if (categoryKey === 'ACCESSIBILITY') {
            return _buildAccessibilityAudit(file);
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
       ACCESSIBILITY AUDIT
    ============================================================ */
    function _buildAccessibilityAudit(file) {
        var rules = Array.isArray(file.rules) ? file.rules : [];
        var rule = rules.find(function (item) { return item.ruleId === 'ACCESSIBILITY_AXE'; }) || null;
        var status = rule ? _normStatus(rule.status) : 'SKIPPED';
        var notExecuted = !rule || status === 'ERROR' || status === 'SKIPPED';
        var findings = rule && Array.isArray(rule.findings) ? rule.findings : [];
        var rows = findings.map(_parseAccessibilityFinding);
        var groups = _groupAccessibilityFindings(rows);
        var severityCounts = _accessibilitySeverityCounts(rows);
        var violationCount = status === 'FAIL' ? rows.length : 0;
        var impactedCount = status === 'FAIL' ? _uniqueAccessibilityElementCount(rows) : 0;
        var score = notExecuted ? 'N/A' : _accessibilityScore(rows);
        var displayStatus = notExecuted ? 'Needs Attention' : _accessibilityDisplayStatus(status, severityCounts);
        var statusClass = notExecuted || displayStatus === 'Needs Attention' ? 'badge-skipped' : _badgeCls(status);
        var reason = _accessibilityNotExecutedReason(rule);

        var statsHtml =
            '<div class="section-label">Accessibility Summary</div>' +
            '<div class="summary-strip strip-links accessibility-summary-strip">' +
            _tile('Overall Status', displayStatus, notExecuted ? 'scan incomplete' : (status === 'PASS' ? 'ready for launch' : 'review required'), notExecuted ? 'tile-skipped' : (status === 'PASS' ? 'tile-passed' : 'tile-failed'), '&#9855;') +
            _tile('Accessibility Score', score, notExecuted ? 'not available' : 'out of 100', status === 'PASS' ? 'tile-passed' : (notExecuted ? 'tile-skipped' : 'tile-warning'), '&#9733;') +
            _tile('Total Issues', violationCount, status === 'FAIL' ? 'items found' : 'none found', status === 'FAIL' ? 'tile-failed' : 'tile-passed', '&#10007;') +
            _tile('Unique Elements Impacted', impactedCount, status === 'FAIL' ? 'need updates' : 'none', status === 'FAIL' ? 'tile-failed' : 'tile-passed', '&#9635;') +
            '</div>' +
            _buildAccessibilitySeverityBreakdown(severityCounts);

        var bodyHtml;
        if (!rule) {
            bodyHtml = _buildAccessibilityNotExecuted('Accessibility rule was not included in this audit run.');
        } else if (notExecuted) {
            bodyHtml = _buildAccessibilityNotExecuted(reason);
        } else if (status === 'PASS') {
            bodyHtml =
                '<div class="pass-all-banner">Accessibility scan completed successfully - 0 issues found.</div>' +
                '<div class="validation-list">' +
                '<div class="val-card">' +
                '<button class="val-card-header" aria-expanded="false">' +
                '<span class="val-card-chevron">&#9654;</span>' +
                '<span class="val-card-name">Accessibility scan completed</span>' +
                '<div class="val-card-right">' +
                '<span class="status-badge badge-pass">PASS</span>' +
                '</div>' +
                '</button>' +
                '<div class="val-card-body">' +
                '<div class="no-findings">No accessibility issues were detected in the rendered email.</div>' +
                '</div>' +
                '</div>' +
                '</div>';
        } else if (groups.length > 0) {
            bodyHtml =
                '<div class="section-label">Accessibility Issues</div>' +
                '<div class="links-panel accessibility-panel">' +
                '<div class="links-table-wrap">' +
                '<table class="links-table accessibility-table">' +
                '<thead><tr>' +
                '<th scope="col">Issue</th>' +
                '<th scope="col">Severity</th>' +
                '<th scope="col">Elements Affected</th>' +
                '<th scope="col">Recommendation</th>' +
                '</tr></thead>' +
                '<tbody>' + groups.map(_buildAccessibilityGroupRow).join('') + '</tbody>' +
                '</table>' +
                '</div>' +
                '</div>';
        } else {
            bodyHtml = _buildAccessibilityNotExecuted('Accessibility result did not include violation details.');
        }

        return (
            '<div class="category-header">' +
            '<div class="category-eyebrow">AUDIT CATEGORY</div>' +
            '<h1 class="category-title">&#9855; Accessibility</h1>' +
            '<p class="category-description">Highlights accessibility issues in business-friendly terms while keeping technical details available for follow-up.</p>' +
            '<div class="accessibility-state"><span class="status-badge ' + statusClass + '">' + _esc(displayStatus) + '</span></div>' +
            '</div>' +
            statsHtml +
            bodyHtml
        );
    }

    function _buildAccessibilityNotExecuted(reason) {
        return (
            '<div class="validation-list">' +
            '<div class="val-card">' +
            '<button class="val-card-header" aria-expanded="true">' +
            '<span class="val-card-chevron">&#9654;</span>' +
            '<span class="val-card-name">Accessibility scan not executed</span>' +
            '<div class="val-card-right">' +
            '<span class="status-badge badge-skipped">NOT EXECUTED</span>' +
            '</div>' +
            '</button>' +
            '<div class="val-card-body" style="display:block">' +
            '<div class="findings-list">' +
            '<div class="finding-item">' +
            '<div class="finding-index">!</div>' +
            '<div class="finding-content">' +
            '<div class="finding-impact">' + _esc(reason || 'Accessibility scan was not executed.') + '</div>' +
            '</div>' +
            '</div>' +
            '</div>' +
            '</div>' +
            '</div>' +
            '</div>'
        );
    }

    function _accessibilityNotExecutedReason(rule) {
        if (!rule) {
            return 'Accessibility rule was not included in this audit run.';
        }
        return rule.errorMessage || rule.businessImpact || 'Accessibility scan was not executed.';
    }

    function _buildAccessibilitySeverityBreakdown(counts) {
        return (
            '<div class="section-label">Severity Breakdown</div>' +
            '<div class="accessibility-severity-grid">' +
            _accessibilitySeverityCard('Critical', counts.critical, 'accessibility-critical') +
            _accessibilitySeverityCard('Serious', counts.serious, 'accessibility-serious') +
            _accessibilitySeverityCard('Moderate', counts.moderate, 'accessibility-moderate') +
            _accessibilitySeverityCard('Minor', counts.minor, 'accessibility-minor') +
            '</div>'
        );
    }

    function _accessibilitySeverityCard(label, count, cls) {
        return (
            '<div class="accessibility-severity-card ' + cls + '">' +
            '<span>' + _esc(label) + '</span>' +
            '<strong>' + _esc(String(count || 0)) + '</strong>' +
            '</div>'
        );
    }

    function _groupAccessibilityFindings(rows) {
        var byRule = {};
        rows.forEach(function (row) {
            var key = row.axeRuleId || row.ruleName || 'accessibility-issue';
            if (!byRule[key]) {
                byRule[key] = {
                    axeRuleId: row.axeRuleId,
                    issue: row.issue,
                    severity: row.severity,
                    explanation: row.explanation,
                    recommendation: row.recommendation,
                    helpUrl: row.helpUrl,
                    reason: row.reason,
                    rows: []
                };
            }
            byRule[key].rows.push(row);
            if (!byRule[key].helpUrl && row.helpUrl) {
                byRule[key].helpUrl = row.helpUrl;
            }
        });

        return Object.keys(byRule).map(function (key) {
            var group = byRule[key];
            group.elements = _uniqueAccessibilityElements(group.rows);
            return group;
        }).sort(function (a, b) {
            var severityDiff = _accessibilitySeverityWeight(b.severity) - _accessibilitySeverityWeight(a.severity);
            if (severityDiff !== 0) { return severityDiff; }
            return b.elements.length - a.elements.length;
        });
    }

    function _buildAccessibilityGroupRow(group) {
        return (
            '<tr>' +
            '<td class="links-cell-text accessibility-issue-cell">' +
            '<div class="accessibility-issue-name">' + _esc(group.issue) + '</div>' +
            '<div class="accessibility-issue-explanation">' + _esc(group.explanation) + '</div>' +
            (group.helpUrl ? '<div class="accessibility-help">' + _linkAnchorWithLabel(group.helpUrl, 'Learn More') + '</div>' : '') +
            '</td>' +
            '<td>' + _accessibilitySeverityBadge(group.severity) + '</td>' +
            '<td class="links-cell-count">' + _esc(String(group.elements.length)) + '</td>' +
            '<td class="links-cell-text">' +
            '<div>' + _esc(group.recommendation) + '</div>' +
            _buildAccessibilityDetails(group) +
            '</td>' +
            '</tr>'
        );
    }

    function _buildAccessibilityDetails(group) {
        var selectors = group.elements.length
            ? '<ul class="accessibility-selector-list">' + group.elements.map(function (element) {
                return '<li><code>' + _esc(element) + '</code></li>';
            }).join('') + '</ul>'
            : '<div class="links-empty">No selector details available.</div>';

        var description = group.reason || 'Original axe description was not provided.';
        var snippet = '<div class="links-empty">HTML snippet is not available in the current report data.</div>';

        return (
            '<details class="accessibility-details">' +
            '<summary>View technical details</summary>' +
            '<div class="accessibility-details-grid">' +
            '<div><dt>Impacted Selectors</dt><dd>' + selectors + '</dd></div>' +
            '<div><dt>Original axe Rule</dt><dd><code>' + _esc(group.axeRuleId || 'unknown') + '</code></dd></div>' +
            '<div><dt>Original Description</dt><dd>' + _esc(description) + '</dd></div>' +
            '<div><dt>Affected HTML Snippet</dt><dd>' + snippet + '</dd></div>' +
            '</div>' +
            '</details>'
        );
    }

    function _accessibilitySeverityBadge(severity) {
        var normalised = _accessibilitySeverity(severity);
        return '<span class="link-status-badge accessibility-severity-badge accessibility-' +
            _esc(normalised) + '">' + _esc(_titleCase(normalised)) + '</span>';
    }

    function _accessibilitySeverityCounts(rows) {
        var counts = { critical: 0, serious: 0, moderate: 0, minor: 0 };
        rows.forEach(function (row) {
            counts[_accessibilitySeverity(row.severity)] += 1;
        });
        return counts;
    }

    function _uniqueAccessibilityElementCount(rows) {
        return _uniqueAccessibilityElements(rows).length;
    }

    function _uniqueAccessibilityElements(rows) {
        var seen = {};
        rows.forEach(function (row) {
            var element = String(row.element || '').trim();
            if (!element || element === '(no nodes)' || element === '(unknown)') {
                return;
            }
            seen[element] = true;
        });
        return Object.keys(seen);
    }

    function _accessibilityScore(rows) {
        if (!rows.length) { return 100; }
        var score = 100;
        rows.forEach(function (row) {
            switch (_accessibilitySeverity(row.severity)) {
                case 'critical': score -= 20; break;
                case 'serious':  score -= 12; break;
                case 'moderate': score -= 7;  break;
                case 'minor':    score -= 3;  break;
                default:         score -= 5;  break;
            }
        });
        return Math.max(0, score);
    }

    function _accessibilityDisplayStatus(status, severityCounts) {
        if (status === 'PASS') { return 'PASS'; }
        if (status === 'FAIL' && ((severityCounts.critical || 0) > 0 || (severityCounts.serious || 0) > 0)) {
            return 'FAIL';
        }
        if (status === 'FAIL') { return 'Needs Attention'; }
        return status || 'Needs Attention';
    }

    function _parseAccessibilityFinding(text) {
        var lines = String(text || '').split(/\r?\n/);
        var title = (lines[0] || 'Accessibility Violation').trim();
        var axeRuleId = title.replace(/^Accessibility Violation\s*-\s*/i, '').trim();
        if (!axeRuleId || axeRuleId === title && /^Accessibility Violation$/i.test(title)) {
            axeRuleId = 'accessibility-issue';
        }
        var guidance = _accessibilityGuidance(axeRuleId);

        return {
            axeRuleId: axeRuleId,
            ruleName: axeRuleId,
            issue: guidance.name,
            severity: guidance.severity,
            explanation: guidance.explanation,
            recommendation: guidance.recommendation,
            element: _findingField(lines, 'Displayed Text') || '(unknown)',
            helpUrl: _findingField(lines, 'Destination'),
            reason: _findingField(lines, 'Reason'),
            raw: String(text || '')
        };
    }

    function _accessibilityGuidance(ruleId) {
        var id = String(ruleId || '').trim();
        var known = ACCESSIBILITY_RULE_GUIDANCE[id];
        if (known) {
            return known;
        }

        var friendly = id && id !== 'accessibility-issue'
            ? _titleCase(id.replace(/-/g, ' '))
            : 'Accessibility Issue';
        return {
            name: friendly,
            severity: ACCESSIBILITY_SEVERITY_BY_RULE[id] || 'moderate',
            explanation: 'This accessibility check found content that may be difficult for some recipients to use.',
            recommendation: 'Review the affected elements and update the email markup or content to meet accessibility best practices.'
        };
    }

    function _accessibilitySeverity(severity) {
        var value = String(severity || '').toLowerCase();
        if (value === 'critical' || value === 'serious' || value === 'moderate' || value === 'minor') {
            return value;
        }
        return 'moderate';
    }

    function _accessibilitySeverityWeight(severity) {
        switch (_accessibilitySeverity(severity)) {
            case 'critical': return 4;
            case 'serious':  return 3;
            case 'moderate': return 2;
            case 'minor':    return 1;
            default:         return 0;
        }
    }

    function _findingField(lines, label) {
        var escapedLabel = label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        var pattern = new RegExp('^\\s*' + escapedLabel + '\\s*:\\s*(.*)$');
        for (var i = 0; i < lines.length; i++) {
            var line = lines[i] || '';
            var match = line.match(pattern);
            if (match) {
                return match[1].trim();
            }
        }
        return '';
    }

    /* ============================================================
       LINKS AUDIT
    ============================================================ */
    function _buildLinksAudit(file) {
        var links = Array.isArray(file.links) ? file.links : [];
        var total = links.length;
        var passed = links.filter(function (link) { return _linkValidationStatus(link) === 'PASS'; }).length;
        var failed = links.filter(function (link) { return _linkValidationStatus(link) === 'FAIL'; }).length;
        var protectedCount = links.filter(function (link) { return _linkValidationStatus(link) === 'PROTECTED'; }).length;
        var skipped = links.filter(function (link) { return _linkValidationStatus(link) === 'SKIPPED'; }).length;
        var screenshots = links.filter(function (link) {
            return link.screenshotPath && String(link.screenshotPath).trim();
        }).length;
        var timedLinks = links.filter(function (link) {
            return typeof link.responseTimeMs === 'number';
        });
        var avgResponse = timedLinks.length
            ? Math.round(timedLinks.reduce(function (sum, link) {
                return sum + Number(link.responseTimeMs || 0);
            }, 0) / timedLinks.length)
            : null;

        var statsHtml =
            '<div class="section-label">Link Audit Summary</div>' +
            '<div class="summary-strip strip-links">' +
            _tile('Clickable Elements', total, 'from rendered email', 'tile-total', '&#128279;') +
            _tile('Passed', passed, 'loaded journeys', 'tile-passed', '&#10003;') +
            _tile('Failed', failed, 'needs review', 'tile-failed', '&#10007;') +
            _tile('Protected', protectedCount, 'edge/WAF challenge', 'tile-warning', '&#9888;') +
            _tile('Skipped', skipped, 'not browser links', 'tile-skipped', '&#8211;') +
            _tile('Screenshots Captured', screenshots, 'destination pages', 'tile-total', '&#9635;') +
            '</div>';

        if (total === 0) {
            return (
                '<div class="category-header">' +
                '<div class="category-eyebrow">AUDIT CATEGORY</div>' +
                '<h1 class="category-title">&#128279; Links</h1>' +
                '<p class="category-description">Shows every clickable element captured from the rendered email and the customer journey validation data from the audit run.</p>' +
                '</div>' +
                statsHtml +
                '<div class="no-results">' +
                '<div class="no-results-icon">&#128279;</div>' +
                '<div class="no-results-title">No clickable elements captured for this email</div>' +
                '<div class="no-results-sub">Older reports or emails without interactive elements may not include structured link journey data.</div>' +
                '</div>'
            );
        }

        return (
            '<div class="category-header">' +
            '<div class="category-eyebrow">AUDIT CATEGORY</div>' +
            '<h1 class="category-title">&#128279; Links</h1>' +
            '<p class="category-description">Shows every clickable element captured from the rendered email and the customer journey validation data from the audit run.</p>' +
            '</div>' +
            statsHtml +
            '<div class="section-label">Clickable Journeys</div>' +
            '<div class="links-panel" data-links-panel>' +
            '<div class="links-toolbar">' +
            '<label class="sr-only" for="links-search">Search links</label>' +
            '<input id="links-search" class="links-search" type="search" placeholder="Search links..." autocomplete="off" />' +
            '<div class="links-page-size">' +
            '<label for="links-page-size">Rows</label>' +
            '<select id="links-page-size" class="links-page-size-select">' +
            '<option value="10">10</option>' +
            '<option value="25">25</option>' +
            '<option value="50">50</option>' +
            '</select>' +
            '</div>' +
            '</div>' +
            '<div class="links-table-wrap">' +
            '<table class="links-table">' +
            '<thead><tr>' +
            _linkSortHeader('index', '#') +
            _linkSortHeader('visibleText', 'Visible Text') +
            _linkSortHeader('finalUrl', 'Destination URL') +
            _linkSortHeader('pageTitle', 'Destination Title') +
            _linkSortHeader('validationStatus', 'Validation') +
            '<th scope="col">Screenshot</th>' +
            _linkSortHeader('reason', 'Notes') +
            '</tr></thead>' +
            '<tbody id="links-table-body"></tbody>' +
            '</table>' +
            '</div>' +
            '<div class="links-pagination" id="links-pagination"></div>' +
            '</div>'
        );
    }

    function _linkSortHeader(key, label) {
        return '<th scope="col"><button class="links-sort-btn" type="button" data-link-sort="' +
            _esc(key) + '">' + _esc(label) + ' <span aria-hidden="true">&#8597;</span></button></th>';
    }

    /* ============================================================
       IMAGES AUDIT
    ============================================================ */
    function _buildImagesAudit(file) {
        var images = Array.isArray(file.images) ? file.images : [];
        var total = images.length;
        var rendered = images.filter(function (image) { return image.rendered && _imageValidationStatus(image) !== 'FAIL'; }).length;
        var failed = images.filter(function (image) { return _imageValidationStatus(image) === 'FAIL'; }).length;
        var warnings = images.filter(function (image) { return _imageValidationStatus(image) === 'WARNING'; }).length;
        var screenshots = images.filter(function (image) {
            return image.screenshotPath && String(image.screenshotPath).trim();
        }).length;

        var statsHtml =
            '<div class="section-label">Image Audit Summary</div>' +
            '<div class="summary-strip strip-5">' +
            _tile('Images Found', total, 'visible assets', 'tile-total', '&#128444;') +
            _tile('Rendered', rendered, 'loaded correctly', 'tile-passed', '&#10003;') +
            _tile('Failed', failed, 'needs review', 'tile-failed', '&#10007;') +
            _tile('Warnings', warnings, 'alt text only', 'tile-warning', '&#9888;') +
            _tile('Screenshots Captured', screenshots, 'cropped images', 'tile-total', '&#9635;') +
            '</div>';

        if (total === 0) {
            return (
                '<div class="category-header">' +
                '<div class="category-eyebrow">AUDIT CATEGORY</div>' +
                '<h1 class="category-title">&#128444; Images</h1>' +
                '<p class="category-description">Shows every visible image captured from the rendered email and the validation data from the audit run.</p>' +
                '</div>' +
                statsHtml +
                '<div class="no-results">' +
                '<div class="no-results-icon">&#128444;</div>' +
                '<div class="no-results-title">No rendered images captured for this email</div>' +
                '<div class="no-results-sub">Older reports or text-only emails may not include structured image validation data.</div>' +
                '</div>'
            );
        }

        return (
            '<div class="category-header">' +
            '<div class="category-eyebrow">AUDIT CATEGORY</div>' +
            '<h1 class="category-title">&#128444; Images</h1>' +
            '<p class="category-description">Shows every visible image captured from the rendered email and the validation data from the audit run.</p>' +
            '</div>' +
            statsHtml +
            '<div class="section-label">Rendered Images</div>' +
            '<div class="links-panel images-panel" data-images-panel>' +
            '<div class="links-toolbar">' +
            '<label class="sr-only" for="images-search">Search images</label>' +
            '<input id="images-search" class="links-search" type="search" placeholder="Search images..." autocomplete="off" />' +
            '<div class="links-page-size">' +
            '<label for="images-page-size">Rows</label>' +
            '<select id="images-page-size" class="links-page-size-select">' +
            '<option value="10">10</option>' +
            '<option value="25">25</option>' +
            '<option value="50">50</option>' +
            '</select>' +
            '</div>' +
            '</div>' +
            '<div class="links-table-wrap">' +
            '<table class="links-table images-table">' +
            '<thead><tr>' +
            _imageSortHeader('index', '#') +
            '<th scope="col">Preview</th>' +
            _imageSortHeader('altText', 'Alt Text') +
            _imageSortHeader('imageUrl', 'Image URL') +
            _imageSortHeader('validationStatus', 'Status') +
            '<th scope="col">Screenshot</th>' +
            _imageSortHeader('notes', 'Notes') +
            '</tr></thead>' +
            '<tbody id="images-table-body"></tbody>' +
            '</table>' +
            '</div>' +
            '<div class="links-pagination" id="images-pagination"></div>' +
            '</div>'
        );
    }

    function _imageSortHeader(key, label) {
        return '<th scope="col"><button class="links-sort-btn" type="button" data-image-sort="' +
            _esc(key) + '">' + _esc(label) + ' <span aria-hidden="true">&#8597;</span></button></th>';
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

    /* ============================================================
       WIRE LINKS TABLE
    ============================================================ */
    function wireLinks(container, file) {
        var panel = container.querySelector('[data-links-panel]');
        if (!panel) { return; }

        var links = Array.isArray(file.links) ? file.links.slice() : [];
        var tbody = panel.querySelector('#links-table-body');
        var search = panel.querySelector('#links-search');
        var pageSize = panel.querySelector('#links-page-size');
        var pager = panel.querySelector('#links-pagination');
        var state = {
            query: '',
            sortKey: 'validationStatus',
            sortDir: 'asc',
            page: 1,
            pageSize: pageSize ? Number(pageSize.value) : 10
        };

        function filteredLinks() {
            var q = state.query.toLowerCase();
            var rows = !q ? links : links.filter(function (link) {
                return [
                    link.visibleText,
                    link.originalUrl,
                    link.finalUrl,
                    link.pageTitle,
                    link.reason,
                    link.validationStatus,
                    link.validationNote,
                    _statusLabel(link)
                ].join(' ').toLowerCase().indexOf(q) >= 0;
            });

            rows.sort(function (a, b) {
                var result = _compareLinkValue(a, b, state.sortKey);
                return state.sortDir === 'asc' ? result : -result;
            });

            return rows;
        }

        function render() {
            var rows = filteredLinks();
            var totalPages = Math.max(1, Math.ceil(rows.length / state.pageSize));
            state.page = Math.min(state.page, totalPages);
            var start = (state.page - 1) * state.pageSize;
            var visibleRows = rows.slice(start, start + state.pageSize);

            tbody.innerHTML = visibleRows.map(_buildLinkRow).join('');
            pager.innerHTML = _buildLinksPager(rows.length, state.page, totalPages);
            _wireLinksPager(pager, state, render);
            _wireLinkScreenshotButtons(tbody, links);
        }

        if (search) {
            search.addEventListener('input', function () {
                state.query = search.value.trim();
                state.page = 1;
                render();
            });
        }

        if (pageSize) {
            pageSize.addEventListener('change', function () {
                state.pageSize = Number(pageSize.value) || 10;
                state.page = 1;
                render();
            });
        }

        panel.querySelectorAll('[data-link-sort]').forEach(function (btn) {
            btn.addEventListener('click', function () {
                var key = btn.dataset.linkSort;
                if (state.sortKey === key) {
                    state.sortDir = state.sortDir === 'asc' ? 'desc' : 'asc';
                } else {
                    state.sortKey = key;
                    state.sortDir = 'asc';
                }
                state.page = 1;
                render();
            });
        });

        render();
    }

    /* ============================================================
       WIRE IMAGES TABLE
    ============================================================ */
    function wireImages(container, file) {
        var panel = container.querySelector('[data-images-panel]');
        if (!panel) { return; }

        var images = Array.isArray(file.images) ? file.images.slice() : [];
        var tbody = panel.querySelector('#images-table-body');
        var search = panel.querySelector('#images-search');
        var pageSize = panel.querySelector('#images-page-size');
        var pager = panel.querySelector('#images-pagination');
        var state = {
            query: '',
            sortKey: 'validationStatus',
            sortDir: 'asc',
            page: 1,
            pageSize: pageSize ? Number(pageSize.value) : 10
        };

        function filteredImages() {
            var q = state.query.toLowerCase();
            var rows = !q ? images : images.filter(function (image) {
                return [
                    image.altText,
                    image.imageUrl,
                    image.validationStatus,
                    image.notes,
                    _imageStatusLabel(image),
                    image.imageType
                ].join(' ').toLowerCase().indexOf(q) >= 0;
            });

            rows.sort(function (a, b) {
                var result = _compareImageValue(a, b, state.sortKey);
                return state.sortDir === 'asc' ? result : -result;
            });

            return rows;
        }

        function render() {
            var rows = filteredImages();
            var totalPages = Math.max(1, Math.ceil(rows.length / state.pageSize));
            state.page = Math.min(state.page, totalPages);
            var start = (state.page - 1) * state.pageSize;
            var visibleRows = rows.slice(start, start + state.pageSize);

            tbody.innerHTML = visibleRows.map(_buildImageRow).join('');
            pager.innerHTML = _buildImagesPager(rows.length, state.page, totalPages);
            _wireLinksPager(pager, state, render);
            _wireImagePreviewButtons(tbody, images);
        }

        if (search) {
            search.addEventListener('input', function () {
                state.query = search.value.trim();
                state.page = 1;
                render();
            });
        }

        if (pageSize) {
            pageSize.addEventListener('change', function () {
                state.pageSize = Number(pageSize.value) || 10;
                state.page = 1;
                render();
            });
        }

        panel.querySelectorAll('[data-image-sort]').forEach(function (btn) {
            btn.addEventListener('click', function () {
                var key = btn.dataset.imageSort;
                if (state.sortKey === key) {
                    state.sortDir = state.sortDir === 'asc' ? 'desc' : 'asc';
                } else {
                    state.sortKey = key;
                    state.sortDir = 'asc';
                }
                state.page = 1;
                render();
            });
        });

        render();
    }

    function _buildImageRow(image) {
        var altText = image.altText && String(image.altText).trim()
            ? image.altText
            : '(No Alt Text)';
        var shot = image.screenshotPath && String(image.screenshotPath).trim()
            ? image.screenshotPath
            : null;
        var thumbPath = image.thumbnailPath || shot;
        var preview = thumbPath
            ? '<button class="image-thumb-btn" type="button" data-image-preview="' + _esc(image.index) + '" aria-label="View image preview">' +
                '<img class="image-thumb" src="' + _esc(_fileSrc(thumbPath)) + '" alt="Image preview" />' +
                '</button>'
            : '<span class="image-thumb-empty">No Preview</span>';
        var screenshotAction = shot
            ? '<button class="links-screenshot-btn" type="button" data-image-preview="' +
                _esc(image.index) + '">View</button>'
            : '<button class="links-screenshot-btn" type="button" disabled>No Screenshot</button>';

        return (
            '<tr>' +
            '<td class="links-cell-count">' + _esc(String(Number(image.index) + 1)) + '</td>' +
            '<td>' + preview + '</td>' +
            '<td class="links-cell-text">' + _esc(altText) + '</td>' +
            '<td class="links-cell-url">' + _linkAnchor(image.imageUrl) + '</td>' +
            '<td>' + _imageValidationBadge(image) + '</td>' +
            '<td>' + screenshotAction + '</td>' +
            '<td class="links-cell-text">' + _esc(_imageNotes(image)) + '</td>' +
            '</tr>'
        );
    }

    function _buildImagesPager(totalRows, page, totalPages) {
        var label = totalRows === 0
            ? 'No images match the current search'
            : 'Page ' + page + ' of ' + totalPages + ' &#8226; ' + totalRows + ' Image' + (totalRows === 1 ? '' : 's');

        return (
            '<span class="links-page-label">' + label + '</span>' +
            '<div class="links-page-actions">' +
            '<button type="button" class="links-page-btn" data-link-page="prev" ' + (page <= 1 ? 'disabled' : '') + '>Previous</button>' +
            '<button type="button" class="links-page-btn" data-link-page="next" ' + (page >= totalPages ? 'disabled' : '') + '>Next</button>' +
            '</div>'
        );
    }

    function _wireImagePreviewButtons(tbody, images) {
        tbody.querySelectorAll('[data-image-preview]').forEach(function (btn) {
            btn.addEventListener('click', function () {
                var index = Number(btn.dataset.imagePreview);
                var image = images.find(function (item) { return Number(item.index) === index; });
                _openImageModal(image || null);
            });
        });
    }

    function _compareImageValue(a, b, key) {
        if (key === 'httpStatus' || key === 'naturalWidth' || key === 'naturalHeight' ||
            key === 'displayWidth' || key === 'displayHeight') {
            var av = a[key] == null ? Number.MAX_SAFE_INTEGER : Number(a[key]);
            var bv = b[key] == null ? Number.MAX_SAFE_INTEGER : Number(b[key]);
            return av - bv;
        }
        if (key === 'index') {
            return Number(a.index || 0) - Number(b.index || 0);
        }

        var left = String(a[key] || '').toLowerCase();
        var right = String(b[key] || '').toLowerCase();
        return left.localeCompare(right);
    }

    function _openImageModal(image) {
        var modal = _ensureImageModal();
        var body = modal.querySelector('.link-modal-body');
        if (!body) { return; }

        if (!image) {
            body.innerHTML = '<div class="screenshot-unavailable"><div class="screenshot-unavailable-msg">No screenshot available.</div></div>';
        } else {
            body.innerHTML = _buildImageModalBody(image);
        }

        modal.removeAttribute('hidden');
        modal.classList.add('is-visible');
        var close = modal.querySelector('[data-image-modal-close]');
        if (close) { close.focus(); }
    }

    function _ensureImageModal() {
        var modal = document.getElementById('image-detail-modal');
        if (modal) { return modal; }

        modal = document.createElement('div');
        modal.id = 'image-detail-modal';
        modal.className = 'link-modal';
        modal.setAttribute('hidden', '');
        modal.innerHTML =
            '<div class="link-modal-backdrop" data-image-modal-close></div>' +
            '<section class="link-modal-card" role="dialog" aria-modal="true" aria-labelledby="image-modal-title">' +
            '<div class="link-modal-header">' +
            '<h2 id="image-modal-title">Image Preview</h2>' +
            '<button class="link-modal-close" type="button" data-image-modal-close aria-label="Close image preview">&times;</button>' +
            '</div>' +
            '<div class="link-modal-body"></div>' +
            '</section>';
        document.body.appendChild(modal);

        modal.addEventListener('click', function (event) {
            if (event.target.hasAttribute('data-image-modal-close')) {
                _closeLinkModal(modal);
            }
        });

        document.addEventListener('keydown', function (event) {
            if (event.key === 'Escape' && modal.classList.contains('is-visible')) {
                _closeLinkModal(modal);
            }
        });

        return modal;
    }

    function _buildImageModalBody(image) {
        var altText = image.altText && String(image.altText).trim()
            ? image.altText
            : '(No Alt Text)';
        var screenshotPath = image.screenshotPath && String(image.screenshotPath).trim()
            ? String(image.screenshotPath).trim()
            : null;
        var previewPath = image.thumbnailPath || screenshotPath;
        var previewHtml = previewPath
            ? '<div class="image-modal-preview-thumb"><img src="' + _esc(_fileSrc(previewPath)) + '" alt="Image preview" /></div>'
            : '<span class="links-empty">No preview available</span>';
        var shotHtml = screenshotPath
            ? '<div class="link-modal-screenshot image-modal-screenshot"><img src="' + _esc(_fileSrc(screenshotPath)) + '" alt="Rendered image screenshot" /></div>'
            : '<div class="screenshot-unavailable"><div class="screenshot-unavailable-msg">No screenshot available.</div></div>';

        return (
            '<div class="link-modal-preview">' +
            '<dl class="link-modal-meta image-modal-meta">' +
            '<div><dt>Image Preview</dt><dd>' + previewHtml + '</dd></div>' +
            '<div><dt>Validation</dt><dd>' + _imageValidationBadge(image) + '</dd></div>' +
            '<div><dt>Alt Text</dt><dd>' + _esc(altText) + '</dd></div>' +
            '<div><dt>HTTP Status</dt><dd>' + _imageHttpStatusBadge(image) + '</dd></div>' +
            '<div class="link-modal-field-full"><dt>Image URL</dt><dd>' + _linkAnchor(image.imageUrl) + '</dd></div>' +
            '</dl>' +
            '<div class="link-modal-screenshot-section">' +
            '<div class="link-modal-screenshot-title">Screenshot</div>' +
            shotHtml +
            '</div>' +
            '</div>'
        );
    }

    function _buildLinkRow(link) {
        var visibleText = link.visibleText && String(link.visibleText).trim()
            ? link.visibleText
            : '(No Visible Text)';
        var destinationTitle = link.pageTitle && String(link.pageTitle).trim()
            ? link.pageTitle
            : 'Unknown';
        var screenshotAction = link.screenshotPath && String(link.screenshotPath).trim()
            ? '<button class="links-screenshot-btn" type="button" data-link-screenshot="' +
                _esc(link.index) + '">View Screenshot</button>'
            : '<button class="links-screenshot-btn" type="button" disabled>No Screenshot</button>';

        return (
            '<tr>' +
            '<td class="links-cell-count">' + _esc(String(Number(link.index) + 1)) + '</td>' +
            '<td class="links-cell-text">' + _esc(visibleText) + '</td>' +
            '<td class="links-cell-url">' + _linkAnchor(link.finalUrl || link.originalUrl) + '</td>' +
            '<td class="links-cell-text">' + _esc(destinationTitle) + '</td>' +
            '<td>' + _validationBadge(link) + '</td>' +
            '<td>' + screenshotAction + '</td>' +
            '<td class="links-cell-text">' + _esc(_linkReason(link)) + '</td>' +
            '</tr>'
        );
    }

    function _linkAnchor(url) {
        if (!url) {
            return '<span class="links-empty">N/A</span>';
        }
        return '<a href="' + _esc(url) + '" target="_blank" rel="noopener noreferrer">' +
            _esc(url) + '</a>';
    }

    function _linkAnchorWithLabel(url, label) {
        if (!url) {
            return '<span class="links-empty">N/A</span>';
        }
        return '<a href="' + _esc(url) + '" target="_blank" rel="noopener noreferrer">' +
            _esc(label || url) + '</a>';
    }

    function _validationBadge(link) {
        var status = _linkValidationStatus(link) || 'SKIPPED';
        var cls = status === 'PASS'
            ? 'link-status-pass'
            : status === 'FAIL'
                ? 'link-status-fail'
                : status === 'PROTECTED'
                    ? 'link-status-protected'
                    : 'link-status-skipped';
        return '<span class="link-status-badge ' + cls + '">' + _esc(status) + '</span>';
    }

    function _httpStatusBadge(link) {
        var code = Number(link.httpStatus);
        if (!code) {
            var note = link.reason || link.validationNote || _linkTypeLabel(link) || 'Not Checked';
            return '<span class="link-status-badge ' + _linkTypeBadgeClass(link) + '">' +
                _esc(note) + '</span>';
        }

        var label = _statusLabel(link);
        var cls = 'link-status-unknown';
        if (code >= 200 && code < 300) { cls = 'link-status-success'; }
        else if (code >= 300 && code < 400) { cls = 'link-status-redirect'; }
        else if (code >= 400 && code < 500) { cls = 'link-status-client'; }
        else if (code >= 500) { cls = 'link-status-server'; }

        return '<span class="link-status-badge ' + cls + '">' + _esc(label) + '</span>';
    }

    function _imageValidationBadge(image) {
        var status = _imageValidationStatus(image);
        var cls = status === 'PASS'
            ? 'link-status-pass'
            : status === 'WARNING'
                ? 'link-status-protected'
                : 'link-status-fail';
        return '<span class="link-status-badge ' + cls + '">' + _esc(status) + '</span>';
    }

    function _imageHttpStatusBadge(image) {
        var code = Number(image.httpStatus);
        if (!code) {
            return '<span class="link-status-badge link-status-unknown">Not Available</span>';
        }

        var label = String(code) + (_reasonPhrase(code) ? ' ' + _reasonPhrase(code) : '');
        var cls = 'link-status-unknown';
        if (code >= 200 && code < 300) { cls = 'link-status-success'; }
        else if (code >= 300 && code < 400) { cls = 'link-status-redirect'; }
        else if (code >= 400 && code < 500) { cls = 'link-status-client'; }
        else if (code >= 500) { cls = 'link-status-server'; }

        return '<span class="link-status-badge ' + cls + '">' + _esc(label) + '</span>';
    }

    function _imageStatusLabel(image) {
        var code = Number(image.httpStatus);
        if (!code) {
            return 'Not Available';
        }
        return String(code) + (_reasonPhrase(code) ? ' ' + _reasonPhrase(code) : '');
    }

    function _imageValidationStatus(image) {
        var status = String(image.validationStatus || '').toUpperCase();
        if (status === 'PASS' || status === 'FAIL' || status === 'WARNING') {
            return status;
        }
        if (image.warning) {
            return 'WARNING';
        }
        return image.rendered && image.imageLoaded && image.screenshotPath ? 'PASS' : 'FAIL';
    }

    function _imageNotes(image) {
        return image.notes || (_imageValidationStatus(image) === 'PASS' ? 'Rendered correctly' : 'Needs review');
    }

    function _statusLabel(link) {
        var code = Number(link.httpStatus);
        if (!code) {
            return link.validationNote || _linkTypeLabel(link) || 'Not Checked';
        }
        var statusText = link.statusText ? String(link.statusText).trim() : _reasonPhrase(code);
        return String(code) + (statusText ? ' ' + statusText : '');
    }

    function _buildLinksPager(totalRows, page, totalPages) {
        var label = totalRows === 0
            ? 'No links match the current search'
            : 'Page ' + page + ' of ' + totalPages + ' &#8226; ' + totalRows + ' Link' + (totalRows === 1 ? '' : 's');

        return (
            '<span class="links-page-label">' + label + '</span>' +
            '<div class="links-page-actions">' +
            '<button type="button" class="links-page-btn" data-link-page="prev" ' + (page <= 1 ? 'disabled' : '') + '>Previous</button>' +
            '<button type="button" class="links-page-btn" data-link-page="next" ' + (page >= totalPages ? 'disabled' : '') + '>Next</button>' +
            '</div>'
        );
    }

    function _wireLinksPager(pager, state, render) {
        pager.querySelectorAll('[data-link-page]').forEach(function (btn) {
            btn.addEventListener('click', function () {
                if (btn.dataset.linkPage === 'prev') {
                    state.page = Math.max(1, state.page - 1);
                } else {
                    state.page += 1;
                }
                render();
            });
        });
    }

    function _wireLinkScreenshotButtons(tbody, links) {
        tbody.querySelectorAll('[data-link-screenshot]').forEach(function (btn) {
            btn.addEventListener('click', function () {
                var index = Number(btn.dataset.linkScreenshot);
                var link = links.find(function (item) { return Number(item.index) === index; });
                _openLinkModal(link || null);
            });
        });
    }

    function _compareLinkValue(a, b, key) {
        if (key === 'httpStatus' || key === 'redirectCount') {
            var av = a[key] == null ? Number.MAX_SAFE_INTEGER : Number(a[key]);
            var bv = b[key] == null ? Number.MAX_SAFE_INTEGER : Number(b[key]);
            return av - bv;
        }
        if (key === 'index') {
            return Number(a.index || 0) - Number(b.index || 0);
        }

        var left = String(a[key] || '').toLowerCase();
        var right = String(b[key] || '').toLowerCase();
        return left.localeCompare(right);
    }

    function _openLinkModal(link) {
        var modal = _ensureLinkModal();
        var body = modal.querySelector('.link-modal-body');
        if (!body) { return; }

        if (!link) {
            body.innerHTML = '<div class="screenshot-unavailable"><div class="screenshot-unavailable-msg">No screenshot available.</div></div>';
        } else {
            body.innerHTML = _buildLinkModalBody(link);
        }

        modal.removeAttribute('hidden');
        modal.classList.add('is-visible');
        var close = modal.querySelector('[data-link-modal-close]');
        if (close) { close.focus(); }
    }

    function _ensureLinkModal() {
        var modal = document.getElementById('link-detail-modal');
        if (modal) { return modal; }

        modal = document.createElement('div');
        modal.id = 'link-detail-modal';
        modal.className = 'link-modal';
        modal.setAttribute('hidden', '');
        modal.innerHTML =
            '<div class="link-modal-backdrop" data-link-modal-close></div>' +
            '<section class="link-modal-card" role="dialog" aria-modal="true" aria-labelledby="link-modal-title">' +
            '<div class="link-modal-header">' +
            '<h2 id="link-modal-title">Landing Page Preview</h2>' +
            '<button class="link-modal-close" type="button" data-link-modal-close aria-label="Close landing page preview">&times;</button>' +
            '</div>' +
            '<div class="link-modal-body"></div>' +
            '</section>';
        document.body.appendChild(modal);

        modal.addEventListener('click', function (event) {
            if (event.target.hasAttribute('data-link-modal-close')) {
                _closeLinkModal(modal);
            }
        });

        document.addEventListener('keydown', function (event) {
            if (event.key === 'Escape' && modal.classList.contains('is-visible')) {
                _closeLinkModal(modal);
            }
        });

        return modal;
    }

    function _closeLinkModal(modal) {
        modal.classList.remove('is-visible');
        modal.setAttribute('hidden', '');
    }

    function _buildLinkModalBody(link) {
        var originalUrl = link.originalUrl || '';
        var finalUrl = link.finalUrl || (_linkType(link) === 'HTTP' ? originalUrl : '');
        var screenshotPath = link.screenshotPath && String(link.screenshotPath).trim()
            ? String(link.screenshotPath).trim()
            : null;
        var responseTime = typeof link.responseTimeMs === 'number'
            ? _fmtDuration(link.responseTimeMs)
            : 'N/A';
        var shotHtml = screenshotPath
            ? '<div class="link-modal-screenshot"><img src="' + _esc(_fileSrc(screenshotPath)) + '" alt="Destination page screenshot" /></div>'
            : '<div class="screenshot-unavailable"><div class="screenshot-unavailable-msg">No screenshot available.</div></div>';

        return (
            '<div class="link-modal-preview">' +
            '<dl class="link-modal-meta">' +
            '<div><dt>Visible Text</dt><dd>' + _esc(link.visibleText || '(No Visible Text)') + '</dd></div>' +
            '<div><dt>Validation</dt><dd>' + _validationBadge(link) + '</dd></div>' +
            '<div><dt>Original URL</dt><dd>' + _linkAnchor(originalUrl) + '</dd></div>' +
            '<div><dt>HTTP Status</dt><dd>' + _httpStatusBadge(link) + '</dd></div>' +
            '<div><dt>Destination URL</dt><dd>' + _linkAnchor(finalUrl) + '</dd></div>' +
            '<div><dt>Response Time</dt><dd><span class="links-empty">' + _esc(responseTime) + '</span></dd></div>' +
            '<div class="link-modal-field-full"><dt>Destination Title</dt><dd>' + (link.pageTitle ? _esc(link.pageTitle) : '<span class="links-empty">Unknown</span>') + '</dd></div>' +
            '</dl>' +
            '<div class="link-modal-screenshot-section">' +
            '<div class="link-modal-screenshot-title">Destination Screenshot</div>' +
            shotHtml +
            '</div>' +
            '</div>'
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

    function _isHttpBand(status, band) {
        var code = Number(status);
        return code >= band && code < band + 100;
    }

    function _linkType(link) {
        return String(link.linkType || '').toUpperCase();
    }

    function _linkTypeLabel(link) {
        switch (_linkType(link)) {
            case 'TEMPLATE_PLACEHOLDER': return 'Template Placeholder';
            case 'TELEPHONE':            return 'Telephone Link';
            case 'MAILTO':               return 'Email Link';
            case 'ANCHOR':               return 'Anchor Link';
            case 'EMPTY_HREF':           return 'Empty Href';
            case 'RELATIVE':             return 'Relative URL';
            case 'INTERACTIVE':          return 'Interactive Element';
            case 'UNSUPPORTED':          return 'Unsupported Link Type';
            case 'HTTP':                 return 'Web Page';
            default:                     return '';
        }
    }

    function _linkValidationStatus(link) {
        var status = String(link.validationStatus || '').toUpperCase();
        if (status === 'PASS' || status === 'FAIL' || status === 'SKIPPED' || status === 'PROTECTED') {
            return status;
        }
        if (_linkType(link) === 'TEMPLATE_PLACEHOLDER') {
            return 'FAIL';
        }
        if (_linkType(link) && _linkType(link) !== 'HTTP') {
            return 'SKIPPED';
        }
        if (Number(link.httpStatus) >= 400) {
            return 'FAIL';
        }
        if (link.screenshotPath) {
            return 'PASS';
        }
        return '';
    }

    function _linkReason(link) {
        var reason = link.reason || link.validationNote || '';
        if (reason) { return reason; }
        if (_linkValidationStatus(link) === 'PASS') {
            return Number(link.redirectCount) > 0
                ? 'Redirect completed successfully'
                : 'Page loaded successfully';
        }
        return _linkTypeLabel(link) || 'Not checked';
    }

    function _linkTypeBadgeClass(link) {
        switch (_linkType(link)) {
            case 'TEMPLATE_PLACEHOLDER': return 'link-status-template';
            case 'TELEPHONE':
            case 'MAILTO':
            case 'ANCHOR':
            case 'RELATIVE':             return 'link-status-nonhttp';
            case 'INTERACTIVE':          return 'link-status-unknown';
            case 'EMPTY_HREF':
            case 'UNSUPPORTED':          return 'link-status-client';
            default:                     return 'link-status-unknown';
        }
    }

    function _reasonPhrase(code) {
        switch (Number(code)) {
            case 100: return 'Continue';
            case 101: return 'Switching Protocols';
            case 200: return 'OK';
            case 201: return 'Created';
            case 202: return 'Accepted';
            case 204: return 'No Content';
            case 301: return 'Moved Permanently';
            case 302: return 'Found';
            case 303: return 'See Other';
            case 304: return 'Not Modified';
            case 307: return 'Temporary Redirect';
            case 308: return 'Permanent Redirect';
            case 400: return 'Bad Request';
            case 401: return 'Unauthorized';
            case 403: return 'Forbidden';
            case 404: return 'Not Found';
            case 405: return 'Method Not Allowed';
            case 408: return 'Request Timeout';
            case 409: return 'Conflict';
            case 410: return 'Gone';
            case 429: return 'Too Many Requests';
            case 500: return 'Internal Server Error';
            case 501: return 'Not Implemented';
            case 502: return 'Bad Gateway';
            case 503: return 'Service Unavailable';
            case 504: return 'Gateway Timeout';
            default:  return '';
        }
    }

    function _titleCase(value) {
        return String(value || '').replace(/\w\S*/g, function (word) {
            return word.charAt(0).toUpperCase() + word.slice(1).toLowerCase();
        });
    }

    function _fileSrc(path) {
        return path.indexOf('://') >= 0 ? path : 'file://' + path.replace(/\\/g, '/');
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
        wireScreenshot:         wireScreenshot,
        wireLinks:              wireLinks,
        wireImages:             wireImages
    };

}());
