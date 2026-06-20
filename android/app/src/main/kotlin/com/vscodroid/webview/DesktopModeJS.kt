package com.vscodroid.webview

/**
 * JavaScript injected into the VS Code WebView to enforce desktop-class behaviour.
 *
 * All scripts are:
 * - Idempotent (guarded by a window flag)
 * - Wrapped in try-catch so any error never crashes VS Code's own JS
 * - Injected in onPageFinished (safe timing — VS Code DOM is ready)
 */
object DesktopModeJS {

    // NOTE: An earlier version of this object included MATCH_MEDIA_OVERRIDE
    // (spoofing matchMedia(pointer:coarse)/(hover:none)) and DESKTOP_CSS
    // (forcing fixed-pixel sizes on VS Code's activity bar/tabs/status bar
    // via !important rules). Both were removed after direct evidence: loading
    // the same VS Code server URL in stock Chrome — with NO such overrides —
    // already renders the correct, fully-proportioned desktop 3-pane layout.
    // That means VS Code's own responsive CSS activates the desktop layout
    // purely from viewport WIDTH (which VSCodroidWebView.configure's wide-
    // viewport setting already provides), not from matchMedia or UA sniffing.
    // The removed overrides were therefore unnecessary at best, and at worst
    // were fighting VS Code's own layout calculations — forcing fixed pixel
    // sizes onto a page that the WebView was *also* independently scaling via
    // loadWithOverviewMode, producing compounding/incorrect proportions. See
    // VSCodroidWebView.kt's class doc for the full reasoning.

    /**
     * Adds padding for display cutouts/notches only — does NOT set any fixed
     * pixel sizes on VS Code's own UI elements. This is intentionally narrow
     * in scope (unlike the removed DESKTOP_CSS) so it cannot fight VS Code's
     * own layout calculations: it only reserves space at the very edges of
     * the screen for the activity bar / status bar / sidebar so they aren't
     * obscured by a notch or rounded corner.
     */
    val SAFE_AREA_CSS = """
(function() {
    try {
        if (document.getElementById('vscodroid-safe-area-css')) return;
        var s = document.createElement('style');
        s.id = 'vscodroid-safe-area-css';
        s.textContent = [
            '.part.activitybar{padding-left:env(safe-area-inset-left,0px)}',
            '.part.statusbar{padding-left:env(safe-area-inset-left,0px);padding-right:env(safe-area-inset-right,0px);padding-bottom:env(safe-area-inset-bottom,0px)}',
            '.part.sidebar{padding-left:env(safe-area-inset-left,0px)}'
        ].join('');
        document.head.appendChild(s);
    } catch(e) { /* cosmetic only */ }
})();
""".trimIndent()

