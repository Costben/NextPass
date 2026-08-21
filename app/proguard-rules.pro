-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list

# Xposed 入口
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# Launcher activity is referenced by the manifest and by the provider-row hook.
-keep public class com.craftool.ui.MainActivity { *; }
