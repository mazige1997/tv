# TVBox Kotlin + Rust(电视版重构)

本项目是 `TVBox OSC` 电视版的重构版本:**应用层 Kotlin + 原生核心 Rust**,所有业务功能保持与原版一致。仓库已自包含:`player/`、`quickjs/` 两个成熟 Java 模块随仓库一起提交,不再依赖上一级目录,可直接用于 GitHub Actions 云编译。

## 目录结构

```
.
├── settings.gradle.kts        # 工程入口(内置 player / quickjs 模块)
├── build.gradle.kts           # AGP 8.2.2 + Kotlin 1.9.24
├── gradle.properties
├── gradlew / gradlew.bat      # Gradle 8.7 wrapper
├── .github/workflows/build.yml # 云编译 + 发布 GitHub Release
├── player/                    # 播放器框架模块(doikki / Exo / IJK 封装)
├── quickjs/                   # quickjs 引擎封装模块
├── app/
│   ├── build.gradle.kts       # 电视版单 flavor "tv";内置 buildRustCore 任务
│   ├── proguard-rules.pro
│   ├── libs/                  # 迅雷 jar / xwalk aar 等本地依赖
│   └── src/main/
│       ├── java/com/github/tvbox/kt/...   # 全部 Kotlin 新代码
│       └── res/ assets/                   # 与原版一致(界面/UA库)
└── rust/
    ├── core/                  # tvbox-core:纯 Rust 逻辑(含 M3u8 去广告 / 弹幕引擎)
    └── jni/                   # tvbox-jni → libtvbox_core.so(JNI 入口 + catch_unwind 防崩溃)
```

## 构建方式

### 本机构建(需要 JDK 17 + Android SDK/NDK + Rust)

```bash
./gradlew assembleTvRelease     # 输出 app/build/outputs/apk/tv/release/*.apk
```

`app/build.gradle.kts` 中的 `buildRustCore` 任务会在编译前自动用 NDK 交叉编译 Rust 核心
(三 ABI:`arm64-v8a` / `armeabi-v7a` / `x86_64`),产物放在 `build/rust/`。
未安装 Rust 工具链时该任务会跳过并回退到运行时 Kotlin 实现,保证纯 Android 构建不被阻断。
本机路径通过 `ANDROID_HOME` / `JAVA_HOME` 环境变量或 `local.properties` 提供;
`local.properties` 和 `org.gradle.java.home` 已被 `.gitignore` / 配置移除,不会提交到仓库。

### GitHub Actions 云编译与 Release

工作流:`.github/workflows/build.yml`

1. `rust-test`:运行 `cargo test -p tvbox-core --release --locked`,测试通过才继续编译。
2. `build-release`:配置 JDK 17 + Android SDK/NDK 25 + Rust 三个 Android target →
   执行 `./gradlew assembleTvRelease` → 上传构建产物(Artifact)。
3. 发布方式:
   - 推送 `v*` 标签(如 `git tag v2.0.1 && git push origin v2.0.1`)→ 自动创建 GitHub Release,
     并上传 Release APK 与 `mapping.txt`。
   - 在 Actions 页面手动触发 `workflow_dispatch` → 同样创建 Release;
     可填写 `release_tag`,留空则使用 `v<run number>`。
   - 普通分支 push / PR 只跑编译、上传 Artifact,不会创建 Release。

发布 Release 需要仓库具备 `contents: write` 权限(工作流已声明)。

### 运行时 Rust 回退设计

`NativeCore.available` 标志决定走 Rust 还是 Kotlin 实现:
- 直播源解析:`LiveParseHelper` → `NativeCore.parseLive` 或 `TxtSubscribe`
- 配置解码:`ApiConfig.FindResult` → `NativeCore.decodeConfig` 或 `AES` 工具
- joinUrl:`HtmlParser.joinUrl` → `NativeCore.joinUrl` 或 `java.net.URL`
- M3U8 去广告:`M3u8.purify` → `NativeCore.purifyM3u8` 或纯 Kotlin 实现

因此无 Rust 环境编译的 APK 依旧功能完整,只是少了 Rust 的速度优势。

## 关键工程决策

| 决策 | 说明 |
|---|---|
| 内置旧 `player`/`quickjs` 模块 | 播放器框架(doikki VideoView + Exo/IJK 封装)与 quickjs 引擎是成熟 Java 库,重写风险高、无用户收益;两模块已复制进仓库并由 `settings.gradle.kts` 直接引用,保证 GitHub Actions 可独立构建,同时保留 AGP8 namespace 兼容补丁 |
| 跳过 Python 爬虫 | pyramid(Chaquopy)依赖本机 Python 路径,CI 困难;保留 `PyLoader` 接口位,后续可插回 |
| `applicationId` 不变 | `com.github.tvbox.osc.jun`;Hawk 键、Room 库名(`tvbox.v3.db`)与原版一致 → 覆盖安装后配置/历史/收藏无缝沿用 |
| minSdk 21 / targetSdk 28 | 与电视盒生态兼容(原版 minSdk 19 + MultiDex 不再需要) |
| 界面资源原样复用 | `res/` 原版拷贝,仅替换自定义 View 类的包名引用;保证界面零变化 |

## 模块索引(Kotlin 侧,`com.github.tvbox.kt.*`)

- `KtApp` 应用入口(NativeCore 加载、OkGo/LoadSir/AutoSize/QuickJS 初始化)
- `core/` NativeCore(JNI)、NativeProxy、net(OkHttp/OkDns)、spider(Spider/JarLoader/JsLoader/ProtectedInitJar/js 桥全套)
- `api/` ApiConfig(配置加载/解析/直播/规则)、DanmakuApi(弹幕搜索)
- `server/` RemoteServer(nanohttpd:push/proxy/file/transfer)、ControlManager、ShellUtils
- `cache/` + `data/` Room(Cache/VodCollect/VodRecord)+ CacheManager/RoomDataManger
- `player/` ExoPlayer/IjkMediaPlayer/MyVideoView/controller(VodController/LiveController)/danmu(Parser/DanmuLoadController)/thirdparty(Kodi/MX/Reex/RemoteTVBox/VLC)
- `util/` 配置/网络/直播解析/去广告(M3u8)/图片/字幕/迅雷 Thunder 等工具
- `ui/` Activity/Fragment/Adapter/Dialog(见 PROGRESS.md 移植进度)

## 与原版的兼容性

- 三类爬虫全部支持:jar(DexClassLoader + csp_ 类名)、JS(quickjs ES 模块/字节码/自定义 jsapi dex)、push_agent
- 配置格式兼容:`spider`/`sites`/`parses`/`lives`/`rules`/`hosts`/`doh`/`ads`/`wallpaper`/`danmaku`,含 AES 加密配置、图片包装 jar、`2423` 封装
- 直播格式兼容:txt/m3u/json 三种直播源(统一 `#EXTM3U` 元数据、`ua`/`parse`/`click`/`header` 行内设置、catchup 回看)
- 本地服务兼容:`/proxy?do=...`、`/push/`、`/file/`、`/action`、`/media`、`/transfer/*`、`/dns-query`

## 尚未完成的部分

见 `tv/PROGRESS.md` —— 剩余为 SourceViewModel/SubtitleViewModel、subtitle 引擎包与 UI 层
(activity/fragment/adapter/dialog/widget)的机械性 Kotlin 移植;核心设施、爬虫体系、播放器
控制器、弹幕、API 层均已移植完成。Rust 核心 110/110 单元测试通过,云编译与 Release 工作流已就绪。
