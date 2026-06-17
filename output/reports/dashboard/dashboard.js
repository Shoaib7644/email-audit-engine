/* ================================================================
   dashboard.js — Email Audit Dashboard
   Vanilla JavaScript, no frameworks, no dependencies.
   Targets DOM produced by dashboard.html.
================================================================ */

"use strict";

/* ================================================================
   STATE
================================================================ */
let _allFiles   = [];   // full FileAuditData array from RunAuditData.files
let _activeIdx  = -1;   // index into _allFiles currently shown

/* ================================================================
   BOOT
================================================================ */
document.addEventListener("DOMContentLoaded", function () {

    // ── 1. Load DASHBOARD_DATA injected by ReportTemplateRenderer ────────────
    var data;

    try {
        // DASHBOARD_DATA is set by the inline <script> block in dashboard.html.
        // ReportTemplateRenderer replaces {{DASHBOARD_DATA}} with the raw JSON
        // string (not quoted), so the global may already be an object.
        data = (typeof DASHBOARD_DATA === "string")
            ? JSON.parse(DASHBOARD_DATA)
            : DASHBOARD_DATA;
    } catch (err) {
        console.error("[Dashboard] Failed to parse DASHBOARD_DATA:", err);
        showFatalError("Failed to load audit data. Check the browser console for details.");
        return;
    }

    if (!data || typeof data !== "object") {
        showFatalError("DASHBOARD_DATA is empty or not a valid object.");
        return;
    }

    _allFiles = Array.isArray(data.files) ? data.files : [];

    // ── 2. Render static topbar counters ─────────────────────────────────────
    renderTopbar(data);

    // ── 3. Populate sidebar ───────────────────────────────────────────────────
    buildSidebar(_allFiles);

    // ── 4. Wire sidebar search ────────────────────────────────────────────────
    initSearch();

    // ── 5. Auto-select: first failed file, falling back to first file ─────────
    if (_allFiles.length > 0) {
        var firstFailIdx = findIndex(_allFiles, function (f) {
            return normaliseStatus(f.overallStatus) !== "PASS";
        });
        selectFile(firstFailIdx >= 0 ? firstFailIdx : 0);
    }
});

/* ================================================================
   TOPBAR
================================================================ */
function renderTopbar(data) {
    setText("tb-passed",   data.passedFiles || 0);
    setText("tb-failed",   data.failedFiles || 0);

    var egEl = document.getElementById("tb-generated");
    if (egEl && data.generatedAt) {
        try {
            egEl.textContent = "Generated " + new Date(data.generatedAt).toLocaleString();
        } catch (_) {
            egEl.textContent = String(data.generatedAt);
        }
    }
}

/* ================================================================
   SIDEBAR — file list
================================================================ */
function buildSidebar(files) {
    var list = document.getElementById("sidebar-list");
    if (!list) { return; }

    if (!files || files.length === 0) {
        list.innerHTML = '<div class="sidebar-empty">No emails audited.</div>';
        return;
    }

    list.innerHTML = "";

    files.forEach(function (file, i) {
        var item     = document.createElement("div");
        var isPassed = normaliseStatus(file.overallStatus) === "PASS";
        var failCnt  = file.failedChecks || 0;

        item.className    = "sidebar-item";
        item.dataset.index = String(i);
        item.setAttribute("tabindex", "0");
        item.setAttribute("role", "button");
        item.setAttribute("aria-label", file.fileName + " – " + (isPassed ? "PASS" : "FAIL"));

        item.innerHTML =
            '<span class="sidebar-item-dot ' + (isPassed ? "pass" : "fail") + '"></span>' +
            '<span class="sidebar-item-name" title="' + esc(file.fileName) + '">' + esc(file.fileName) + "</span>" +
            (failCnt > 0 ? '<span class="sidebar-item-count">' + failCnt + "\u2717</span>" : "");

        item.addEventListener("click",   function () { selectFile(i); });
        item.addEventListener("keydown", function (e) {
            if (e.key === "Enter" || e.key === " ") { e.preventDefault(); selectFile(i); }
        });

        list.appendChild(item);
    });
}

