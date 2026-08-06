"use strict";
/* ================================================================
   statistics.js  —  Aggregate metric computation
   Pure functions over normalised data.  No DOM.  No state mutations.
   Depends on: Utils, State
   Load order: 4 of 11
================================================================ */

var Statistics = (function () {

    /* ── Per-file rule counts ───────────────────────────────────── */
    function fileRuleCounts(file) {
        if (!file) {
            return { total: 0, passed: 0, failed: 0, errors: 0, skipped: 0 };
        }
        return {
            total:   file.totalRules,
            passed:  file.passedCount,
            failed:  file.failedCount,
            errors:  file.errorCount,
            skipped: file.skippedCount
        };
    }

    /* ── Pass rate (0–100, integer) ─────────────────────────────── */
    function passRate(file) {
        if (!file || file.totalRules === 0) { return 0; }
        return Math.round((file.passedCount / file.totalRules) * 100);
    }

    /* ── Global scorecard (all files combined) ──────────────────── */
    function globalScorecard(files) {
        var totalRules  = 0;
        var passedRules = 0;

        files.forEach(function (f) {
            totalRules  += f.totalRules;
            passedRules += f.passedCount;
        });

        var pct = totalRules > 0 ? Math.round((passedRules / totalRules) * 100) : 0;

        return {
            totalFiles:  files.length,
            passedFiles: files.filter(function (f) { return f.overallStatus === "PASS"; }).length,
            failedFiles: files.filter(function (f) { return f.overallStatus !== "PASS"; }).length,
            totalRules:  totalRules,
            passedRules: passedRules,
            passRate:    pct
        };
    }

    /* ── Rules for a given category key ────────────────────────── */
    function rulesForCategory(file, categoryKey) {
        if (!file) { return []; }
        var ruleIds = Categories.ruleIdsForCategory(categoryKey);
        if (!ruleIds || ruleIds.length === 0) { return file.rules; }
        return file.rules.filter(function (r) {
            return ruleIds.indexOf(r.ruleId) >= 0;
        });
    }

    /* ── Category-level status (worst-case roll-up) ─────────────── */
    function categoryStatus(file, categoryKey) {
        var rules = rulesForCategory(file, categoryKey);
        if (rules.length === 0) { return "SKIPPED"; }
        var hasError  = rules.some(function (r) { return r.status === "ERROR"; });
        var hasFail   = rules.some(function (r) { return r.status === "FAIL";  });
        if (hasError) { return "ERROR"; }
        if (hasFail)  { return "FAIL";  }
        var allSkipped = rules.every(function (r) { return r.status === "SKIPPED"; });
        if (allSkipped) { return "SKIPPED"; }
        return "PASS";
    }

    /* ── Failed count within a category ─────────────────────────── */
    function categoryFailCount(file, categoryKey) {
        return rulesForCategory(file, categoryKey).filter(function (r) {
            return r.status === "FAIL" || r.status === "ERROR";
        }).length;
    }

    /* ── Right-panel category breakdown rows ────────────────────── */
    function categoryBreakdown(file) {
        return Categories.ALL_CATEGORIES.map(function (cat) {
            var rules      = rulesForCategory(file, cat.key);
            var failCount  = rules.filter(function (r) {
                return r.status === "FAIL" || r.status === "ERROR";
            }).length;
            var passCount  = rules.filter(function (r) { return r.status === "PASS"; }).length;
            var status     = categoryStatus(file, cat.key);
            return {
                key:       cat.key,
                label:     cat.label,
                status:    status,
                failCount: failCount,
                passCount: passCount,
                total:     rules.length
            };
        });
    }

    /* ── Public API ─────────────────────────────────────────────── */
    return {
        fileRuleCounts:    fileRuleCounts,
        passRate:          passRate,
        globalScorecard:   globalScorecard,
        rulesForCategory:  rulesForCategory,
        categoryStatus:    categoryStatus,
        categoryFailCount: categoryFailCount,
        categoryBreakdown: categoryBreakdown
    };

}());
