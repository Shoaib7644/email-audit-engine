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
            businessImpact: String(raw.businessImpact  || ""),
            errorMessage:   String(raw.errorMessage    || "")
        };
    }

    /* ── Normalise a single extracted link ─────────────────────── */
    function normaliseLink(raw, index) {
        raw = raw || {};
        return {
            index:          index,
            visibleText:    String(raw.visibleText || "").trim(),
            originalUrl:    String(raw.originalUrl || ""),
            finalUrl:       String(raw.finalUrl || ""),
            linkType:       String(raw.linkType || ""),
            validationNote: String(raw.validationNote || ""),
            validationStatus: _linkValidationStatus(raw),
            reason:         String(raw.reason || raw.validationNote || ""),
            pageTitle:      String(raw.pageTitle || "Unknown"),
            httpStatus:     typeof raw.httpStatus === "number" ? raw.httpStatus : null,
            statusText:     String(raw.statusText || ""),
            redirectCount:  typeof raw.redirectCount === "number" ? raw.redirectCount : null,
            redirectChain:  Array.isArray(raw.redirectChain) ? raw.redirectChain.map(String) : [],
            responseTimeMs: typeof raw.responseTimeMs === "number" ? raw.responseTimeMs : null,
            screenshotPath: raw.screenshotPath ? String(raw.screenshotPath).trim() : null,
            element:        String(raw.element || ""),
            ariaLabel:      String(raw.ariaLabel || ""),
            title:          String(raw.title || ""),
            target:         String(raw.target || ""),
            domIndex:       typeof raw.domIndex === "number" ? raw.domIndex : null,
            bounds:         String(raw.bounds || "")
        };
    }

    /* ── Normalise a single rendered image ────────────────────── */
    function normaliseImage(raw, index) {
        raw = raw || {};
        return {
            index:            index,
            imageUrl:         String(raw.imageUrl || ""),
            altText:          String(raw.altText || ""),
            httpStatus:       typeof raw.httpStatus === "number" ? raw.httpStatus : null,
            validationStatus: _imageValidationStatus(raw),
            warning:          !!raw.warning,
            naturalWidth:     typeof raw.naturalWidth === "number" ? raw.naturalWidth : null,
            naturalHeight:    typeof raw.naturalHeight === "number" ? raw.naturalHeight : null,
            displayWidth:     typeof raw.displayWidth === "number" ? raw.displayWidth : null,
            displayHeight:    typeof raw.displayHeight === "number" ? raw.displayHeight : null,
            imageLoaded:      !!raw.imageLoaded,
            rendered:         !!raw.rendered,
            screenshotPath:   raw.screenshotPath ? String(raw.screenshotPath).trim() : null,
            thumbnailPath:    raw.thumbnailPath ? String(raw.thumbnailPath).trim() : null,
            notes:            String(raw.notes || ""),
            bounds:           String(raw.bounds || ""),
            imageType:        String(raw.imageType || "")
        };
    }

    function _imageValidationStatus(raw) {
        var explicit = String(raw.validationStatus || "").toUpperCase();
        if (explicit === "PASS" || explicit === "FAIL" || explicit === "WARNING") {
            return explicit;
        }
        if (raw.warning) {
            return "WARNING";
        }
        if (raw.screenshotPath && raw.imageLoaded && raw.rendered) {
            return "PASS";
        }
        return "FAIL";
    }

    function _linkValidationStatus(raw) {
        var explicit = String(raw.validationStatus || "").toUpperCase();
        if (explicit === "PASS" || explicit === "FAIL" || explicit === "SKIPPED" || explicit === "PROTECTED") {
            return explicit;
        }

        var type = String(raw.linkType || "").toUpperCase();
        if (type === "TEMPLATE_PLACEHOLDER") {
            return "FAIL";
        }
        if (type && type !== "HTTP") {
            return "SKIPPED";
        }

        if (typeof raw.httpStatus === "number" && raw.httpStatus >= 400) {
            return "FAIL";
        }

        if (raw.screenshotPath) {
            return "PASS";
        }

        return "";
    }

    /* ── Normalise a single file entry ─────────────────────────── */
    function normaliseFile(raw) {
        var rules        = Array.isArray(raw.rules) ? raw.rules.map(normaliseRule) : [];
        var links        = Array.isArray(raw.links) ? raw.links.map(normaliseLink) : [];
        var images       = Array.isArray(raw.images) ? raw.images.map(normaliseImage) : [];
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
            links:         links,
            images:        images,
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
        normaliseRule: normaliseRule,
        normaliseLink: normaliseLink,
        normaliseImage: normaliseImage
    };

}());