/* ================================================================
   SIDEBAR — search / filter
================================================================ */
function initSearch() {
    var input = document.getElementById("sidebar-search");
    if (!input) { return; }

    input.addEventListener("input", function () {
        var q = input.value.toLowerCase().trim();
        var items = document.querySelectorAll(".sidebar-item");

        items.forEach(function (item) {
            var name = (item.querySelector(".sidebar-item-name") || {}).textContent || "";
            item.style.display = name.toLowerCase().indexOf(q) >= 0 ? "" : "none";
        });
    });
}

/* ================================================================
   FILE SELECTION — update sidebar + render right panel
================================================================ */

/**
 * Selects the file at index `idx` in _allFiles:
 *   1. Marks the correct sidebar item active.
 *   2. Hides the welcome state.
 *   3. Renders the full detail view for that file.
 */
function selectFile(idx) {
    if (idx < 0 || idx >= _allFiles.length) { return; }

    _activeIdx = idx;

    // Update sidebar active state
    var items = document.querySelectorAll(".sidebar-item");
    items.forEach(function (item, i) {
        var isActive = (i === idx);
        item.classList.toggle("active", isActive);
        item.setAttribute("aria-selected", isActive ? "true" : "false");
    });

    // Scroll the active item into view in case the list is long
    if (items[idx]) {
        items[idx].scrollIntoView({ block: "nearest", behavior: "smooth" });
    }

    // Show detail, hide welcome
    var welcome = document.getElementById("welcome-state");
    var detail  = document.getElementById("detail-view");
    if (welcome) { welcome.style.display = "none"; }
    if (detail)  { detail.style.display  = "block"; }

    renderDetail(_allFiles[idx]);
}

/* ================================================================
   DETAIL VIEW — top-level orchestrator
================================================================ */
function renderDetail(file) {
    var detail = document.getElementById("detail-view");
    if (!detail) { return; }

    var total   = file.totalChecks  || 0;
    var passed  = file.passedChecks || 0;
    var failed  = file.failedChecks || 0;
    var status  = normaliseStatus(file.overallStatus);
    var rules   = Array.isArray(file.rules) ? file.rules : [];

    var failRules = rules.filter(function (r) { return normaliseStatus(r.status) !== "PASS"; });
    var passRules = rules.filter(function (r) { return normaliseStatus(r.status) === "PASS"; });

    detail.innerHTML =
        buildDetailHeader(file, passed, total, status) +
        buildSummaryStrip(total, passed, failed)       +
        buildScreenshotSection(file)                   +
        buildValidationSection(failRules, passRules);

    wireExpandCollapse(detail);
    wireScreenshotExpand(detail);
}

/* ================================================================
   SECTION: DETAIL HEADER  (filename + status badge + gauge)
================================================================ */
function buildDetailHeader(file, passed, total, status) {
    var badgeCls = statusBadgeClass(status);

    return (
        '<div class="detail-header">' +
        '<div class="detail-header-left">' +
        '<div class="detail-filename">' + esc(file.fileName) + "</div>" +
        '<div class="detail-meta">' +
        '<span class="status-badge ' + badgeCls + '">' + esc(status) + "</span>" +
        '<span class="detail-meta-item">' +
        '<span style="color:var(--text-muted)">\u25a3</span> ' +
        total + " checks" +
        "</span>" +
        "</div>" +
        "</div>" +
        '<div class="detail-header-right">' +
        buildGauge(passed, total) +
        "</div>" +
        "</div>"
    );
}

/* ================================================================
   SECTION: SUMMARY STRIP (total / passed / failed tiles)
================================================================ */
function buildSummaryStrip(total, passed, failed) {
    return (
        '<div class="summary-strip">' +
        buildTile("Total Checks", total, "rules evaluated", "tile-total")  +
        buildTile("Passed",       passed, "no findings",   "tile-passed")  +
        buildTile("Failed",       failed, "need review",   "tile-failed")  +
        "</div>"
    );
}

