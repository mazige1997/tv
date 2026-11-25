//! tvbox-core:TVBox Kotlin 电视版的 Rust 原生核心。
//!
//! 只承载与 Android 无关的纯计算单元,每个单元都有对应的单元测试,
//! 并与旧版 Java 实现逐一对齐语义:
//! - [`crypto`]   MD5 / Base64 / Hex / AES-ECB / AES-CBC(配置解码使用)
//! - [`config`]   配置内容解码(`ApiConfig.FindResult` / `getImgJar`)
//! - [`live`]     直播源解析(txt / m3u / json)
//! - [`m3u8`]     M3U8 去广告引擎(播放期 CPU 热点,移植自 `M3u8.kt`)
//! - [`proxy`]    本地代理 M3U8 URI 重写(播放期热点,移植自 `ProxyUtil.kt`)
//! - [`danmaku`]  弹幕 XML 解析 + 搜索纯函数簇(播放期热点,移植自 `Danmu.kt`/`DanmakuApi.kt`)
//! - [`url`]      URL 相对地址解析

pub mod config;
pub mod crypto;
pub mod danmaku;
pub mod live;
pub mod m3u8;
pub mod proxy;
pub mod url;
