package com.nextpass

import android.os.Bundle
import android.widget.Toast
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperCheckbox
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.extra.SuperDropdown
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * NextPass 的正常应用主界面（Miuix / Compose 版）。
 *
 * 服务商列表（内置+自定义）→ 填写地址、密钥和需要的模型
 * → 保存一份去重后的 nextpass.json。自定义服务商可以直接删除。
 */
class MainActivity : ComponentActivity() {

    private data class ModelRow(val id: String, val label: String)

    private enum class Confirm { DELETE_PROVIDER, CLEAR_MODELS, CLEAR_CUSTOM }

    private val presets = listOf(
        // 默认 BaseURL 指向本地 New API 端口（智谱 hook 到本地）
        Triple("zhipu", "智谱", "http://192.168.31.179:3002/v1"),
        Triple("deepseek", "DeepSeek", "https://api.deepseek.com"),
        Triple("volcengine", "火山引擎", "https://ark.cn-beijing.volces.com/api/v3"),
        Triple("moonshot", "月之暗面", "https://api.moonshot.cn/v1"),
        Triple("custom-minimax", "MiniMax", "https://api.minimaxi.com/v1"),
        Triple("custom-qwen", "千问", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
        Triple("custom", "自定义", "")
    )

    private val currentConfigs = mutableStateListOf<ProviderConfig>()
    private val checkedModels = mutableStateListOf<String>()
    private val modelRows = mutableStateListOf<ModelRow>()

    private var selectedProviderId by mutableStateOf("zhipu")
    private var displayName by mutableStateOf("")
    private var baseUrl by mutableStateOf("")
    private var apiKey by mutableStateOf("")
    private var apiType by mutableStateOf("openai-completions")
    private var useCustomBaseUrl by mutableStateOf(true)
    private var useCustomApiKey by mutableStateOf(false)
    private var enabled by mutableStateOf(true)
    private var useHttp by mutableStateOf(true)
    private var useHttps by mutableStateOf(false)
    private var statusText by mutableStateOf("")
    private val confirmAction = mutableStateOf<Confirm?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        val loaded = NextPassConfigStore.load()
        currentConfigs.clear()
        currentConfigs.addAll(ProviderConfig.normalize(loaded))
        if (loaded.isNotEmpty()) NextPassConfigStore.save(currentConfigs.toList())
        refreshProviderSelection()

        setContent {
            val dark = isSystemInDarkTheme()
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) { dark },
                    navigationBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) { dark }
                )
                onDispose { }
            }
            MiuixTheme(colors = if (dark) darkColorScheme() else lightColorScheme()) {
                SettingsScreen()
            }
        }
    }

    private fun providerOptions(): List<Triple<String, String, String>> {
        val known = presets.map { it.first }.toSet()
        val custom = currentConfigs.filter { it.providerId.isNotBlank() && it.providerId !in known }
            .map { Triple(it.providerId, it.displayName.ifEmpty { it.providerId }, it.baseUrl) }
        return (presets + custom).distinctBy { it.first }
    }

    @Composable
    private fun SettingsScreen() {
        val scrollBehavior = MiuixScrollBehavior()
        Scaffold(
            topBar = { TopAppBar(title = "NextPass", scrollBehavior = scrollBehavior) }
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .overScrollVertical()
                    .scrollEndHaptic()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(horizontal = 12.dp),
                contentPadding = PaddingValues(top = innerPadding.calculateTopPadding(), bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { ProviderCard() }
                item { InputCard() }
                item { ToggleCard() }
                item { ProtocolCard() }

                item { ModelCard() }

                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        SuperArrow(
                            title = "获取模型",
                            summary = "从 BaseURL 的 /v1/models 拉取可用模型列表",
                            onClick = { fetchModels() }
                        )
                        HorizontalDivider()
                        SuperArrow(
                            title = "测试连接",
                            summary = "向 BaseURL 发起一次 GET 请求验证可达性",
                            onClick = { testConnection() }
                        )
                        HorizontalDivider()
                        SuperArrow(
                            title = "清空当前模型",
                            summary = "移除当前服务商已勾选的全部模型",
                            onClick = { confirmAction.value = Confirm.CLEAR_MODELS }
                        )
                    }
                }

                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        SuperArrow(
                            title = "删除当前配置",
                            summary = "删除当前服务商的自定义配置",
                            onClick = { confirmAction.value = Confirm.DELETE_PROVIDER }
                        )
                        HorizontalDivider()
                        SuperArrow(
                            title = "清空自定义",
                            summary = "移除全部自定义服务商及其模型",
                            onClick = { confirmAction.value = Confirm.CLEAR_CUSTOM }
                        )
                    }
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        insideMargin = PaddingValues(16.dp)
                    ) {
                        Button(
                            onClick = { saveConfig() },
                            colors = ButtonDefaults.buttonColorsPrimary(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("保存配置") }
                    }
                }

                if (statusText.isNotEmpty()) {
                    item {
                        Text(
                            text = statusText,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }
            }
        }
        ConfirmDialog()
    }

    @Composable
    private fun ProviderCard() {
        val options = providerOptions()
        val selectedIdx = options.indexOfFirst { it.first == selectedProviderId }
        Card(modifier = Modifier.fillMaxWidth()) {
            SuperDropdown(
                items = options.map { it.second },
                selectedIndex = if (selectedIdx >= 0) selectedIdx else 0,
                title = "服务商",
                summary = baseUrl.ifBlank { null },
                onSelectedIndexChange = { idx ->
                    if (idx in options.indices) {
                        applyPreset(options[idx].first, options[idx].second, options[idx].third)
                    }
                }
            )
            HorizontalDivider()
            SuperArrow(
                title = "新增自定义服务商",
                summary = "在 custom 插槽添加一个可编辑的服务商",
                onClick = { addCustomProvider() }
            )
        }
    }

    @Composable
    private fun InputCard() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = "显示名称",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                TextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = "API 地址（BaseURL）",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                TextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = "API Key",
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    @Composable
    private fun ToggleCard() {
        Card(modifier = Modifier.fillMaxWidth()) {
            SuperSwitch(
                title = "使用自定义 BaseURL",
                checked = useCustomBaseUrl,
                onCheckedChange = { useCustomBaseUrl = it }
            )
            HorizontalDivider()
            SuperSwitch(
                title = "使用自定义 API Key",
                checked = useCustomApiKey,
                onCheckedChange = { useCustomApiKey = it }
            )
            HorizontalDivider()
            SuperSwitch(
                title = "启用此服务商",
                summary = "只有启用的服务商会被注入到小布 Cloud",
                checked = enabled,
                onCheckedChange = { enabled = it }
            )
        }
    }

    @Composable
    private fun ProtocolCard() {
        Card(modifier = Modifier.fillMaxWidth()) {
            SuperSwitch(
                title = "HTTP",
                summary = "http:// 明文，用于请求本地端口模型",
                checked = useHttp,
                onCheckedChange = {
                    useHttp = it
                    if (it) useHttps = false
                }
            )
            HorizontalDivider()
            SuperSwitch(
                title = "HTTPS",
                summary = "加密连接",
                checked = useHttps,
                onCheckedChange = {
                    useHttps = it
                    if (it) useHttp = false
                }
            )
        }
    }

    @Composable
    private fun ModelCard() {
        Card(modifier = Modifier.fillMaxWidth()) {
            if (modelRows.isEmpty()) {
                Text(
                    text = "尚未获取模型；已保存的模型会在这里显示",
                    style = MiuixTheme.textStyles.body2,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                modelRows.forEachIndexed { index, row ->
                    SuperCheckbox(
                        title = row.label,
                        checked = checkedModels.contains(row.id),
                        onCheckedChange = { on ->
                            if (on) checkedModels.add(row.id) else checkedModels.remove(row.id)
                        }
                    )
                    if (index < modelRows.lastIndex) HorizontalDivider()
                }
            }
        }
    }

    @Composable
    private fun ConfirmDialog() {
        val confirm = confirmAction.value
        val (title, summary) = when (confirm) {
            Confirm.DELETE_PROVIDER -> "删除当前配置" to "将删除当前服务商的自定义配置，确定继续？"
            Confirm.CLEAR_MODELS -> "清空当前模型" to "将移除当前服务商已勾选的全部模型，确定继续？"
            Confirm.CLEAR_CUSTOM -> "清空自定义" to "将移除全部自定义服务商及其模型，确定继续？"
            null -> "" to ""
        }
        SuperDialog(
            show = confirm != null,
            title = title,
            summary = summary,
            onDismissRequest = { confirmAction.value = null }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(
                    text = "取消",
                    onClick = { confirmAction.value = null },
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    text = "确定",
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        when (confirm) {
                            Confirm.DELETE_PROVIDER -> deleteCurrentConfig()
                            Confirm.CLEAR_MODELS -> clearCurrentModels()
                            Confirm.CLEAR_CUSTOM -> clearCustomConfigs()
                            null -> {}
                        }
                        confirmAction.value = null
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    private fun refreshProviderSelection(selectId: String? = null) {
        val options = providerOptions()
        val id = selectId
            ?: currentConfigs.firstOrNull()?.providerId
            ?: options.firstOrNull()?.first
            ?: return
        val idx = options.indexOfFirst { it.first == id }
        if (idx >= 0) {
            applyPreset(options[idx].first, options[idx].second, options[idx].third)
        }
    }

    private fun addCustomProvider() {
        persistCurrentIntoSelected()
        if (currentConfigs.any { it.providerId == "custom" }) {
            refreshProviderSelection("custom")
            toast("已有一个自定义服务商，直接修改它即可")
        } else {
            currentConfigs.add(ProviderConfig(providerId = "custom", displayName = "新服务商", enabled = true))
            refreshProviderSelection("custom")
            toast("填写 Base URL、API Key 和模型后保存")
        }
    }

    private fun applyPreset(id: String, name: String, defaultUrl: String) {
        selectedProviderId = id
        val existing = currentConfigs.firstOrNull { it.providerId == id }
        if (existing != null) {
            displayName = existing.displayName.ifEmpty { name }
            baseUrl = existing.baseUrl
            apiKey = existing.apiKey
            apiType = existing.apiType
            useCustomBaseUrl = existing.useCustomBaseUrl
            useCustomApiKey = existing.useCustomApiKey
            enabled = existing.enabled
            syncProtocolFlags(existing)
            checkedModels.clear()
            checkedModels.addAll(existing.modelIds)
            modelRows.clear()
            existing.modelIds.forEach { modelRows.add(ModelRow(it, it)) }
        } else {
            if (displayName.isEmpty()) displayName = name
            baseUrl = defaultUrl
            apiKey = ""
            apiType = "openai-completions"
            useCustomBaseUrl = true
            useCustomApiKey = false
            enabled = true
            // 默认：HTTP（智谱 hook 到本地端口的默认形态）
            useHttp = true
            useHttps = false
            checkedModels.clear()
            modelRows.clear()
        }
    }

    private fun syncProtocolFlags(c: ProviderConfig) {
        // 兼容旧配置：useHttp/useHttps 都未定义时根据 baseUrl scheme 推断
        if (!c.useHttp && !c.useHttps) {
            useHttp = c.baseUrl.startsWith("http://")
            useHttps = !useHttp
        } else {
            useHttp = c.useHttp
            useHttps = c.useHttps
        }
    }

    /** 把当前表单内容写回当前选中 provider 的内存配置（切换前调用，防丢改动）。 */
    private fun persistCurrentIntoSelected() {
        val updated = currentConfig()
        val idx = currentConfigs.indexOfFirst { it.providerId == updated.providerId }
        if (idx >= 0) currentConfigs[idx] = updated else currentConfigs.add(updated)
    }

    private fun currentConfig(): ProviderConfig {
        val c = ProviderConfig(providerId = selectedProviderId)
        c.displayName = displayName.trim()
        c.baseUrl = baseUrl.trim()
        c.apiKey = apiKey.trim()
        c.apiType = apiType.trim().ifEmpty { "openai-completions" }
        // A populated custom value is authoritative. This prevents a saved
        // provider from silently losing its URL/key when the switch was left
        // off on an older screen.
        c.useCustomBaseUrl = useCustomBaseUrl || c.baseUrl.isNotBlank()
        c.useCustomApiKey = useCustomApiKey || c.apiKey.isNotBlank()
        c.enabled = enabled
        c.useHttp = useHttp
        c.useHttps = useHttps
        c.modelIds.addAll(checkedModels.map { it.trim() }.filter { it.isNotEmpty() }.distinct())
        return c
    }

    private fun saveConfig() {
        try {
            persistCurrentIntoSelected()
            val normalized = ProviderConfig.normalize(currentConfigs.toList())
            currentConfigs.clear()
            currentConfigs.addAll(normalized)
            val persisted = NextPassConfigStore.save(normalized)
            refreshProviderSelection(selectedProviderId)
            toast("已保存 ${currentConfigs.size} 个服务商配置")
            statusText = if (persisted) "已保存。重启小布 Cloud 后生效" else "已保存到本机，重启小布 Cloud 后生效"
        } catch (t: Throwable) {
            statusText = "保存失败：${t.message ?: "未知错误"}"
            toast("保存失败，请检查输入")
        }
    }

    private fun deleteCurrentConfig() {
        val removed = currentConfigs.removeAll { it.providerId == selectedProviderId }
        if (!removed) {
            toast("当前是内置服务商，没有自定义配置可删除")
            return
        }
        NextPassConfigStore.save(currentConfigs.toList())
        checkedModels.clear()
        modelRows.clear()
        refreshProviderSelection(presets.first().first)
        toast("已删除当前配置")
        statusText = "已删除。重启小布 Cloud 后生效"
    }

    private fun clearCurrentModels() {
        checkedModels.clear()
        modelRows.clear()
        val idx = currentConfigs.indexOfFirst { it.providerId == selectedProviderId }
        if (idx >= 0) {
            val old = currentConfigs[idx]
            currentConfigs[idx] = ProviderConfig(
                providerId = old.providerId,
                displayName = old.displayName,
                baseUrl = old.baseUrl,
                apiKey = old.apiKey,
                apiType = old.apiType,
                enabled = old.enabled,
                useCustomBaseUrl = old.useCustomBaseUrl,
                useCustomApiKey = old.useCustomApiKey,
                useHttp = old.useHttp,
                useHttps = old.useHttps,
                requestHeaders = mutableListOf<String>().apply { addAll(old.requestHeaders) },
                responseHeaders = mutableListOf<String>().apply { addAll(old.responseHeaders) },
                timeoutMs = old.timeoutMs,
                lasthookError = old.lasthookError
            )
        }
        NextPassConfigStore.save(currentConfigs.toList())
        toast("已清空当前模型")
    }

    private fun clearCustomConfigs() {
        // `custom` is the editable slot, not an immutable built-in preset.
        val known = presets.filter { it.first != "custom" }.map { it.first }.toSet()
        val removed = currentConfigs.removeAll { it.providerId !in known }
        if (!removed) {
            toast("没有自定义配置")
            return
        }
        NextPassConfigStore.save(currentConfigs.toList())
        checkedModels.clear()
        modelRows.clear()
        refreshProviderSelection(presets.first().first)
        toast("已清空全部自定义配置和模型")
        statusText = "已清理。重启小布 Cloud 后生效"
    }

    private fun testConnection() {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) { toast("请先填写 BaseURL"); return }
        statusText = "测试中..."
        Thread {
            try {
                val code = httpGet(base, apiKey.trim())
                runOnUiThread {
                    statusText = if (code == 200) "连接成功 (HTTP $code)" else "失败 (HTTP $code)"
                    toast(if (code == 200) "连接成功" else "HTTP $code")
                }
            } catch (t: Throwable) {
                runOnUiThread { statusText = "测试失败: ${t.message}"; toast("失败: ${t.message}") }
            }
        }.start()
    }

    private fun fetchModels() {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) { toast("请先填写 BaseURL"); return }
        statusText = "拉取模型..."
        Thread {
            try {
                val (code, body) = httpGetBody(base, apiKey.trim())
                if (code != 200) { runOnUiThread { statusText = "获取模型失败 (HTTP $code)" }; return@Thread }
                val arr = JSONObject(body).optJSONArray("data")
                runOnUiThread {
                    modelRows.clear()
                    checkedModels.clear()
                    if (arr == null) { statusText = "响应中无 data 数组"; return@runOnUiThread }
                    val seen = mutableSetOf<String>()
                    for (i in 0 until arr.length()) {
                        val m = arr.getJSONObject(i)
                        val id = m.optString("id")
                        if (id.isBlank() || !seen.add(id)) continue
                        val name = m.optString("name", id)
                        // Fetching only discovers models. Nothing is selected or
                        // persisted until the user checks it explicitly.
                        modelRows.add(ModelRow(id, "$id  ($name)"))
                    }
                    statusText = "已获取 ${seen.size} 个模型，请勾选要使用的"
                }
            } catch (t: Throwable) {
                runOnUiThread { statusText = "获取模型失败: ${t.message}" }
            }
        }.start()
    }

    private fun httpGet(base: String, key: String): Int {
        val conn = (URL(modelsEndpoint(base)).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            if (key.isNotEmpty()) setRequestProperty("Authorization", "Bearer $key")
        }
        return conn.responseCode.also { conn.disconnect() }
    }

    private fun httpGetBody(base: String, key: String): Pair<Int, String> {
        val conn = (URL(modelsEndpoint(base)).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 15000
            if (key.isNotEmpty()) setRequestProperty("Authorization", "Bearer $key")
        }
        val code = conn.responseCode
        val body = try {
            conn.inputStream.bufferedReader().use(BufferedReader::readText)
        } catch (t: Throwable) {
            conn.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
        }
        conn.disconnect()
        return code to body
    }

    private fun modelsEndpoint(base: String): String =
        if (base.endsWith("/v1")) "$base/models" else "$base/v1/models"

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
