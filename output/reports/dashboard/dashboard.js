"use strict";

/* ================================================================
   STATE
================================================================ */
let _allFiles       = [];
let _activeIdx      = -1;
let _activeCategory = "overview";

/* ================================================================
   CATEGORY MAP  — maps sidebar keys → rule IDs
   "*" in the array means "all rules"
================================================================ */
const CATEGORY_MAP = {
    overview:       ["*"],
    inventoryLinks: ["LINK_VALIDATION"],
    tracking:       ["CTA_VALIDATION", "LINK_TEXT_VALIDATION"],
    brokenHtml:     ["DUPLICATE_ID", "HEADING_HIERARCHY"],
    images:         ["ALT_TEXT_VALIDATION"],
    urlDefense:     ["URL_DEFENSE"],
    accessibility:  ["ACCESSIBILITY_AXE"],
    privacy:        ["PRIVACY_LINK"],
    viewOnline:     ["VIEW_ONLINE_LINK"],
    disclaimer:     ["DISCLAIMER_PRESENT"],
    unsubscribe:    ["BROKEN_ANCHOR", "CTA_VALIDATION"]
};

/* ================================================================
   SIDEBAR LABELS  — human-readable names for each category key
================================================================ */
const SIDEBAR_LABELS = {
    overview:       "Overview",
    inventoryLinks: "Inventory & Inspect Link",
    tracking:       "Tracking Links & CTAs",
    brokenHtml:     "Broken HTML Codes",
    images:         "Image Inventory & Rendering Accuracy",
    urlDefense:     "URL Defense Wrappers Cleanup",
    accessibility:  "Accessibility Violations",
    privacy:        "Privacy Link Validation",
    viewOnline:     "Disclosure / View in Browser",
    disclaimer:     "Reply-to Text / Disclaimer",
    unsubscribe:    "Legal – Unsubscribe Link"
};

/* ================================================================
   RECOMMENDATION MAP  — one recommendation per rule ID
================================================================ */
const RECOMMENDATIONS = {
    PRIVACY_LINK:       "Verify that the Privacy Policy destination URL is correct and accessible.",
    VIEW_ONLINE_LINK:   "Verify the View Online macro and confirm the hosted URL resolves correctly.",
    LINK_VALIDATION:    "Correct the broken hyperlink and ensure the destination URL is live.",
    CTA_VALIDATION:     "Verify the CTA destination URL and review button wording for clarity.",
    LINK_TEXT_VALIDATION: "Update link text to be descriptive and meaningful for all readers.",
    ALT_TEXT_VALIDATION:"Add descriptive ALT text to all images for accessibility and deliverability.",
    ACCESSIBILITY_AXE:  "Resolve the flagged accessibility violations to meet WCAG 2.1 AA standards.",
    BROKEN_ANCHOR:      "Correct the broken internal anchor so unsubscribe navigation works reliably.",
    HEADING_HIERARCHY:  "Fix the heading structure so H1→H2→H3 levels are used in the correct order.",
    DUPLICATE_ID:       "Remove or rename duplicate HTML element IDs to prevent rendering issues.",
    CONTENT_VALIDATION: "Correct all content placeholders before deployment.",
    DISCLAIMER_PRESENT: "Verify the legal disclaimer is present and matches the approved copy.",
    URL_DEFENSE:        "Remove or update URL defense wrappers that may be breaking destination links."
};

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
    populateEmailDropdown(_allFiles);
    initializeSidebar();

    if (_allFiles.length > 0) {
        var firstFailIdx = findIndex(_allFiles, function (f) {
            return normaliseStatus(f.overallStatus) !== "PASS";
        });
        selectFile(firstFailIdx >= 0 ? firstFailIdx : 0);
    }
});

