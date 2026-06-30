"use strict";
/* ================================================================
   app.js  —  Application entry point
   Changes:
     • Renders Execution Summary banner above main content
     • Passes full auditData root counts into State for renderer
================================================================ */

document.addEventListener("DOMContentLoaded", function () {

    var auditData;

    if (typeof DASHBOARD_DATA === "undefined" || DASHBOARD_DATA === null) {
        _devModeNotice();
        return;
    }

    try {
        auditData = (typeof DASHBOARD_DATA === "string")
            ? JSON.parse(DASHBOARD_DATA)
            : DASHBOARD_DATA;

        auditData = Parser.parse(auditData);

    } catch (err) {
        console.error("[App] Failed to parse DASHBOARD_DATA:", err);
        _fatalError(
            "The audit report could not be loaded. " +
            "DASHBOARD_DATA is missing or malformed. " +
            "Check the browser console for details."
        );
        return;
    }

    if (!auditData || !Array.isArray(auditData.files)) {
        _fatalError("Audit data is empty or contains no email results.");
        return;
    }

    /* ── Seed state — include full auditData so renderer can read
       root-level totals for the Execution Summary banner ── */
    State.set("auditData", auditData);
    State.set("files",     auditData.files);

    /* ── Populate email selector dropdown ── */
    EmailSelector.populate(auditData.files);

    /* ── Wire all modules ── */
    Integration.init(auditData);

    /* ── Default to Overview category ── */
    State.set("selectedCategory", "overview");
    Sidebar.setActiveCategory("overview");

    /* ── Auto-select first failing email, or first email ── */
    if (auditData.files.length > 0) {
        EmailSelector.autoSelect(auditData.files);
    }

});

/* ================================================================
   DEV-MODE NOTICE
   Shown when DASHBOARD_DATA placeholder has not been substituted
================================================================ */
function _devModeNotice() {
    var main = document.getElementById("main-panel");
    if (!main) { return; }
    main.innerHTML =
        '<div style="display:flex;flex-direction:column;align-items:center;justify-content:center;min-height:60vh;gap:16px;padding:40px;text-align:center">' +
        '<div style="font-size:48px;opacity:0.35">&#9883;</div>' +
        '<h2 style="font-size:18px;font-weight:700;color:var(--accent)">Developer Preview</h2>' +
        '<p style="font-size:13px;color:var(--text-secondary);max-width:420px;line-height:1.7">' +
        'No audit data has been injected into this dashboard.<br>' +
        'Run the Email Audit Engine to produce a report with real data.<br><br>' +
        '<span style="color:var(--text-muted);font-family:var(--font-mono);font-size:12px">' +
        'The Java engine replaces <code>{{DASHBOARD_DATA}}</code> at runtime.' +
        '</span>' +
        '</p>' +
        '</div>';
}

/* ================================================================
   FATAL ERROR DISPLAY
================================================================ */
function _fatalError(message) {
    var main = document.getElementById("main-panel");
    if (!main) { return; }
    main.innerHTML =
        '<div style="display:flex;flex-direction:column;align-items:center;justify-content:center;min-height:60vh;gap:16px;padding:40px;text-align:center">' +
        '<div style="font-size:48px;opacity:0.3">&#9888;</div>' +
        '<h2 style="font-size:18px;font-weight:700;color:var(--red)">Dashboard failed to load</h2>' +
        '<p style="font-size:13px;color:var(--text-muted);max-width:380px;line-height:1.65">' +
        Utils.esc(message) +
        '</p>' +
        '</div>';
}