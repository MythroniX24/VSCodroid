#!/usr/bin/env node
/**
 * VSCodroid Server Fallback Bootstrap
 *
 * This script is only used when ProcessManager cannot find server-main.js
 * directly (i.e. vscode-reh has not been built/extracted yet).
 *
 * It starts a minimal HTTP server that:
 *  - Responds to /healthz with 200 OK (used by ProcessManager health checks)
 *  - Responds to / with a diagnostic HTML page
 *
 * When server-main.js IS present, ProcessManager launches it directly
 * without going through this script — eliminating the fork() that
 * Android's phantom-process killer would otherwise kill.
 */

'use strict';

const http = require('http');
const path = require('path');
const fs   = require('fs');

const args = {};
process.argv.slice(2).forEach(arg => {
    const eqIdx = arg.indexOf('=');
    if (eqIdx > 0) {
        const key = arg.slice(0, eqIdx).replace(/^--/, '');
        args[key] = arg.slice(eqIdx + 1);
    } else {
        args[arg.replace(/^--/, '')] = true;
    }
});

const HOST = args['host'] || '127.0.0.1';
const PORT = parseInt(args['port'], 10) || 13337;

const SERVER_DIR   = path.dirname(__filename);
const REH_MAIN     = path.join(SERVER_DIR, 'vscode-reh', 'out', 'server-main.js');
const rehAvailable = fs.existsSync(REH_MAIN);

// ── Minimal health server ────────────────────────────────────────────────────
// Intentionally kept alive even if vscode-reh is present so that the
// Android WebView gets a quick response while ProcessManager boots the real
// server in a direct-launch scenario. This process will be replaced on the
// next hot-restart once ProcessManager detects vscode-reh is available.

const server = http.createServer((req, res) => {
    // Health probe — ProcessManager checks this first
    if (req.url === '/healthz') {
        res.writeHead(200, { 'Content-Type': 'text/plain', 'Cache-Control': 'no-store' });
        res.end('OK\n');
        return;
    }

    const body = rehAvailable
        ? diagnosticPageReady()
        : diagnosticPageNotBuilt();

    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end(body);
});

server.on('error', err => {
    process.stderr.write('[server.js] HTTP server error: ' + err.message + '\n');
    process.exit(1);
});

server.listen(PORT, HOST, () => {
    process.stdout.write('[server.js] Minimal health server on http://' + HOST + ':' + PORT + '\n');
});

process.on('SIGTERM', () => { server.close(); process.exit(0); });
process.on('SIGINT',  () => { server.close(); process.exit(0); });

// ── Diagnostic pages ─────────────────────────────────────────────────────────

function diagnosticPageReady() {
    return `<!DOCTYPE html>
<html><head><title>VSCodroid</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{background:#1e1e1e;color:#ccc;font-family:monospace;padding:40px;text-align:center}</style>
</head><body>
<h1 style="color:#4fc3f7">VSCodroid</h1>
<p>VS Code server found. Reloading…</p>
<p style="color:#666">Node.js ${process.version} | port ${PORT}</p>
<script>setTimeout(()=>location.reload(),2000)</script>
</body></html>`;
}

function diagnosticPageNotBuilt() {
    return `<!DOCTYPE html>
<html><head><title>VSCodroid — Setup Required</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{background:#1e1e1e;color:#ccc;font-family:monospace;padding:40px;max-width:600px;margin:auto}
code{background:#333;padding:2px 6px;border-radius:3px}h2{color:#f9a825}</style>
</head><body>
<h1 style="color:#4fc3f7">VSCodroid</h1>
<h2>VS Code Server Not Found</h2>
<p>The VS Code server binary (<code>vscode-reh</code>) has not been
extracted yet. This usually means the APK was built without the full asset bundle.</p>
<h3>Required build steps:</h3>
<ol>
<li>Build VS Code REH: <code>./scripts/build-vscode.sh</code></li>
<li>Package assets: <code>./scripts/package-assets.sh</code></li>
<li>Rebuild the APK with the bundled assets</li>
</ol>
<p style="color:#666">Node.js ${process.version} on ${process.platform}/${process.arch} | port ${PORT}</p>
</body></html>`;
}
