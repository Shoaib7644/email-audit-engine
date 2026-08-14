"use strict";
/* ================================================================
   state.js  —  Central application state
   Single source of truth.  No DOM.  No rendering.
   Other modules subscribe via State.on() and update via State.set().
   Depends on: Utils
   Load order: 3 of 11
================================================================ */

var State = (function () {

    /* ── Internal state object ──────────────────────────────────── */
    var _state = {
        auditData:        null,   /* parsed root payload            */
        files:            [],     /* normalised file array          */
        selectedFileIdx:  -1,     /* index into files[]             */
        selectedCategory: "overview", /* nav category key           */
        searchQuery:      ""      /* topbar search filter           */
    };

    /* ── Subscriber registry ────────────────────────────────────── */
    var _listeners = {};          /* { eventName: [fn, fn, …] }     */

    function on(event, fn) {
        if (!_listeners[event]) { _listeners[event] = []; }
        _listeners[event].push(fn);
    }

    function off(event, fn) {
        if (!_listeners[event]) { return; }
        _listeners[event] = _listeners[event].filter(function (f) { return f !== fn; });
    }

    function _emit(event, payload) {
        var fns = _listeners[event] || [];
        fns.forEach(function (fn) {
            try { fn(payload); } catch (e) { console.error("[State] listener error on " + event, e); }
        });
    }

    /* ── State mutations ────────────────────────────────────────── */
    function set(key, value) {
        if (!(key in _state)) {
            console.warn("[State] Unknown key:", key);
            return;
        }
        _state[key] = value;
        _emit("change:" + key, value);
        _emit("change", { key: key, value: value });
    }

    function get(key) {
        return _state[key];
    }

    /* ── Convenience selectors ──────────────────────────────────── */
    function selectedFile() {
        var idx = _state.selectedFileIdx;
        if (idx < 0 || idx >= _state.files.length) { return null; }
        return _state.files[idx];
    }

    function allFiles() {
        return _state.files;
    }

    /* ── Public API ─────────────────────────────────────────────── */
    return {
        on:           on,
        off:          off,
        set:          set,
        get:          get,
        selectedFile: selectedFile,
        allFiles:     allFiles
    };

}());
