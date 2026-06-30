"use strict";
/* ================================================================
   parser.js  —  Raw data → normalised internal model
   Depends on: Utils
   Load order: 2 of 11
================================================================ */

var Parser = (function () {

    /* ── Normalise a single rule ────────────────────────────────── */
    function normaliseRule(raw) {
        return {
            ruleId:         String(raw.ruleId         || ""),
            ruleName:       String(raw.ruleName        || raw.ruleId || ""),
            status:         Utils.normaliseStatus(raw.status),
            severity:       String(raw.severity        || "INFO").toUpperCase(),
            findings:       Array.isArray(raw.findings) ? raw.findings.map(String) : [],
            businessImpact: String(raw.businessImpact  || "")
        };
    }

    /* ── Normalise a single file entry ─────────────────────────── */
    function normaliseFile(raw) {
        var rules        = Array.isArray(raw.rules) ? raw.rules.map(normaliseRule) : [];
        var passedRules  = rules.filter(function (r) { return r.status === "PASS"; });
        var failedRules  = rules.filter(function (r) { return r.status === "FAIL"; });
        var errorRules   = rules.filter(function (r) { return r.status === "ERROR"; });
        var skippedRules = rules.filter(function (r) { return r.status === "SKIPPED"; });

        return {
            fileName:      String(raw.fileName      || "Unnamed"),
            overallStatus: Utils.normaliseStatus(raw.overallStatus),
            failedChecks:  typeof raw.failedChecks === "number" ? raw.failedChecks : failedRules.length,
            screenshotPath: raw.screenshotPath ? String(raw.screenshotPath).trim() : null,
            rules:         rules,
            /* pre-partitioned for fast access */
            passedRules:   passedRules,
            failedRules:   failedRules,
            errorRules:    errorRules,
            skippedRules:  skippedRules,
            totalRules:    rules.length,
            passedCount:   passedRules.length,
            failedCount:   failedRules.length + errorRules.length,
            errorCount:    errorRules.length,
            skippedCount:  skippedRules.length
        };
    }

    /* ── Parse the root payload ─────────────────────────────────── */
    function parse(raw) {
        if (!raw || typeof raw !== "object") {
            throw new Error("DASHBOARD_DATA is not a valid object.");
        }

        var files = Array.isArray(raw.files) ? raw.files.map(normaliseFile) : [];

        return {
            passedFiles:  typeof raw.passedFiles === "number" ? raw.passedFiles : 0,
            failedFiles:  typeof raw.failedFiles === "number" ? raw.failedFiles : 0,
            generatedAt:  raw.generatedAt ? String(raw.generatedAt) : null,
            files:        files
        };
    }

    /* ── Public API ─────────────────────────────────────────────── */
    return {
        parse:         parse,
        normaliseFile: normaliseFile,
        normaliseRule: normaliseRule
    };

}());
