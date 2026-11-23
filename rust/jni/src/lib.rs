//! JNI 桥接:Kotlin 侧类 `com.github.tvbox.kt.core.NativeCore`。
//!
//! 每个导出函数保持"原子化"——单一职责、无状态、异常返回 null/空串,
//! 由 Kotlin 侧决定回退策略(Rust 不可用时走纯 Kotlin 实现)。
//!
//! 工程级约束:**所有 JNI 入口统一 catch_unwind**。Rust panic 若跨越 FFI 边界
//! 即为未定义行为(cdylib panic=abort 下直接崩溃进程),因此每个入口都在
//! catch_unwind 内执行纯计算,panic 一律降级为 null / 空数组,由 Kotlin 回退。

use jni::objects::{JClass, JString};
use jni::sys::{jbyteArray, jboolean, jlong, jstring};
use jni::JNIEnv;

use tvbox_core::config;
use tvbox_core::crypto;
use tvbox_core::danmaku;
use tvbox_core::live;
use tvbox_core::m3u8;
use tvbox_core::url;

fn to_rust(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s)
        .map(|v| v.to_string_lossy().into_owned())
        .unwrap_or_default()
}

fn to_java(env: &mut JNIEnv, s: String) -> jstring {
    match env.new_string(s) {
        Ok(j) => j.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn null() -> jstring {
    std::ptr::null_mut()
}

/// 统一的 panic 防线:捕获所有 panic,失败返回 None(调用方转换为 null/空)。
fn guarded<T, F: FnOnce() -> T>(f: F) -> Option<T> {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)).ok()
}

/// md5Hex(input: String): String
#[no_mangle]
pub extern "system" fn Java_com_github_tvbox_kt_core_NativeCore_md5Hex(
    mut env: JNIEnv,
    _class: JClass,
    input: JString,
) -> jstring {
    let input = to_rust(&mut env, &input);
    match guarded(|| crypto::md5_hex(&input)) {
        Some(out) => to_java(&mut env, out),
        None => null(),
    }
}

/// joinUrl(parent: String, child: String): String
#[no_mangle]
pub extern "system" fn Java_com_github_tvbox_kt_core_NativeCore_joinUrl(
    mut env: JNIEnv,
    _class: JClass,
    parent: JString,
    child: JString,
) -> jstring {
    let parent = to_rust(&mut env, &parent);
    let child = to_rust(&mut env, &child);
    match guarded(|| url::join_url(&parent, &child)) {
        Some(out) => to_java(&mut env, out),
        None => null(),
    }
}

/// parseLive(text: String): String —— 完整数组形态(含元数据)
#[no_mangle]
pub extern "system" fn Java_com_github_tvbox_kt_core_NativeCore_parseLive(
    mut env: JNIEnv,
    _class: JClass,
    text: JString,
) -> jstring {
    let text = to_rust(&mut env, &text);
    match guarded(|| live::parse_to_json(&text)) {
        Some(out) => to_java(&mut env, out),
        None => null(),
    }
}

/// parseLiveSimple(text: String): String —— 折叠形态 {group:{channel:[urls]}}
#[no_mangle]
pub extern "system" fn Java_com_github_tvbox_kt_core_NativeCore_parseLiveSimple(
    mut env: JNIEnv,
    _class: JClass,
    text: JString,
) -> jstring {
    let text = to_rust(&mut env, &text);
    match guarded(|| live::parse_simple(&text)) {
        Some(out) => to_java(&mut env, out),
        None => null(),
    }
}

/// decodeConfig(body: String, configKey: String?): String?
#[no_mangle]
pub extern "system" fn Java_com_github_tvbox_kt_core_NativeCore_decodeConfig(
    mut env: JNIEnv,
    _class: JClass,
    body: JString,
    config_key: JString,
) -> jstring {
    let body = to_rust(&mut env, &body);
    let key = env
        .get_string(&config_key)
        .map(|v| v.to_string_lossy().into_owned())
        .ok()
        .filter(|v| !v.is_empty());
    match guarded(|| config::find_result(&body, key.as_deref())) {
        Some(Some(result)) => to_java(&mut env, result),
        _ => null(),
    }
}

