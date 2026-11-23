//! 配置内容解码,移植自 `ApiConfig.FindResult` 与 `ApiConfig.getImgJar`。
//!
//! 兼容社区常见的三种封装格式:
//! 1. 明文 JSON / Base64 图片内嵌 jar(`8位随机字符**` 前缀)
//! 2. `2423` 开头的 hex 封装(内嵌密钥/IV,`$#...#$` 定界)
//! 3. AES/ECB 加密的 hex 内容(密钥来自配置地址上的 `;pk;` 片段)

use crate::crypto::{aes_cbc_decrypt, aes_ecb_decrypt, base64_decode, hex_decode, is_json, right_padding};

/// 判断出现 `8位字母数字**` 标记的位置(等价正则 `[A-Za-z0-9]{8}\*\*`)。
fn find_marker(content: &str) -> Option<usize> {
    let bytes = content.as_bytes();
    let n = bytes.len();
    let mut i = 0usize;
    while i + 10 <= n {
        if bytes[i + 8] == b'*' && bytes[i + 9] == b'*' {
            let ok = (0..8).all(|k| bytes[i + k].is_ascii_alphanumeric());
            if ok {
                return Some(i);
            }
        }
        i += 1;
    }
    None
}

/// 等价 `ApiConfig.FindResult`。失败时语义与 Java 一致:
/// - 解密函数返回 None → 整体返回 None(Java 返回 null)
/// - 中途异常但 json 未被覆盖 → 返回原始 body
pub fn find_result(body: &str, config_key: Option<&str>) -> Option<String> {
    let original = body;
    let mut content = body.to_string();

    // if (AES.isJson(content)) return content;
    if is_json(&content) {
        return Some(content);
    }

    // 剥离 Base64 图片包装:8位字符** 之后的内容整体 Base64 解码
    if let Some(idx) = find_marker(&content) {
        // Java: content.substring(content.indexOf(matcher.group()) + 10)
        let tail = match content.get(idx + 10..) {
            Some(t) => t,
            None => return Some(original.to_string()),
        };
        let decoded = match base64_decode(tail) {
            Some(bytes) => String::from_utf8_lossy(&bytes).into_owned(),
            // Base64.decode 抛异常 → catch 返回原始 json
            None => return Some(original.to_string()),
        };
        content = decoded;
    }

    // content.trim()
    let content = content.trim().to_string();

    if content.starts_with("2423") {
        // 2423 封装:去空白 → 取 data → hex 解码出"密钥载体"字符串 → 提取 key/iv
        let stripped: String = content.chars().filter(|c| !c.is_whitespace()).collect();
        let start = match stripped.find("2324") {
            Some(i) => i + 4,
            None => return Some(original.to_string()),
        };
        let end = stripped.len().checked_sub(26)?;
        if start >= end {
            return Some(original.to_string());
        }
        // 字节安全切片:end / 13 字节尾巴可能落在多字节 UTF-8 字符中间,
        // 直接 [a..b] 会 panic;Java 侧等价位置抛异常被 catch 后返回原文,这里同语义
        let data = match stripped.get(start..end) {
            Some(d) => d,
            None => return Some(original.to_string()),
        };
        let carrier = match hex_decode(&stripped) {
            Some(bytes) => String::from_utf8_lossy(&bytes).to_lowercase(),
            None => return Some(original.to_string()),
        };
        let dollar = match carrier.find("$#") {
            Some(i) => i,
            None => return Some(original.to_string()),
        };
        let hash = match carrier.find("#$") {
            Some(i) => i,
            None => return Some(original.to_string()),
        };
        if dollar + 2 > hash {
            return Some(original.to_string());
        }
        let key_part = &carrier[dollar + 2..hash];
        let iv_part = match carrier.get(carrier.len().saturating_sub(13)..) {
            Some(v) => v,
            None => return Some(original.to_string()),
        };
        let key = right_padding(key_part, "0", 16);
        let iv = right_padding(iv_part, "0", 16);
        let data_bytes = hex_decode(data)?;
        let plain = aes_cbc_decrypt(&data_bytes, key.as_bytes(), iv.as_bytes())?;
        return Some(String::from_utf8_lossy(&plain).into_owned());
    }

    if let Some(pk) = config_key {
        if !pk.is_empty() && !is_json(&content) {
            // AES.ECB(content, configKey):内容为 hex,密钥补 '0' 到 16 字节
            let key = right_padding(pk, "0", 16);
            let data = hex_decode(&content)?;
            let plain = aes_ecb_decrypt(&data, key.as_bytes())?;
            return Some(String::from_utf8_lossy(&plain).into_owned());
        }
    }

    Some(content)
}

