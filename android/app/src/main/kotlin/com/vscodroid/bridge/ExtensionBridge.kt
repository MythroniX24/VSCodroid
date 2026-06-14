package com.vscodroid.bridge

import android.content.Context
import android.net.Uri
import android.webkit.JavascriptInterface
import com.vscodroid.bridge.SecurityManager
import com.vscodroid.util.Environment
import com.vscodroid.util.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * JavaScript interface bridge for VS Code extension management.
 *
 * Exposes APIs for:
 * - **VSIX installation**: Copy a user-selected `.vsix` file from SAF URI to the
 *   extensions directory and trigger a VS Code reload.
 * - **Extension status**: Query which extensions are installed and their metadata.
 * - **Compatibility layer**: Provide stubs for VS Code extension host APIs that
 *   behave differently on Android (e.g., clipboard, file system, process API).
 *
 * Registered as `AndroidExtensionBridge` in the WebView's JavaScript context.
 *
 * Security: All methods that modify state require a valid [authToken] from
 * [SecurityManager].
 */
class ExtensionBridge(
    private val context: Context,
    private val security: SecurityManager,
    private val onVsixInstalled: (extensionId: String) -> Unit = {}
) {
    private val tag = "ExtensionBridge"

    // -- Extension Listing --

    /**
     * Returns a JSON array of all installed VS Code extensions.
     *
     * Each entry:
     * ```json
     * {
     *   "id": "publisher.extension-name",
     *   "version": "1.2.3",
     *   "displayName": "Extension Name",
     *   "installed": true
     * }
     * ```
     */
    @JavascriptInterface
    fun getInstalledExtensions(authToken: String): String {
        if (!security.validateToken(authToken)) return "[]"
        val extensionsDir = File(Environment.getExtensionsDir(context))
        if (!extensionsDir.exists()) return "[]"

        val result = JSONArray()
        val subdirs = extensionsDir.listFiles { f -> f.isDirectory } ?: return "[]"

        for (dir in subdirs) {
            try {
                val packageJson = File(dir, "package.json")
                if (!packageJson.exists()) continue
                val pkg = JSONObject(packageJson.readText())
                val publisher = pkg.optString("publisher", "")
                val name = pkg.optString("name", "")
                val version = pkg.optString("version", "0.0.0")
                val displayName = pkg.optString("displayName", name)
                result.put(JSONObject().apply {
                    put("id", if (publisher.isNotEmpty()) "$publisher.$name" else name)
                    put("version", version)
                    put("displayName", displayName)
                    put("installed", true)
                    put("path", dir.absolutePath)
                })
            } catch (e: Exception) {
                Logger.d(tag, "Failed to read extension ${dir.name}: ${e.message}")
            }
        }
        return result.toString()
    }

    // -- VSIX Installation --

    /**
     * Installs a VS Code extension from a `.vsix` file at the given content URI.
     *
     * A `.vsix` file is a ZIP archive containing:
     * - `extension/` — the extension source directory
     * - `extension/package.json` — extension metadata
     * - `[Content_Types].xml` — OOXML type manifest (ignored)
     *
     * Installation steps:
     * 1. Open the SAF content URI
     * 2. Stream the ZIP into a temp directory
     * 3. Read publisher + name from `extension/package.json`
     * 4. Move to `extensions/{publisher}.{name}-{version}/`
     * 5. Call [onVsixInstalled] to trigger VS Code reload
     *
     * @param vsixUriString SAF content:// URI of the .vsix file
     * @param authToken Security token
     * @return JSON `{ "success": true, "extensionId": "publisher.name", "version": "1.0.0" }`
     *         or `{ "success": false, "error": "message" }`
     */
    @JavascriptInterface
    fun installVsixFromUri(vsixUriString: String, authToken: String): String {
        if (!security.validateToken(authToken)) {
            return errorResult("Unauthorized")
        }
        Logger.i(tag, "Installing VSIX from URI: $vsixUriString")

        return try {
            val uri = Uri.parse(vsixUriString)
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return errorResult("Could not open file")

            installVsixFromStream(inputStream)
        } catch (e: Exception) {
            Logger.e(tag, "VSIX install failed", e)
            errorResult(e.message ?: "Unknown error")
        }
    }

    /**
     * Installs a VSIX extension from a local file path.
     *
     * Used when the file is already in the app's files directory
     * (e.g., downloaded from the network via a command).
     */
    @JavascriptInterface
    fun installVsixFromPath(filePath: String, authToken: String): String {
        if (!security.validateToken(authToken)) return errorResult("Unauthorized")
        Logger.i(tag, "Installing VSIX from path: $filePath")

        // Validate path is within accessible directories
        val canonical = File(filePath).canonicalPath
        val allowed = listOf(
            context.filesDir.absolutePath,
            context.cacheDir.absolutePath,
            context.getExternalFilesDir(null)?.absolutePath ?: ""
        )
        if (allowed.none { it.isNotEmpty() && canonical.startsWith(it) }) {
            Logger.w(tag, "VSIX path access denied: $canonical")
            return errorResult("Path not accessible")
        }

        return try {
            installVsixFromStream(FileInputStream(filePath))
        } catch (e: Exception) {
            Logger.e(tag, "VSIX install from path failed", e)
            errorResult(e.message ?: "Unknown error")
        }
    }

    /**
     * Removes an installed extension by its ID (`publisher.name`).
     *
     * Deletes the extension directory from the extensions dir.
     * VS Code will need a reload to reflect the change.
     */
    @JavascriptInterface
    fun removeExtension(extensionId: String, authToken: String): String {
        if (!security.validateToken(authToken)) return errorResult("Unauthorized")
        val extensionsDir = File(Environment.getExtensionsDir(context))
        val subdirs = extensionsDir.listFiles() ?: return errorResult("Extensions dir not found")

        // Find directory matching publisher.name (ignoring version suffix)
        val target = subdirs.firstOrNull { dir ->
            val name = dir.name.lowercase()
            name.startsWith(extensionId.lowercase()) ||
                name == extensionId.lowercase()
        } ?: return errorResult("Extension not found: $extensionId")

        return try {
            target.deleteRecursively()
            Logger.i(tag, "Removed extension: ${target.name}")
            JSONObject().apply {
                put("success", true)
                put("extensionId", extensionId)
            }.toString()
        } catch (e: Exception) {
            Logger.e(tag, "Failed to remove extension $extensionId", e)
            errorResult(e.message ?: "Unknown error")
        }
    }

    // -- Extension Compatibility Shims --

    /**
     * Returns compatibility information for the current VS Code extension host.
     *
     * Extensions can query this to adapt their behaviour for the Android environment.
     * The response mimics what desktop VS Code would return, with Android-specific
     * additions.
     */
    @JavascriptInterface
    fun getExtensionHostInfo(authToken: String): String {
        if (!security.validateToken(authToken)) return "{}"
        return JSONObject().apply {
            put("platform", "linux")         // reported platform (android → linux shim)
            put("arch", "arm64")
            put("shell", Environment.getBashPath(context))
            put("isAndroid", true)
            put("vscodroidVersion", getVersionName())
            put("supportsVsix", true)
            put("supportsTerminal", true)
            put("supportsGit", true)
            put("extensionsDir", Environment.getExtensionsDir(context))
            put("userDataDir", Environment.getUserDataDir(context))
        }.toString()
    }

    // -- Private helpers --

    private fun installVsixFromStream(inputStream: InputStream): String {
        val extensionsDir = File(Environment.getExtensionsDir(context))
        extensionsDir.mkdirs()

        // Extract to a temp directory first (atomic install)
        val tempDir = File(context.cacheDir, "vsix_install_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            ZipInputStream(inputStream.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.isDirectory) {
                        File(tempDir, entry.name).mkdirs()
                    } else {
                        val outFile = File(tempDir, entry.name)
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out ->
                            zip.copyTo(out, bufferSize = 65536)
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            // Read package.json from extension/
            val packageJsonFile = File(tempDir, "extension/package.json")
            if (!packageJsonFile.exists()) {
                return errorResult("Invalid VSIX: missing extension/package.json")
            }

            val pkg = JSONObject(packageJsonFile.readText())
            val publisher = pkg.optString("publisher", "unknown")
            val name = pkg.optString("name", "unknown")
            val version = pkg.optString("version", "0.0.0")
            val extensionId = "$publisher.$name"
            val dirName = "$publisher.$name-$version"

            Logger.i(tag, "Installing extension: $extensionId version $version")

            // Remove any existing version of this extension
            extensionsDir.listFiles()?.filter { dir ->
                dir.name.lowercase().startsWith("$publisher.$name".lowercase())
            }?.forEach { old ->
                Logger.d(tag, "Removing old version: ${old.name}")
                old.deleteRecursively()
            }

            // Move extension/ subdirectory to final location
            val extensionSrc = File(tempDir, "extension")
            val extensionDest = File(extensionsDir, dirName)
            if (!extensionSrc.renameTo(extensionDest)) {
                // rename() can fail across filesystems; fall back to copy
                extensionSrc.copyRecursively(extensionDest, overwrite = true)
            }

            Logger.i(tag, "Extension installed: $extensionDest")
            onVsixInstalled(extensionId)

            return JSONObject().apply {
                put("success", true)
                put("extensionId", extensionId)
                put("version", version)
                put("path", extensionDest.absolutePath)
            }.toString()

        } finally {
            tempDir.deleteRecursively()
            try { inputStream.close() } catch (_: Exception) {}
        }
    }

    private fun errorResult(message: String): String =
        JSONObject().apply {
            put("success", false)
            put("error", message)
        }.toString()

    private fun getVersionName(): String =
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) { "unknown" }
}
