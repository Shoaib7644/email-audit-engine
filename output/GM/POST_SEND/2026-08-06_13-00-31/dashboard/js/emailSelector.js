"use strict";
/* ================================================================
   emailSelector.js  —  Email dropdown + search box
   • Populates #email-select with file options
   • Wires change event → State.set('selectedFileIdx')
   • Wires #topbar-search to filter dropdown options
   Depends on: Utils, State
   Load order: 9 of 11
================================================================ */

var EmailSelector = (function () {

    var _files = [];

    /* ── Populate the <select> element ─────────────────────────── */
    function populate(files) {
        _files = files;
        var select = document.getElementById('email-select');
        if (!select) { return; }

        /* Clear everything after the default placeholder option */
        while (select.options.length > 1) { select.remove(1); }

        files.forEach(function (file, i) {
            var opt   = document.createElement('option');
            opt.value = String(i);
            var icon  = Utils.normaliseStatus(file.overallStatus) === 'PASS' ? '✓ ' : '✗ ';
            opt.text  = icon + file.fileName;
            opt.setAttribute('data-filename', file.fileName.toLowerCase());
            select.appendChild(opt);
        });
    }

    /* ── Select a specific file index programmatically ──────────── */
    function selectIndex(idx) {
        var select = document.getElementById('email-select');
        if (!select) { return; }
        if (idx >= 0 && idx < _files.length) {
            select.value = String(idx);
        } else {
            select.value = '';
        }
    }

    /* ── Wire the <select> change event ─────────────────────────── */
    function wireSelect() {
        var select = document.getElementById('email-select');
        if (!select) { return; }
        select.addEventListener('change', function () {
            var raw = select.value;
            if (raw === '') {
                State.set('selectedFileIdx', -1);
            } else {
                State.set('selectedFileIdx', parseInt(raw, 10));
            }
        });
    }

    /* ── Wire the topbar search to filter dropdown options ──────── */
    function wireSearch() {
        var input  = document.getElementById('topbar-search');
        var select = document.getElementById('email-select');
        if (!input || !select) { return; }

        input.addEventListener('input', function () {
            var q = input.value.toLowerCase().trim();
            var opts = select.querySelectorAll('option[data-filename]');

            opts.forEach(function (opt) {
                var name = opt.getAttribute('data-filename') || '';
                opt.hidden = q.length > 0 && name.indexOf(q) < 0;
            });

            /* If the currently selected option is now hidden, deselect */
            var currentOpt = select.options[select.selectedIndex];
            if (currentOpt && currentOpt.hidden) {
                select.value = '';
                State.set('selectedFileIdx', -1);
            }
        });
    }

    /* ── Auto-select first failing file, else first file ────────── */
    function autoSelect(files) {
        if (!files || files.length === 0) { return; }
        var firstFailIdx = -1;
        for (var i = 0; i < files.length; i++) {
            if (Utils.normaliseStatus(files[i].overallStatus) !== 'PASS') {
                firstFailIdx = i;
                break;
            }
        }
        var targetIdx = firstFailIdx >= 0 ? firstFailIdx : 0;
        selectIndex(targetIdx);
        State.set('selectedFileIdx', targetIdx);
    }

    /* ── Public API ─────────────────────────────────────────────── */
    return {
        populate:    populate,
        selectIndex: selectIndex,
        autoSelect:  autoSelect,
        wireSelect:  wireSelect,
        wireSearch:  wireSearch
    };

}());