/* ================================================================
   TOPBAR  (preserved exactly)
================================================================ */
function renderTopbar(data) {
    setText("tb-passed",  data.passedFiles  || 0);
    setText("tb-failed",  data.failedFiles  || 0);

    var skippedFiles =
        (data.skippedFiles != null)
            ? data.skippedFiles
            : ((data.totalFiles || 0) - (data.passedFiles || 0) - (data.failedFiles || 0));
    var tbSkip = document.getElementById("tb-skipped");
    if (tbSkip) { tbSkip.textContent = skippedFiles; }
    var tbSkipWrap = document.getElementById("tb-skipped-wrap");
    if (tbSkipWrap && skippedFiles > 0) { tbSkipWrap.style.display = ""; }

    var egEl = document.getElementById("tb-generated");
    if (egEl && data.generatedAt) {
        try {
            egEl.textContent = "Generated " + new Date(data.generatedAt).toLocaleString();
        } catch (_) {
            egEl.textContent = String(data.generatedAt);
        }
    }

    var ms = null;
    if      (data.executionTimeMs  != null) { ms = Number(data.executionTimeMs);  }
    else if (data.durationMs       != null) { ms = Number(data.durationMs);       }
    else if (data.executionTimeSec != null) { ms = Number(data.executionTimeSec) * 1000; }
    else if (data.executionTime    != null) {
        var raw = Number(data.executionTime);
        ms = raw > 1000 ? raw : raw * 1000;
    } else if (data.duration != null) {
        var rawDur = Number(data.duration);
        ms = rawDur > 1000 ? rawDur : rawDur * 1000;
    } else if (typeof AUDIT_EXECUTION_TIME_MS !== "undefined" && AUDIT_EXECUTION_TIME_MS !== null) {
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
   EMAIL DROPDOWN  — NEW (replaces old sidebar file list)
================================================================ */
function populateEmailDropdown(files) {
    var sel = document.getElementById("email-selector");
    if (!sel) { return; }

    sel.innerHTML = "";

    if (!files || files.length === 0) {
        var opt = document.createElement("option");
        opt.textContent = "No emails audited";
        sel.appendChild(opt);
        return;
    }

    files.forEach(function (file, i) {
        var opt = document.createElement("option");
        opt.value       = String(i);
        opt.textContent = file.fileName || ("Email " + (i + 1));
        sel.appendChild(opt);
    });

    sel.addEventListener("change", function () {
        selectFile(parseInt(sel.value, 10));
    });
}

/* ================================================================
   CATEGORY SIDEBAR  — NEW
================================================================ */
function initializeSidebar() {
    var nav = document.getElementById("category-nav");
    if (!nav) { return; }

    nav.innerHTML = "";

    Object.keys(SIDEBAR_LABELS).forEach(function (key) {
        var btn = document.createElement("button");
        btn.className        = "cat-nav-btn" + (key === "overview" ? " active" : "");
        btn.dataset.category = key;
        btn.textContent      = SIDEBAR_LABELS[key];

        btn.addEventListener("click", function () {
            _activeCategory = key;

            /* Update active state */
            nav.querySelectorAll(".cat-nav-btn").forEach(function (b) {
                b.classList.toggle("active", b === btn);
            });

            /* Re-render content panel for currently selected email */
            if (_activeIdx >= 0 && _activeIdx < _allFiles.length) {
                renderCategory(_activeCategory, _allFiles[_activeIdx]);
            }
        });

        nav.appendChild(btn);
    });
}

/* ================================================================
   FILE SELECTION
================================================================ */
function selectFile(idx) {
    if (idx < 0 || idx >= _allFiles.length) { return; }

    _activeIdx = idx;

    /* Sync dropdown */
    var sel = document.getElementById("email-selector");
    if (sel) { sel.value = String(idx); }

    /* Show content panel */
    var welcome = document.getElementById("welcome-state");
    var detail  = document.getElementById("detail-view");
    if (welcome) { welcome.style.display = "none"; }
    if (detail)  { detail.style.display  = "block"; }

    renderCategory(_activeCategory, _allFiles[idx]);
}

/* ================================================================
   RENDER CATEGORY  — NEW main rendering entry point
================================================================ */
function renderCategory(category, file) {
    if (!file) { return; }

    var panel = document.getElementById("content-panel");
    if (!panel) {
        /* Fallback: write into legacy detail-view if content-panel is absent */
        panel = document.getElementById("detail-view");
    }
    if (!panel) { return; }

    if (category === "overview") {
        panel.innerHTML = renderOverview(file);
    } else {
        var ruleIds = CATEGORY_MAP[category] || [];
        var rules   = Array.isArray(file.rules) ? file.rules : [];

        /* Filter rules matching this category */
        var filtered = rules.filter(function (r) {
            return ruleIds.indexOf(r.ruleId) >= 0;
        });

        panel.innerHTML = renderCategoryCards(category, filtered);
    }

    wireExpandCollapse(panel);
}

/* ================================================================
   RENDER OVERVIEW  — NEW
================================================================ */
function renderOverview(file) {
    var rules        = Array.isArray(file.rules) ? file.rules : [];
    var passedRules  = rules.filter(function (r) { return normaliseStatus(r.status) === "PASS";    });
    var failedRules  = rules.filter(function (r) { return normaliseStatus(r.status) === "FAIL";    });
    var errorRules   = rules.filter(function (r) { return normaliseStatus(r.status) === "ERROR";   });
    var skippedRules = rules.filter(function (r) { return normaliseStatus(r.status) === "SKIPPED"; });

    var totalRules  = rules.length;
    var passedCount = passedRules.length;
    var failedCount = failedRules.length + errorRules.length;
    var skippedCount= skippedRules.length;
    var status      = normaliseStatus(file.overallStatus);

    /* File execution time */
    var fileMs = null;
    if      (file.executionTimeMs  != null) { fileMs = Number(file.executionTimeMs);  }
    else if (file.durationMs       != null) { fileMs = Number(file.durationMs);       }
    else if (file.executionTimeSec != null) { fileMs = Number(file.executionTimeSec) * 1000; }
    else if (file.executionTime    != null) {
        var rawFile = Number(file.executionTime);
        fileMs = rawFile > 1000 ? rawFile : rawFile * 1000;
    }

    var timeChip = (fileMs !== null && !isNaN(fileMs))
        ? '<span class="overview-meta-chip">\u23F1 ' + formatDuration(fileMs) + "</span>"
        : "";

    /* Build category status pills for quick-scan table */
    var categoryRows = Object.keys(SIDEBAR_LABELS).filter(function (k) { return k !== "overview"; }).map(function (key) {
        var ruleIds = CATEGORY_MAP[key] || [];
        var catRules = rules.filter(function (r) { return ruleIds.indexOf(r.ruleId) >= 0; });
        var catFail  = catRules.filter(function (r) {
            var s = normaliseStatus(r.status);
            return s === "FAIL" || s === "ERROR";
        });
        var catStatus = catRules.length === 0 ? "SKIPPED"
            : catFail.length > 0 ? "FAIL" : "PASS";

        return (
            '<tr class="overview-row" data-category="' + esc(key) + '">' +
            '<td class="overview-cat-name">' + esc(SIDEBAR_LABELS[key]) + "</td>" +
            '<td><span class="status-badge ' + statusBadgeClass(catStatus) + '">' + esc(catStatus) + "</span></td>" +
            '<td class="overview-finding-count">' +
            (catFail.length > 0 ? catFail.length + " issue" + (catFail.length !== 1 ? "s" : "") : "—") +
            "</td>" +
            "</tr>"
        );
    }).join("");

    var html =
        '<div class="overview-header">' +
        '<div class="overview-filename">' + esc(file.fileName) + "</div>" +
        '<div class="overview-meta">' +
        '<span class="status-badge ' + statusBadgeClass(status) + '">' + esc(status) + "</span>" +
        '<span class="overview-meta-chip">' + totalRules + " checks</span>" +
        timeChip +
        "</div>" +
        "</div>" +

        '<div class="overview-tiles">' +
        buildOverviewTile("Total Checks",  totalRules,   "rules evaluated", "tile-total")   +
        buildOverviewTile("Passed",        passedCount,  "no findings",     "tile-passed")  +
        buildOverviewTile("Failed",        failedCount,  "need review",     "tile-failed")  +
        (skippedCount > 0 ? buildOverviewTile("Skipped", skippedCount, "not evaluated", "tile-skipped") : "") +
        "</div>" +

        buildScreenshotSection(file) +

        '<div class="overview-table-section">' +
        '<div class="section-label">Audit Summary by Category</div>' +
        '<table class="overview-table">' +
        '<thead><tr><th>Category</th><th>Status</th><th>Issues</th></tr></thead>' +
        '<tbody>' + categoryRows + "</tbody>" +
        "</table>" +
        "</div>";

    return '<div class="overview-wrap">' + html + "</div>";
}

function buildOverviewTile(label, value, sub, modifier) {
    return (
        '<div class="summary-tile ' + modifier + '">' +
        '<div class="summary-tile-label">' + esc(label)  + "</div>" +
        '<div class="summary-tile-value">' + value        + "</div>" +
        '<div class="summary-tile-sub">'   + esc(sub)    + "</div>" +
        "</div>"
    );
}

/* ================================================================
   RENDER CATEGORY CARDS  — NEW
================================================================ */
function renderCategoryCards(category, filteredRules) {
    var label = SIDEBAR_LABELS[category] || category;

    var html = '<div class="category-panel">' +
        '<div class="category-panel-header">' +
        '<h2 class="category-panel-title">' + esc(label) + "</h2>" +
        "</div>";

    if (filteredRules.length === 0) {
        html +=
            '<div class="no-results-msg">' +
            '<div class="no-results-icon">\u2713</div>' +
            '<div class="no-results-title">No checks found for this category</div>' +
            '<div class="no-results-sub">This email may not include rules mapped to this section, or the audit did not run these checks.</div>' +
            "</div>";
    } else {
        html += '<div class="biz-cards-list">';
        filteredRules.forEach(function (rule) {
            html += createValidationCard(rule);
        });
        html += "</div>";
    }

    html += "</div>";
    return html;
}

/* ================================================================
   VALIDATION CARD  — NEW business-friendly card layout
================================================================ */
function createValidationCard(rule) {
    var ruleId        = rule.ruleId        || "";
    var ruleName      = rule.ruleName      || ruleId;
    var status        = normaliseStatus(rule.status);
    var severity      = rule.severity      || "";
    var findings      = Array.isArray(rule.findings) ? rule.findings : [];
    var businessImpact= rule.businessImpact || "";
    var technicalNote = findings.length > 0 ? findings[0] : (rule.message || "");

    var badgeCls = statusBadgeClass(status);
    var sevCls   = severityChipClass(severity);
    var rec      = getRecommendation(ruleId);

    var statusLabel = createStatusBadge(status);
    var sevLabel    = severity ? createSeverityBadge(severity, sevCls) : "";
    var recHtml     = rec
        ? '<div class="biz-card-row"><span class="biz-card-label">Recommendation</span><span class="biz-card-value biz-card-rec">' + esc(rec) + "</span></div>"
        : "";
    var businessHtml= businessImpact
        ? '<div class="biz-card-row"><span class="biz-card-label">Business Result</span><span class="biz-card-value">' + esc(businessImpact) + "</span></div>"
        : "";
    var techHtml    = technicalNote
        ? '<div class="biz-card-row"><span class="biz-card-label">Technical Finding</span><span class="biz-card-value biz-card-tech">' + esc(technicalNote) + "</span></div>"
        : "";

    /* Extra findings beyond first */
    var extraFindings = "";
    if (findings.length > 1) {
        extraFindings = '<div class="biz-card-extra-findings">';
        for (var i = 1; i < findings.length; i++) {
            extraFindings +=
                '<div class="biz-card-extra-item">' +
                '<span class="biz-card-extra-num">' + (i + 1) + "</span>" +
                '<span class="biz-card-extra-text">' + esc(findings[i]) + "</span>" +
                "</div>";
        }
        extraFindings += "</div>";
    }

    var isExpanded = (status === "FAIL" || status === "ERROR");

    return (
        '<div class="biz-card' + (isExpanded ? " expanded" : "") + '">' +
        '<button class="biz-card-header" aria-expanded="' + isExpanded + '">' +
        '<span class="biz-card-chevron">\u25b6</span>' +
        '<span class="biz-card-title">' + esc(ruleName) + "</span>" +
        '<div class="biz-card-badges">' +
        sevLabel +
        statusLabel +
        "</div>" +
        "</button>" +
        '<div class="biz-card-body">' +
        businessHtml +
        techHtml +
        extraFindings +
        recHtml +
        "</div>" +
        "</div>"
    );
}

/* ================================================================
   BADGE HELPERS  — NEW
================================================================ */
function createStatusBadge(status) {
    return '<span class="status-badge ' + statusBadgeClass(status) + '">' + esc(status) + "</span>";
}

function createSeverityBadge(severity, sevCls) {
    return '<span class="severity-chip ' + (sevCls || severityChipClass(severity)) + '">' + esc(severity) + "</span>";
}

/* ================================================================
   RECOMMENDATION LOOKUP  — NEW
================================================================ */
function getRecommendation(ruleId) {
    return RECOMMENDATIONS[ruleId] || null;
}

/* ================================================================
   CLEAR CONTENT  — NEW
================================================================ */
function clearContent() {
    var panel = document.getElementById("content-panel") || document.getElementById("detail-view");
    if (panel) { panel.innerHTML = ""; }
}

/* ================================================================
   SECTION: SCREENSHOT  (preserved exactly)
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
    if (wrap) { wrap.innerHTML = buildScreenshotUnavailable(); }
}

/* ================================================================
   EXPAND / COLLAPSE WIRING  — updated to cover both card types
================================================================ */
function wireExpandCollapse(container) {
    /* Legacy val-cards */
    var valHeaders = container.querySelectorAll(".val-card-header");
    valHeaders.forEach(function (header) {
        header.addEventListener("click", function () {
            var card     = header.closest(".val-card");
            var expanded = card.classList.toggle("expanded");
            header.setAttribute("aria-expanded", expanded ? "true" : "false");
        });
    });

    /* New business cards */
    var bizHeaders = container.querySelectorAll(".biz-card-header");
    bizHeaders.forEach(function (header) {
        header.addEventListener("click", function () {
            var card     = header.closest(".biz-card");
            var expanded = card.classList.toggle("expanded");
            header.setAttribute("aria-expanded", expanded ? "true" : "false");
        });
    });

    /* Overview table rows — clicking a row navigates to that category */
    var overviewRows = container.querySelectorAll(".overview-row");
    overviewRows.forEach(function (row) {
        row.style.cursor = "pointer";
        row.addEventListener("click", function () {
            var cat = row.dataset.category;
            if (!cat) { return; }
            _activeCategory = cat;

            /* Sync sidebar active state */
            var nav = document.getElementById("category-nav");
            if (nav) {
                nav.querySelectorAll(".cat-nav-btn").forEach(function (b) {
                    b.classList.toggle("active", b.dataset.category === cat);
                });
            }

            if (_activeIdx >= 0 && _activeIdx < _allFiles.length) {
                renderCategory(cat, _allFiles[_activeIdx]);
            }
        });
    });

    /* Screenshot expand */
    var expandBtn = container.querySelector(".screenshot-expand-trigger");
    if (expandBtn) {
        expandBtn.addEventListener("click", function () {
            var wrap    = container.querySelector(".screenshot-img-wrap");
            var overlay = container.querySelector(".screenshot-expand-btn");
            if (wrap)    { wrap.style.maxHeight = "none"; wrap.style.overflow = "visible"; }
            if (overlay) { overlay.style.display = "none"; }
        });
    }
}

/* ================================================================
   GAUGE  (preserved exactly — still used implicitly via overview)
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
   CSS CLASS HELPERS  (preserved exactly)
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
   UTILITIES  (preserved exactly)
================================================================ */
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
    var panel = document.getElementById("main-panel") || document.getElementById("content-panel");
    if (panel) {
        panel.innerHTML =
            "<div style='padding:40px 32px;color:var(--red);font-size:14px;line-height:1.6'>" +
            "<strong>Dashboard failed to load</strong><br>" +
            esc(msg) +
            "</div>";
    }
}