function buildTile(label, value, sub, modifier) {
    return (
        '<div class="summary-tile ' + modifier + '">' +
        '<div class="summary-tile-label">' + esc(label) + "</div>" +
        '<div class="summary-tile-value">' + value + "</div>" +
        '<div class="summary-tile-sub">' + esc(sub) + "</div>" +
        "</div>"
    );
}

/* ================================================================
   SECTION: SCREENSHOT
================================================================ */

/**
 * Renders the screenshot card for a file.
 *
 * file.screenshotPath is the absolute filesystem path string serialised by
 * DashboardDataCollector from AuditContext.getScreenshotPath().
 * It is null when no screenshot was captured (skipped file, render failure,
 * or screenshots disabled).
 */
function buildScreenshotSection(file) {
    var rawPath = file.screenshotPath;
    var screenshotPath = (rawPath && String(rawPath).trim()) ? String(rawPath).trim() : null;

    var cardContent;

    if (screenshotPath) {
        // Build a file:// URI for absolute OS paths; pass through URLs unchanged.
        var src = screenshotPath.indexOf("://") >= 0
            ? screenshotPath
            : "file://" + screenshotPath.replace(/\\/g, "/");

        cardContent =
            '<div class="screenshot-img-wrap" style="max-height:420px;overflow:hidden;position:relative">' +
            '<img class="screenshot-img"' +
            ' src="' + esc(src) + '"' +
            ' alt="Screenshot of ' + esc(file.fileName) + '"' +
            ' onerror="handleScreenshotError(this)"' +
            "/>" +
            '<div class="screenshot-expand-btn">' +
            '<button class="screenshot-expand-trigger">View full screenshot ↓</button>' +
            "</div>" +
            "</div>";
    } else {
        cardContent = buildScreenshotUnavailable();
    }

    return (
        '<div class="screenshot-section">' +
        '<div class="section-label">Screenshot</div>' +
        '<div class="screenshot-frame">' +
        '<div class="screenshot-toolbar">' +
        '<span class="screenshot-dot" style="background:#f85149"></span>' +
        '<span class="screenshot-dot" style="background:#d29922"></span>' +
        '<span class="screenshot-dot" style="background:#3fb950"></span>' +
        '<span class="screenshot-url">' + esc(file.fileName) + "</span>" +
        "</div>" +
        cardContent +
        "</div>" +
        "</div>"
    );
}

/** Placeholder shown when AuditContext captured no screenshot for this file. */
function buildScreenshotUnavailable() {
    return (
        '<div class="screenshot-unavailable">' +
        '<div class="screenshot-unavailable-icon">▣</div>' +
        '<div class="screenshot-unavailable-msg">Screenshot unavailable</div>' +
        '<div class="screenshot-unavailable-sub">' +
        "No screenshot was captured for this email. " +
        "This can occur when the file was skipped, rendering failed, " +
        "or screenshot capture is disabled in the configuration." +
        "</div>" +
        "</div>"
    );
}

/** Called via onerror on the screenshot <img> when the file cannot be loaded. */
function handleScreenshotError(img) {
    var wrap = img.closest(".screenshot-img-wrap");
    if (wrap) {
        wrap.innerHTML = buildScreenshotUnavailable();
    }
}

/* Wire the "View full screenshot" button after innerHTML is set */
function wireScreenshotExpand(container) {
    var btn = container.querySelector(".screenshot-expand-trigger");
    if (!btn) { return; }

    btn.addEventListener("click", function () {
        var wrap = container.querySelector(".screenshot-img-wrap");
        var overlay = container.querySelector(".screenshot-expand-btn");
        if (wrap)    { wrap.style.maxHeight = "none"; wrap.style.overflow = "visible"; }
        if (overlay) { overlay.style.display = "none"; }
    });
}

