"use strict";
/* ================================================================
   integration.js  —  State → DOM orchestration layer
   ================================================================
   Changes:
     1. Execution Summary rendered ONCE into #exec-summary-bar
        in the header area on init — NOT inside overview content.
     2. Counts always computed by iterating the files array
        directly, never from root-level passedFiles/failedFiles
        which the Java engine may populate with rule counts, not
        email counts.
     3. Export → PDF via browser print-to-PDF.
     4. Screenshot on Overview only (flag passed to Renderer).
================================================================ */

var Integration = (function () {

    var _emptyState  = null;
    var _detailView  = null;
    var _generatedAt = null;
    var _syncingHash = false;

    /* ================================================================
       PUBLIC INIT
    ================================================================ */
    function init(auditData) {
        _emptyState  = document.getElementById('empty-state');
        _detailView  = document.getElementById('detail-view');
        _generatedAt = auditData.generatedAt;

        /* Topbar timestamp */
        Utils.setText('hdr-timestamp', Utils.formatDate(_generatedAt));

        /* ── Execution Summary rendered ONCE into the header bar ── */
        _renderExecutionSummary(auditData);

        _wireTheme();
        _wireExport();

        Sidebar.wireNavButtons();
        _wireHashNavigation();

        EmailSelector.wireSelect();
        EmailSelector.wireSearch();

        State.on('change:selectedFileIdx',  _onFileChange);
        State.on('change:selectedCategory', _onCategoryChange);
    }

    /* ================================================================
       EXECUTION SUMMARY  — rendered once into #exec-summary-bar
       Counts are ALWAYS computed by iterating the files array.
       We never read root-level passedFiles / failedFiles because
       the Java engine sometimes puts rule-level counts there, not
       email-level counts, which causes the numbers to be wrong.
    ================================================================ */
    function _renderExecutionSummary(auditData) {
        var bar = document.getElementById('exec-summary-bar');
        if (!bar) { return; }

        var files = Array.isArray(auditData.files) ? auditData.files : [];

        /* Compute from the files array — ground truth */
        var total   = files.length;
        var passed  = 0;
        var failed  = 0;
        var skipped = 0;

        files.forEach(function (f) {
            var s = _normStatus(f.overallStatus);
            if      (s === 'PASS')                  { passed++;  }
            else if (s === 'FAIL' || s === 'ERROR') { failed++;  }
            else                                     { skipped++; }
        });

        var rate      = total > 0 ? Math.round((passed / total) * 100) : 0;
        var rateColor = rate >= 80 ? 'var(--green)' : rate >= 50 ? 'var(--amber)' : 'var(--red)';

        bar.innerHTML =
            '<div class="esb-label">Execution Summary</div>' +
            '<div class="esb-cards">' +
            _esbCard(total,          'Emails',  'var(--accent)',        '&#128231;') +
            _esbCard(passed,         'Passed',  'var(--green)',         '&#10003;') +
            _esbCard(failed,         'Failed',  'var(--red)',           '&#10007;') +
            _esbCard(skipped,        'Skipped', 'var(--text-secondary)','&#9675;')  +
            _esbCard(rate + '%',     'Pass Rate', rateColor,            '&#128200;') +
            '</div>';
    }

    function _esbCard(value, label, color, icon) {
        return (
            '<div class="esb-card">' +
            '<span class="esb-card-icon" aria-hidden="true">' + icon + '</span>' +
            '<span class="esb-card-value" style="color:' + color + '">' + value + '</span>' +
            '<span class="esb-card-label">' + _esc(label) + '</span>' +
            '</div>'
        );
    }

    /* ================================================================
       STATUS NORMALISER  (local copy — keeps integration self-contained)
    ================================================================ */
    function _normStatus(status) {
        if (!status) { return 'SKIPPED'; }
        switch (String(status).toUpperCase()) {
            case 'PASS': case 'PASSED': case 'SUCCESS': return 'PASS';
            case 'FAIL': case 'FAILED':                 return 'FAIL';
            case 'ERROR':                               return 'ERROR';
            default:                                    return 'SKIPPED';
        }
    }

    function _esc(v) {
        if (v == null) { return ''; }
        return String(v)
            .replace(/&/g, '&amp;')
            .replace(/</g,  '&lt;')
            .replace(/>/g,  '&gt;')
            .replace(/"/g,  '&quot;')
            .replace(/'/g,  '&#039;');
    }

    /* ================================================================
       STATE HANDLERS
    ================================================================ */

    function _onFileChange() {
        var file = State.selectedFile();

        if (!file) {
            _showEmptyState();
            Sidebar.reset();
            return;
        }

        Sidebar.updateBadges(file);
        Sidebar.updateScorecard(file);

        EmailSelector.selectIndex(State.get('selectedFileIdx'));

        _renderCenter(file, State.get('selectedCategory'));
    }

    function _onCategoryChange(categoryKey) {
        Sidebar.setActiveCategory(categoryKey);
        _writeCategoryHash(categoryKey);
        var file = State.selectedFile();
        if (!file) { return; }
        _renderCenter(file, categoryKey);
    }

    /* ================================================================
       CENTER PANEL RENDERING
       Screenshot flag is true only for the overview category.
    ================================================================ */
    function _renderCenter(file, categoryKey) {
        if (!_detailView) { return; }

        if (_emptyState) { _emptyState.style.display = 'none'; }

        var html =
            Renderer.buildDetailHeader(file) +
            Renderer.buildCategoryView(file, categoryKey, _generatedAt, categoryKey === 'overview');

        _detailView.innerHTML = html;
        _detailView.style.display = 'block';

        _detailView.classList.remove('is-visible');
        requestAnimationFrame(function () {
            requestAnimationFrame(function () {
                _detailView.classList.add('is-visible');
            });
        });

        Findings.wireAccordion(_detailView);
        Renderer.wireScreenshot(_detailView);
        Renderer.wireLinks(_detailView, file);
        Renderer.wireImages(_detailView, file);

        var main = document.getElementById('main-panel');
        if (main) { main.scrollTop = 0; }
    }

    /* ================================================================
       HASH NAVIGATION
    ================================================================ */
    function _wireHashNavigation() {
        window.addEventListener('hashchange', function () {
            var category = _categoryFromHash();
            if (!category) { return; }
            _syncingHash = true;
            State.set('selectedCategory', category);
            _syncingHash = false;
        });
    }

    function _writeCategoryHash(categoryKey) {
        if (_syncingHash || !categoryKey) { return; }
        var next = '#' + encodeURIComponent(categoryKey);
        if (window.location.hash !== next) {
            window.location.hash = next;
        }
    }

    function _categoryFromHash() {
        var raw = window.location.hash ? window.location.hash.substring(1) : '';
        if (!raw) { return null; }
        try {
            return decodeURIComponent(raw);
        } catch (_) {
            return raw;
        }
    }

    /* ================================================================
       EMPTY STATE
    ================================================================ */
    function _showEmptyState() {
        if (_emptyState) { _emptyState.style.display  = ''; }
        if (_detailView) {
            _detailView.style.display = 'none';
            _detailView.classList.remove('is-visible');
        }
    }

    /* ================================================================
       THEME TOGGLE
    ================================================================ */
    function _wireTheme() {
        var btn  = document.getElementById('btn-theme');
        var root = document.documentElement;
        if (!btn) { return; }

        var saved = _safeStorage('get', 'ea-theme');
        if (saved === 'light') { root.setAttribute('data-theme', 'light'); }

        btn.addEventListener('click', function () {
            var current = root.getAttribute('data-theme') || 'dark';
            var next    = current === 'dark' ? 'light' : 'dark';
            root.setAttribute('data-theme', next);
            _safeStorage('set', 'ea-theme', next);
        });
    }

    /* ================================================================
       EXPORT — PDF via browser print-to-PDF
    ================================================================ */
    function _wireExport() {
        var btn = document.getElementById('btn-export');
        if (!btn) { return; }
        btn.addEventListener('click', function () { _printToPdf(); });
    }

    function _printToPdf() {
        var STYLE_ID = '__pdf-print-style__';

        var prev = document.getElementById(STYLE_ID);
        if (prev) { prev.parentNode.removeChild(prev); }

        var style    = document.createElement('style');
        style.id     = STYLE_ID;
        style.textContent =
            '@media print {\n' +
            '  .nav-sidebar, .right-panel,\n' +
            '  #btn-theme, #btn-export, .topbar-controls,\n' +
            '  .screenshot-expand-btn { display: none !important; }\n' +
            '  .app-shell { display: block !important; }\n' +
            '  .main-panel, #main-panel, #detail-view {\n' +
            '    width: 100% !important; max-width: 100% !important;\n' +
            '    padding: 8px !important; overflow: visible !important;\n' +
            '  }\n' +
            '  .biz-card-body, .val-card-body,\n' +
            '  .findings-list { display: block !important; }\n' +
            '  .biz-card-chevron, .val-card-chevron { display: none !important; }\n' +
            '  .biz-card, .val-card, .summary-tile, .overview-table tr {\n' +
            '    break-inside: avoid; page-break-inside: avoid;\n' +
            '  }\n' +
            '  .summary-strip, .exec-summary-cards {\n' +
            '    display: grid !important;\n' +
            '    grid-template-columns: 1fr 1fr !important;\n' +
            '    gap: 10px !important;\n' +
            '  }\n' +
            '  .screenshot-img-wrap {\n' +
            '    max-height: none !important; overflow: visible !important;\n' +
            '  }\n' +
            '  * { -webkit-print-color-adjust: exact !important;\n' +
            '       print-color-adjust: exact !important; }\n' +
            '  @page { size: A4 portrait; margin: 14mm 12mm; }\n' +
            '}\n';

        document.head.appendChild(style);

        setTimeout(function () {
            window.print();
            setTimeout(function () {
                var el = document.getElementById(STYLE_ID);
                if (el) { el.parentNode.removeChild(el); }
            }, 1200);
        }, 150);
    }

    /* ================================================================
       SAFE localStorage WRAPPER
    ================================================================ */
    function _safeStorage(op, key, value) {
        try {
            if (op === 'get') { return localStorage.getItem(key); }
            if (op === 'set') { localStorage.setItem(key, value); }
        } catch (_) { /* silently ignore */ }
        return null;
    }

    return { init: init };

}());
