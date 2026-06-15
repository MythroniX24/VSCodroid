#!/usr/bin/env node
/**
 * VSCodroid Server Bootstrap  (server.js)
 *
 * Responsibilities:
 *  1. Parse args passed by ProcessManager (both --flag value and --flag=value)
 *  2. Patch product.json with VSCodroid branding / Open VSX / trusted domains
 *  3. If vscode-reh is present  → fork server-main.js (full VS Code)
 *  4. If vscode-reh is absent   → start minimal HTTP fallback server
 *
 * The /healthz endpoint is always available so ProcessManager health checks
 * succeed immediately while VS Code itself finishes initialising.
 */

'use strict';

const http   = require('http');
const path   = require('path');
const fs     = require('fs');
const cp     = require('child_process');

// ── Arg parsing ──────────────────────────────────────────────────────────────
// Handles BOTH formats:
//   --flag value      (ProcessManager passes space-separated)
//   --flag=value      (legacy / fallback)
const args = {};
const argv = process.argv.slice(2);
for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (!a.startsWith('--')) continue;
    const eqIdx = a.indexOf('=');
    if (eqIdx > 0) {
        // --flag=value format
        args[a.slice(2, eqIdx)] = a.slice(eqIdx + 1);
    } else {
        const key = a.slice(2);
        // Peek at next token: if it doesn't start with -- it's the value
        if (i + 1 < argv.length && !argv[i + 1].startsWith('--')) {
            args[key] = argv[++i];
        } else {
            args[key] = true;   // boolean flag
        }
    }
}

const HOST = args['host'] || '127.0.0.1';
const PORT = parseInt(args['port'], 10) || 13337;

const SERVER_DIR    = path.dirname(__filename);
const REH_DIR       = path.join(SERVER_DIR, 'vscode-reh');
const REH_MAIN      = path.join(REH_DIR, 'out', 'server-main.js');
const PRODUCT_JSON  = path.join(REH_DIR, 'product.json');

process.stdout.write('[server.js] host=' + HOST + ' port=' + PORT + '\n');
process.stdout.write('[server.js] args: ' + JSON.stringify(args) + '\n');

// ── product.json patch ────────────────────────────────────────────────────────
// Called BEFORE forking so VS Code reads patched values on startup.
// ProcessManager also patches this in Kotlin, but we repeat here as a fallback
// in case the Kotlin patch ran before assets were extracted.
function patchProductJson() {
    if (!fs.existsSync(PRODUCT_JSON)) return;
    try {
        var p = JSON.parse(fs.readFileSync(PRODUCT_JSON, 'utf8'));
        p.nameShort        = 'VSCodroid';
        p.nameLong         = 'VSCodroid';
        p.applicationName  = 'vscodroid';
        p.dataFolderName   = '.vscodroid';
        p.quality          = 'stable';
        p.enableTelemetry  = false;
        p.updateUrl        = '';
        p.extensionsGallery = {
            serviceUrl:           'https://open-vsx.org/vscode/gallery',
            itemUrl:              'https://open-vsx.org/vscode/item',
            resourceUrlTemplate:  'https://open-vsx.org/vscode/unpkg/{publisher}/{name}/{version}/{path}',
            controlUrl:           ''
        };
        p.linkProtectionTrustedDomains = [
            'https://open-vsx.org',
            'https://useblackbox.io',
            'https://github.com',
            'https://raw.githubusercontent.com'
        ];
        fs.writeFileSync(PRODUCT_JSON, JSON.stringify(p, null, 2), 'utf8');
        process.stdout.write('[server.js] product.json patched\n');
    } catch (e) {
        process.stderr.write('[server.js] product.json patch failed: ' + e.message + '\n');
    }
}

// ── Minimal health HTTP server ────────────────────────────────────────────────
// Always running so /healthz is available immediately regardless of whether
// the full VS Code server (server-main.js) has started yet.
var healthServer = http.createServer(function(req, res) {
    if (req.url === '/healthz') {
        res.writeHead(200, {'Content-Type': 'text/plain', 'Cache-Control': 'no-store'});
        res.end('OK\n');
        return;
    }
    // All other requests: serve a dark loading/diagnostic page
    var rehReady = fs.existsSync(REH_MAIN);
    res.writeHead(200, {'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store'});
    res.end(rehReady ? pageLoading() : pageNotBuilt());
});

healthServer.on('error', function(err) {
    // Port conflict — pick a different port is handled by ProcessManager
    process.stderr.write('[server.js] Health server error: ' + err.message + '\n');
});

