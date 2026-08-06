"use strict";
/* ================================================================
   findings.js  —  Finding card and validation section HTML builders
   Returns HTML strings only.  Caller is responsible for insertion.
   Depends on: Utils, Categories
   Load order: 6 of 11
================================================================ */

var Findings = (function () {

    /* ── Single validation card ─────────────────────────────────── */
    function buildValCard(rule, autoExpand) {
        var ruleId      = rule.ruleId;
        var status      = rule.status;
        var severity    = rule.severity;
        var findings    = rule.findings;
        var impact      = rule.businessImpact;
        var badgeCls    = Utils.statusBadgeClass(status);
        var sevCls      = Utils.severityChipClass(severity);
        var isFail      = status === "FAIL" || status === "ERROR";
        var isSkipped   = status === "SKIPPED";
        var findingCnt  = findings.length;

        /* ── Business label: hide raw ruleId from business users ── */
        var cat         = Categories.getCategory(Categories.categoryForRule(ruleId));
        var displayName = cat ? cat.label : (rule.ruleName || ruleId);

        /* ── Terminology: "finding(s)" for FAIL/ERROR, "validation(s)"
           for everything else (PASS/SKIPPED), per requirement 3. The
           count pill itself also picks up a neutral/green modifier
           class when not a failure, per requirement 1. ── */
        var countWord    = isFail ? (findingCnt !== 1 ? 'findings' : 'finding')
            : (findingCnt !== 1 ? 'validations' : 'validation');
        var countPillCls = 'finding-count-pill' + (isFail ? '' : ' finding-count-pill--pass');

        var headerHtml =
            '<button class="val-card-header" aria-expanded="' + autoExpand + '">' +
            '<span class="val-card-chevron" aria-hidden="true">&#9658;</span>' +
            '<span class="val-card-rule-id">' + Utils.esc(ruleId) + '</span>' +
            '<span class="val-card-name">'    + Utils.esc(displayName) + '</span>' +
            '<div class="val-card-right">' +
            (findingCnt > 0
                ? '<span class="' + countPillCls + '">' + findingCnt +
                ' ' + countWord + '</span>'
                : '') +
            (severity
                ? '<span class="severity-chip ' + sevCls + '">' +
                Utils.esc(severity) + '</span>'
                : '') +
            '<span class="status-badge ' + badgeCls + '">' +
            Utils.esc(status) + '</span>' +
            '</div>' +
            '</button>';

        var bodyHtml;

        if (isSkipped) {
            bodyHtml =
                '<div class="no-findings skipped-note">' +
                'This check was not evaluated for this campaign.' +
                (impact
                    ? ' <span style="color:var(--text-secondary);font-weight:400">' +
                    Utils.esc(impact) + '</span>'
                    : '') +
                '</div>';

        } else if (isFail) {
            /* ── FAIL / ERROR — unchanged red alert rendering ─────── */
            if (findings.length === 0 && !impact) {
                bodyHtml = '<div class="no-findings" style="color:var(--red)">Issue detected — no further details available.</div>';
            } else {
                bodyHtml = '<div class="findings-list">';
                if (findings.length > 0) {
                    findings.forEach(function (finding, i) {

                        var findingText = "";
                        var recommendation = "";

                        if (typeof finding === "string") {
                            findingText = finding;
                        } else {
                            findingText = finding.text || "";
                            recommendation = finding.recommendation || "";
                        }

                        bodyHtml +=
                            '<div class="finding-item">' +
                            '<div class="finding-index">#' + (i + 1) + '</div>' +

                            '<div class="finding-content">' +

                            (i === 0 && impact
                                ? '<div class="finding-impact">' +
                                Utils.esc(impact) +
                                '</div>'
                                : '') +

                            '<div class="finding-text">' +
                            Utils.esc(findingText) +
                            '</div>' +

                            (recommendation
                                ? '<div class="finding-recommendation">' +
                                '<div class="finding-rec-label">Recommendation</div>' +
                                '<div class="finding-rec-text">' +
                                Utils.esc(recommendation) +
                                '</div>' +
                                '</div>'
                                : '') +

                            '</div>' +
                            '</div>';
                    });
                } else {
                    bodyHtml +=
                        '<div class="finding-item">' +
                        '<div class="finding-index">#1</div>' +
                        '<div class="finding-content">' +
                        '<div class="finding-impact">' + Utils.esc(impact) + '</div>' +
                        '</div>' +
                        '</div>';
                }
                bodyHtml += '</div>';
            }

        } else if (findings.length === 0) {
            /* ── PASS, no evidence attached — original neutral message ── */
            bodyHtml = '<div class="no-findings">All checks passed — no issues found.</div>';

        } else {
            /* ── PASS with evidence — neutral/green "Validation Details"
               section. No Business Impact, no Recommendation, no
               "finding" wording, no red styling — per requirements
               1, 2, 3, and 4. Recommendation/impact fields on the
               finding objects (if present) are intentionally ignored
               here since both are FAIL/ERROR-only per spec. ── */
            bodyHtml =
                '<div class="findings-list">' +
                '<div class="section-label" style="margin:0 0 8px">Validation Details</div>';

            findings.forEach(function (finding, i) {
                var findingText = (typeof finding === "string") ? finding : (finding.text || "");

                bodyHtml +=
                    '<div class="finding-item finding-item--pass">' +
                    '<div class="finding-index">V' + (i + 1) + '</div>' +
                    '<div class="finding-content">' +
                    '<div class="finding-text">' +
                    Utils.esc(findingText) +
                    '</div>' +
                    '</div>' +
                    '</div>';
            });

            bodyHtml += '</div>';
        }

        return (
            '<div class="val-card' + (autoExpand ? ' expanded' : '') + '">' +
            headerHtml +
            '<div class="val-card-body">' + bodyHtml + '</div>' +
            '</div>'
        );
    }

    /* ── Full validation section for a rule group ───────────────── */
    function buildValidationSection(passedRules, failedRules, errorRules, skippedRules) {
        var html = '<div class="validation-list">';

        var isEmpty =
            passedRules.length  === 0 &&
            failedRules.length  === 0 &&
            errorRules.length   === 0 &&
            skippedRules.length === 0;

        if (isEmpty) {
            html += '<p style="color:var(--text-muted);font-size:13px;padding:16px 0">' +
                'No rules are mapped to this category for the selected email.</p>';
            html += '</div>';
            return html;
        }

        /* Failed — always expanded */
        if (failedRules.length > 0) {
            html += buildGroupLabel('Issues Found (' + failedRules.length + ')', '');
            failedRules.forEach(function (r) { html += buildValCard(r, true); });
        }

        /* Error — always expanded */
        if (errorRules.length > 0) {
            html += buildGroupLabel('Errors (' + errorRules.length + ')', 'margin-top:20px');
            errorRules.forEach(function (r) { html += buildValCard(r, true); });
        }

        /* All-pass banner when nothing failed */
        if (failedRules.length === 0 && errorRules.length === 0 && passedRules.length > 0) {
            html += '<div class="pass-all-banner">All ' + passedRules.length +
                ' check' + (passedRules.length !== 1 ? 's' : '') +
                ' passed — no issues detected.</div>';
        }

        /* Skipped — collapsed */
        if (skippedRules.length > 0) {
            html += buildGroupLabel('Skipped (' + skippedRules.length + ')', 'margin-top:20px');
            skippedRules.forEach(function (r) { html += buildValCard(r, false); });
        }

        /* Passed — collapsed */
        if (passedRules.length > 0 && (failedRules.length > 0 || errorRules.length > 0)) {
            html += buildGroupLabel('Passed (' + passedRules.length + ')', 'margin-top:20px');
            passedRules.forEach(function (r) { html += buildValCard(r, false); });
        }

        html += '</div>';
        return html;
    }

    /* ── Small group divider label ──────────────────────────────── */
    function buildGroupLabel(text, style) {
        return '<div class="section-label" style="' + style + '">' + Utils.esc(text) + '</div>';
    }

    /* ── Wire expand/collapse on all .val-card-header buttons ───── */
    function wireAccordion(container) {
        var headers = container.querySelectorAll('.val-card-header');
        headers.forEach(function (btn) {
            btn.addEventListener('click', function () {
                var card     = btn.closest('.val-card');
                var expanded = card.classList.toggle('expanded');
                btn.setAttribute('aria-expanded', expanded ? 'true' : 'false');
            });
        });
    }

    /* ── Public API ─────────────────────────────────────────────── */
    return {
        buildValCard:            buildValCard,
        buildValidationSection:  buildValidationSection,
        wireAccordion:           wireAccordion
    };

}());