#!/usr/bin/env node
/**
 * VSCodroid Server Bootstrap
 *
 * Receives --flag=value args from ProcessManager, patches product.json,
 * then either forks server-main.js (full VS Code) or starts a minimal
 * fallback HTTP server if vscode-reh hasn't been extracted yet.
 *
 * IMPORTANT: We do NOT start any extra health-server on a side-port.
 * ProcessManager waits for PORT itself to respond — this is the only
 * reliable signal that VS Code is actually ready to serve the WebView.
 */
'use strict';

const http = require('http');
const path = require('path');
const fs   = require('fs');
const cp   = require('child_process');

// ── Arg parsing: --flag=value format (what ProcessManager sends) ─────────────
const args = {};
process.argv.slice(2).forEach(function(arg) {
    if (!arg.startsWith('--')) return;
    var eq = arg.indexOf('=');
    if (eq > 0) {
        args[arg.slice(2, eq)] = arg.slice(eq + 1);
    } else {
        args[arg.slice(2)] = true;
    }
});

var HOST = args['host'] || '127.0.0.1';
var PORT = parseInt(args['port'], 10) || 13337;
var EXT_DIR  = args['extensions-dir']  || '';
var DATA_DIR = args['user-data-dir']   || '';
var SRVR_DIR = args['server-data-dir'] || DATA_DIR;
var LOGS_DIR = args['logsPath']        || '';

var SERVER_DIR   = path.dirname(__filename);   // .../files/server/
var REH_DIR      = path.join(SERVER_DIR, 'vscode-reh');
var REH_MAIN     = path.join(REH_DIR, 'out', 'server-main.js');
var PRODUCT_JSON = path.join(REH_DIR, 'product.json');

process.stdout.write('[server.js] port=' + PORT + ' host=' + HOST + '\n');

// ── Patch product.json ────────────────────────────────────────────────────────
function patchProductJson() {
    if (!fs.existsSync(PRODUCT_JSON)) return;
    try {
        var p = JSON.parse(fs.readFileSync(PRODUCT_JSON, 'utf8'));
        p.nameShort       = 'VSCodroid';
        p.nameLong        = 'VSCodroid';
        p.applicationName = 'vscodroid';
        p.dataFolderName  = '.vscodroid';
        p.quality         = 'stable';
        p.enableTelemetry = false;
        p.updateUrl       = '';
        p.extensionsGallery = {
            serviceUrl:          'https://open-vsx.org/vscode/gallery',
            itemUrl:             'https://open-vsx.org/vscode/item',
            resourceUrlTemplate: 'https://open-vsx.org/vscode/unpkg/{publisher}/{name}/{version}/{path}',
            controlUrl:          ''
        };
        p.linkProtectionTrustedDomains = [
            'https://open-vsx.org',
            'https://useblackbox.io',
            'https://github.com',
            'https://raw.githubusercontent.com'
        ];
        fs.writeFileSync(PRODUCT_JSON, JSON.stringify(p, null, 2), 'utf8');
        process.stdout.write('[server.js] product.json patched\n');
    } catch(e) {
        process.stderr.write('[server.js] product.json patch error: ' + e.message + '\n');
    }
}

// ── Main: fork VS Code or start fallback ─────────────────────────────────────
if (fs.existsSync(REH_MAIN)) {

    patchProductJson();

    // Build args for server-main.js — pass every flag we received
    var fwdArgs = [
        '--host',             HOST,
        '--port',             String(PORT),
        '--without-connection-token',
        '--accept-server-license-terms',
        '--log',              args['log'] || 'info'
    ];
    if (EXT_DIR)  { fwdArgs.push('--extensions-dir',  EXT_DIR);  }
    if (DATA_DIR) { fwdArgs.push('--user-data-dir',   DATA_DIR); }
    if (SRVR_DIR) { fwdArgs.push('--server-data-dir', SRVR_DIR); }
    if (LOGS_DIR) { fwdArgs.push('--logsPath',         LOGS_DIR); }

    process.stdout.write('[server.js] Launching: ' + REH_MAIN + ' ' + fwdArgs.join(' ') + '\n');

    var child = cp.fork(REH_MAIN, fwdArgs, {
        env:      process.env,
        stdio:    'inherit',
        detached: false       // child exits when parent exits
    });

    child.on('error', function(err) {
        process.stderr.write('[server.js] fork error: ' + err.message + '\n');
        process.exit(1);
    });

    child.on('exit', function(code) {
        process.stdout.write('[server.js] server-main.js exited: ' + code + '\n');
        process.exit(code || 0);
    });

    // Keep this process alive so the watchdog (which monitors THIS process)
    // gets notified when server-main.js dies.
    process.on('SIGTERM', function() { try { child.kill('SIGTERM'); } catch(_) {} });
    process.on('SIGINT',  function() { try { child.kill('SIGTERM'); } catch(_) {} });

} else {

    // vscode-reh not extracted — start minimal HTTP server so the user
    // sees a helpful diagnostic instead of "connection refused".
    process.stdout.write('[server.js] vscode-reh not found — starting fallback on ' + HOST + ':' + PORT + '\n');

    var server = http.createServer(function(req, res) {
        // /healthz — fast health probe for ProcessManager
        if (req.url === '/healthz') {
            res.writeHead(200, {'Content-Type': 'text/plain', 'Cache-Control': 'no-store'});
            res.end('OK\n');
            return;
        }
        // All other paths — diagnostic page
        res.writeHead(200, {'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store'});
        res.end(
            '<!DOCTYPE html><html><head>' +
            '<meta name="viewport" content="width=device-width,initial-scale=1">' +
            '<style>*{margin:0;padding:0;box-sizing:border-box}' +
            'body{background:#1e1e1e;color:#ccc;font-family:monospace;' +
            'display:flex;align-items:center;justify-content:center;height:100vh;flex-direction:column;gap:16px;padding:24px;text-align:center}' +
            'h1{color:#007acc}code{background:#252526;padding:2px 8px;border-radius:3px;font-size:13px}</style></head>' +
            '<body><h1>VSCodroid</h1>' +
            '<p>VS Code server not found.</p>' +
            '<p><code>vscode-reh</code> assets are not in this APK.</p>' +
            '<p style="color:#858585;font-size:13px">A release build with bundled VS Code assets is required.</p>' +
            '<p style="color:#858585;font-size:12px">Node.js ' + process.version + ' | port ' + PORT + '</p>' +
            '</body></html>'
        );
    });

    server.on('error', function(err) {
        process.stderr.write('[server.js] HTTP error: ' + err.message + '\n');
        process.exit(1);
    });

    server.listen(PORT, HOST, function() {
        process.stdout.write('[server.js] Fallback ready on ' + HOST + ':' + PORT + '\n');
    });

    process.on('SIGTERM', function() { server.close(); process.exit(0); });
    process.on('SIGINT',  function() { server.close(); process.exit(0); });
}

process.on('uncaughtException', function(err) {
    process.stderr.write('[server.js] Uncaught: ' + err.stack + '\n');
    process.exit(1);
});
