"use strict";
/* ================================================================
   sidebar.js  —  Left navigation sidebar
   ================================================================
   Responsibilities:
     • wireNavButtons()        wire category nav click handlers
     • setActiveCategory(key)  highlight the active nav button
     • updateBadges(file)      show per-category fail counts
                               (counts filtered by category ruleIds —
                               NOT the total email fail count)
     • updateScorecard(file)   update Passed/Failed KPIs + bar
     • reset()                 clear badges and scorecard

   FIX: updateBadges() now filters file.rules by each category's
   rule IDs before counting failures.  Previously it was putting
   the total email fail count on every badge, so every category
   showed the same wrong number (e.g. 9 on URL Defense Wrappers
   even when that category had 0 matching rules).
================================================================ */

var Sidebar = (function () {

    /* ============================================================
       CATEGORY → RULE ID MAP
       Must stay in sync with renderer.js CATEGORY_RULES.
       The badge for each nav button is calculated by filtering
       file.rules to only rules in this list, then counting FAILs.
    ============================================================ */
    var CATEGORY_RULES = {
        overview:        null,                                        /* special: show total */
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
       wireNavButtons
       Attaches click handlers to every .nav-btn[data-category].
       On click: updates State.selectedCategory which triggers
       Integration._onCategoryChange → re-render.
    ============================================================ */
    function wireNavButtons() {
        var btns = document.querySelectorAll('.nav-btn[data-category]');
        btns.forEach(function (btn) {
            btn.addEventListener('click', function () {
                var cat = btn.dataset.category;
                if (!cat) { return; }
                State.set('selectedCategory', cat);
            });
        });
    }

    /* ============================================================
       setActiveCategory
       Marks the matching nav button as aria-current="true" and
       removes it from all others.
    ============================================================ */
    function setActiveCategory(categoryKey) {
        var btns = document.querySelectorAll('.nav-btn[data-category]');
        btns.forEach(function (btn) {
            var isActive = btn.dataset.category === categoryKey;
            btn.setAttribute('aria-current', isActive ? 'true' : 'false');
        });
    }

    /* ============================================================
       updateBadges
       Called whenever the selected email changes.
       Each badge shows the number of FAIL/ERROR rules that belong
       to that category — NOT the total email fail count.

       Logic per category:
         1. Filter file.rules to only ruleIds in CATEGORY_RULES[cat]
         2. Count how many have status FAIL or ERROR
         3. If count > 0  → show red badge with count
            If count === 0 and category has matching rules → green ✓
            If no matching rules at all → clear badge (hide it)
    ============================================================ */
    function updateBadges(file) {
        var allRules = Array.isArray(file.rules) ? file.rules : [];

        Object.keys(CATEGORY_RULES).forEach(function (cat) {
            var badgeEl = document.getElementById('badge-' + cat);
            if (!badgeEl) { return; }

            /* Overview: show total email fail count */
            if (cat === 'overview') {
                var totalFail = allRules.filter(function (r) {
                    var s = _normStatus(r.status);
                    return s === 'FAIL' || s === 'ERROR';
                }).length;

                if (totalFail > 0) {
                    badgeEl.textContent  = totalFail;
                    badgeEl.className    = 'nav-badge nav-badge--fail';
                } else {
                    badgeEl.textContent  = '';
                    badgeEl.className    = 'nav-badge nav-badge--pass';
                    badgeEl.textContent  = '✓';
                }
                return;
            }

            /* All other categories: filter to category rule IDs only */
            var ruleIds  = CATEGORY_RULES[cat];
            var catRules = allRules.filter(function (r) {
                return ruleIds.indexOf(r.ruleId) >= 0;
            });

            if (catRules.length === 0) {
                /* No rules for this category in this email — hide badge */
                badgeEl.textContent = '';
                badgeEl.className   = 'nav-badge';
                return;
            }

            var failCount = catRules.filter(function (r) {
                var s = _normStatus(r.status);
                return s === 'FAIL' || s === 'ERROR';
            }).length;

            if (failCount > 0) {
                badgeEl.textContent = failCount;
                badgeEl.className   = 'nav-badge nav-badge--fail';
            } else {
                /* All passing for this category */
                badgeEl.textContent = '✓';
                badgeEl.className   = 'nav-badge nav-badge--pass';
            }
        });
    }

    /* ============================================================
       updateScorecard
       Updates the Passed / Failed KPI tiles and progress bar
       in the top of the left sidebar.
       These show per-email rule counts (not category counts).
    ============================================================ */
    function updateScorecard(file) {
        var rules   = Array.isArray(file.rules) ? file.rules : [];
        var passed  = rules.filter(function (r) { return _normStatus(r.status) === 'PASS'; }).length;
        var failed  = rules.filter(function (r) {
            var s = _normStatus(r.status);
            return s === 'FAIL' || s === 'ERROR';
        }).length;
        var total   = rules.length;
        var rate    = total > 0 ? Math.round((passed / total) * 100) : 0;
        var barColor = rate >= 80 ? 'var(--green)' : rate >= 50 ? 'var(--amber)' : 'var(--red)';

        _setText('sc-passed', passed);
        _setText('sc-failed', failed);
        _setText('sc-pct',    rate + '%');

        var fill = document.getElementById('sc-bar-fill');
        var bar  = document.getElementById('sc-bar');
        if (fill) {
            fill.style.width      = rate + '%';
            fill.style.background = barColor;
        }
        if (bar) {
            bar.setAttribute('aria-valuenow', rate);
        }
    }

    /* ============================================================
       reset
       Called when no email is selected (e.g. on empty state).
       Clears all badges and resets scorecard to dashes.
    ============================================================ */
    function reset() {
        /* Clear all badges */
        var badges = document.querySelectorAll('.nav-badge');
        badges.forEach(function (b) {
            b.textContent = '';
            b.className   = 'nav-badge';
        });

        /* Reset scorecard */
        _setText('sc-passed', '–');
        _setText('sc-failed', '–');
        _setText('sc-pct',    '–');

        var fill = document.getElementById('sc-bar-fill');
        if (fill) { fill.style.width = '0%'; fill.style.background = 'var(--border)'; }
    }

    /* ============================================================
       PRIVATE HELPERS
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

    function _setText(id, value) {
        var el = document.getElementById(id);
        if (el) { el.textContent = String(value); }
    }

    /* ============================================================
       PUBLIC API
    ============================================================ */
    return {
        wireNavButtons:    wireNavButtons,
        setActiveCategory: setActiveCategory,
        updateBadges:      updateBadges,
        updateScorecard:   updateScorecard,
        reset:             reset
    };

}());