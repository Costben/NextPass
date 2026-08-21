package com.nextpass

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 多服务商（provider）配置数据模型与 JSON 读写.
 *
 * 配置存于 claw 私有目录 files/nextpass.json（root 可写，claw 进程可读）.
 * 数组形式，每个元素 = 一个服务商条目，与任意内置服务商（zhipu/deepseek/...）或
 * "自定义"（custom）对应。未出现在配置里的 provider 完全不受影响。
 */
data class ProviderConfig(
    var providerId: String = "",
    var displayName: String = "",
    var baseUrl: String = "",
    var apiKey: String = "",
    var apiType: String = "openai-completions",
    var enabled: Boolean = true,
    var useCustomBaseUrl: Boolean = true,
    var useCustomApiKey: Boolean = false,
    var modelIds: MutableList<String> = mutableListOf(),
    // 网络/协议：仅对"自定义"服务商有效，随 nextpass.json 持久化
    var useHttp: Boolean = false,
    var useHttps: Boolean = true,
    var requestHeaders: MutableList<String> = mutableListOf(),
    var responseHeaders: MutableList<String> = mutableListOf(),
    var timeoutMs: Int = 15000,
    // 桌面入口自检 only
    var lasthookError: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("providerId", providerId)
        put("displayName", displayName)
        put("baseUrl", baseUrl)
        put("apiKey", apiKey)
        put("apiType", apiType)
        put("enabled", enabled)
        put("useCustomBaseUrl", useCustomBaseUrl)
        put("useCustomApiKey", useCustomApiKey)
        put("modelIds", JSONArray(modelIds))
        put("useHttp", useHttp)
        put("useHttps", useHttps)
        put("requestHeaders", JSONArray(requestHeaders))
        put("responseHeaders", JSONArray(responseHeaders))
        put("timeoutMs", timeoutMs)
        put("lasthookError", lasthookError)
    }

    companion object {
        /** Keep one stable record per provider and remove legacy temporary entries. */
        fun normalize(items: List<ProviderConfig>): MutableList<ProviderConfig> {
            val merged = linkedMapOf<String, ProviderConfig>()
            for (raw in items) {
                val rawId = raw.providerId.trim()
                if (rawId.isEmpty()) continue
                val generatedCustom = rawId.matches(Regex("custom-?\\d+"))
                val hasData = (raw.displayName.isNotBlank() && raw.displayName.trim() != "新服务商") || raw.baseUrl.isNotBlank() ||
                    raw.apiKey.isNotBlank() || raw.modelIds.any { it.isNotBlank() }
                if ((generatedCustom || rawId == "custom-minimax" || rawId == "custom-qwen") && !hasData) continue
                val id = if (generatedCustom) "custom" else rawId
                val normalizedUrl = normalizeBaseUrl(raw.baseUrl, raw.apiType)
                val urlIsHttp = normalizedUrl.startsWith("http://", ignoreCase = true)
                val urlIsHttps = normalizedUrl.startsWith("https://", ignoreCase = true)
                val clean = raw.copy(
                    providerId = id,
                    displayName = raw.displayName.trim(),
                    baseUrl = normalizedUrl,
                    apiKey = raw.apiKey.trim(),
                    apiType = raw.apiType.trim().ifEmpty { "openai-completions" },
                    modelIds = raw.modelIds.map { it.trim() }
                        .filter { it.isNotEmpty() }.distinct().toMutableList(),
                    requestHeaders = raw.requestHeaders.map { it.trim() }
                        .filter { it.isNotEmpty() }.distinct().toMutableList(),
                    responseHeaders = raw.responseHeaders.map { it.trim() }
                        .filter { it.isNotEmpty() }.distinct().toMutableList()
                )
                if (urlIsHttp) {
                    clean.useHttp = true
                    clean.useHttps = false
                } else if (urlIsHttps) {
                    clean.useHttp = false
                    clean.useHttps = true
                }
                val old = merged[id]
                if (old == null) {
                    merged[id] = clean
                } else {
                    if (clean.displayName.isNotEmpty()) old.displayName = clean.displayName
                    if (clean.baseUrl.isNotEmpty()) old.baseUrl = clean.baseUrl
                    if (clean.apiKey.isNotEmpty()) old.apiKey = clean.apiKey
                    if (clean.apiType.isNotEmpty()) old.apiType = clean.apiType
                    old.enabled = old.enabled && clean.enabled
                    old.useCustomBaseUrl = old.useCustomBaseUrl || clean.useCustomBaseUrl || clean.baseUrl.isNotBlank()
                    old.useCustomApiKey = old.useCustomApiKey || clean.useCustomApiKey || clean.apiKey.isNotBlank()
                    old.useHttp = old.useHttp || clean.useHttp
                    old.useHttps = old.useHttps || clean.useHttps
                    old.modelIds = (old.modelIds + clean.modelIds).distinct().toMutableList()
                }
            }
            val custom = merged["custom"]
            val zhipu = merged["zhipu"]
            if (custom != null && zhipu != null && zhipu.baseUrl == custom.baseUrl && zhipu.apiKey == custom.apiKey) {
                custom.modelIds = (custom.modelIds + zhipu.modelIds).distinct().toMutableList()
                merged.remove("zhipu")
            }
            return merged.values.toMutableList()
        }

        private fun normalizeBaseUrl(raw: String, apiType: String): String {
            val url = raw.trim().trimEnd('/')
            if (url.isEmpty()) return ""
            if (apiType.trim().ifEmpty { "openai-completions" } == "openai-completions" &&
                url.matches(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://[^/]+$"))) {
                return "$url/v1"
            }
            return url
        }

        fun fromJson(o: JSONObject): ProviderConfig = ProviderConfig(
            providerId = o.optString("providerId"),
            displayName = o.optString("displayName"),
            baseUrl = o.optString("baseUrl"),
            apiKey = o.optString("apiKey"),
            apiType = o.optString("apiType", "openai-completions"),
            enabled = o.optBoolean("enabled", true),
            useCustomBaseUrl = o.optBoolean("useCustomBaseUrl", true) || o.optString("baseUrl").isNotBlank(),
            useCustomApiKey = o.optBoolean("useCustomApiKey", false) || o.optString("apiKey").isNotBlank(),
            modelIds = run {
                val l = mutableListOf<String>()
                val a = o.optJSONArray("modelIds")
                if (a != null) for (i in 0 until a.length()) l.add(a.optString(i))
                l
            },
            useHttp = o.optBoolean("useHttp", false),
            useHttps = o.optBoolean("useHttps", true),
            requestHeaders = run {
                val l = mutableListOf<String>()
                val a = o.optJSONArray("requestHeaders")
                if (a != null) for (i in 0 until a.length()) l.add(a.optString(i))
                l
            },
            responseHeaders = run {
                val l = mutableListOf<String>()
                val a = o.optJSONArray("responseHeaders")
                if (a != null) for (i in 0 until a.length()) l.add(a.optString(i))
                l
            },
            timeoutMs = o.optInt("timeoutMs", 15000),
            lasthookError = o.optString("lasthookError")
        )
    }
}

