/*
 * Stamps the colour theme on <html> before the stylesheet applies, so the
 * first paint is already in the right one. Loaded without defer, in <head>,
 * for exactly that reason; app.js only flips it afterwards.
 *
 * A file rather than an inline <script>: the Content-Security-Policy allows
 * scripts from this origin only (see SecurityConfig).
 */
(function () {
    var saved = null;
    try { saved = localStorage.getItem('dbbackup-theme'); } catch (e) { /* private mode */ }
    var preferred = window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
    document.documentElement.dataset.theme = saved || preferred;
}());