    /**
     * Intercepts VS Code's OWN built-in "Open Folder" action (every way a user
     * can trigger it) and redirects it to [AndroidBridge.openFolderPicker] —
     * Android's native SAF folder-tree picker — instead of letting VS Code show
     * its own dialog.
     *
     * ── Why this is needed ───────────────────────────────────────────────────
     * VSCodroid runs full vscode-reh (server) mode, not the browser-only
     * vscode.dev. In this mode, VS Code's own built-in "Open Folder" command
     * (File menu, Ctrl+K Ctrl+O, the welcome-page button, Command Palette)
     * shows VS Code's OWN dialog browsing the REMOTE filesystem — which here
     * means the Node.js server process's filesystem, i.e. the app's PRIVATE
     * Android storage (`/data/data/com.vscodroid/...`), not the phone's real
     * storage the user actually wants to browse. That dialog never goes
     * through our [AndroidBridge]/SAF integration at all, which is why it
     * looked broken/empty/unfamiliar with no real "pick a folder from my
     * phone" option — a completely different, unrelated code path from our
     * custom `vscodroid.openFolderPicker` Command Palette entry.
     *
     * ── Three independent interception layers ────────────────────────────────
     * Implemented as three SEPARATE mechanisms (not relying on any single one)
     * because each covers a different real-world entry point and none alone is
     * guaranteed reliable across VS Code versions:
     *
     * 1. Keydown capture-phase listener for the Ctrl+K, Ctrl+O chord — the
     *    standard cross-version "Open Folder" keybinding. Captured BEFORE VS
     *    Code's own keybinding service sees the event (capture phase, on
     *    `document`), so this is guaranteed to win regardless of VS Code's
     *    internal command-resolution behaviour.
     *
     * 2. Click capture-phase listener for any element whose `href` (or an
     *    ancestor's `href`) is a `command:` URI containing "openFolder" — this
     *    is VS Code's standard convention for File-menu items and the
     *    welcome/empty-Explorer "Open Folder" button. Falls back to matching
     *    visible button/link TEXT content ("Open Folder") for elements that
     *    don't expose a `command:` href directly, maximising coverage without
     *    depending on a specific DOM attribute convention.
     *
     * 3. `CommandsRegistry.registerCommand('workbench.action.files.openFolder', ...)`
     *    override — VS Code's command registry is explicitly designed to allow
     *    extensions to override built-in commands by registering on the same
     *    id (this is how formatter/linter extensions routinely override
     *    built-in actions). Added as defense-in-depth for the Command Palette
     *    typed-search path specifically, which doesn't go through a clickable
     *    DOM link the way the menu/welcome-button paths do.
     */
    val INTERCEPT_OPEN_FOLDER = """
(function() {
    try {
        if (window.__vscodroidOpenFolderIntercepted) return;
        window.__vscodroidOpenFolderIntercepted = true;

        function triggerNativeFolderPicker() {
            var token = (window.__vscodroid || {}).authToken;
            if (token && typeof AndroidBridge !== 'undefined') {
                AndroidBridge.openFolderPicker(token);
                return true;
            }
            return false;
        }

        // Layer 1: Ctrl+K, Ctrl+O keyboard chord
        var awaitingChordKey = false;
        var chordTimeout = null;
        document.addEventListener('keydown', function(e) {
            try {
                var key = (e.key || '').toLowerCase();
                if (e.ctrlKey && !e.shiftKey && !e.altKey && key === 'k') {
                    awaitingChordKey = true;
                    clearTimeout(chordTimeout);
                    chordTimeout = setTimeout(function() { awaitingChordKey = false; }, 1500);
                    return;
                }
                if (awaitingChordKey && e.ctrlKey && !e.shiftKey && !e.altKey && key === 'o') {
                    awaitingChordKey = false;
                    clearTimeout(chordTimeout);
                    if (triggerNativeFolderPicker()) {
                        e.preventDefault();
                        e.stopImmediatePropagation();
                    }
                    return;
                }
                awaitingChordKey = false;
            } catch(err) {}
        }, true);

        // Layer 2: click on any "Open Folder" link/button (command: href or text match)
        document.addEventListener('click', function(e) {
            try {
                var el = e.target;
                for (var depth = 0; el && depth < 6; depth++, el = el.parentElement) {
                    var href = el.getAttribute && (el.getAttribute('href') || el.getAttribute('data-href'));
                    if (href && /command:.*openfolder/i.test(href)) {
                        if (triggerNativeFolderPicker()) {
                            e.preventDefault();
                            e.stopImmediatePropagation();
                        }
                        return;
                    }
                    var text = (el.textContent || '').trim().toLowerCase();
                    if (text === 'open folder' || text === 'open folder...') {
                        if (triggerNativeFolderPicker()) {
                            e.preventDefault();
                            e.stopImmediatePropagation();
                        }
                        return;
                    }
                }
            } catch(err) {}
        }, true);

        // Layer 3: override the command registry entry (Command Palette path)
        var attempts = 0;
        var iv = setInterval(function() {
            attempts++;
            if (attempts > 60) { clearInterval(iv); return; }
            try {
                var cs = window.require && window.require('vs/platform/commands/common/commands');
                if (!cs || !cs.CommandsRegistry) return;
                clearInterval(iv);
                cs.CommandsRegistry.registerCommand('workbench.action.files.openFolder', function() {
                    triggerNativeFolderPicker();
                });
            } catch(err) { /* retry */ }
        }, 250);
    } catch(e) {}
})();
""".trimIndent()

