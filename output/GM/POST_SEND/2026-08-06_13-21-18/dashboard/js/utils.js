"use strict";
/* ================================================================
   utils.js  —  Pure utility functions
   No DOM access.  No state.  No side effects.
   Load order: 1 of 11
================================================================ */

var Utils = (function () {

    /* ── HTML escaping ─────────────────────────────────────────── */
    function esc(value) {
        if (value == null) { return ""; }
        return String(value)
            .replace(/&/g,  "&amp;")
            .replace(/</g,  "&lt;")
            .replace(/>/g,  "&gt;")
            .replace(/"/g,  "&quot;")
            .replace(/'/g,  "&#039;");
    }

    /* ── Status normalisation ──────────────────────────────────── */
    function normaliseStatus(raw) {
        if (!raw) { return "SKIPPED"; }
        switch (String(raw).toUpperCase()) {
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
            case "SKIP":
                return "SKIPPED";
            default:
                return "SKIPPED";
        }
    }

    /* ── CSS class helpers ─────────────────────────────────────── */
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
        switch (String(severity).toUpperCase()) {
            case "CRITICAL": return "sev-critical";
            case "HIGH":     return "sev-high";
            case "MEDIUM":   return "sev-medium";
            case "LOW":      return "sev-low";
            default:         return "sev-info";
        }
    }

    function badgeDotClass(status) {
        switch (normaliseStatus(status)) {
            case "PASS":    return "pass";
            case "FAIL":    return "fail";
            case "ERROR":   return "warn";
            default:        return "na";
        }
    }

    /* ── Date formatting ───────────────────────────────────────── */
    function formatDate(iso) {
        if (!iso) { return ""; }
        try {
            return new Date(iso).toLocaleString(undefined, {
                year:   "numeric",
                month:  "short",
                day:    "numeric",
                hour:   "2-digit",
                minute: "2-digit"
            });
        } catch (_) {
            return String(iso);
        }
    }

    /* ── Safe DOM setText ──────────────────────────────────────── */
    function setText(id, value) {
        var el = document.getElementById(id);
        if (el) { el.textContent = String(value == null ? "" : value); }
    }

    /* ── Gauge arc maths ───────────────────────────────────────── */
    function gaugeOffset(passed, total, radius) {
        var pct    = total > 0 ? Math.min(100, Math.round((passed / total) * 100)) : 0;
        var circ   = 2 * Math.PI * radius;
        var offset = circ - (pct / 100) * circ;
        return { pct: pct, offset: offset, circ: circ };
    }

    function gaugeColour(pct) {
        if (pct >= 80) { return "var(--green)"; }
        if (pct >= 50) { return "var(--amber)"; }
        return "var(--red)";
    }

    /* ── Toast helper ──────────────────────────────────────────── */
    var _toastTimer = null;

    function showToast(msg, variant) {
        var el = document.getElementById("toast");
        if (!el) { return; }
        el.textContent = msg;
        el.className   = "toast is-visible" + (variant ? " toast--" + variant : "");
        if (_toastTimer) { clearTimeout(_toastTimer); }
        _toastTimer = setTimeout(function () {
            el.className = "toast";
        }, 3200);
    }

    /* ── Clamp a number ────────────────────────────────────────── */
    function clamp(val, min, max) {
        return Math.min(max, Math.max(min, val));
    }

    /* ── Public API ────────────────────────────────────────────── */
    return {
        esc:               esc,
        normaliseStatus:   normaliseStatus,
        statusBadgeClass:  statusBadgeClass,
        severityChipClass: severityChipClass,
        badgeDotClass:     badgeDotClass,
        formatDate:        formatDate,
        setText:           setText,
        gaugeOffset:       gaugeOffset,
        gaugeColour:       gaugeColour,
        showToast:         showToast,
        clamp:             clamp
    };

}());
