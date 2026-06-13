package com.vscodroid.webview

/**
 * Centralized repository of JavaScript strings injected into the VS Code WebView
 * to enforce desktop-class behaviour on Android.
 *
 * All scripts are idempotent (guarded by a window flag) so they can be re-injected
 * safely on page reload without accumulating duplicate listeners.
 */
object DesktopModeJS {

    /**
     * Overrides [window.matchMedia] so VS Code's startup detection sees:
     *   - (pointer: fine)   → desktop mouse, not touch
     *   - (hover: hover)    → hover events available
     *   - (pointer: coarse) → false (no coarse pointer)
     *   - (hover: none)     → false (hover is available)
     *   - (prefers-color-scheme: dark) → true (force dark theme)
     *
     * Must run BEFORE VS Code's workbench.js parses these at startup — inject
     * this at the earliest possible moment (onPageStarted or via evaluateJavascript
     * immediately after loadUrl).
     *
     * VS Code web checks these values in:
     *   workbench/browser/web.main.ts  → isMobile detection
     *   workbench/browser/contextkeys.ts → inputLatency/touch context keys
     *   platform/theme/common/colorUtils.ts → prefers-color-scheme
     */
    const val MATCH_MEDIA_OVERRIDE = """
(function() {
    if (window.__vscodroidMatchMediaPatched) return;
    window.__vscodroidMatchMediaPatched = true;

    var _origMatchMedia = window.matchMedia.bind(window);

    // Map of query string → forced MediaQueryList result
    var _overrides = {
        '(pointer: coarse)': false,
        '(pointer: fine)': true,
        '(hover: none)': false,
        '(hover: hover)': true,
        '(any-pointer: coarse)': false,
        '(any-hover: none)': false,
        '(prefers-color-scheme: dark)': true,
        '(prefers-color-scheme: light)': false
    };

    function makeResult(query, matches) {
        var listeners = [];
        return {
            matches: matches,
            media: query,
            onchange: null,
            addListener: function(fn) { listeners.push(fn); },
            removeListener: function(fn) {
                var i = listeners.indexOf(fn);
                if (i >= 0) listeners.splice(i, 1);
            },
            addEventListener: function(type, fn) {
                if (type === 'change') listeners.push(fn);
            },
            removeEventListener: function(type, fn) {
                if (type === 'change') {
                    var i = listeners.indexOf(fn);
                    if (i >= 0) listeners.splice(i, 1);
                }
            },
            dispatchEvent: function() { return true; }
        };
    }

    window.matchMedia = function(query) {
        var trimmed = (query || '').trim().toLowerCase();
        if (trimmed in _overrides) {
            return makeResult(query, _overrides[trimmed]);
        }
        return _origMatchMedia(query);
    };

    // Also override navigator.maxTouchPoints to appear as a desktop that happens
    // to support stylus (value 1 = stylus, not mobile multi-touch tablet).
    // VS Code checks maxTouchPoints > 0 to decide whether to show touch UI.
    // We set it to 0 (pure desktop mouse, no touch UI) so the full desktop
    // workflow is presented.
    try {
        Object.defineProperty(navigator, 'maxTouchPoints', {
            get: function() { return 0; },
            configurable: true
        });
    } catch(e) {}
})();
"""