    /**
     * Exposes window.__vscodroid.fireContextMenu(x, y) for long-press right-click.
     * Called by VSCodroidWebViewComponent when a long-press is detected.
     */
    val CONTEXT_MENU_BRIDGE = """
(function() {
    try {
        if (window.__vscodroidContextMenuInstalled) return;
        window.__vscodroidContextMenuInstalled = true;
        window.__vscodroid = window.__vscodroid || {};
        window.__vscodroid.fireContextMenu = function(x, y) {
            try {
                var el = document.elementFromPoint(x, y) || document.body;
                var init = {bubbles:true,cancelable:true,clientX:x,clientY:y,
                            screenX:x,screenY:y,button:2,buttons:2,composed:true};
                el.dispatchEvent(new MouseEvent('mousedown', Object.assign({},init)));
                el.dispatchEvent(new MouseEvent('mouseup',   Object.assign({},init,{buttons:0})));
                el.dispatchEvent(new MouseEvent('contextmenu', init));
            } catch(e) {}
        };
        window.__vscodroid.fireClick = function(x, y) {
            try {
                var el = document.elementFromPoint(x, y) || document.body;
                var init = {bubbles:true,cancelable:true,clientX:x,clientY:y,button:0,buttons:1};
                el.dispatchEvent(new MouseEvent('mousedown', init));
                el.dispatchEvent(new MouseEvent('mouseup',   Object.assign({},init,{buttons:0})));
                el.dispatchEvent(new MouseEvent('click',     Object.assign({},init,{buttons:0})));
            } catch(e) {}
        };
    } catch(e) {}
})();
""".trimIndent()

    /**
     * Memory pressure handler — called from Kotlin on onTrimMemory.
     */
    val MEMORY_PRESSURE_HANDLER = """
(function() {
    try {
        if (window.__vscodroidMemoryHandlerActive) return;
        window.__vscodroidMemoryHandlerActive = true;
        window.__vscodroid = window.__vscodroid || {};
        window.__vscodroid.onLowMemory = function(level) {
            try { if (typeof gc==='function') gc(); } catch(e) {}
        };
    } catch(e) {}
})();
""".trimIndent()

    /**
     * BroadcastChannel relay so Web Worker contexts can call AndroidBridge APIs.
     * AndroidBridge is only available in the main page context, not Workers.
     */
    val BRIDGE_RELAY = """
(function() {
    try {
        if (typeof AndroidBridge === 'undefined') return;
        if (window.__vscodroidRelayActive) return;
        window.__vscodroidRelayActive = true;
        var ch = new BroadcastChannel('vscodroid-bridge');
        ch.onmessage = function(e) {
            try {
                var d = e.data;
                var token = (window.__vscodroid||{}).authToken;
                if (!token || !d || !d.cmd) return;
                var result, ok = true, err = '';
                try {
                    switch(d.cmd) {
                        case 'openFolderPicker':   AndroidBridge.openFolderPicker(token); break;
                        case 'getRecentFolders':   result=AndroidBridge.getRecentFolders(token); break;
                        case 'openRecentFolder':   AndroidBridge.openRecentFolder(token,d.uri); break;
                        case 'generateSshKey':     result=AndroidBridge.generateSshKey(token,d.comment||''); break;
                        case 'getSshPublicKey':    result=AndroidBridge.getSshPublicKey(token); break;
                        case 'openExternalUrl':    AndroidBridge.openExternalUrl(d.url,token); break;
                        case 'openVsixPicker':     AndroidBridge.openVsixPicker(token); break;
                        case 'showAboutDialog':    AndroidBridge.showAboutDialog(token); break;
                        case 'clearCaches':        result=AndroidBridge.clearCaches(token); break;
                        case 'generateBugReport':  result=AndroidBridge.generateBugReport(token); break;
                        case 'startGitHubAuth':    AndroidBridge.startGitHubAuth(d.url,token); break;
                        case 'openToolchainSettings': AndroidBridge.openToolchainSettings(token); break;
                        default: ok=false; err='Unknown: '+d.cmd;
                    }
                } catch(ex) { ok=false; err=String(ex); }
                ch.postMessage({id:d.id, ok:ok, data:result, error:err});
            } catch(e) {}
        };
    } catch(e) {}
})();
""".trimIndent()

