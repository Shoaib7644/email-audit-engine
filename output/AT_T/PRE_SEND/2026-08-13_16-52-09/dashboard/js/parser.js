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

    /* ── Normalise campaign validation payload ────────────────── */
    function normaliseCampaignValidation(raw) {
        raw = raw || {};
        var rows = Array.isArray(raw.rows) ? raw.rows.map(function (row, index) {
            row = row || {};
            return {
                index:       typeof row.index === "number" ? row.index : index + 1,
                identifier:  String(row.identifier || ""),
                type:        String(row.type || ""),
                actualType:  String(row.actualType || ""),
                expectedUrl: String(row.expectedUrl || ""),
                actualUrl:   String(row.actualUrl || ""),
                visibleText: String(row.visibleText || ""),
                expectedLabel: String(row.expectedLabel || ""),
                actualLabel: String(row.actualLabel || ""),
                expectedCategory: String(row.expectedCategory || ""),
                actualCategory: String(row.actualCategory || ""),
                expectedTracking: Array.isArray(row.expectedTracking) ? row.expectedTracking.map(String) : [],
                actualTrackingParameters: row.actualTrackingParameters && typeof row.actualTrackingParameters === "object" ? row.actualTrackingParameters : {},
                urlStatus: Utils.normaliseStatus(row.urlStatus || ""),
                trackingStatus: Utils.normaliseStatus(row.trackingStatus || ""),
                labelStatus: Utils.normaliseStatus(row.labelStatus || ""),
                categoryStatus: Utils.normaliseStatus(row.categoryStatus || ""),
                elementStatus: Utils.normaliseStatus(row.elementStatus || ""),
                typeStatus: Utils.normaliseStatus(row.typeStatus || ""),
                screenshotStatus: Utils.normaliseStatus(row.screenshotStatus || ""),
                screenshotPath: String(row.screenshotPath || ""),
                linkValidationStatus: Utils.normaliseStatus(row.linkValidationStatus || ""),
                finalDestinationUrl: String(row.finalDestinationUrl || ""),
                httpStatus: String(row.httpStatus || ""),
                validation:  Utils.normaliseStatus(row.validation || ""),
                notes:       String(row.notes || ""),
                rawColumns:  row.rawColumns && typeof row.rawColumns === "object" ? row.rawColumns : {}
            };
        }) : [];

        return {
            specificationSelected: !!raw.specificationSelected,
            message:               String(raw.message || "No campaign specification selected."),
            expectedEntries:       typeof raw.expectedEntries === "number" ? raw.expectedEntries : 0,
            matched:               typeof raw.matched === "number" ? raw.matched : 0,
            missing:               typeof raw.missing === "number" ? raw.missing : 0,
            unexpected:            typeof raw.unexpected === "number" ? raw.unexpected : 0,
            trackingErrors:        typeof raw.trackingErrors === "number" ? raw.trackingErrors : 0,
            urlErrors:             typeof raw.urlErrors === "number" ? raw.urlErrors : 0,
            passed:                typeof raw.passed === "number" ? raw.passed : 0,
            failed:                typeof raw.failed === "number" ? raw.failed : 0,
            warnings:              typeof raw.warnings === "number" ? raw.warnings : 0,
            originalHeaders:       Array.isArray(raw.originalHeaders) ? raw.originalHeaders.map(String) : [],
            rows:                  rows
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

    function normaliseEmailMetadata(raw) {
        raw = raw || {};
        return {
            available:    !!raw.available,
            subject:      String(raw.subject || ''),
            from:         String(raw.from || ''),
            to:           Array.isArray(raw.to) ? raw.to.map(String) : [],
            cc:           Array.isArray(raw.cc) ? raw.cc.map(String) : [],
            bcc:          Array.isArray(raw.bcc) ? raw.bcc.map(String) : [],
            replyTo:      String(raw.replyTo || ''),
            receivedDate: raw.receivedDate ? String(raw.receivedDate) : '',
            messageId:    String(raw.messageId || '')
        };
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
            campaignValidation: normaliseCampaignValidation(raw.campaignValidation),
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
            client:       raw.client ? String(raw.client) : 'General',
            validationMode: raw.validationMode ? String(raw.validationMode) : 'PRE_SEND',
            inputSource:  raw.inputSource ? String(raw.inputSource) : 'HTML Folder',
            emailMetadata: normaliseEmailMetadata(raw.emailMetadata),
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
        normaliseImage: normaliseImage,
        normaliseCampaignValidation: normaliseCampaignValidation
    };

}());