    /**
     * Injects CSS that enforces desktop-class rendering inside VS Code's workbench.
     *
     * Targets:
     * 1. Undo VS Code's own "pointer: coarse" responsive overrides.
     * 2. Restore desktop-proportioned activity bar (48px wide → compact sidebar).
     * 3. Restore desktop-proportioned editor tabs (35px height).
     * 4. Restore desktop-proportioned panel/terminal.
     * 5. Ensure the status bar has desktop height (22px not 28px).
     * 6. Keep scrollbars visible (VS Code hides them on mobile).
     * 7. Restore context menu font size (VS Code enlarges for touch).
     */
    const val DESKTOP_CSS = """
(function() {
    if (document.getElementById('vscodroid-desktop-css')) return;

    var style = document.createElement('style');
    style.id = 'vscodroid-desktop-css';
    style.textContent = [

        /* === Activity Bar === */
        /* Desktop width: 48px. Mobile collapses it. Force desktop. */
        '.part.activitybar { width: 48px !important; min-width: 48px !important; }',
        '.activitybar .action-item { height: 48px !important; width: 48px !important; }',
        '.activitybar .action-label { height: 48px !important; width: 48px !important; }',
        '.activitybar .active-item-indicator { width: 2px !important; }',

        /* === Editor Tabs === */
        '.tabs-container { height: 35px !important; }',
        '.tabs-container .tab { height: 35px !important; min-height: 35px !important; }',
        '.tabs-container .tab .tab-label { font-size: 13px !important; }',
        '.editor-group-watermark { font-size: 12px !important; }',

        /* === Side Bar === */
        '.part.sidebar .composite.viewlet { min-width: 170px; }',
        '.monaco-list-row { height: 22px !important; min-height: 22px !important; }',
        '.monaco-list-row .label-name { font-size: 13px !important; }',

        /* === Status Bar === */
        '.part.statusbar { height: 22px !important; }',
        '.statusbar-item { height: 22px !important; line-height: 22px !important; }',
        '.statusbar-item .codicon { font-size: 14px !important; }',

        /* === Panel (Terminal / Output / Problems) === */
        '.part.panel .title { height: 35px !important; }',
        '.part.panel .title .tabs-container { height: 35px !important; }',
        '.part.panel .title .tabs-container .tab { height: 35px !important; }',

        /* === Context Menus === */
        /* VS Code enlarges context menus for touch targets. Restore desktop size. */
        '.monaco-menu .action-menu-item { height: 26px !important; }',
        '.monaco-menu .action-label { padding: 0 8px !important; font-size: 13px !important; }',
        '.context-view.monaco-menu-container { font-size: 13px !important; }',

        /* === Quick Input (Command Palette, Quick Open) === */
        '.quick-input-widget { max-width: 640px !important; }',
        '.quick-input-list .monaco-list-row { height: 22px !important; }',

        /* === Breadcrumbs === */
        '.breadcrumbs-control { height: 22px !important; }',
        '.breadcrumb-item { height: 22px !important; line-height: 22px !important; }',

        /* === Editor Font === */
        '.monaco-editor .view-line { font-size: 14px; line-height: 19px; }',

        /* === Scrollbars — keep visible on desktop === */
        '.monaco-scrollable-element > .scrollbar { opacity: 0.5 !important; }',
        '.monaco-scrollable-element > .scrollbar:hover { opacity: 1.0 !important; }',
        '.monaco-scrollable-element > .scrollbar.active { opacity: 1.0 !important; }',

        /* === Title bar — show full desktop title bar === */
        '.part.titlebar { height: 30px !important; }',
        '.titlebar-left, .titlebar-center, .titlebar-right { height: 30px !important; line-height: 30px !important; }',

        /* === Minimap — restore to desktop size === */
        '.minimap { display: block !important; }',

        /* Pointer events override: ensure hover CSS works despite touch UA */
        '@media (pointer: coarse) {',
        '  .monaco-list-row { height: 22px !important; }',
        '  .tabs-container .tab { height: 35px !important; }',
        '  .activitybar .action-item { height: 48px !important; }',
        '  .part.statusbar { height: 22px !important; }',
        '}'

    ].join('\n');

    document.head.appendChild(style);
})();
"""

