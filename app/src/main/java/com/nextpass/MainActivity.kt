package com.nextpass

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.text.method.PasswordTransformationMethod
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * NextPass 的正常应用主界面。
 *
 * 这是唯一的桌面入口，配置直接保存在共享配置文件中，供目标进程里的
 * Xposed hooks 读取。它不设置安全窗口、透明窗口或自动结束行为。
 *
 *  服务商下拉（内置+自定义）→ 填写地址、密钥和需要的模型
 *  → 保存一份去重后的 nextpass.json。自定义服务商可以直接删除。
 */
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var providerSpinner: Spinner
    private lateinit var baseUrlInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var apiTypeInput: EditText
    private lateinit var useBaseUrlBox: CheckBox
    private lateinit var useApiKeyBox: CheckBox
    private lateinit var enabledBox: CheckBox
    private lateinit var httpBox: CheckBox
    private lateinit var httpsBox: CheckBox
    private lateinit var modelList: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var displayNameInput: EditText

    private val presets = listOf(
        // 内置服务商仅提供官方默认地址；如需接入本地/自建网关，自行填写 Base URL。
        Triple("zhipu", "智谱", "https://open.bigmodel.cn/api/paas"),
        Triple("deepseek", "DeepSeek", "https://api.deepseek.com"),
        Triple("volcengine", "火山引擎", "https://ark.cn-beijing.volces.com/api/v3"),
        Triple("moonshot", "月之暗面", "https://api.moonshot.cn/v1"),
        Triple("custom-minimax", "MiniMax", "https://api.minimaxi.com/v1"),
        Triple("custom-qwen", "千问", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
        Triple("custom", "自定义", "")
    )

    private var currentConfigs: MutableList<ProviderConfig> = mutableListOf()
    private var selectedProviderId: String = "zhipu"
    private val checkedModels = mutableSetOf<String>()
    private var providerOptions: List<Triple<String, String, String>> = emptyList()
    private var suppressProviderSelection = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "NextPass"
        val loaded = NextPassConfigStore.load()
        currentConfigs = ProviderConfig.normalize(loaded)
        // Rewrite legacy timestamp-based entries once so old duplicates disappear
        // even before the user presses Save.
        if (loaded.isNotEmpty()) NextPassConfigStore.save(currentConfigs)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 服务商
        content.addView(label("服务商"))
        providerSpinner = Spinner(this)
        providerOptions = buildProviderOptions()
        providerSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, providerOptions.map { it.second })
        providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (!suppressProviderSelection && position in providerOptions.indices) {
                    val option = providerOptions[position]
                    applyPreset(option.first, option.second, option.third)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        content.addView(providerSpinner)

        content.addView(label("显示名称"))
        displayNameInput = EditText(this).apply { hint = "自定义显示名" }
        content.addView(displayNameInput)

        content.addView(label("API 地址（BaseURL）"))
        baseUrlInput = EditText(this).apply { inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI }
        content.addView(baseUrlInput)

        content.addView(label("API Key"))
        apiKeyInput = EditText(this).apply {
            // Keep the value visually masked without using the password input
            // classification that makes the whole Android 16 window sensitive
            // to screenshots and screen recording.
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        content.addView(apiKeyInput)

        apiTypeInput = EditText(this).apply { setText("openai-completions") }

        useBaseUrlBox = checkBox("使用自定义 BaseURL")
        useApiKeyBox = checkBox("使用自定义 API Key")
        enabledBox = checkBox("启用此服务商")
        enabledBox.isChecked = true

        httpBox = checkBox("HTTP（http:// 明文，请求本地端口模型）")
        httpsBox = checkBox("HTTPS（加密）")
        httpBox.setOnClickListener { if (httpBox.isChecked) httpsBox.isChecked = false }
        httpsBox.setOnClickListener { if (httpsBox.isChecked) httpBox.isChecked = false }

        content.addView(label("模型（获取后只勾选要用的）"))
        modelList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(modelList)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val testBtn = Button(this).apply { text = "测试" }
        val fetchBtn = Button(this).apply { text = "获取模型" }
        val saveBtn = Button(this).apply { text = "保存" }
        val addBtn = Button(this).apply { text = "新增自定义" }
        testBtn.setOnClickListener { testConnection() }
        fetchBtn.setOnClickListener { fetchModels() }
        saveBtn.setOnClickListener { saveConfig() }
        addBtn.setOnClickListener {
            if (currentConfigs.any { it.providerId == "custom" }) {
                refreshProviderOptions("custom")
                toast("已有一个自定义服务商，直接修改它即可")
            } else {
                currentConfigs.add(ProviderConfig(providerId = "custom", displayName = "新服务商", enabled = true))
                refreshProviderOptions("custom")
                toast("填写 Base URL、API Key 和模型后保存")
            }
        }
        btnRow.addView(testBtn)
        btnRow.addView(fetchBtn)
        btnRow.addView(saveBtn)
        btnRow.addView(addBtn)
        content.addView(btnRow)

        val manageRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val deleteBtn = Button(this).apply { text = "删除当前配置" }
        val clearModelsBtn = Button(this).apply { text = "清空当前模型" }
        val clearCustomBtn = Button(this).apply { text = "清空自定义" }
        deleteBtn.setOnClickListener { deleteCurrentConfig() }
        clearModelsBtn.setOnClickListener { clearCurrentModels() }
        clearCustomBtn.setOnClickListener { clearCustomConfigs() }
        manageRow.addView(deleteBtn)
        manageRow.addView(clearModelsBtn)
        manageRow.addView(clearCustomBtn)
        content.addView(manageRow)

        statusText = TextView(this).apply { text = "" }
        content.addView(statusText)

        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)
        markUiCaptureable(root, scroll, content, apiKeyInput)

        selectPresetForCurrentConfig()
    }

    private fun markUiCaptureable(vararg views: View) {
        if (Build.VERSION.SDK_INT >= 35) {
            views.forEach { it.contentSensitivity = View.CONTENT_SENSITIVITY_NOT_SENSITIVE }
        }
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 14f
        setPadding(0, dp(12), 0, dp(2))
    }

    private fun checkBox(t: String) = CheckBox(this).apply { text = t }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // 首次打开：若已有配置，回填第一个 provider
    private fun selectPresetForCurrentConfig() {
        val c = currentConfigs.firstOrNull()
        if (c != null) {
            val idx = providerOptions.indexOfFirst { it.first == c.providerId }
            if (idx >= 0) {
                providerSpinner.setSelection(idx)
                applyPreset(providerOptions[idx].first, providerOptions[idx].second, providerOptions[idx].third)
            }
        }
    }

    private fun buildProviderOptions(): List<Triple<String, String, String>> {
        val known = presets.map { it.first }.toSet()
        val custom = currentConfigs.filter { it.providerId.isNotBlank() && it.providerId !in known }
            .map { Triple(it.providerId, it.displayName.ifEmpty { it.providerId }, it.baseUrl) }
        return (presets + custom).distinctBy { it.first }
    }

    private fun refreshProviderOptions(selectId: String? = null) {
        providerOptions = buildProviderOptions()
        providerSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, providerOptions.map { it.second })
        val idx = selectId?.let { id -> providerOptions.indexOfFirst { it.first == id } } ?: -1
        if (idx >= 0) {
            suppressProviderSelection = true
            providerSpinner.setSelection(idx)
            suppressProviderSelection = false
            val option = providerOptions[idx]
            applyPreset(option.first, option.second, option.third)
        }
    }

    private fun applyPreset(id: String, name: String, defaultUrl: String) {
        selectedProviderId = id
        if (displayNameInput.text.isNullOrEmpty()) displayNameInput.setText(name)
        val existing = currentConfigs.firstOrNull { it.providerId == id }
        if (existing != null) {
            displayNameInput.setText(existing.displayName.ifEmpty { name })
            baseUrlInput.setText(existing.baseUrl)
            apiKeyInput.setText(existing.apiKey)
            apiTypeInput.setText(existing.apiType)
            useBaseUrlBox.isChecked = existing.useCustomBaseUrl
            useApiKeyBox.isChecked = existing.useCustomApiKey
            enabledBox.isChecked = existing.enabled
            syncProtocolBoxes(existing)
            modelList.removeAllViews()
            checkedModels.clear()
            existing.modelIds.forEach {
                checkedModels.add(it)
                addModelChip(it)
            }
        } else {
            baseUrlInput.setText(defaultUrl)
            apiTypeInput.setText("openai-completions")
            useBaseUrlBox.isChecked = true
            useApiKeyBox.isChecked = false
            enabledBox.isChecked = true
            // 默认：HTTPS（通用服务商地址）
            httpBox.isChecked = false
            httpsBox.isChecked = true
            modelList.removeAllViews()
            checkedModels.clear()
        }
    }

    private fun syncProtocolBoxes(c: ProviderConfig) {
        // 兼容旧配置：useHttp/useHttps 都未定义时根据 baseUrl scheme 推断
        if (!c.useHttp && !c.useHttps) {
            httpBox.isChecked = c.baseUrl.startsWith("http://")
            httpsBox.isChecked = !httpBox.isChecked
        } else {
            httpBox.isChecked = c.useHttp
            httpsBox.isChecked = c.useHttps
        }
    }

    private fun persist() {
        NextPassConfigStore.save(currentConfigs)
    }

    private fun currentConfig(): ProviderConfig {
        val c = currentConfigs.firstOrNull { it.providerId == selectedProviderId }
            ?: ProviderConfig(providerId = selectedProviderId).also { currentConfigs.add(it) }
        c.displayName = displayNameInput.text.toString().trim()
        c.baseUrl = baseUrlInput.text.toString().trim()
        c.apiKey = apiKeyInput.text.toString().trim()
        c.apiType = apiTypeInput.text.toString().trim().ifEmpty { "openai-completions" }
        // A populated custom value is authoritative. This prevents a saved
        // provider from silently losing its URL/key when the checkbox was left
        // unchecked on an older screen.
        c.useCustomBaseUrl = useBaseUrlBox.isChecked || c.baseUrl.isNotBlank()
        c.useCustomApiKey = useApiKeyBox.isChecked || c.apiKey.isNotBlank()
        c.enabled = enabledBox.isChecked
        c.useHttp = httpBox.isChecked
        c.useHttps = httpsBox.isChecked
        c.modelIds.clear()
        c.modelIds.addAll(checkedModels.map { it.trim() }.filter { it.isNotEmpty() }.distinct())
        return c
    }

    private fun saveConfig() {
        try {
            currentConfig()
            currentConfigs = ProviderConfig.normalize(currentConfigs)
            val persisted = NextPassConfigStore.save(currentConfigs)
            refreshProviderOptions(selectedProviderId)
            toast("已保存 ${currentConfigs.size} 个服务商配置")
            statusText.text = if (persisted) "已保存。重启小布 Cloud 后生效" else "已保存到本机，重启小布 Cloud 后生效"
        } catch (t: Throwable) {
            statusText.text = "保存失败：${t.message ?: "未知错误"}"
            toast("保存失败，请检查输入")
        }
    }

    private fun deleteCurrentConfig() {
        val removed = currentConfigs.removeAll { it.providerId == selectedProviderId }
        if (!removed) {
            toast("当前是内置服务商，没有自定义配置可删除")
            return
        }
        NextPassConfigStore.save(currentConfigs)
        checkedModels.clear()
        val fallback = presets.first().first
        refreshProviderOptions(fallback)
        toast("已删除当前配置")
        statusText.text = "已删除。重启小布 Cloud 后生效"
    }

    private fun clearCurrentModels() {
        checkedModels.clear()
        modelList.removeAllViews()
        currentConfigs.firstOrNull { it.providerId == selectedProviderId }?.modelIds?.clear()
        NextPassConfigStore.save(currentConfigs)
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
        NextPassConfigStore.save(currentConfigs)
        checkedModels.clear()
        refreshProviderOptions(presets.first().first)
        toast("已清空全部自定义配置和模型")
        statusText.text = "已清理。重启小布 Cloud 后生效"
    }

    private fun testConnection() {
        val base = baseUrlInput.text.toString().trim().trimEnd('/')
        if (base.isEmpty()) { toast("请先填写 BaseURL"); return }
        statusText.text = "测试中..."
        Thread {
            try {
                val code = httpGet(base, apiKeyInput.text.toString().trim())
                ui.post {
                    statusText.text = if (code == 200) "连接成功 (HTTP $code)" else "失败 (HTTP $code)"
                    toast(if (code == 200) "连接成功" else "HTTP $code")
                }
            } catch (t: Throwable) {
                ui.post { statusText.text = "测试失败: ${t.message}"; toast("失败: ${t.message}") }
            }
        }.start()
    }

    private fun fetchModels() {
        val base = baseUrlInput.text.toString().trim().trimEnd('/')
        if (base.isEmpty()) { toast("请先填写 BaseURL"); return }
        statusText.text = "拉取模型..."
        Thread {
            try {
                val (code, body) = httpGetBody(base, apiKeyInput.text.toString().trim())
                if (code != 200) { ui.post { statusText.text = "获取模型失败 (HTTP $code)" }; return@Thread }
                val arr = JSONObject(body).optJSONArray("data")
                ui.post {
                    modelList.removeAllViews()
                    checkedModels.clear()
                    if (arr == null) { statusText.text = "响应中无 data 数组"; return@post }
                    val seen = mutableSetOf<String>()
                    for (i in 0 until arr.length()) {
                        val m = arr.getJSONObject(i)
                        val id = m.optString("id")
                        if (id.isBlank() || !seen.add(id)) continue
                        val name = m.optString("name", id)
                        // Fetching only discovers models. Nothing is selected or
                        // persisted until the user checks it explicitly.
                        addModelChip("$id  ($name)", checkedModels.contains(id))
                    }
                    statusText.text = "已获取 ${seen.size} 个模型，请勾选要使用的"
                }
            } catch (t: Throwable) {
                ui.post { statusText.text = "获取模型失败: ${t.message}" }
            }
        }.start()
    }

    private fun addModelChip(label: String, selected: Boolean = true) {
        val id = label.substringBefore("  (")
        val cb = CheckBox(this).apply { text = label }
        cb.setOnCheckedChangeListener { _, checked ->
            if (checked) checkedModels.add(id) else checkedModels.remove(id)
        }
        cb.isChecked = selected
        modelList.addView(cb)
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