object NextPassConfigStore {
    /**
     * 配置持久化（配置 UI 运行在 NextPass 进程，而 hook 运行在
     * com.oplus.claw 进程，两个 uid 必须都能读写同一个文件）。
     *
     * 因此 **不写** claw 私有目录（模块进程无权写、还会闪退），改存共享位置：
     *  - 首选：/sdcard/NextPass/nextpass.json（两部进程都能读写，root 预创建 666）
     *  - 回退：模块自身私有目录 /data/data/com.nextpass/files/（只有 UI 进程，重启后仍保留）
     */
    const val SDCARD_FILE = "/sdcard/NextPass/nextpass.json"
    const val LEGACY_SDCARD_FILE = "/sdcard/CraftUi/nextpass.json"
    const val MODULE_FILE = "/data/data/com.nextpass/files/nextpass.json"
    const val TARGET_FILE = "/data/data/com.oplus.claw/files/nextpass.json"
    private const val BRIDGE_URI = "content://com.nextpass.config"

    /** Load from the normal app file, or from the exported bridge when called in Cloud. */
    fun load(context: Context? = null): List<ProviderConfig> {
        if (context != null) {
            val bridged = try {
                context.contentResolver.call(Uri.parse(BRIDGE_URI), "read_config", null, null)
                    ?.getString("json")
            } catch (t: Throwable) {
                Log.w("NextPass", "config bridge failed", t)
                null
            }
            if (bridged != null) {
                val parsed = ProviderConfig.normalize(parse(bridged))
                Log.i("NextPass", "config bridge returned ${parsed.size} providers")
                if (parsed.isNotEmpty() || bridged.trim() == "[]") return parsed
            } else {
                Log.w("NextPass", "config bridge unavailable for ${context.packageName}")
            }
        }
        val files = if (context?.packageName == "com.oplus.claw") {
            listOf(File(TARGET_FILE), File(SDCARD_FILE), File(LEGACY_SDCARD_FILE), File(MODULE_FILE))
        } else {
            listOf(File(SDCARD_FILE), File(LEGACY_SDCARD_FILE), File(MODULE_FILE))
        }
        val text = files.firstNotNullOfOrNull { f ->
            f.takeIf { it.canRead() && it.length() > 0 }?.let { runCatching { it.readText() }.getOrNull() }
        } ?: return emptyList()
        return ProviderConfig.normalize(parse(text))
    }