    /**
     * Overrides [window.open] and window navigation APIs to route external URLs
     * through [AndroidBridge], and installs keyboard shortcut handlers for
     * VS Code commands that conflict with Android system shortcuts.
     */
    const val WINDOW_OPEN_OVERRIDE = """
(function() {
    if (window.__vscodroidOpenPatched) return;
    window.__vscodroidOpenPatched = true;

    var orig = window.open;
    window.open = function(url, target, features) {
        if (url && /^https?:/.test(url) && typeof AndroidBridge !== 'undefined') {
            var t = (window.__vscodroid || {}).authToken;
            if (t) {
                AndroidBridge.openExternalUrl(url, t);
                return null;
            }
        }
        return orig.apply(window, arguments);
    };
})();
"""

    /**
     * Patches the VS Code workbench to install a synthetic contextmenu handler.
     *
     * When invoked from [TouchContextMenuHandler] via [WebView.evaluateJavascript],
     * this fires a real `contextmenu` event at the element under the touch point
     * so VS Code's context menu system activates identically to a desktop right-click.
     *
     * The function is exposed on `window.__vscodroid.fireContextMenu(x, y)` so it
     * can be called from Kotlin with device-independent pixel coordinates.
     */
    const val CONTEXT_MENU_BRIDGE = """
(function() {
    if (window.__vscodroidContextMenuInstalled) return;
    window.__vscodroidContextMenuInstalled = true;

    window.__vscodroid = window.__vscodroid || {};

    window.__vscodroid.fireContextMenu = function(x, y) {
        // Find the topmost element at the touch point
        var el = document.elementFromPoint(x, y);
        if (!el) el = document.body;

        // VS Code's contextmenu handling requires button=2 and correct coordinates
        var init = {
            bubbles: true,
            cancelable: true,
            clientX: x,
            clientY: y,
            screenX: x,
            screenY: y,
            x: x,
            y: y,
            button: 2,
            buttons: 2,
            composed: true
        };

        // Dispatch mousedown first — some context menu providers check this
        el.dispatchEvent(new MouseEvent('mousedown', Object.assign({}, init, { button: 2, buttons: 2 })));
        el.dispatchEvent(new MouseEvent('mouseup', Object.assign({}, init, { button: 2, buttons: 0 })));
        el.dispatchEvent(new MouseEvent('contextmenu', init));
    };

    // Also expose fireClick for programmatic single-click (used by trackpad tap)
    window.__vscodroid.fireClick = function(x, y, button) {
        button = button || 0;
        var el = document.elementFromPoint(x, y);
        if (!el) el = document.body;
        var init = {
            bubbles: true, cancelable: true,
            clientX: x, clientY: y,
            button: button, buttons: button === 0 ? 1 : (button === 2 ? 2 : 4)
        };
        el.dispatchEvent(new MouseEvent('mousedown', init));
        el.dispatchEvent(new MouseEvent('mouseup', Object.assign({}, init, { buttons: 0 })));
        el.dispatchEvent(new MouseEvent('click', Object.assign({}, init, { buttons: 0 })));
    };
})();
"""

