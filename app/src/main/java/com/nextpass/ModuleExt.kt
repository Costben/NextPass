package com.nextpass

import android.content.Context
import android.util.Log

/**
 * Internal extension implementation:
 * Injects access gate bypass hooks for internal-beta qualifications.
 */
object ModuleExt {
    private const val TAG = "NextPass"
    private const val PARSER_CLASS = "com.oplus.claw.welcome.c3"
    private const val OK_CLASS = "com.oplus.claw.welcome.r0"
    private const val GATE_CLASS = "com.oplus.claw.welcome.k3"

    fun onPackageReady(module: ModuleMain, cl: ClassLoader) {
        try {
            installAccessGateHook(module, cl)
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "access gate hook failed: $t")
        }
    }

    private fun installAccessGateHook(module: ModuleMain, cl: ClassLoader) {
        installGateStateHooks(module, cl)

        val parserClass = Class.forName(PARSER_CLASS, false, cl)
        val okClass = Class.forName(OK_CLASS, false, cl)
        val m4Class = Class.forName("com.oplus.claw.welcome.m4", false, cl)
        val n3Class = Class.forName("com.oplus.claw.settings.n3", false, cl)

        val target = module.findMethod(
            parserClass,
            "e",
            arrayOf(m4Class, String::class.java, String::class.java, Boolean::class.javaPrimitiveType!!)
        ) ?: throw IllegalStateException("c3.e(m4,String,String,boolean) not found")

        module.hook(target).intercept { chain ->
            val recordId = chain.getArg(1) as? String ?: "nextpass-ok"
            module.log(Log.INFO, TAG, "c3.e -> force Ok (recordId=$recordId)")
            val okCtor = module.findConstructor(
                okClass,
                arrayOf(
                    String::class.java,
                    String::class.java,
                    Integer::class.java,
                    String::class.java,
                    Boolean::class.javaPrimitiveType!!,
                    n3Class
                )
            )
            if (okCtor != null) {
                okCtor.newInstance(recordId, null, null, null, false, null)
            } else {
                chain.proceed()
            }
        }
        module.log(Log.INFO, TAG, "access gate hook installed")
    }

    private fun installGateStateHooks(module: ModuleMain, cl: ClassLoader) {
        val gateClass = Class.forName(GATE_CLASS, false, cl)
        val grant = module.findMethod(gateClass, "e", emptyArray())
            ?: throw IllegalStateException("k3.e() not found")
        val hydrate = module.findMethod(gateClass, "b", arrayOf(Context::class.java))
            ?: throw IllegalStateException("k3.b(Context) not found")
        val deny = module.findMethod(gateClass, "d", arrayOf(String::class.java))
            ?: throw IllegalStateException("k3.d(String) not found")
        val blockedCall = module.findMethod(
            gateClass,
            "a",
            arrayOf(Class.forName("mh.g1", false, cl), String::class.java)
        ) ?: throw IllegalStateException("k3.a(mh.g1,String) not found")

        module.hook(grant).intercept { chain ->
            val result = chain.proceed()
            module.log(Log.INFO, TAG, "k3.e() -> granted")
            result
        }
        module.hook(hydrate).intercept {
            grant.invoke(null)
            module.log(Log.INFO, TAG, "k3.b(Context) -> true")
            true
        }
        module.hook(deny).intercept {
            grant.invoke(null)
            module.log(Log.INFO, TAG, "k3.d(reason) -> suppressed deny")
            null
        }
        module.hook(blockedCall).intercept {
            module.log(Log.INFO, TAG, "k3.a(model,message) -> suppressed blocked call")
            null
        }
        module.log(Log.INFO, TAG, "access state hooks installed for k3")
    }
}
