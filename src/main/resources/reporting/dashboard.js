"use strict";

/* ================================================================
   STATE
================================================================ */
let _allFiles   = [];
let _activeIdx  = -1;

/* ================================================================
   BOOT
================================================================ */
document.addEventListener("DOMContentLoaded", function () {

    var data;

    try {
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

    renderTopbar(data);
    buildSidebar(_allFiles);
    initSearch();

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
    setText("tb-passed",  data.passedFiles  || 0);
    setText("tb-failed",  data.failedFiles  || 0);

    /* ── Skipped file count ── */
    var skippedFiles =
        (data.skippedFiles != null)
            ? data.skippedFiles
            : ((data.totalFiles || 0) - (data.passedFiles || 0) - (data.failedFiles || 0));
    var tbSkip = document.getElementById("tb-skipped");
    if (tbSkip) { tbSkip.textContent = skippedFiles; }
    var tbSkipWrap = document.getElementById("tb-skipped-wrap");
    if (tbSkipWrap && skippedFiles > 0) { tbSkipWrap.style.display = ""; }

    /* ── Generated-at timestamp ── */
    var egEl = document.getElementById("tb-generated");
    if (egEl && data.generatedAt) {
        try {
            egEl.textContent = "Generated " + new Date(data.generatedAt).toLocaleString();
        } catch (_) {
            egEl.textContent = String(data.generatedAt);
        }
    }

    /* ── Execution time ──
         Priority order (first non-null wins):
           1. data.executionTimeMs   — ms number in the JSON payload (add to your DTO)
           2. data.durationMs        — alias
           3. data.executionTimeSec  — seconds float in JSON
           4. data.executionTime     — raw value; >1000 treated as ms, else seconds
           5. data.duration          — alias for executionTime
           6. AUDIT_EXECUTION_TIME_MS — injected via {{EXECUTION_TIME_MS}} placeholder
              in the HTML template (fallback when you cannot change the DTO)
    */
    var ms = null;
    if      (data.executionTimeMs  != null) { ms = Number(data.executionTimeMs);  }
    else if (data.durationMs       != null) { ms = Number(data.durationMs);       }
    else if (data.executionTimeSec != null) { ms = Number(data.executionTimeSec) * 1000; }
    else if (data.executionTime    != null) {
        var raw = Number(data.executionTime);
        ms = raw > 1000 ? raw : raw * 1000;
    } else if (data.duration       != null) {
        var rawDur = Number(data.duration);
        ms = rawDur > 1000 ? rawDur : rawDur * 1000;
    } else if (typeof AUDIT_EXECUTION_TIME_MS !== "undefined" && AUDIT_EXECUTION_TIME_MS !== null) {
        /* Populated from {{EXECUTION_TIME_MS}} placeholder in dashboard.html */
        ms = Number(AUDIT_EXECUTION_TIME_MS);
    }

    var etEl     = document.getElementById("tb-exec-time");
    var etWrapEl = document.getElementById("tb-exec-time-wrap");
    if (etEl && ms !== null && !isNaN(ms)) {
        etEl.textContent = "\u23F1 " + formatDuration(ms);
        if (etWrapEl) { etWrapEl.style.display = ""; }
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

        item.className     = "sidebar-item";
        item.dataset.index = String(i);
        item.setAttribute("tabindex", "0");
        item.setAttribute("role", "button");
        item.setAttribute("aria-label", file.fileName + " \u2013 " + (isPassed ? "PASS" : "FAIL"));

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
   BUG FIX: removed stray closing parenthesis on style.display line
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
            /* ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
               FIXED: was  "none";)   — extra closing paren caused a
               SyntaxError that silently killed the entire search feature.
            */
        });
    });
}

