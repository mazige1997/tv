import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

// ---------- Rust 原生核心 ----------
// tvbox-core 以 cdylib 形式参与构建;CI 与本机(装了 cargo-ndk)会自动编译,
// 未安装 Rust 工具链时回退使用预编译 .so,保证纯 Android 构建不被阻断。
val rustProjectDir = rootProject.projectDir.resolve("rust")
val rustJniDir = rustProjectDir.resolve("jni")
val rustLibName = "libtvbox_core.so"
val rustAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

val rustOutDir = File(rootDir, "build/rust")          // jniLibs 源:build/rust/<abi>/libtvbox_core.so
val rustTargetDir = File(rootDir, "build/rust-target") // cargo 原始产物(不得混入 jniLibs)

tasks.register("buildRustCore") {
    group = "build"
    description = "用 cargo-ndk 交叉编译 Rust 核心库(libtvbox_core.so)"
    outputs.dir(rustOutDir)
    // 输入跟踪 Rust 源码与清单,防止源码变更后任务被误判 up-to-date 而打包过期 .so
    inputs.dir(rustProjectDir.resolve("core/src"))
    inputs.dir(rustProjectDir.resolve("jni/src"))
    inputs.file(rustProjectDir.resolve("core/Cargo.toml"))
    inputs.file(rustProjectDir.resolve("jni/Cargo.toml"))
    inputs.file(rustProjectDir.resolve("Cargo.lock"))
    doLast {
        val isWindows = org.gradle.internal.os.OperatingSystem.current().isWindows
        val cargoHome = System.getenv("CARGO_HOME") ?: "${System.getProperty("user.home")}/.cargo"
        val cargo = File("$cargoHome/bin", if (isWindows) "cargo.exe" else "cargo")
        if (!cargo.exists()) {
            logger.warn("[buildRustCore] 未检测到 cargo,跳过 Rust 编译,使用已存在的预编译 .so")
            return@doLast
        }
        // AGP 在未安装 NDK 时访问 ndkDirectory 会抛异常,必须容错
        val ndkDir = try {
            val d = android.ndkDirectory
            if (d != null && d.exists()) d else null
        } catch (th: Throwable) {
            null
        }
        if (ndkDir == null) {
            logger.warn("[buildRustCore] 未检测到 Android NDK,跳过 Rust 编译,使用已存在的预编译 .so")
            return@doLast
        }
        val ndkBin = ndkDir.resolve("toolchains/llvm/prebuilt")
            .listFiles()?.firstOrNull()?.resolve("bin") ?: return@doLast
        val host = if (org.gradle.internal.os.OperatingSystem.current().isWindows) "windows-x86_64" else "linux-x86_64"
        rustAbis.forEach { abi ->
            val triple = when (abi) {
                "arm64-v8a" -> "aarch64-linux-android21"
                "armeabi-v7a" -> "armv7a-linux-androideabi21"
                else -> "x86_64-linux-android21"
            }
            val target = when (abi) {
                "arm64-v8a" -> "aarch64-linux-android"
                "armeabi-v7a" -> "armv7-linux-androideabi"
                else -> "x86_64-linux-android"
            }
            val clang = ndkBin.resolve("$triple-clang${if (isWindows) ".cmd" else ""}")
            val pb = ProcessBuilder(
                listOf(
                    cargo.absolutePath, "build", "--release",
                    "--target", target,
                    "--manifest-path", rustJniDir.resolve("Cargo.toml").absolutePath
                )
            )
            pb.environment()["CC"] = clang.absolutePath
            pb.environment()["AR"] = ndkBin.resolve("llvm-ar${if (isWindows) ".exe" else ""}").absolutePath
            // cargo 需要为每个 target 显式指定链接器(NDK clang wrapper)
            pb.environment()["CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"] = clang.absolutePath
            pb.environment()["CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER"] = clang.absolutePath
            pb.environment()["CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER"] = clang.absolutePath
            pb.environment()["CARGO_TARGET_DIR"] = rustTargetDir.absolutePath
            pb.directory(rustProjectDir)
            pb.inheritIO()
            val exit = pb.start().waitFor()
            if (exit != 0) throw GradleException("Rust 编译失败: $abi")
            val so = rustTargetDir.resolve("$target/release/$rustLibName")
            val dest = rustOutDir.resolve(abi)
            dest.mkdirs()
            so.copyTo(dest.resolve(rustLibName), overwrite = true)
        }
    }
}