    /**
     * Registers VSCodroid commands in VS Code's command palette.
     * Uses a retry loop (max 60 attempts × 300ms = 18s) to wait for VS Code
     * to register its command registry.
     */
    val PALETTE_COMMANDS = """
(function() {
    try {
        if (window.__vscodroidCommandsRegistered) return;
        window.__vscodroidCommandsRegistered = true;

        var attempts = 0;
        var iv = setInterval(function() {
            attempts++;
            if (attempts > 60) { clearInterval(iv); return; }
            try {
                var cs = window.require && window.require('vs/platform/commands/common/commands');
                if (!cs || !cs.CommandsRegistry) return;
                clearInterval(iv);

                function token()  { return (window.__vscodroid||{}).authToken; }
                function bridge() { return typeof AndroidBridge!=='undefined'?AndroidBridge:null; }

                function reg(id, title, fn) {
                    try { cs.CommandsRegistry.registerCommand(id, fn); } catch(e) {}
                    try {
                        var ar = window.require('vs/platform/actions/common/actions');
                        ar.MenuRegistry.appendMenuItem(ar.MenuId.CommandPalette,
                            {command:{id:id,title:title}});
                    } catch(e) {}
                }

                reg('vscodroid.openFolderPicker',
                    'VSCodroid: Open Folder from Device Storage',
                    function(){ var b=bridge(),t=token(); if(b&&t) b.openFolderPicker(t); });

                reg('vscodroid.installExtension',
                    'VSCodroid: Install Extension from VSIX',
                    function(){ var b=bridge(),t=token(); if(b&&t) b.openVsixPicker(t); });

                reg('vscodroid.generateSshKey',
                    'VSCodroid: Generate SSH Key',
                    function(){ var b=bridge(),t=token(); if(b&&t) b.generateSshKey(t,''); });

                reg('vscodroid.about',
                    'VSCodroid: About',
                    function(){ var b=bridge(),t=token(); if(b&&t) b.showAboutDialog(t); });

                reg('vscodroid.openToolchainSettings',
                    'VSCodroid: Manage Toolchains',
                    function(){ var b=bridge(),t=token(); if(b&&t) b.openToolchainSettings(t); });

            } catch(e) { /* VS Code not ready yet, will retry */ }
        }, 300);
    } catch(e) {}
})();
""".trimIndent()

    /**
     * Overrides window.open to route external URLs through AndroidBridge.
     */
    val WINDOW_OPEN_OVERRIDE = """
(function() {
    try {
        if (window.__vscodroidOpenPatched) return;
        window.__vscodroidOpenPatched = true;
        var orig = window.open;
        window.open = function(url, target, features) {
            try {
                if (url && /^https?:/.test(url) && typeof AndroidBridge!=='undefined') {
                    var t = (window.__vscodroid||{}).authToken;
                    if (t) { AndroidBridge.openExternalUrl(url, t); return null; }
                }
            } catch(e) {}
            return orig ? orig.apply(window, arguments) : null;
        };
    } catch(e) {}
})();
""".trimIndent()
}
