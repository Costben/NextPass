package com.nextpass

/**
 * Hook extension point.
 * Open-source base implementation is a no-op.
 */
object ModuleExt {
    fun onPackageReady(module: ModuleMain, cl: ClassLoader) {
        // No-op in open-source release
    }
}