/// 等价 `ApiConfig.getImgJar`:提取 `8位字符**` 之后的 Base64 内容为字节(jar 包)。
pub fn get_img_jar(body: &str) -> Vec<u8> {
    if let Some(idx) = find_marker(body) {
        if let Some(tail) = body.get(idx + 10..) {
            if let Some(bytes) = base64_decode(tail) {
                return bytes;
            }
        }
    }
    Vec::new()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::{aes_ecb_encrypt, base64_encode};

    #[test]
    fn plain_json_passthrough() {
        let body = "{\"sites\":[]}";
        assert_eq!(find_result(body, None).unwrap(), body);
    }

    #[test]
    fn marker_base64_wrapper() {
        let payload = base64_encode(b"{\"sites\":[],\"name\":\"kt\"}");
        let body = format!("ab12cd34**{payload}");
        let out = find_result(&body, None).unwrap();
        assert!(out.contains("\"sites\""));
        // getImgJar 提取 jar 字节
        let jar = get_img_jar(&body);
        assert_eq!(String::from_utf8_lossy(&jar), "{\"sites\":[],\"name\":\"kt\"}");
    }

    #[test]
    fn config_key_ecb_decrypt() {
        // 构造:JSON 明文 → AES-128-ECB(ASCII 密钥右补'0'到16字节)加密 → hex 编码
        let json = "{\"sites\":[],\"wallpaper\":\"http://a/b\"}";
        let key = right_padding("mypk", "0", 16); // "mypk000000000000"
        assert_eq!(key.len(), 16);
        let cipher = aes_ecb_encrypt(json.as_bytes(), key.as_bytes()).unwrap();
        let mut body_hex = String::new();
        for b in &cipher {
            body_hex.push_str(&format!("{:02x}", b));
        }
        let out = find_result(&body_hex, Some("mypk")).unwrap();
        assert_eq!(out, json);
    }

    #[test]
    fn marker_bad_base64_returns_original() {
        let body = "ab12cd34**!!!not-base64!!!";
        assert_eq!(find_result(body, None).unwrap(), body);
    }

    // ---------------- 健壮性:恶意 / 畸形输入零 panic ----------------

    /// decodeConfig:全零输入、空串、纯空白、空 key(空 key 视为无 key,与 JNI 过滤一致)。
    #[test]
    fn robust_find_result_zero_and_empty() {
        assert_eq!(find_result("", None).unwrap(), "");
        assert_eq!(find_result("   \r\n ", None).unwrap(), "");
        let zeros = "0".repeat(64);
        assert_eq!(find_result(&zeros, None).unwrap(), zeros);
        // 空 key:JNI 层 filter 掉空串 → 走无 key 路径 → 原样返回
        assert_eq!(find_result(&zeros, Some("")).unwrap(), zeros);
        // 全零 + 有 key:内容是合法 hex → 进入 AES 解密,结果由 padding 校验决定
        // (零 key 全零密文)——只断言确定性,不得 panic
        let r1 = find_result(&zeros, Some("k"));
        let r2 = find_result(&zeros, Some("k"));
        assert_eq!(r1, r2, "确定性");
    }

    /// decodeConfig:2423 封装的全部畸形形态 → None(→ JNI null → Kotlin 回退原文)
    /// 或 Some(original),均不 panic。
    #[test]
    fn robust_find_result_2423_malformed() {
        // 缺 "2324" 定界符 → 原文(分支先于长度检查)
        assert_eq!(find_result("2423", None).unwrap(), "2423");
        // 长度不足 26:checked_sub 失败 → None
        assert_eq!(find_result("24232324", None), None);
        // 缺 "2324" 定界符 → 原文
        assert_eq!(find_result(&format!("2423{}", "a".repeat(40)), None).unwrap(),
                   format!("2423{}", "a".repeat(40)));
        // 非十六进制内容(含中文)→ 原文
        let cjk_body = format!("24232324{}", "密码".repeat(15));
        assert_eq!(find_result(&cjk_body, None).unwrap(), cjk_body);
        // 多字节字符使字节切片落在 char 中间 → get() 返回 None → 原文(修复前 panic)
        let mid_char = format!("24232324{}{}", "a".repeat(4), "密".repeat(30));
        assert_eq!(find_result(&mid_char, None).unwrap(), mid_char);
        // "$#"/"#$" 结构残缺(key 段为空 / 数据段非 16 倍数)→ None → Kotlin 回退
        let broken = format!("24232324{}", "0".repeat(30));
        assert_eq!(find_result(&broken, None), None);
        // 非 16 倍数 data 走到 CBC → None
        assert_eq!(find_result(&format!("24232324{}{}", "41414141", "0".repeat(28)), None), None);
    }

    /// decodeConfig:错误 base64 字符的多种形态 + 超长数据(1MB)零 panic。
    #[test]
    fn robust_find_result_base64_and_1mb() {
        // 合法标记后各种非法 base64(Rust 解码器宽容空白与 padding,但非法字符必失败)
        for tail in ["!!!", "\u{FFFD}\u{FFFD}", "夏\u{0}x", "===!!!==="] {
            let body = format!("ab12cd34**{tail}");
            assert_eq!(find_result(&body, None).unwrap(), body, "tail={:?}", tail);
        }
        // 1MB 明文 JSON 直通(高频真实路径)
        let mut big = String::with_capacity(1024 * 1024 + 16);
        big.push_str("{\"sites\":[");
        for i in 0..50000 {
            if i > 0 {
                big.push(',');
            }
            big.push_str(&format!("{{\"k\":{},\"v\":\"value{}\"}}", i, i % 97));
        }
        big.push_str("]}");
        assert!(big.len() > 1024 * 1024, "构造 1MB 量级: {} 字节", big.len());
        assert_eq!(find_result(&big, None).unwrap(), big);
        // 1MB 非 hex 内容 + 有 key:hex_decode 快速失败 → None(→ Kotlin 回退)
        let big_junk = "z".repeat(1024 * 1024 + 1); // 奇数长度,必失败
        assert_eq!(find_result(&big_junk, Some("pk")), None);
    }

    /// extractJar:空 body / 无标记 / 残缺标记 / 非法 base64 / 1MB 数据。
    #[test]
    fn robust_get_img_jar_malformed() {
        assert!(get_img_jar("").is_empty());
        assert!(get_img_jar("no marker here").is_empty());
        // 标记必须是 8 位字母数字 + "**":7 位不算
        assert!(get_img_jar("ab12cd3**base64data").is_empty());
        assert!(get_img_jar("abc12345!!**data").is_empty(), "标记内含非字母数字");
        // 标记后无内容 / 非法 base64
        assert!(get_img_jar("ab12cd34**").is_empty());
        assert!(get_img_jar("ab12cd34**!!!").is_empty());
        // 1MB jar 数据往返
        let jar: Vec<u8> = (0..(1024 * 1024)).map(|i| (i % 253) as u8).collect();
        let body = format!("ab12cd34**{}", base64_encode(&jar));
        assert_eq!(get_img_jar(&body), jar);
        // find_result 对同一 1MB 包装:解码后非 JSON → 返回解码文本(不 panic)
        // jar 字节含非法 UTF-8 → from_utf8_lossy 用 U+FFFD 替换,长度不必相等
        let out = find_result(&body, None).unwrap();
        assert!(!out.is_empty());
    }
}
