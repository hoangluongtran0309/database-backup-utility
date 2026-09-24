/*
 * The console's only script, and everything in it is a convenience: every page
 * works with it switched off. Plain DOM, no framework — there are a handful of
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

    /*
     * One submit per page. Every form here either starts something on the
     * server (a backup, a restore, a connection test) or destroys something,
     * and a second click while the first request is in flight used to start
     * a second backup. Once a form submits, its button shows what is happening
     * — a connection test can take the whole connect timeout — and every other
     * submit on the page is refused until the browser navigates away.
     *
     * Must be registered after initConfirmDialog(): both listen on document,
     * and a submit the dialog intercepted (defaultPrevented) has not happened.
     */
    function initSubmitGuard() {
        var busy = false;

        document.addEventListener('submit', function (event) {
            if (event.defaultPrevented) return;
            if (busy) { event.preventDefault(); return; }
            busy = true;
            var form = event.target;
            // requestSubmit() from the confirm dialog carries no submitter.
            var button = event.submitter || form.querySelector('[type="submit"]');
            if (!button) return;
            // Disabled after the event, not during it: a disabled submitter is
            // left out of the form data, and one of them may one day carry a value.
            setTimeout(function () {
                button.dataset.idleLabel = button.textContent;
                if (button.dataset.busyLabel) button.textContent = button.dataset.busyLabel;
                button.setAttribute('aria-busy', 'true');
                button.disabled = true;
            }, 0);
        });

        // Back/forward cache restores the page exactly as it was left — with the
        // button still disabled and nothing in flight. Put it back.
        window.addEventListener('pageshow', function (event) {
            if (!event.persisted) return;
            busy = false;
            document.querySelectorAll('[aria-busy="true"]').forEach(function (button) {
                if (button.dataset.idleLabel) button.textContent = button.dataset.idleLabel;
                button.removeAttribute('aria-busy');
                button.disabled = false;
            });
        });
    }

    /*
     * A select marked data-autosubmit submits its form as soon as it changes —
     * the restore page's choice of target, which reloads the page so the
     * warning describes the target chosen. The form's own button, marked
     * data-autosubmit-fallback, is what does this with the script off, so it
     * is hidden only once the script is known to be running.
     */
    function initAutoSubmit() {
        document.querySelectorAll('select[data-autosubmit]').forEach(function (select) {
            var fallback = select.form && select.form.querySelector('[data-autosubmit-fallback]');
            if (fallback) fallback.hidden = true;
            select.addEventListener('change', function () {
                if (select.form.requestSubmit) select.form.requestSubmit(); else select.form.submit();
            });
        });
    }

    /* Custom S3-compatible endpoints usually require path-style access. */
    function initStoragePathStyleDefault() {
        var endpoint = document.querySelector('[data-storage-endpoint]');
        var pathStyle = document.querySelector('[data-storage-path-style]');
        if (!endpoint || !pathStyle) return;
        var manuallyChanged = false;
        pathStyle.addEventListener('change', function () { manuallyChanged = true; });
        endpoint.addEventListener('input', function () {
            if (!manuallyChanged) pathStyle.checked = endpoint.value.trim() !== '';
        });
    }

    /* A new target shows either network credentials or one SQLite file path. */
    function initEnginePortDefault() {
        var engine = document.querySelector('[data-engine-select]');
        var port = document.getElementById('port');
        var authGroup = document.querySelector('[data-mongodb-auth-group]');
        var authDatabase = document.getElementById('authenticationDatabase');
        var oracleGroup = document.querySelector('[data-oracle-directory-group]');
        var oracleDirectory = document.getElementById('dataPumpDirectory');
        var networkGroups = document.querySelectorAll('[data-network-target-field]');
        var database = document.getElementById('database');
        var networkLabel = document.querySelector('[data-network-database-label]');
        var oracleLabel = document.querySelector('[data-oracle-database-label]');
        var sqliteLabel = document.querySelector('[data-sqlite-database-label]');
        var sqliteHelp = document.querySelector('[data-sqlite-database-help]');
        var oracleHelp = document.querySelector('[data-oracle-database-help]');
        var verificationGroup = document.querySelector('[data-verification-target-field]');
        var verifyAfterBackup = document.getElementById('verifyAfterBackup');
        if (!engine) return;

        function renderEngineFields(changePort) {
            var option = engine.options[engine.selectedIndex];
            var sqlite = engine.value === 'SQLITE';
            var oracle = engine.value === 'ORACLE';
            var verificationSupported = !option || option.dataset.verificationSupported !== 'false';
            networkGroups.forEach(function (group) {
                group.hidden = sqlite;
                group.querySelectorAll('input, select').forEach(function (field) {
                    field.disabled = sqlite;
                });
            });
            if (changePort && port) {
                port.value = option && option.dataset.defaultPort ? option.dataset.defaultPort : '';
            }
            if (authGroup && authDatabase) {
                var mongo = engine.value === 'MONGODB';
                authGroup.hidden = !mongo;
                authDatabase.disabled = !mongo;
                authDatabase.required = mongo;
                if (changePort && mongo && !authDatabase.value) authDatabase.value = 'admin';
            }
            if (oracleGroup && oracleDirectory) {
                oracleGroup.hidden = !oracle;
                oracleDirectory.disabled = !oracle;
                oracleDirectory.required = oracle;
            }
            if (networkLabel) networkLabel.hidden = sqlite || oracle;
            if (oracleLabel) oracleLabel.hidden = !oracle;
            if (sqliteLabel) sqliteLabel.hidden = !sqlite;
            if (sqliteHelp) sqliteHelp.hidden = !sqlite;
            if (oracleHelp) oracleHelp.hidden = !oracle;
            if (database && changePort) {
                database.placeholder = sqlite ? 'apps/shop.db' : (oracle ? 'FREEPDB1' : 'shop');
            }
            if (verificationGroup && verifyAfterBackup) {
                verificationGroup.hidden = !verificationSupported;
                verifyAfterBackup.disabled = !verificationSupported;
                if (!verificationSupported) verifyAfterBackup.checked = false;
            }
        }

        renderEngineFields(false);
        engine.addEventListener('change', function () { renderEngineFields(true); });
    }

    /*
     * Follows a running backup or restore (ADR-010). The page marks the block
     * that can change with data-live, and data-live-active="true" while the
     * job runs; this re-fetches the same URL, and swaps the block for the one
     * in the response. Nothing is derived here: the server renders every
     * state, badges and buttons included, exactly as a reload would.
     *
     * Stops at the first finished state, so an error message is never
     * replaced while someone is reading it. Each poll is a signed-in request,
     * so a followed job keeps its session alive until it finishes (ADR-011).
     */
    function initLiveRegion() {
        var region = document.querySelector('[data-live]');
        if (!region || region.dataset.liveActive !== 'true' || !window.fetch) return;
        var announcer = document.querySelector('[data-live-announcer]');
        var INTERVAL = 2000, MAX_BACKOFF = 30000;
        var delay = INTERVAL;
        var warning = null;

        function warn(text) {
            if (!warning) {
                warning = document.createElement('p');
                warning.className = 'alert alert-warning';
                warning.setAttribute('role', 'status');
                region.parentNode.insertBefore(warning, region);
            }
            warning.textContent = text;
        }

        function clearWarning() {
            if (warning) { warning.remove(); warning = null; }
        }

        function schedule() { setTimeout(poll, delay); }

        function poll() {
            // A background tab has nobody to show it to. Rather than keep
            // waking up to check, wait to be looked at — then ask at once, not
            // at the end of however long the backoff had grown to.
            if (document.hidden) {
                document.addEventListener('visibilitychange', function resume() {
                    if (document.hidden) return;
                    document.removeEventListener('visibilitychange', resume);
                    poll();
                });
                return;
            }
            fetch(window.location.href, { headers: { Accept: 'text/html' }, cache: 'no-store' })
                .then(function (response) {
                    if (!response.ok) throw new Error('HTTP ' + response.status);
                    return response.text();
                })
                .then(function (html) {
                    var doc = new DOMParser().parseFromString(html, 'text/html');
                    if (doc.querySelector('[data-sign-in]')) {
                        // Answered with the sign-in page: the session ended —
                        // timed out, or signed out from another tab. Asking
                        // again will not change that, so stop and say so. The
                        // link comes back here once signed in.
                        warn('You have been signed out. ');
                        var again = document.createElement('a');
                        again.href = window.location.href;
                        again.textContent = 'Sign in again to keep following this job.';
                        warning.appendChild(again);
                        return;
                    }
                    var next = doc.querySelector('[data-live]');
                    if (!next) {
                        // Redirected somewhere else — the job was deleted, most
                        // likely. Say so rather than keep asking.
                        warn('This page can no longer follow the job. Reload to see what happened.');
                        return;
                    }
                    clearWarning();
                    delay = INTERVAL;
                    // Swapped only when something changed, so focus and text
                    // selection survive the polls that find nothing new.
                    if (next.innerHTML !== region.innerHTML) region.innerHTML = next.innerHTML;
                    if (next.dataset.liveActive === 'true') { schedule(); return; }
                    region.dataset.liveActive = 'false';
                    if (announcer) announcer.textContent = next.dataset.liveAnnounce || '';
                })
                .catch(function () {
                    warn('Lost contact with the console. The job is unaffected; still trying…');
                    delay = Math.min(delay * 2, MAX_BACKOFF);
                    schedule();
                });
        }

        schedule();
    }

    document.addEventListener('DOMContentLoaded', function () {
        renderThemeToggles();
        document.querySelectorAll('[data-theme-toggle]').forEach(function (b) { b.addEventListener('click', toggleTheme); });
        initDrawer();
        initConfirmDialog();
        initSubmitGuard();
        initAutoSubmit();
        initStoragePathStyleDefault();
        initEnginePortDefault();
        initLiveRegion();
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