    /**
     * Registers VSCodroid-specific commands in VS Code's command palette.
     *
     * Waits for VS Code's module system (`window.require`) to be available, then
     * registers commands. Uses a retry loop to avoid fragile timing dependencies.
     *
     * Registered commands:
     * - `vscodroid.openInBrowser`       — open localhost URL in device browser
     * - `vscodroid.generateSshKey`      — generate ed25519 SSH key pair
     * - `vscodroid.copySshPublicKey`    — copy public key to clipboard
     * - `vscodroid.about`               — show about dialog
     * - `vscodroid.openFolderPicker`    — open Android SAF folder picker
     * - `vscodroid.installExtension`    — open VSIX picker (file chooser)
     * - `vscodroid.openToolchainSettings` — open toolchain manager
     */
    val PALETTE_COMMANDS = """
(function() {
    if (window.__vscodroidCommandsRegistered) return;
    window.__vscodroidCommandsRegistered = true;

    var attempts = 0;
    var interval = setInterval(function() {
        attempts++;
        if (attempts > 60) { clearInterval(interval); return; }
        try {
            var cs = window.require('vs/platform/commands/common/commands');
            if (!cs || !cs.CommandsRegistry) return;
            var ar = window.require('vs/platform/actions/common/actions');
            var ns = window.require('vs/platform/notification/common/notification');
            var qs = null;
            try { qs = window.require('vs/platform/quickinput/common/quickInput'); } catch(e) {}

            function getToken() { return (window.__vscodroid || {}).authToken; }
            function getBridge() { return typeof AndroidBridge !== 'undefined' ? AndroidBridge : null; }

            function registerCmd(id, title, fn) {
                try {
                    cs.CommandsRegistry.registerCommand(id, fn);
                    if (ar && ar.MenuRegistry && ar.MenuId) {
                        ar.MenuRegistry.appendMenuItem(ar.MenuId.CommandPalette, {
                            command: { id: id, title: title }
                        });
                    }
                } catch(e) {}
            }

            // Open in Browser
            registerCmd('vscodroid.openInBrowser', 'VSCodroid: Open in Browser', function(accessor) {
                var token = getToken(); var bridge = getBridge();
                if (!token || !bridge) return;
                if (qs) {
                    var svc = accessor.get(qs.IQuickInputService);
                    var box = svc.createInputBox();
                    box.title = 'Open in Browser';
                    box.placeholder = 'http://localhost:5173';
                    box.value = 'http://localhost:';
                    box.onDidAccept(function() {
                        var url = box.value.trim();
                        box.dispose();
                        if (!url) return;
                        if (!/^https?:\/\//.test(url)) url = 'http://' + url;
                        bridge.openExternalUrl(url, token);
                    });
                    box.onDidHide(function() { box.dispose(); });
                    box.show();
                }
            });

            // SSH Key commands
            registerCmd('vscodroid.generateSshKey', 'VSCodroid: Generate SSH Key', function(accessor) {
                var token = getToken(); var bridge = getBridge();
                if (!token || !bridge) return;
                var notif = accessor.get(ns.INotificationService);
                try {
                    var r = JSON.parse(bridge.generateSshKey(token, ''));
                    if (r.success) {
                        notif.info((r.existed ? 'SSH key already exists.' : 'SSH key generated!') + '\n' + r.publicKey);
                    } else {
                        notif.error('SSH key failed: ' + (r.error || 'unknown'));
                    }
                } catch(e) { notif.error('SSH error: ' + e); }
            });

            registerCmd('vscodroid.copySshPublicKey', 'VSCodroid: Copy SSH Public Key', function(accessor) {
                var token = getToken(); var bridge = getBridge();
                if (!token || !bridge) return;
                var notif = accessor.get(ns.INotificationService);
                var key = bridge.getSshPublicKey(token);
                if (key) { bridge.copyToClipboard(key); notif.info('SSH public key copied.'); }
                else { notif.warn('No SSH key found. Generate one first.'); }
            });

            // About
            registerCmd('vscodroid.about', 'VSCodroid: About', function() {
                var token = getToken(); var bridge = getBridge();
                if (token && bridge) bridge.showAboutDialog(token);
            });

            // Folder Picker
            registerCmd('vscodroid.openFolderPicker', 'VSCodroid: Open Folder from Device Storage', function() {
                var token = getToken(); var bridge = getBridge();
                if (token && bridge) bridge.openFolderPicker(token);
            });

            // Toolchain Settings
            registerCmd('vscodroid.openToolchainSettings', 'VSCodroid: Manage Toolchains', function() {
                var token = getToken(); var bridge = getBridge();
                if (token && bridge) bridge.openToolchainSettings(token);
            });

            // Install Extension from VSIX
            registerCmd('vscodroid.installExtension', 'VSCodroid: Install Extension from VSIX', function() {
                var token = getToken(); var bridge = getBridge();
                if (token && bridge) bridge.openVsixPicker(token);
            });

            clearInterval(interval);
        } catch(e) { /* VS Code not ready, retry */ }
    }, 200);
})();
""".trimIndent()