android {
    namespace = "com.github.tvbox.kt"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.github.tvbox.osc.jun"   // 与原版一致,配置/历史数据可直接沿用
        minSdk = 21
        targetSdk = 28                                // 电视盒兼容性:维持原版行为
        versionCode = 2
        versionName = "2.0.0-kt"
        ndkVersion = "25.2.9519653"                   // 显式声明,保证 buildRustCore 能拿到 NDK

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }

    // 公开签名:keystore 与密码随仓库提交,仅用于 TVBox 社区构建,勿用于个人敏感应用
    signingConfigs {
        create("release") {
            storeFile = file("tvbox-release.jks")
            storePassword = "tvbox123"
            keyAlias = "tvbox"
            keyPassword = "tvbox123"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    flavorDimensions += "mode"
    productFlavors {
        // 电视版:唯一构建目标,横屏 leanback
        create("tv") {
            dimension = "mode"
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(File(rootDir, "build/rust")) // buildRustCore 产物
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/beans.xml")
        }
    }

    applicationVariants.all {
        outputs.all {
            val output = this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output?.outputFileName = "TVBox_${buildType.name}-kt-${name}.apk"
        }
    }
}

tasks.named("preBuild") {
    dependsOn("buildRustCore")
}

dependencies {
    // 复用成熟的播放器框架与 quickjs 引擎模块
    implementation(project(":player"))
    implementation(project(":quickjs"))

    // 原版依赖库(jar/aar),保证 thunder(磁力/迅雷)、xwalk(webview嗅探)能力不丢
    api(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))

    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.fourthline.cling:cling-core:2.1.2")
    implementation("org.fourthline.cling:cling-support:2.1.2")
    compileOnly("javax.enterprise:cdi-api:1.2")
    compileOnly("javax.inject:javax.inject:1")
    compileOnly("javax.annotation:javax.annotation-api:1.3.2")
    compileOnly("javax.servlet:javax.servlet-api:3.1.0")
    implementation("com.google.zxing:core:3.3.0")
    implementation("androidx.appcompat:appcompat:1.3.0")
    implementation("androidx.media:media:1.2.1")
    implementation("androidx.constraintlayout:constraintlayout:2.0.4")
    implementation("androidx.lifecycle:lifecycle-extensions:2.2.0")
    implementation("androidx.recyclerview:recyclerview:1.2.1")

    implementation("com.squareup.okhttp3:okhttp:3.12.11")
    kapt("androidx.room:room-compiler:2.3.0")
    implementation("androidx.room:room-runtime:2.3.0")
    implementation("com.squareup.okio:okio:2.8.0")
    implementation("com.github.CymChad:BaseRecyclerViewAdapterHelper:2.9.45-androidx")
    implementation("com.kingja.loadsir:loadsir:1.3.8")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.squareup.picasso:picasso:2.71828")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    kapt("com.github.bumptech.glide:compiler:4.16.0")
    implementation("me.jessyan:autosize:1.2.1")
    implementation("com.thoughtworks.xstream:xstream:1.4.15") {
        // Android 框架自带 XmlPullParser;排除 xmlpull/xpp3 避免 R8 "library class implements program class" 冲突
        exclude(group = "xmlpull", module = "xmlpull")
        exclude(group = "xpp3", module = "xpp3_min")
    }
    implementation("org.greenrobot:eventbus:3.2.0")
    implementation("com.orhanobut:hawk:2.0.1")
    implementation("com.github.ctiao:DanmakuFlameMaster:0.9.25")

    implementation("com.lzy.net:okgo:3.0.4")
    implementation("com.owen:tv-recyclerview:3.0.0")
    implementation("com.github.getActivity:XXPermissions:13.6")
    implementation("org.jsoup:jsoup:1.14.1")
    implementation("com.github.hedzr:android-file-chooser:v1.2.0-final")
    implementation("commons-io:commons-io:2.11.0")
    implementation("com.googlecode.juniversalchardet:juniversalchardet:1.0.3")
    // http3 / cronet
    implementation("com.google.net.cronet:cronet-okhttp:0.1.0")
    implementation("org.chromium.net:cronet-embedded:101.4951.41")
    implementation("org.brotli:dec:0.1.2")
}
