/*
 * The console's only script, and everything in it is a convenience: every page
 * works with it switched off. Plain DOM, no framework — there are four
 * behaviours here, not an application.
 */
(function () {
    'use strict';

    var THEME_KEY = 'dbbackup-theme';

    /*
     * Theme. layout/base.html stamps data-theme in <head>, before first paint;
     * this only flips it. Reads the live value rather than a copy cached at
     * load, so a change made in another tab does not make the first click
     * appear to do nothing.
     */
    function currentTheme() {
        return document.documentElement.dataset.theme === 'light' ? 'light' : 'dark';
    }

    function renderThemeToggles() {
        var dark = currentTheme() === 'dark';
        document.querySelectorAll('[data-theme-icon]').forEach(function (el) {
            el.textContent = dark ? '☀' : '☾';
        });
        document.querySelectorAll('[data-theme-label]').forEach(function (el) {
            el.textContent = dark ? 'Light theme' : 'Dark theme';
        });
    }

    function toggleTheme() {
        var next = currentTheme() === 'dark' ? 'light' : 'dark';
        document.documentElement.dataset.theme = next;
        try { localStorage.setItem(THEME_KEY, next); } catch (e) { /* private mode */ }
        renderThemeToggles();
    }

    /* The sidebar becomes a drawer below 768px (see app.css). */
    function initDrawer() {
        var sidebar = document.getElementById('app-navigation');
        var overlay = document.querySelector('[data-drawer-overlay]');
        if (!sidebar || !overlay) return;

        function open() {
            sidebar.classList.add('is-open');
            overlay.hidden = false;
            document.body.classList.add('drawer-active');
            var first = sidebar.querySelector('a, button');
            if (first) first.focus();
        }

        function close() {
            if (!sidebar.classList.contains('is-open')) return;
            sidebar.classList.remove('is-open');
            overlay.hidden = true;
            document.body.classList.remove('drawer-active');
            var opener = document.querySelector('[data-drawer-open]');
            if (opener) opener.focus();
        }

        document.querySelectorAll('[data-drawer-open]').forEach(function (b) { b.addEventListener('click', open); });
        document.querySelectorAll('[data-drawer-close]').forEach(function (b) { b.addEventListener('click', close); });
        overlay.addEventListener('click', close);
        document.addEventListener('keydown', function (event) {
            if (event.key === 'Escape') close();
        });
    }

    /*
     * The one confirmation dialog (fragments/confirm-dialog). A form declaring
     * data-confirm does not submit on its own: the submit is intercepted, the
     * dialog opens, and the form is submitted only if the operator accepts.
     * Used instead of the native confirm(), which ignores the theme and blocks
     * the event loop.
     */
    function initConfirmDialog() {
        var dialog = document.getElementById('confirm-dialog');
        if (!dialog) return;
        var modal = dialog.querySelector('[data-confirm-modal]');
        var title = dialog.querySelector('[data-confirm-title]');
        var body = dialog.querySelector('[data-confirm-body]');
        var accept = dialog.querySelector('[data-confirm-accept]');
        var cancel = dialog.querySelector('[data-confirm-cancel]');
        var pendingForm = null;
        var lastFocused = null;

        function close() {
            dialog.hidden = true;
            pendingForm = null;
            // Focus goes back where it came from, or the operator is dropped at
            // the top of the document with no idea which row they were on.
            if (lastFocused && lastFocused.isConnected) lastFocused.focus();
        }

        function open(form) {
            pendingForm = form;
            lastFocused = document.activeElement;
            title.textContent = form.dataset.confirmTitle || 'Confirm';
            body.textContent = form.dataset.confirm;
            accept.textContent = form.dataset.confirmAccept || 'Delete';
            dialog.hidden = false;
            accept.focus();
        }

        document.addEventListener('submit', function (event) {
            var form = event.target;
            if (!form.dataset || !form.dataset.confirm || form.dataset.confirmed === 'true') return;
            event.preventDefault();
            open(form);
        });

        accept.addEventListener('click', function () {
            var form = pendingForm;
            close();
            if (!form) return;
            // Marked rather than submitted through form.submit(), which skips
            // the submit event and any other handler on the form.
            form.dataset.confirmed = 'true';
            if (form.requestSubmit) form.requestSubmit(); else form.submit();
        });
        cancel.addEventListener('click', close);
        dialog.addEventListener('click', function (event) { if (event.target === dialog) close(); });
        document.addEventListener('keydown', function (event) {
            if (dialog.hidden) return;
            if (event.key === 'Escape') { close(); return; }
            if (event.key !== 'Tab') return;
            var nodes = Array.prototype.slice.call(modal.querySelectorAll('button')).filter(function (n) { return !n.disabled; });
            if (!nodes.length) return;
            var first = nodes[0], last = nodes[nodes.length - 1];
            if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
            else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
        });
    }

    document.addEventListener('DOMContentLoaded', function () {
        renderThemeToggles();
        document.querySelectorAll('[data-theme-toggle]').forEach(function (b) { b.addEventListener('click', toggleTheme); });
        initDrawer();
        initConfirmDialog();
        // A flash reports the navigation that rendered this page; once read it
        // can go. Removed rather than hidden so the layout closes up.
        document.querySelectorAll('[data-flash-dismiss]').forEach(function (button) {
            button.addEventListener('click', function () {
                var flash = button.closest('[data-flash]');
                if (flash) flash.remove();
            });
        });
    });
}());