// ── Launch VS Code or fallback ────────────────────────────────────────────────
if (fs.existsSync(REH_MAIN)) {
    process.stdout.write('[server.js] vscode-reh found — launching server-main.js\n');
    patchProductJson();

    // Build args array for server-main.js — MUST use space-separated pairs,
    // not --flag=value, because VS Code's argv parser uses minimist which
    // treats --flag=value as a literal string for some flag names.
    var serverArgs = [];
    var forwardKeys = [
        'host', 'port', 'extensions-dir', 'user-data-dir',
        'server-data-dir', 'logsPath', 'log'
    ];
    forwardKeys.forEach(function(key) {
        if (args[key] !== undefined && args[key] !== true) {
            serverArgs.push('--' + key);
            serverArgs.push(String(args[key]));
        }
    });
    if (args['without-connection-token'] === true) {
        serverArgs.push('--without-connection-token');
    }
    if (args['accept-server-license-terms'] === true) {
        serverArgs.push('--accept-server-license-terms');
    }

    process.stdout.write('[server.js] forking: ' + REH_MAIN + ' ' + serverArgs.join(' ') + '\n');

    var child = cp.fork(REH_MAIN, serverArgs, {
        env:   process.env,
        stdio: 'inherit',
        detached: false         // child dies when parent dies (prevents orphan)
    });

    child.on('error', function(err) {
        process.stderr.write('[server.js] Fork error: ' + err.message + '\n');
        process.exit(1);
    });
    child.on('exit', function(code, sig) {
        process.stdout.write('[server.js] server-main.js exited code=' + code + ' sig=' + sig + '\n');
        process.exit(code || 0);
    });

    // Health server listens on same port ONLY until server-main.js binds it.
    // server-main.js will fail to bind if health server holds the port.
    // Solution: health server binds to PORT+1; ProcessManager only checks /healthz.
    // ACTUALLY: we start the health server on a separate internal port (PORT+1)
    // and leave PORT for the real VS Code server.
    var healthPort = PORT + 1;
    healthServer.listen(healthPort, '127.0.0.1', function() {
        process.stdout.write('[server.js] Health server on port ' + healthPort + '\n');
    });

} else {
    // No vscode-reh — run health+fallback server on main port
    process.stdout.write('[server.js] vscode-reh not found — starting fallback on port ' + PORT + '\n');
    healthServer.listen(PORT, HOST, function() {
        process.stdout.write('[server.js] Fallback server running on ' + HOST + ':' + PORT + '\n');
    });
}

process.on('SIGTERM', function() { healthServer.close(); process.exit(0); });
process.on('SIGINT',  function() { healthServer.close(); process.exit(0); });
process.on('uncaughtException', function(err) {
    process.stderr.write('[server.js] Uncaught: ' + err.stack + '\n');
    process.exit(1);
});

// ── Diagnostic pages ─────────────────────────────────────────────────────────
function pageLoading() {
    return '<!DOCTYPE html><html><head>' +
        '<meta name="viewport" content="width=device-width,initial-scale=1">' +
        '<style>*{margin:0;padding:0}body{background:#1e1e1e;color:#858585;' +
        'font-family:monospace;display:flex;align-items:center;justify-content:center;' +
        'height:100vh;flex-direction:column;gap:16px}' +
        '.s{width:32px;height:32px;border:3px solid #333;border-top-color:#007acc;' +
        'border-radius:50%;animation:spin .8s linear infinite}' +
        '@keyframes spin{to{transform:rotate(360deg)}}</style></head>' +
        '<body><div style="color:#ccc;font-size:18px">VSCodroid</div>' +
        '<div>VS Code is starting…</div><div class="s"></div>' +
        '<script>setTimeout(function(){location.reload()},3000)</script>' +
        '</body></html>';
}

function pageNotBuilt() {
    return '<!DOCTYPE html><html><head>' +
        '<meta name="viewport" content="width=device-width,initial-scale=1">' +
        '<style>*{margin:0;padding:0;box-sizing:border-box}body{background:#1e1e1e;color:#ccc;' +
        'font-family:monospace;padding:40px;max-width:600px;margin:auto}' +
        'h1{color:#007acc;margin-bottom:16px}p{color:#858585;margin:8px 0;font-size:14px}' +
        'code{background:#252526;padding:2px 6px;border-radius:3px}</style></head>' +
        '<body><h1>VSCodroid</h1>' +
        '<p>VS Code server not found (<code>vscode-reh</code> not extracted).</p>' +
        '<p>The release APK must include the bundled VS Code server assets.</p>' +
        '<p>Node.js ' + process.version + ' on port ' + PORT + '</p>' +
        '</body></html>';
}
