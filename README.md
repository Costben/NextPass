# NextPass

面向 `com.oplus.claw`（小布 Cloud）的 LSPosed 注入式 Hook 模块，用于 **自定义 AI 服务商（Provider）接入**。

NextPass 同时是一个普通可打开设置应用的 APK，也是一个 LSPosed 模块。在目标进程（`com.oplus.claw`）里只做 hook 注入，不隐藏、不结束任何 UI。

## 功能

| # | 功能 | 说明 |
|---|------|------|
| 1 | 明文 HTTP 放行 | 支持 `http://` 地址（本地自建网关场景） |
| 2 | Base URL / API Type 注入 | 按 `providerId` 精确匹配（`mh.e1` 构造器），只改写出现在 `nextpass.json` 且 `enabled=true` 的 provider |
| 3 | API Key 注入 | 对配置里 `useCustomApiKey=true` 的 provider 运行时注入 key |
| 4 | Provider 目录补齐 | 让自定义 provider 出现在目标 App 自带的 provider / 模型设置界面 |
| 5 | 自定义模型表单补全 | 编辑 / 保存时回填 Base URL 与 API Key |
| 6 | 原生 Provider 入口行 | 目标设置页第一行回调直接打开 NextPass 配置 Activity |

未出现在配置文件里的内置 provider（qwen / minimax / …）保持原样，互不影响。

## 配置存储

配置存于共享 JSON：`/sdcard/NextPass/nextpass.json`（两进程 uid 均可读写），模块私有目录 `/data/data/com.nextpass/files/nextpass.json` 作镜像回退。数据结构见 `ProviderConfig.kt`。

## 目录结构

```
app/src/main/
  java/com/nextpass/
    ModuleMain.kt            # Xposed 入口与全部 hook
    MainActivity.kt          # 普通桌面设置界面
    ProviderConfig.kt        # 配置模型 + nextpass.json 读写
    ConfigContentProvider.kt # 跨 uid 读配置的 ContentProvider 桥
  res/                       # 应用图标与资源
  resources/META-INF/xposed/ # 模块声明（module.prop / scope / init）
  AndroidManifest.xml
```

## 构建

需要 Android SDK 与 JDK 17。首次构建：

```bash
# 在 local.properties 写入本机 SDK 路径（或设置 ANDROID_HOME）
echo "sdk.dir=/path/to/Android/sdk" > local.properties

./gradlew :app:assembleRelease
```

产物：`app/build/outputs/apk/release/`。

- 依赖：`io.github.libxposed:api:102.0.0`（compileOnly）
- R8 已开启，`proguard-rules.pro` 保留 Xposed 入口与 `MainActivity`

## 使用

1. 安装 `app-release.apk`（Release 页面提供现成构建）。
2. LSPosed 中启用模块，作用域勾选 `com.oplus.claw`。
3. 打开 NextPass 桌面应用，添加 / 修改 provider 配置并保存。
4. 重启小布 Cloud 生效。

## 许可证

仅供学习与技术研究使用。使用前请自行确认对目标 App 相关条款的合规性。