/* ================================================================
   SECTION: VALIDATION CARDS
================================================================ */
function buildValidationSection(failRules, passRules) {
    var html = '<div class="section-label">Validation Results</div>' +
        '<div class="validation-list">';

    if (failRules.length === 0 && passRules.length === 0) {
        html += '<div class="sidebar-empty">No rule results available.</div>';

    } else {
        // All rules passed — show banner then collapsed pass cards
        if (failRules.length === 0) {
            var n = passRules.length;
            html +=
                '<div class="pass-all-banner">' +
                "All " + n + " rule" + (n !== 1 ? "s" : "") + " passed \u2014 no issues detected." +
                "</div>";
        } else {
            // Failed rules first — auto-expanded
            failRules.forEach(function (rule) {
                html += buildValCard(rule, true);
            });
        }

        // Passed rules section (always collapsed)
        if (passRules.length > 0) {
            html +=
                '<div class="section-label" style="margin-top:20px;margin-bottom:12px">' +
                "Passed Rules (" + passRules.length + ")" +
                "</div>";
            passRules.forEach(function (rule) {
                html += buildValCard(rule, false);
            });
        }
    }

    html += "</div>";
    return html;
}

/**
 * Builds one validation card for a single RuleAuditData entry.
 *
 * @param {Object}  rule        – RuleAuditData object
 * @param {boolean} autoExpand  – true for failing rules, false for passing
 */
function buildValCard(rule, autoExpand) {
    var ruleId      = rule.ruleId        || "";
    var ruleName    = rule.ruleName      || ruleId;
    var status      = normaliseStatus(rule.status);
    var severity    = rule.severity      || "";
    var findings    = Array.isArray(rule.findings) ? rule.findings : [];
    var impact      = rule.businessImpact || "";

    var badgeCls     = statusBadgeClass(status);
    var sevCls       = severityChipClass(severity);
    var isFail       = status !== "PASS";
    var findingCount = findings.length;
    var expanded     = autoExpand && isFail;

    // ── Card header ───────────────────────────────────────────────────────────
    var headerHtml =
        '<button class="val-card-header" aria-expanded="' + expanded + '">' +
        '<span class="val-card-chevron">\u25b6</span>' +
        '<span class="val-card-rule-id">' + esc(ruleId) + "</span>" +
        '<span class="val-card-name">'    + esc(ruleName) + "</span>" +
        '<div class="val-card-right">' +
        (findingCount > 0
            ? '<span class="finding-count-pill">' +
            findingCount + " finding" + (findingCount !== 1 ? "s" : "") +
            "</span>"
            : "") +
        (severity
            ? '<span class="severity-chip ' + sevCls + '">' + esc(severity) + "</span>"
            : "") +
        '<span class="status-badge ' + badgeCls + '">' + esc(status) + "</span>" +
        "</div>" +
        "</button>";

    // ── Card body (findings + business impact) ────────────────────────────────
    var bodyHtml;

    if (!isFail) {
        bodyHtml = '<div class="no-findings">All checks passed for this rule.</div>';

    } else if (findings.length === 0 && !impact) {
        bodyHtml = '<div class="no-findings">No finding details available.</div>';

    } else {
        bodyHtml = '<div class="findings-list">';

        if (findings.length > 0) {
            findings.forEach(function (findingText, i) {
                bodyHtml +=
                    '<div class="finding-item">' +
                    '<div class="finding-index">#' + (i + 1) + "</div>" +
                    '<div class="finding-content">' +
                    // Business impact shown once per rule, on the first finding
                    (i === 0 && impact
                        ? '<div class="finding-impact">' + esc(impact) + "</div>"
                        : "") +
                    '<div class="finding-text">' + esc(findingText) + "</div>" +
                    "</div>" +
                    "</div>";
            });
        } else {
            // No findings text, but have a business impact string
            bodyHtml +=
                '<div class="finding-item">' +
                '<div class="finding-index">#1</div>' +
                '<div class="finding-content">' +
                '<div class="finding-impact">' + esc(impact) + "</div>" +
                "</div>" +
                "</div>";
        }

        bodyHtml += "</div>";
    }

    return (
        '<div class="val-card' + (expanded ? " expanded" : "") + '">' +
        headerHtml +
        '<div class="val-card-body">' + bodyHtml + "</div>" +
        "</div>"
    );
}

