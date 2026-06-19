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
