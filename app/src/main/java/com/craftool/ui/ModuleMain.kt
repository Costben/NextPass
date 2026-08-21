package com.craftool.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.security.NetworkSecurityPolicy
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import org.json.JSONArray
import org.json.JSONObject

/**
 * CraftUi — com.oplus.claw 注入式 Hook 模块。
 *
 * CraftUi 自身是普通可打开的设置应用；本类只在目标进程中安装 hooks，
 * 不负责隐藏或结束任何 UI。
 *
 *  1. cleartext 放行：支持 http:// 明文地址（本地 New API 场景）.
 *  2. mh.e1 构造器：**按 providerId 精确匹配**注入 baseUrl / apiType.
 *     只有出现在 nextpass.json 且 enabled=true 的 provider 才被改写，
 *     其余 provider（qwen/minimax/...）保持原样 → 天然支持多配置、互不影响.
 *  3. g1.q(provider)：对配置里 useCustomApiKey 的 provider 注入 apiKey.
 */
class ModuleMain : XposedModule() {

    companion object {
        private const val TAG = "CraftUi"
        private const val TARGET_PACKAGE = "com.oplus.claw"

        // 混淆后的关键类（编译期不可见，运行时用 ClassLoader 加载）
        private const val MODEL_CLASS = "mh.e1"                            // 模型对象（含 baseUrl）
        private const val KEY_MGR_CLASS = "com.oplus.claw.feedback.g1"     // apiKey 获取
        private const val SETTINGS_UI_CLASS = "com.oplus.claw.settings.o1"
    }