/* ================================================================
   FILE SELECTION
================================================================ */
function selectFile(idx) {
    if (idx < 0 || idx >= _allFiles.length) { return; }

    _activeIdx = idx;

    var items = document.querySelectorAll(".sidebar-item");
    items.forEach(function (item, i) {
        var isActive = (i === idx);
        item.classList.toggle("active", isActive);
        item.setAttribute("aria-selected", isActive ? "true" : "false");
    });

    if (items[idx]) {
        items[idx].scrollIntoView({ block: "nearest", behavior: "smooth" });
    }

    var welcome = document.getElementById("welcome-state");
    var detail  = document.getElementById("detail-view");
    if (welcome) { welcome.style.display = "none"; }
    if (detail)  { detail.style.display  = "block"; }

    renderDetail(_allFiles[idx]);
}

/* ================================================================
   DETAIL VIEW
   BUG FIX: pass rule arrays (not counts) to buildValidationSection
================================================================ */
function renderDetail(file) {
    var detail = document.getElementById("detail-view");
    if (!detail) { return; }

    var rules = Array.isArray(file.rules) ? file.rules : [];

    var passedRules  = rules.filter(function (r) { return normaliseStatus(r.status) === "PASS";    });
    var failedRules  = rules.filter(function (r) { return normaliseStatus(r.status) === "FAIL";    });
    var errorRules   = rules.filter(function (r) { return normaliseStatus(r.status) === "ERROR";   });
    var skippedRules = rules.filter(function (r) { return normaliseStatus(r.status) === "SKIPPED"; });

    var totalRules   = rules.length;
    var passedCount  = passedRules.length;
    var failedCount  = failedRules.length + errorRules.length;
    var skippedCount = skippedRules.length;

    var status = normaliseStatus(file.overallStatus);

    /* execution time per file (ms) — optional field */
    var fileMs = null;
    if      (file.executionTimeMs  != null) { fileMs = Number(file.executionTimeMs);  }
    else if (file.durationMs       != null) { fileMs = Number(file.durationMs);       }
    else if (file.executionTimeSec != null) { fileMs = Number(file.executionTimeSec) * 1000; }
    else if (file.executionTime    != null) {
        var rawFile = Number(file.executionTime);
        fileMs = rawFile > 1000 ? rawFile : rawFile * 1000;
    }

    detail.innerHTML =
        buildDetailHeader(file, passedCount, totalRules, status, fileMs) +
        buildSummaryStrip(totalRules, passedCount, failedCount, skippedCount) +
        buildScreenshotSection(file)                                          +
        buildValidationSection(passedRules, failedRules, errorRules, skippedRules);

    wireExpandCollapse(detail);
    wireScreenshotExpand(detail);
}

/* ================================================================
   SECTION: DETAIL HEADER
================================================================ */
function buildDetailHeader(file, passed, total, status, execMs) {
    var badgeCls  = statusBadgeClass(status);
    var timeChip  = (execMs !== null && execMs != null && !isNaN(execMs))
        ? '<span class="detail-meta-item">' +
        '<span style="color:var(--text-muted)">\u23F1</span> ' +
        formatDuration(execMs) +
        "</span>"
        : "";

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
        timeChip +
        "</div>" +
        "</div>" +
        '<div class="detail-header-right">' +
        buildGauge(passed, total) +
        "</div>" +
        "</div>"
    );
}

/* ================================================================
   SECTION: SUMMARY STRIP
================================================================ */
function buildSummaryStrip(total, passed, failed, skipped) {
    var skippedTile = (skipped > 0)
        ? buildTile("Skipped", skipped, "not evaluated", "tile-skipped")
        : "";
    return (
        '<div class="summary-strip">' +
        buildTile("Total Checks", total,  "rules evaluated", "tile-total")  +
        buildTile("Passed",       passed, "no findings",     "tile-passed") +
        buildTile("Failed",       failed, "need review",     "tile-failed") +
        skippedTile +
        "</div>"
    );
}

function buildTile(label, value, sub, modifier) {
    return (
        '<div class="summary-tile ' + modifier + '">' +
        '<div class="summary-tile-label">' + esc(label) + "</div>" +
        '<div class="summary-tile-value">' + value       + "</div>" +
        '<div class="summary-tile-sub">'   + esc(sub)   + "</div>" +
        "</div>"
    );
}