    /** Distinguish a real empty configuration from a bridge that is not ready. */
    fun isAvailable(context: Context): Boolean {
        val bridged = runCatching {
            context.contentResolver.call(Uri.parse(BRIDGE_URI), "read_config", null, null)
                ?.getString("json")
        }.getOrNull()
        if (bridged != null) return true
        val files = if (context.packageName == "com.oplus.claw") {
            listOf(File(TARGET_FILE), File(SDCARD_FILE), File(LEGACY_SDCARD_FILE), File(MODULE_FILE))
        } else {
            listOf(File(SDCARD_FILE), File(LEGACY_SDCARD_FILE), File(MODULE_FILE))
        }
        return files.any { it.canRead() && it.length() > 0 }
    }

    private fun parse(text: String): List<ProviderConfig> = try {
        val arr = JSONArray(text)
        (0 until arr.length()).map { ProviderConfig.fromJson(arr.getJSONObject(it)) }
    } catch (_: Throwable) { emptyList() }

    internal fun serialized(): String {
        val file = listOf(File(SDCARD_FILE), File(MODULE_FILE))
            .firstNotNullOfOrNull { f -> f.takeIf { it.canRead() && it.length() > 0 } }
        return file?.let { runCatching { it.readText() }.getOrNull() } ?: "[]"
    }

    fun findByProviderId(id: String): ProviderConfig? =
        load().firstOrNull { it.providerId == id && it.enabled }

    /** Persist locally; a failed external/shared write must never terminate the Activity. */
    fun save(list: List<ProviderConfig>): Boolean {
        val arr = JSONArray()
        ProviderConfig.normalize(list).forEach { arr.put(it.toJson()) }
        return saveSerialized(arr.toString())
    }

    /** Called by the exported bridge when Cloud requests a delete/update. */
    internal fun saveSerialized(text: String): Boolean {
        val normalized = ProviderConfig.normalize(parse(text))
        val canonical = JSONArray().apply { normalized.forEach { put(it.toJson()) } }.toString()

        // 1) 优先写共享目录（claw hook 与 UI 都能读）
        val sdcard = File(SDCARD_FILE)
        val wroteShared = try {
            sdcard.parentFile?.mkdirs()
            sdcard.setReadable(true, false)
            sdcard.setWritable(true, false)
            sdcard.writeText(canonical)
            true
        } catch (t: Throwable) {
            false
        }

        // 2) 无论共享是否成功，都镜像写一份到模块私有目录（防 sdcard 权限回退丢失）
        try {
            val mod = File(MODULE_FILE)
            mod.parentFile?.mkdirs()
            mod.writeText(canonical)
        } catch (_: Throwable) { /* best-effort */ }

        return wroteShared || runCatching { File(MODULE_FILE).canRead() && File(MODULE_FILE).length() > 0 }.getOrDefault(false)
    }
}