    private var resumedActivity: WeakReference<Activity>? = null
    private var lifecycleRegistered = false
    private var composeProviderDepth = 0
    private var composeProviderRows = 0
    private val customLabelPending = ThreadLocal.withInitial { false }
    private var targetConfigured = false
    private var providerCatalogHookInstalled = false
    private var attachHookInstalled = false
    private var resumeHookInstalled = false
    private var bindHookInstalled = false
    private var targetContext: Context? = null

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != TARGET_PACKAGE) return
        val cl = param.classLoader ?: return

        try {
            installCleartextHook()
            installNativeProviderEntryHook(cl)
            installProviderCatalogHook(cl)
            installCustomModelFormHook(cl)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "hook failed", t)
        }

        installActivityResumeHook(cl)

        // Application.currentApplication() can return a partially initialized
        // object during package injection. Always wait for attach/resume so the
        // target Context and its provider bridge are fully usable.
        installApplicationAttachHook(cl)
        installBindApplicationHook(cl)
    }

    private fun installApplicationAttachHook(cl: ClassLoader) {
        if (attachHookInstalled) return
        attachHookInstalled = true
        val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        hook(attach).intercept { chain ->
            val result = chain.proceed()
            val context = chain.getArg(0) as? Context
            if (!targetConfigured && context != null && context.packageName == TARGET_PACKAGE) {
                configureTarget(context, cl)
            }
            result
        }
    }

    private fun installActivityResumeHook(cl: ClassLoader) {
        if (resumeHookInstalled) return
        resumeHookInstalled = true
        val resume = Activity::class.java.getDeclaredMethod("onResume")
        hook(resume).intercept { chain ->
            val activity = chain.getThisObject() as? Activity
            if (activity != null && activity.packageName == TARGET_PACKAGE && !targetConfigured) {
                configureTarget(activity.applicationContext, cl)
            }
            chain.proceed()
        }
    }

    private fun installBindApplicationHook(cl: ClassLoader) {
        if (bindHookInstalled) return
        bindHookInstalled = true
        try {
            val threadClass = Class.forName("android.app.ActivityThread")
            val dataClass = Class.forName("android.app.ActivityThread\$AppBindData")
            val bind = threadClass.getDeclaredMethod("handleBindApplication", dataClass).apply { isAccessible = true }
            hook(bind).intercept { chain ->
                val result = chain.proceed()
                if (!targetConfigured) {
                    targetApplication()?.let { configureTarget(it, cl) }
                }
                result
            }
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "bind application hook failed: $t")
        }
    }

    private fun configureTarget(context: Context, cl: ClassLoader) {
        if (targetConfigured) return
        targetContext = context.applicationContext
        val configs = NextPassConfigStore.load(context)
        if (configs.isEmpty() && !NextPassConfigStore.isAvailable(context)) {
            // CraftUi may still be starting. Leave this false so the resume
            // hook retries instead of freezing an empty snapshot for the life
            // of the Cloud process.
            log(Log.INFO, TAG, "config bridge not ready; will retry")
            return
        }
        val enabled = configs.filter { it.enabled }
        log(Log.INFO, TAG, "config: ${configs.size} providers (enabled=${enabled.map { it.providerId }})")
        try {
            // Always reconcile the target model preference, including the empty
            // case. This removes legacy CraftUi entries after the last custom
            // provider is deleted.
            syncTargetStores(context, cl, enabled)
            if (enabled.isNotEmpty()) {
                installBaseUrlHook(cl, enabled)
                installApiKeyHook(cl, enabled)
            }
            targetConfigured = true
            log(Log.INFO, TAG, "CraftUi hooks installed")
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "configured hook failed", t)
        }
    }

    private fun targetApplication(): Application? = try {
        Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication")
            .invoke(null) as? Application
    } catch (_: Throwable) { null }

    /** Make CraftUi entries visible to Cloud's own provider/model settings UI. */
    private fun syncTargetStores(context: Context, cl: ClassLoader, configs: List<ProviderConfig>) {
        try {
            val prefs = context.getSharedPreferences("mobileclaw_settings_ui", Context.MODE_PRIVATE)
            // This preference is the bridge's model catalogue. Reconcile it
            // from the single normalized CraftUi source on every Cloud start
            // so entries from older versions cannot accumulate or duplicate.
            val merged = JSONArray()
            val seenKeys = mutableSetOf<String>()
            for (config in configs) {
                for (modelId in config.modelIds.map { it.trim() }.filter { it.isNotEmpty() }) {
                    val modelKey = "${config.providerId}/$modelId"
                    if (!seenKeys.add(modelKey)) continue
                    val entry = JSONObject()
                        .put("entryId", "craftui-${config.providerId}-${modelId.hashCode().toUInt().toString(16)}")
                        .put("displayName", modelId)
                        .put("providerId", config.providerId)
                        .put("modelId", modelId)
                        .put("apiBaseUrl", config.baseUrl)
                        .put("apiKey", if (config.useCustomApiKey) config.apiKey else "")
                        .put("apiType", config.apiType.ifBlank { "openai-completions" })
                        .put("primaryKey", modelKey)
                        .put("purpose", "UNDERSTANDING")
                    merged.put(entry)
                }
            }
            prefs.edit().putString("custom_ai_models_json", merged.toString()).apply()
            log(Log.INFO, TAG, "synced ${merged.length()} custom models into mobileclaw_settings_ui")
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "custom model preference sync failed: $t")
        }

        // Use Cloud's own keystore-backed writer so its settings screen also
        // sees the provider key as configured. Runtime reads are hooked below.
        try {
            val store = findKeyStoreClass(cl)
                ?: throw ClassNotFoundException("api key store")
            val write = store.getDeclaredMethod("s", Context::class.java, String::class.java, String::class.java)
                .apply { isAccessible = true }
            for (config in configs.filter { it.useCustomApiKey && it.apiKey.isNotEmpty() }) {
                write.invoke(null, context, "models.providers.${config.providerId}.apiKey", config.apiKey)
            }
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "api key preference sync unavailable: $t")
        }
    }

    /**
     * The custom-model form reads its provider dropdown from p5.b(). The
     * backing list is an immutable Kotlin list, so mutating p5.a crashes
     * with UnsupportedOperationException. Hook the accessors instead and
     * return a mutable copy containing the current CraftUi providers.
     */
    private fun installProviderCatalogHook(cl: ClassLoader) {
        if (providerCatalogHookInstalled) return
        providerCatalogHookInstalled = true
        try {
            val catalog = Class.forName("com.oplus.claw.settings.p5", false, cl)
            val templateClass = Class.forName("com.oplus.claw.settings.s5", false, cl)
            val ctor = templateClass.getDeclaredConstructor(
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType
            ).apply { isAccessible = true }
            val idField = templateClass.getDeclaredField("a").apply { isAccessible = true }
            fun currentConfigs(): List<ProviderConfig> =
                (targetContext ?: targetApplication())?.let { NextPassConfigStore.load(it) } ?: emptyList()

            val listMethod = catalog.getDeclaredMethod("b").apply { isAccessible = true }
            hook(listMethod).intercept { chain ->
                val original = (chain.proceed() as? Iterable<*>)?.filterNotNull()?.toMutableList()
                    ?: mutableListOf()
                val existingIds = original.mapNotNull { runCatching { idField.get(it) as? String }.getOrNull() }.toMutableSet()
                for (config in currentConfigs().filter { it.enabled && it.providerId.isNotBlank() }) {
                    if (existingIds.add(config.providerId)) {
                        original.add(ctor.newInstance(
                            config.providerId,
                            config.displayName.ifBlank { config.providerId },
                            config.baseUrl,
                            config.apiType.ifBlank { "openai-completions" },
                            null,
                            false
                        ))
                    }
                }
                log(Log.INFO, TAG, "provider catalog read: ${original.size} entries")
                original
            }

            val getMethod = catalog.getDeclaredMethod("g", String::class.java).apply { isAccessible = true }
            hook(getMethod).intercept { chain ->
                val providerId = chain.getArg(0) as? String
                val config = providerId?.let { id -> currentConfigs().firstOrNull { it.enabled && it.providerId == id } }
                if (config != null) {
                    ctor.newInstance(
                        config.providerId,
                        config.displayName.ifBlank { config.providerId },
                        config.baseUrl,
                        config.apiType.ifBlank { "openai-completions" },
                        null,
                        false
                    )
                } else chain.proceed()
            }

            val containsMethod = catalog.getDeclaredMethod("e", String::class.java).apply { isAccessible = true }
            hook(containsMethod).intercept { chain ->
                val providerId = chain.getArg(0) as? String
                if (providerId != null && currentConfigs().any { it.enabled && it.providerId == providerId }) {
                    true
                } else chain.proceed()
            }
            log(Log.INFO, TAG, "provider catalog access hooks installed")
        } catch (t: Throwable) {
            providerCatalogHookInstalled = false
            log(Log.WARN, TAG, "provider catalog hook failed: $t")
        }
    }

    /**
     * Cloud's custom-model list intentionally persists only provider/model IDs;
     * its editor then creates an empty m5 state and leaves Base URL/API Key blank.
     * Supply those two fields from CraftUi whenever the editor renders or saves.
     */
    private fun installCustomModelFormHook(cl: ClassLoader) {
        try {
            val settings = Class.forName("com.oplus.claw.settings.s2", false, cl)
            val m5Class = Class.forName("com.oplus.claw.settings.m5", false, cl)
            val stateMethod = findMethod(
                settings,
                "n",
                arrayOf(
                    m5Class,
                    String::class.java,
                    java.util.List::class.java,
                    Class.forName("n20.a", false, cl),
                    Class.forName("n20.l", false, cl),
                    Class.forName("n20.l", false, cl),
                    Class.forName("n20.q", false, cl),
                    Class.forName("n20.p", false, cl),
                    Class.forName("androidx.compose.runtime.j0", false, cl),
                    Int::class.javaPrimitiveType!!
                )
            ) ?: throw IllegalStateException("settings.s2.n form renderer not found")
            hook(stateMethod).intercept { chain ->
                val args = Array<Any?>(10) { i -> chain.getArg(i) }
                args[0] = enrichCustomFormState(cl, args[0])
                chain.proceed(args)
            }

            val viewModel = Class.forName("com.oplus.claw.settings.am", false, cl)
            val saveMethod = findMethod(
                viewModel,
                "h",
                arrayOf(viewModel, m5Class, Class.forName("x30.b0", false, cl))
            ) ?: throw IllegalStateException("settings.am.h save payload builder not found")
            hook(saveMethod).intercept { chain ->
                val args = Array<Any?>(3) { i -> chain.getArg(i) }
                args[1] = enrichCustomFormState(cl, args[1])
                chain.proceed(args)
            }

            // Cloud's own delete button removes only its local catalogue.
            // Mirror that deletion into CraftUi so the next reconciliation
            // does not silently add the model back.
            val deleteMethod = settings.getDeclaredMethod(
                "C1", Context::class.java, String::class.java, String::class.java,
                String::class.java, Class.forName("com.oplus.claw.settings.o5", false, cl)
            ).apply { isAccessible = true }
            hook(deleteMethod).intercept { chain ->
                val result = chain.proceed()
                val providerId = chain.getArg(2) as? String
                val modelId = chain.getArg(3) as? String
                if (!providerId.isNullOrBlank() && !modelId.isNullOrBlank()) {
                    removeCraftUiModel(providerId, modelId)
                }
                result
            }
            log(Log.INFO, TAG, "custom model form BaseURL/API key hooks installed")
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "custom model form hook failed: $t")
        }
    }

    private fun removeCraftUiModel(providerId: String, modelId: String) {
        val context = targetContext ?: targetApplication() ?: return
        val updated = NextPassConfigStore.load(context).map { cfg ->
            if (cfg.providerId == providerId) {
                cfg.copy(modelIds = cfg.modelIds.filterNot { it == modelId }.toMutableList())
            } else cfg
        }
        val payload = Bundle().apply {
            putString("json", JSONArray().apply { updated.forEach { put(it.toJson()) } }.toString())
        }
        val bridged = runCatching {
            context.contentResolver.call(Uri.parse("content://com.craftool.ui.config"), "write_config", null, payload)
                ?.getBoolean("ok", false) == true
        }.getOrDefault(false)
        if (!bridged) NextPassConfigStore.save(updated)
        log(Log.INFO, TAG, "removed CraftUi model $providerId/$modelId")
    }

    private fun enrichCustomFormState(cl: ClassLoader, state: Any?): Any? {
        if (state == null) return null
        return try {
            val m5Class = state.javaClass
            fun field(vararg names: String): Any? {
                for (name in names) {
                    val value = runCatching {
                        m5Class.getDeclaredField(name).apply { isAccessible = true }.get(state)
                    }.getOrNull()
                    if (value != null) return value
                }
                return null
            }
            // Release builds keep the Kotlin constructor fields as a..q;
            // JADX displays their mapped f134xx names. Support both forms.
            val providerId = (field("d", "f13482d") as? String)
            val modelId = (field("e", "f13483e") as? String)
            val cfg = NextPassConfigStore.load(targetContext ?: targetApplication())
                .firstOrNull { it.enabled && it.providerId == providerId }
                ?: return state
            if (cfg.baseUrl.isBlank() && cfg.apiKey.isBlank() && cfg.modelIds.isEmpty()) return state
            val currentModel = modelId.orEmpty().ifBlank { cfg.modelIds.firstOrNull().orEmpty() }
            val ctor = m5Class.getDeclaredConstructor(
                Class.forName("com.oplus.claw.settings.o5", false, cl), String::class.java,
                String::class.java, String::class.java, String::class.java, String::class.java,
                String::class.java, String::class.java, String::class.java, String::class.java,
                Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                Class.forName("com.oplus.claw.settings.d7", false, cl)
            ).apply { isAccessible = true }
            val enriched = ctor.newInstance(
                field("a", "f13479a"), field("b", "f13480b"),
                cfg.displayName.ifBlank { field("c", "f13481c") as String },
                providerId.orEmpty(), currentModel.ifBlank { modelId.orEmpty() },
                field("f", "f13484f"), field("g", "f13485g"),
                cfg.baseUrl.ifBlank { field("h", "f13486h") as String },
                cfg.apiKey.ifBlank { field("i", "f13487i") as String },
                cfg.apiType.ifBlank { field("j") as String },
                field("k", "f13488k"), field("l", "f13489l"), field("m", "f13490m"),
                field("n", "f13491n"), field("o", "f13492o"), field("p", "f13493p"), field("q", "f13494q")
            )
            log(Log.INFO, TAG, "custom form enriched provider=$providerId model=${currentModel.ifBlank { modelId.orEmpty() }} base=${cfg.baseUrl.isNotBlank()} key=${cfg.apiKey.isNotBlank()}")
            enriched
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "custom model form state enrichment failed: $t")
            state
        }
    }

    // ---------- 1. 放行明文 HTTP（cleartext） ----------
    private fun installCleartextHook() {
        try {
            val noArg = NetworkSecurityPolicy::class.java.getDeclaredMethod("isCleartextTrafficPermitted")
            hook(noArg).intercept { true }
        } catch (t: Throwable) { log(Log.WARN, TAG, "cleartext hook(no-arg) failed: $t") }
        try {
            val withHost = NetworkSecurityPolicy::class.java.getDeclaredMethod("isCleartextTrafficPermitted", String::class.java)
            hook(withHost).intercept { true }
        } catch (t: Throwable) { log(Log.WARN, TAG, "cleartext hook(host) failed: $t") }
        log(Log.INFO, TAG, "cleartext allowed")
    }

    // Keep the target app's provider settings row. Its callback opens the same
    // ordinary launcher Activity as the desktop icon, so there is no hidden or
    // special-purpose configuration Activity anymore.
    private fun installNativeProviderEntryHook(cl: ClassLoader) {
        registerActivityLifecycle()
        val settingsClass = Class.forName(SETTINGS_UI_CLASS, false, cl)
        val viewModelClass = Class.forName("com.oplus.claw.settings.am", false, cl)
        val callbackClass = Class.forName("n20.a", false, cl)
        val composeClass = Class.forName("androidx.compose.runtime.j0", false, cl)
        val target = findMethod(
            settingsClass,
            "q",
            arrayOf(
                viewModelClass,
                callbackClass,
                callbackClass,
                callbackClass,
                callbackClass,
                callbackClass,
                callbackClass,
                composeClass,
                Int::class.javaPrimitiveType!!
            )
        ) ?: throw IllegalStateException("settings.o1.q provider screen not found")

        hook(target).intercept { chain ->
            composeProviderDepth++
            composeProviderRows = 0
            try {
                chain.proceed()
            } finally {
                composeProviderDepth--
            }
        }

        val intClass = Int::class.javaPrimitiveType!!
        val rowMethod = findMethod(settingsClass, "e", arrayOf(callbackClass, composeClass, intClass))
            ?: throw IllegalStateException("settings.o1.e custom-model row not found")
        hook(rowMethod).intercept { chain ->
            val original = chain.proceed()
            if (composeProviderDepth > 0 && composeProviderRows == 0) {
                composeProviderRows++
                val args = arrayOfNulls<Any>(3)
                args[0] = nextPassCallback(cl, callbackClass)
                args[1] = chain.getArg(1)
                args[2] = chain.getArg(2)
                customLabelPending.set(true)
                try {
                    renderNativeProviderDivider(cl, args[1]!!)
                    chain.proceed(args)
                } finally {
                    customLabelPending.set(false)
                }
            }
            original
        }

        val stringsClass = Class.forName("com.oplus.dmp.sdk.f", false, cl)
        val stringMethod = findMethod(stringsClass, "V", arrayOf(intClass, composeClass))
            ?: throw IllegalStateException("resource string resolver not found")
        hook(stringMethod).intercept { chain ->
            if (customLabelPending.get() == true) {
                customLabelPending.set(false)
                "自定义模型接入"
            } else {
                chain.proceed()
            }
        }
        log(Log.INFO, TAG, "native AI provider row extended through Xiaobu Compose renderer")
    }

    private fun renderNativeProviderDivider(cl: ClassLoader, composer: Any) {
        val composerClass = Class.forName("androidx.compose.runtime.j0", false, cl)
        val modifierClass = Class.forName("c2.q", false, cl)
        val modifierRoot = Class.forName("c2.n", false, cl)
            .getDeclaredField("f6147a")
            .apply { isAccessible = true }
            .get(null)
        val modifier = Class.forName("l0.c", false, cl)
            .getDeclaredMethod("D", modifierClass, Float::class.javaPrimitiveType!!, Float::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
            .apply { isAccessible = true }
            .invoke(null, modifierRoot, 16f, 0f, 2)
        val dividerColor = Class.forName(SETTINGS_UI_CLASS, false, cl)
            .getDeclaredMethod("C", composerClass)
            .apply { isAccessible = true }
            .invoke(null, composer)
        Class.forName("k1.q", false, cl)
            .getDeclaredMethod(
                "e",
                Float::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Long::class.javaPrimitiveType!!,
                composerClass,
                modifierClass
            )
            .apply { isAccessible = true }
            .invoke(null, 0f, 6, 2, dividerColor, composer, modifier)
    }

    private fun registerActivityLifecycle() {
        if (lifecycleRegistered) return
        try {
            val currentApplication = Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication")
                .invoke(null) as? Application ?: return
            currentApplication.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    if (activity.packageName == TARGET_PACKAGE) resumedActivity = WeakReference(activity)
                }
                override fun onActivityPaused(activity: Activity) {
                    if (resumedActivity?.get() === activity) resumedActivity = null
                }
                override fun onActivityDestroyed(activity: Activity) {
                    if (resumedActivity?.get() === activity) resumedActivity = null
                }
                override fun onActivityCreated(a: Activity, b: android.os.Bundle?) = Unit
                override fun onActivityStarted(a: Activity) = Unit
                override fun onActivityStopped(a: Activity) = Unit
                override fun onActivitySaveInstanceState(a: Activity, b: android.os.Bundle) = Unit
            })
            lifecycleRegistered = true
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "activity lifecycle registration failed: $t")
        }
    }

    private fun nextPassCallback(cl: ClassLoader, callbackClass: Class<*>): Any =
        Proxy.newProxyInstance(cl, arrayOf(callbackClass)) { _, method, _ ->
            if (method.name == "invoke") {
                val activity = resumedActivity?.get() ?: findCurrentActivity()
                if (activity != null) launchProviderConfig(activity)
            }
            Unit
        }

    private fun launchProviderConfig(activity: Activity) {
        try {
            val intent = Intent().setClassName("com.craftool.ui", "com.craftool.ui.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            activity.startActivity(intent)
            log(Log.INFO, TAG, "CraftUi provider row -> MainActivity")
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "launch CraftUi MainActivity failed: $t")
        }
    }

    private fun findCurrentActivity(): Activity? {
        return try {
            val threadClass = Class.forName("android.app.ActivityThread")
            val thread = threadClass.getDeclaredMethod("currentActivityThread").invoke(null)
            val field = threadClass.getDeclaredField("mActivities").apply { isAccessible = true }
            val records = field.get(thread) as? Map<*, *> ?: return null
            records.values.asSequence().mapNotNull { record ->
                val value = record ?: return@mapNotNull null
                val activityField = value.javaClass.getDeclaredField("activity").apply { isAccessible = true }
                val pausedField = value.javaClass.getDeclaredField("paused").apply { isAccessible = true }
                val activity = activityField.get(value) as? Activity
                if (activity?.packageName == TARGET_PACKAGE && pausedField.getBoolean(value).not()) activity else null
            }.firstOrNull()
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "current Activity lookup failed: $t")
            null
        }
    }

    // ---------- 2. 按 providerId 精确匹配注入 baseUrl / apiType（mh.e1 构造器） ----------
    private fun installBaseUrlHook(cl: ClassLoader, enabled: List<ProviderConfig>) {
        if (enabled.isEmpty()) {
            log(Log.INFO, TAG, "no enabled provider, skip baseUrl hook")
            return
        }
        val modelClass = Class.forName(MODEL_CLASS, false, cl)
        val d1Class = Class.forName("mh.d1", false, cl)
        val b0Class = Class.forName("x30.b0", false, cl)

        val ctor = findConstructor(
            modelClass,
            arrayOf(
                String::class.java, String::class.java, String::class.java, String::class.java, String::class.java,
                Boolean::class.javaPrimitiveType!!, java.util.List::class.java, d1Class,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, java.util.Map::class.java, b0Class
            )
        ) ?: throw IllegalStateException("mh.e1 ctor not found")

        hook(ctor).intercept { chain ->
            val args = Array<Any?>(12) { i -> chain.getArg(i) }
            // e1 构造参数: [0]=id, [1]=name, [2]=api, [3]=provider, [4]=baseUrl
            val provider = args[3] as? String
            val baseUrl = args[4] as? String
            val cfg = if (provider != null) enabled.firstOrNull { it.providerId == provider } else null
            if (cfg != null && baseUrl != null) {
                val newUrl = if (cfg.useCustomBaseUrl && cfg.baseUrl.isNotEmpty()) {
                    schemeByProtocol(cfg, cfg.baseUrl)
                } else baseUrl
                val newApi = if (cfg.useCustomBaseUrl && cfg.apiType.isNotEmpty()) cfg.apiType else args[2]
                if (newUrl != baseUrl) {
                    log(Log.INFO, TAG, "e1[${cfg.providerId}] baseUrl: $baseUrl -> $newUrl")
                }
                args[2] = newApi
                args[4] = newUrl
            }
            chain.proceed(args)
        }
        log(Log.INFO, TAG, "baseUrl hook installed for ${enabled.map { it.providerId }}")
    }

    /**
     * 按服务商配置的访问协议强制 baseUrl 的 scheme。
     * - useHttps=true -> https://（默认）
     * - useHttp=true  -> http://（明文，本地端口模型）
     * - 两者都未配置（旧配置）-> 保持原样
     */
    private fun schemeByProtocol(cfg: ProviderConfig, url: String): String {
        val out = url.trimEnd('/')
        when {
            cfg.useHttp -> return out.replaceFirst(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://"), "http://")
            cfg.useHttps -> return out.replaceFirst(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://"), "https://")
            else -> return out
        }
    }

    // ---------- 3. 可选注入 apiKey（g1.q(provider)） ----------
    private fun installApiKeyHook(cl: ClassLoader, enabled: List<ProviderConfig>) {
        val need = enabled.filter { it.useCustomApiKey && it.apiKey.isNotEmpty() }
        if (need.isEmpty()) {
            log(Log.INFO, TAG, "no custom-apiKey provider, skip apiKey hook")
            return
        }
        val keyStoreClass = findKeyStoreClass(cl)
            ?: throw ClassNotFoundException("api key store")
        val read = findMethod(keyStoreClass, "o", arrayOf(Context::class.java, String::class.java))
        if (read == null) {
            log(Log.WARN, TAG, "api key store read method unavailable; runtime key hook skipped")
            return
        }
        hook(read).intercept { chain ->
            val path = chain.getArg(1) as? String
            val cfg = need.firstOrNull { path == "models.providers.${it.providerId}.apiKey" }
            if (cfg != null) {
                log(Log.INFO, TAG, "api key store read($path) -> injected key")
                cfg.apiKey
            } else chain.proceed()
        }
        log(Log.INFO, TAG, "apiKey hook installed for ${need.map { it.providerId }}")
    }

    private fun findKeyStoreClass(cl: ClassLoader): Class<*>? =
        sequenceOf("te.t0", "te.r0").mapNotNull {
            runCatching { Class.forName(it, false, cl) }.getOrNull()
        }.firstOrNull { clazz ->
            findMethod(clazz, "o", arrayOf(Context::class.java, String::class.java)) != null
        }

    // ---------- 反射辅助 ----------
    private fun findMethod(clazz: Class<*>, name: String, paramTypes: Array<Class<*>>): Method? {
        for (m in clazz.declaredMethods) {
            if (m.name != name) continue
            if (m.parameterTypes.contentEquals(paramTypes)) {
                m.isAccessible = true
                return m
            }
        }
        return null
    }

    private fun findConstructor(clazz: Class<*>, paramTypes: Array<Class<*>>): Constructor<*>? {
        for (c in clazz.declaredConstructors) {
            if (c.parameterTypes.contentEquals(paramTypes)) {
                c.isAccessible = true
                return c
            }
        }
        return null
    }
}