/* Wire expand/collapse toggle on all .val-card-header buttons */
function wireExpandCollapse(container) {
    var headers = container.querySelectorAll(".val-card-header");

    headers.forEach(function (header) {
        header.addEventListener("click", function () {
            var card     = header.closest(".val-card");
            var expanded = card.classList.toggle("expanded");
            header.setAttribute("aria-expanded", expanded ? "true" : "false");
        });
    });
}

/* ================================================================
   PASS-RATE ARC GAUGE
================================================================ */
function buildGauge(passed, total) {
    var pct    = total > 0 ? Math.round((passed / total) * 100) : 0;
    var r      = 31;
    var circ   = 2 * Math.PI * r;
    var offset = circ - (pct / 100) * circ;
    var color  = pct >= 80 ? "var(--green)" : (pct >= 50 ? "var(--amber)" : "var(--red)");

    return (
        '<div class="gauge-wrap">' +
        '<svg class="gauge-svg" width="80" height="80" viewBox="0 0 80 80">' +
        '<circle class="gauge-track" cx="40" cy="40" r="' + r + '"/>' +
        '<circle class="gauge-arc" cx="40" cy="40" r="' + r + '"' +
        ' stroke="' + color + '"' +
        ' stroke-dasharray="' + circ.toFixed(3) + '"' +
        ' stroke-dashoffset="' + offset.toFixed(3) + '"/>' +
        "</svg>" +
        '<div class="gauge-label">' +
        '<div class="gauge-pct" style="color:' + color + '">' + pct + "%</div>" +
        '<div class="gauge-sub">pass rate</div>' +
        "</div>" +
        "</div>"
    );
}

/* ================================================================
   CSS CLASS HELPERS
================================================================ */
function statusBadgeClass(status) {
    switch (normaliseStatus(status)) {
        case "PASS":    return "badge-pass";
        case "FAIL":    return "badge-fail";
        case "ERROR":   return "badge-error";
        case "SKIPPED": return "badge-skipped";
        default:        return "badge-skipped";
    }
}

function severityChipClass(severity) {
    if (!severity) { return "sev-info"; }
    switch (severity.toUpperCase()) {
        case "CRITICAL": return "sev-critical";
        case "HIGH":     return "sev-high";
        case "MEDIUM":   return "sev-medium";
        case "LOW":      return "sev-low";
        default:         return "sev-info";
    }
}

/* ================================================================
   UTILITIES
================================================================ */

/** Normalise a status string to one of: PASS | FAIL | ERROR | SKIPPED */
function normaliseStatus(status) {
    if (!status) { return "SKIPPED"; }
    switch (status.toUpperCase()) {
        case "PASS":
        case "PASSED":
        case "SUCCESS":
            return "PASS";
        case "FAIL":
        case "FAILED":
            return "FAIL";
        case "ERROR":
            return "ERROR";
        case "SKIPPED":
            return "SKIPPED";
        default:
            return "SKIPPED";
    }
}

/** Set element textContent by id, no-op when element is absent */
function setText(id, value) {
    var el = document.getElementById(id);
    if (el) { el.textContent = String(value); }
}

/** Escape HTML special characters for safe innerHTML insertion */
function esc(value) {
    if (value == null) { return ""; }
    return String(value)
        .replace(/&/g,  "&amp;")
        .replace(/</g,  "&lt;")
        .replace(/>/g,  "&gt;")
        .replace(/"/g,  "&quot;")
        .replace(/'/g,  "&#039;");
}

/** Array.prototype.findIndex polyfill-style helper (ES5 compat shim) */
function findIndex(arr, predicate) {
    for (var i = 0; i < arr.length; i++) {
        if (predicate(arr[i], i)) { return i; }
    }
    return -1;
}

/** Replace the main panel with a fatal error message */
function showFatalError(msg) {
    var panel = document.getElementById("main-panel");
    if (panel) {
        panel.innerHTML =
            "<div style='padding:40px 32px;color:var(--red);font-size:14px;line-height:1.6'>" +
            "<strong>Dashboard failed to load</strong><br>" +
            esc(msg) +
            "</div>";
    }
}