/* ================================================================
   SECTION: SCREENSHOT
================================================================ */
function buildScreenshotSection(file) {
    var rawPath        = file.screenshotPath;
    var screenshotPath = (rawPath && String(rawPath).trim()) ? String(rawPath).trim() : null;

    var cardContent;

    if (screenshotPath) {
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
            '<button class="screenshot-expand-trigger">View full screenshot \u2193</button>' +
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

function buildScreenshotUnavailable() {
    return (
        '<div class="screenshot-unavailable">' +
        '<div class="screenshot-unavailable-icon">\u25a3</div>' +
        '<div class="screenshot-unavailable-msg">Screenshot unavailable</div>' +
        '<div class="screenshot-unavailable-sub">' +
        "No screenshot was captured for this email. " +
        "This can occur when the file was skipped, rendering failed, " +
        "or screenshot capture is disabled in the configuration." +
        "</div>" +
        "</div>"
    );
}

function handleScreenshotError(img) {
    var wrap = img.closest(".screenshot-img-wrap");
    if (wrap) {
        wrap.innerHTML = buildScreenshotUnavailable();
    }
}

function wireScreenshotExpand(container) {
    var btn = container.querySelector(".screenshot-expand-trigger");
    if (!btn) { return; }

    btn.addEventListener("click", function () {
        var wrap    = container.querySelector(".screenshot-img-wrap");
        var overlay = container.querySelector(".screenshot-expand-btn");
        if (wrap)    { wrap.style.maxHeight = "none"; wrap.style.overflow = "visible"; }
        if (overlay) { overlay.style.display = "none"; }
    });
}

/* ================================================================
   SECTION: VALIDATION CARDS
   BUG FIX: parameters are now arrays (not numbers).
            Added SKIPPED section with its own heading and expanded cards.
================================================================ */
function buildValidationSection(passedRules, failedRules, errorRules, skippedRules) {
    var html =
        '<div class="section-label">Validation Results</div>' +
        '<div class="validation-list">';

    var hasAny =
        passedRules.length  > 0 ||
        failedRules.length  > 0 ||
        errorRules.length   > 0 ||
        skippedRules.length > 0;

    if (!hasAny) {
        html += '<div class="sidebar-empty">No rule results available.</div>';
    } else {

        /* ── Failed ── */
        if (failedRules.length > 0) {
            html += '<div class="section-label" style="margin-bottom:12px">Failed Rules (' + failedRules.length + ')</div>';
            failedRules.forEach(function (rule) { html += buildValCard(rule, true); });
        }

        /* ── Error ── */
        if (errorRules.length > 0) {
            html += '<div class="section-label" style="margin-top:20px;margin-bottom:12px">Error Rules (' + errorRules.length + ')</div>';
            errorRules.forEach(function (rule) { html += buildValCard(rule, true); });
        }

        /* ── Skipped  (was silently omitted before this fix) ── */
        if (skippedRules.length > 0) {
            html += '<div class="section-label" style="margin-top:20px;margin-bottom:12px">Skipped Rules (' + skippedRules.length + ')</div>';
            skippedRules.forEach(function (rule) { html += buildValCard(rule, true); });
        }

        /* ── Passed ── */
        if (passedRules.length > 0) {
            html += '<div class="section-label" style="margin-top:20px;margin-bottom:12px">Passed Rules (' + passedRules.length + ')</div>';
            passedRules.forEach(function (rule) { html += buildValCard(rule, false); });
        }
    }

    html += "</div>";
    return html;
}

/* ================================================================
   VAL CARD
   BUG FIX: no-findings and no-findings-pass divs were never closed.
================================================================ */
function buildValCard(rule, autoExpand) {
    var ruleId   = rule.ruleId        || "";
    var ruleName = rule.ruleName      || ruleId;
    var status   = normaliseStatus(rule.status);
    var severity = rule.severity      || "";
    var findings = Array.isArray(rule.findings) ? rule.findings : [];
    var impact   = rule.businessImpact || "";

    var badgeCls     = statusBadgeClass(status);
    var sevCls       = severityChipClass(severity);
    var isFail       = status !== "PASS";
    var findingCount = findings.length;

    /* ── Header ── */
    var headerHtml =
        '<button class="val-card-header" aria-expanded="' + autoExpand + '">' +
        '<span class="val-card-chevron">\u25b6</span>' +
        '<span class="val-card-rule-id">' + esc(ruleId)   + "</span>" +
        '<span class="val-card-name">'    + esc(ruleName)  + "</span>" +
        '<div class="val-card-right">' +
        (findingCount > 0
            ? '<span class="finding-count-pill">' + findingCount + " finding" + (findingCount !== 1 ? "s" : "") + "</span>"
            : "") +
        (severity
            ? '<span class="severity-chip ' + sevCls + '">' + esc(severity) + "</span>"
            : "") +
        '<span class="status-badge ' + badgeCls + '">' + esc(status) + "</span>" +
        "</div>" +
        "</button>";

    /* ── Body ── */
    var bodyHtml;

    if (!isFail) {
        /* FIXED: was missing closing </div> */
        bodyHtml = '<div class="no-findings">All checks passed for this rule.</div>';

    } else if (findings.length === 0 && !impact) {
        /* FIXED: was missing closing </div> */
        bodyHtml = '<div class="no-findings">No finding details available.</div>';

    } else {
        bodyHtml = '<div class="findings-list">';

        if (findings.length > 0) {
            findings.forEach(function (findingText, i) {
                bodyHtml +=
                    '<div class="finding-item">' +
                    '<div class="finding-index">#' + (i + 1) + "</div>" +
                    '<div class="finding-content">' +
                    (i === 0 && impact
                        ? '<div class="finding-impact">' + esc(impact) + "</div>"
                        : "") +
                    '<div class="finding-text">' + esc(findingText) + "</div>" +
                    "</div>" +
                    "</div>";
            });
        } else {
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
        '<div class="val-card' + (autoExpand ? " expanded" : "") + '">' +
        headerHtml +
        '<div class="val-card-body">' + bodyHtml + "</div>" +
        "</div>"
    );
}

/* ================================================================
   EXPAND / COLLAPSE WIRING
================================================================ */
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
   GAUGE
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
/**
 * Format a millisecond duration into a human-readable string.
 * Examples:  450 ms → "450 ms"
 *            3 500 ms → "3s 500ms"
 *            75 000 ms → "1m 15s"
 *            3 665 000 ms → "1h 1m 5s"
 */
function formatDuration(ms) {
    ms = Math.round(ms);
    if (ms < 1000)  { return ms + " ms"; }
    var s   = Math.floor(ms  / 1000);
    var rem = ms  % 1000;
    if (s < 60) {
        return s + "s" + (rem > 0 ? " " + rem + "ms" : "");
    }
    var m  = Math.floor(s / 60);
    var rs = s % 60;
    if (m < 60) {
        return m + "m" + (rs > 0 ? " " + rs + "s" : "");
    }
    var h  = Math.floor(m / 60);
    var rm = m % 60;
    return h + "h" + (rm > 0 ? " " + rm + "m" : "") + (rs > 0 ? " " + rs + "s" : "");
}

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

function setText(id, value) {
    var el = document.getElementById(id);
    if (el) { el.textContent = String(value); }
}

/* ================================================================
   esc()
   BUG FIX: original used JS string literals that contained the
   already-escaped entity text (e.g. the source code said "&amp;"
   literally).  The function therefore output "&amp;" into the DOM
   instead of "&", so e.g. a filename containing & would render as
   "&amp;" visible to the user.  Fixed to use the correct Unicode
   escape sequences so the replacement strings are the real HTML
   entity characters.
================================================================ */
function esc(value) {
    if (value == null) { return ""; }
    return String(value)
        .replace(/&/g,  "&amp;")
        .replace(/</g,  "&lt;")
        .replace(/>/g,  "&gt;")
        .replace(/"/g,  "&quot;")
        .replace(/'/g,  "&#039;");
}

function findIndex(arr, predicate) {
    for (var i = 0; i < arr.length; i++) {
        if (predicate(arr[i], i)) { return i; }
    }
    return -1;
}

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