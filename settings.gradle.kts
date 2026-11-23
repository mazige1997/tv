pluginManagement {
    repositories {
        // 国内镜像优先(本地构建提速),官方仓库在后兜底(CI 可用)
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        gradlePluginPortal()
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
        maven {
            // cling/seamless 2.1.2 仅此仓库发布(与原项目一致)
            url = uri("http://4thline.org/m2")
            isAllowInsecureProtocol = true
        }
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://j4uq.github.io/crosswalk/releases/crosswalk/android/maven2") }
        maven { url = uri("https://chaquo.com/maven") }
        maven { url = uri("https://maven.aliyun.com/repository/releases") }
    }
}

rootProject.name = "TVBox-Kotlin"

// 新版 Kotlin 应用模块
include(":app")
project(":app").projectDir = file("app")

// 复用成熟稳定的 Java 库模块(播放器框架 / quickjs 引擎封装),保证零回归
// 模块已随仓库一起提交,不再依赖上一级目录,保证 GitHub Actions 可独立构建
include(":player")
project(":player").projectDir = file("player")

include(":quickjs")
project(":quickjs").projectDir = file("quickjs")