    /**
     * Registers a memory pressure handler and a low-memory cleanup routine.
     */
    const val MEMORY_PRESSURE_HANDLER = """
(function() {
    if (window.__vscodroidMemoryHandlerActive) return;
    window.__vscodroidMemoryHandlerActive = true;
    window.__vscodroid = window.__vscodroid || {};
    window.__vscodroid.onLowMemory = function(level) {
        console.warn('[VSCodroid] Memory pressure: level=' + level);
        try { if (typeof gc === 'function') gc(); } catch(e) {}
        try {
            var entries = performance.getEntries();
            for (var i = 0; i < entries.length; i++) {
                if (entries[i].name && entries[i].name.indexOf('blob:') === 0) {
                    try { URL.revokeObjectURL(entries[i].name); } catch(x) {}
                }
            }
        } catch(e) {}
    };
})();
"""

    /**
     * Installs the BroadcastChannel relay that lets Web Worker extension contexts
     * communicate with AndroidBridge (which is only available in the main page context).
     */
    const val BRIDGE_RELAY = """
(function() {
    if (typeof AndroidBridge === 'undefined') return;
    if (window.__vscodroidRelayActive) return;
    window.__vscodroidRelayActive = true;

    var ch = new BroadcastChannel('vscodroid-bridge');
    ch.onmessage = function(e) {
        var d = e.data;
        var token = (window.__vscodroid || {}).authToken;
        if (!token || !d || !d.cmd) return;
        try {
            var result;
            switch (d.cmd) {
                case 'openFolderPicker':      AndroidBridge.openFolderPicker(token); ch.postMessage({id:d.id,ok:true}); break;
                case 'getRecentFolders':      result=AndroidBridge.getRecentFolders(token); ch.postMessage({id:d.id,ok:true,data:result}); break;
                case 'openRecentFolder':      AndroidBridge.openRecentFolder(token,d.uri); ch.postMessage({id:d.id,ok:true}); break;
                case 'getStorageBreakdown':   result=AndroidBridge.getStorageBreakdown(token); ch.postMessage({id:d.id,ok:true,data:result}); break;
                case 'clearCaches':           result=AndroidBridge.clearCaches(token); ch.postMessage({id:d.id,ok:true,data:result}); break;
                case 'generateBugReport':     result=AndroidBridge.generateBugReport(token); ch.postMessage({id:d.id,ok:true,data:result}); break;
                case 'startGitHubAuth':       AndroidBridge.startGitHubAuth(d.url,token); ch.postMessage({id:d.id,ok:true}); break;
                case 'openToolchainSettings': AndroidBridge.openToolchainSettings(token); ch.postMessage({id:d.id,ok:true}); break;
                case 'generateSshKey':        result=AndroidBridge.generateSshKey(token,d.comment||''); ch.postMessage({id:d.id,ok:true,data:result}); break;
                case 'getSshPublicKey':       result=AndroidBridge.getSshPublicKey(token); ch.postMessage({id:d.id,ok:true,data:result}); break;
                case 'listSshKeys':           result=AndroidBridge.listSshKeys(token); ch.postMessage({id:d.id,ok:true,data:result}); break;
                case 'showAboutDialog':       AndroidBridge.showAboutDialog(token); ch.postMessage({id:d.id,ok:true}); break;
                case 'openExternalUrl':       AndroidBridge.openExternalUrl(d.url,token); ch.postMessage({id:d.id,ok:true}); break;
                case 'openVsixPicker':        AndroidBridge.openVsixPicker(token); ch.postMessage({id:d.id,ok:true}); break;
                default: ch.postMessage({id:d.id,ok:false,error:'Unknown command: '+d.cmd});
            }
        } catch(err) {
            ch.postMessage({id:d.id,ok:false,error:String(err)});
        }
    };
})();
"""
}