/// extractJar(body: String): byte[] —— 从图片包装中提取 jar 字节
#[no_mangle]
pub extern "system" fn Java_com_github_tvbox_kt_core_NativeCore_extractJar(
    mut env: JNIEnv,
    _class: JClass,
    body: JString,
) -> jbyteArray {
    let body = to_rust(&mut env, &body);
    let bytes = guarded(|| config::get_img_jar(&body)).unwrap_or_default();
    match env.byte_array_from_slice(&bytes) {
        Ok(arr) => arr.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// aesDecryptHex(dataHex: String, key: String, iv: String?, cbc: Boolean): String?
#[no_mangle]
pub extern "system" fn Java_com_github_tvbox_kt_core_NativeCore_aesDecryptHex(
    mut env: JNIEnv,
    _class: JClass,
    data_hex: JString,
    key: JString,
    iv: JString,
    cbc: jboolean,
) -> jstring {
    let data_hex = to_rust(&mut env, &data_hex);
    let key = to_rust(&mut env, &key);
    let iv = env
        .get_string(&iv)
        .map(|v| v.to_string_lossy().into_owned())
        .ok()
        .unwrap_or_default();
    let plain = guarded(|| {
        let data = crypto::hex_decode(&data_hex)?;
        let padded = crypto::right_padding(&key, "0", 16);
        if cbc != 0 {
            crypto::aes_cbc_decrypt(&data, padded.as_bytes(), iv.as_bytes())
        } else {
            crypto::aes_ecb_decrypt(&data, padded.as_bytes())
        }
        .map(|p| crypto::bytes_to_string(&p))
    });
    match plain {
        Some(Some(out)) => to_java(&mut env, out),
        _ => null(),
    }
}

/// purifyM3u8(tsUrlPre: String, content: String, adsJson: String): String?
///
/// M3U8 去广告引擎入口:
/// - `adsJson`:广告正则 JSON 数组(`["regex", ...]`,取自 VideoParseRuler.getHostsRegex
///   按 tsUrlPre 包含匹配;空串表示无规则);
/// - 返回 `{"content":"...","removedAds":N}` 或 null(不该代理时,Kotlin 侧回退纯实现);
/// - content 可能达数 MB:Rust 侧全程单次解析/单次序列化,无多余拷贝。
#[no_mangle]
pub extern "system" fn Java_com_github_tvbox_kt_core_NativeCore_purifyM3u8(
    mut env: JNIEnv,
    _class: JClass,
    ts_url_pre: JString,
    content: JString,
    ads_json: JString,
) -> jstring {
    let ts_url_pre = to_rust(&mut env, &ts_url_pre);
    let content = to_rust(&mut env, &content);
    let ads_json = to_rust(&mut env, &ads_json);
    match guarded(|| m3u8::purify_to_json(&ts_url_pre, &content, &ads_json)) {
        Some(Some(json)) => to_java(&mut env, json),
        _ => null(),
    }
}

// ==================== 弹幕(xml 解析 + 搜索纯函数簇) ====================

/// parseDanmuXml(xml: String, startPosition: Long): String?
/// 返回 `[{"p":"...","t":"..."}, ...]`;空输入返回 `[]`;异常返回 null(调用方回退)。
#[no_mangle]
pub extern "system" fn Java_com_github_tvbox_kt_core_NativeCore_parseDanmuXml(
    mut env: JNIEnv,
    _class: JClass,
    xml: JString,
    start_position: jlong,
) -> jstring {
    let xml = to_rust(&mut env, &xml);
    match guarded(|| danmaku::parse_danmu_xml(&xml, start_position)) {
        Some(out) => to_java(&mut env, out),
        None => null(),
    }
}

/// commentJsonToXml(body: String): String?
#[no_mangle]
pub extern "system" fn Java_com_github_tvbox_kt_core_NativeCore_commentJsonToXml(
    mut env: JNIEnv,
    _class: JClass,
    body: JString,
) -> jstring {
    let body = to_rust(&mut env, &body);
    match guarded(|| danmaku::comment_json_to_xml(&body)) {
        Some(out) => to_java(&mut env, out),
        None => null(),
    }
}

/// parseDanmuSearchList(body: String): String? —— JSON 数组 `[{name,url,builtIn}]`
#[no_mangle]
pub extern "system" fn Java_com_github_tvbox_kt_core_NativeCore_parseDanmuSearchList(
    mut env: JNIEnv,
    _class: JClass,
    body: JString,
) -> jstring {
    let body = to_rust(&mut env, &body);
    match guarded(|| danmaku::parse_search_list(&body)) {
        Some(out) => to_java(&mut env, out),
        None => null(),
    }
}

/// parseBuiltinDanmuSearchList(body: String, baseUrl: String): String?
#[no_mangle]
pub extern "system" fn Java_com_github_tvbox_kt_core_NativeCore_parseBuiltinDanmuSearchList(
    mut env: JNIEnv,
    _class: JClass,
    body: JString,
    base_url: JString,
) -> jstring {
    let body = to_rust(&mut env, &body);
    let base_url = to_rust(&mut env, &base_url);
    match guarded(|| danmaku::parse_builtin_search_list(&body, &base_url)) {
        Some(out) => to_java(&mut env, out),
        None => null(),
    }
}

/// parseDanmuUrl(body: String): String?
#[no_mangle]
pub extern "system" fn Java_com_github_tvbox_kt_core_NativeCore_parseDanmuUrl(
    mut env: JNIEnv,
    _class: JClass,
    body: JString,
) -> jstring {
    let body = to_rust(&mut env, &body);
    match guarded(|| danmaku::parse_url(&body)) {
        Some(out) => to_java(&mut env, out),
        None => null(),
    }
}
