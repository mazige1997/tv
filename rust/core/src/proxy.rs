//! 本地代理 M3U8 重写,移植自 `ProxyUtil.processM3u8Content`(播放期 CPU 热点下沉)。
//!
//! 语义基准为 Kotlin 版 `com.github.tvbox.kt.util.ProxyUtil`:
//! - [`rewrite_proxy_content`]  入口:BOM 剥离 / 换行归一 / 逐行分派 / 末尾 `dropLast(1)`
//! - [`rewrite_uri_attributes`] `#EXT-X-KEY` / `#EXT-X-STREAM-INF` 等行的 `URI="..."` 重写
//! - [`proxy_type_for_uri_attr`] 决定 `key` / `m3u8` / `media` 三种代理类型
//! - [`join_url`]               相对地址解析 + 本地代理 URL 拼接 + form-urlencode
//!
//! 与 Kotlin 版保持一致的设计:
//! - 纯函数、无全局可变状态、无 Android / OkHttp 依赖;
//! - `headerQuery` 与代理前缀由 Kotlin 侧预先拼好传入,Rust 不回调宿主;
//! - 输入非法(URI 语法不合法、base 无 scheme 等)时返回 `"null"`,
//!   与 Kotlin `joinUrl()` catch 后返回 null、再由 `StringBuilder.append` 拼出 `"null"` 一致。

use regex::{Captures, Regex};
use std::sync::OnceLock;

use crate::m3u8::resolve_uri;

/// `URI="([^"]+)"`,与 `ProxyUtil.URI_ATTR` 一致。
const URI_ATTR_SRC: &str = r#"URI="([^"]+)""#;

fn uri_attr_regex() -> &'static Regex {
    static R: OnceLock<Regex> = OnceLock::new();
    R.get_or_init(|| Regex::new(URI_ATTR_SRC).expect("static regex"))
}

/// 重写整份 m3u8 文本。
///
/// - `content`:  代理请求到的原始 m3u8 文本
/// - `base_url`: m3u8 最终 URL(OkHttp response.request().url())
/// - `proxy_prefix`: Kotlin 侧拼好的 `"<address>proxy?go=live&type="`
/// - `header_query`: Kotlin 侧拼好的 `"&ua=...&referer=..."`(可为空串)
pub fn rewrite_proxy_content(
    content: &str,
    base_url: &str,
    proxy_prefix: &str,
    header_query: &str,
) -> String {
    let content = content.strip_prefix('\u{FEFF}').unwrap_or(content);
    let normalized = content.replace("\r\n", "\n").replace('\r', "\n");

    // Kotlin: split("\n".toRegex()).dropLast(1) 会保留末尾空串再丢弃它,
    // 因此这里用 split('\n')(保留 trailing empty)后 pop 最后一个元素。
    let mut lines: Vec<&str> = normalized.split('\n').collect();
    if !lines.is_empty() {
        lines.pop();
    }

    let mut out = String::with_capacity(normalized.len() + lines.len() * 16);
    let mut next_is_variant = false;

    for line in lines {
        let item = line.trim();
        if item.is_empty() {
            out.push_str(line);
            out.push('\n');
        } else if item.starts_with('#') {
            out.push_str(&rewrite_uri_attributes(
                base_url,
                line,
                proxy_prefix,
                header_query,
            ));
            out.push('\n');
            next_is_variant = item.starts_with("#EXT-X-STREAM-INF");
        } else {
            let media_type = if next_is_variant || is_m3u8_url(item) {
                "m3u8"
            } else {
                "media"
            };
            out.push_str(&join_url(
                base_url,
                line,
                media_type,
                proxy_prefix,
                header_query,
            ));
            out.push('\n');
            next_is_variant = false;
        }
    }

    // Kotlin 原文末尾的 `.replace("\\n\\n", "\n")`(替换字面量 \n\n)。
    out.replace("\\n\\n", "\n")
}

/// 重写一行里的所有 `URI="..."` 属性。
fn rewrite_uri_attributes(
    base: &str,
    line: &str,
    proxy_prefix: &str,
    header_query: &str,
) -> String {
    uri_attr_regex()
        .replace_all(line, |caps: &Captures| {
            let uri = &caps[1];
            let media_type = proxy_type_for_uri_attr(line, uri);
            format!(
                "URI=\"{}\"",
                join_url(base, uri, media_type, proxy_prefix, header_query)
            )
        })
        .into_owned()
}

/// 与 Kotlin `proxyTypeForUriAttr` 一致。
fn proxy_type_for_uri_attr(line: &str, uri: &str) -> &'static str {
    let upper = line.to_ascii_uppercase();
    if upper.starts_with("#EXT-X-KEY") || upper.starts_with("#EXT-X-SESSION-KEY") {
        return "key";
    }
    if is_m3u8_url(uri) || upper.starts_with("#EXT-X-I-FRAME-STREAM-INF") {
        return "m3u8";
    }
    "media"
}

/// 与 Kotlin `isM3u8Url` 一致:去 query 后小写判断 `.m3u8` / `.m3u` 后缀。
fn is_m3u8_url(url: &str) -> bool {
    let lower = url.to_lowercase();
    let path = lower.split('?').next().unwrap_or("");
    path.ends_with(".m3u8") || path.ends_with(".m3u")
}

/// 与 Kotlin `joinUrl` 一致:解析相对地址并拼本地代理 URL。
fn join_url(
    base0: &str,
    url0: &str,
    media_type: &str,
    proxy_prefix: &str,
    header_query: &str,
) -> String {
    let url = url0.trim();
    if url.starts_with("data:") || url.starts_with("blob:") {
        return url.to_string();
    }

    let base = base0.trim();
    let scheme = match extract_scheme(base) {
        Some(s) if java_uri_legal(base) => s,
        _ => return "null".to_string(),
    };
    if !java_uri_legal(url) {
        return "null".to_string();
    }

    let proxy_base = format!("{}{}{}&url=", proxy_prefix, media_type, header_query);
    let resolved = if url.starts_with("http://") || url.starts_with("https://") {
        url.to_string()
    } else if url.starts_with("://") {
        format!("{}{}", scheme, url)
    } else if url.starts_with("//") {
        format!("{}:{}", scheme, url)
    } else {
        resolve_uri(base, url)
    };

    format!("{}{}", proxy_base, form_urlencode(&resolved))
}

/// 提取 Java `URI.getScheme()` 语义的 scheme(必须在 `/ ? #` 之前且格式合法)。
fn extract_scheme(base: &str) -> Option<&str> {
    let colon = base.find(':')?;
    let stop = base
        .find(|c| c == '/' || c == '?' || c == '#')
        .unwrap_or(base.len());
    if colon >= stop {
        return None;
    }
    let scheme = &base[..colon];
    let mut chars = scheme.chars();
    let first = chars.next()?;
    if !first.is_ascii_alphabetic() {
        return None;
    }
    chars
        .all(|c| c.is_ascii_alphanumeric() || matches!(c, '+' | '-' | '.'))
        .then_some(scheme)
}

/// Java `new URI(...)` 对非法字符的近似校验;失败时 Kotlin 走 catch → `null`。
fn java_uri_legal(s: &str) -> bool {
    if !s.is_ascii() {
        return false;
    }
    let b = s.as_bytes();
    let mut i = 0usize;
    while i < b.len() {
        let c = b[i];
        if c <= 0x20
            || c == 0x7f
            || matches!(
                c,
                b'"' | b'<' | b'>' | b'\\' | b'^' | b'`' | b'{' | b'}' | b'|'
            )
        {
            return false;
        }
        if c == b'%' {
            if i + 2 >= b.len() || !is_hex(b[i + 1]) || !is_hex(b[i + 2]) {
                return false;
            }
            i += 3;
        } else {
            i += 1;
        }
    }
    true
}

fn is_hex(c: u8) -> bool {
    c.is_ascii_hexdigit()
}

/// 等价 `URLEncoder.encode(s, "UTF-8")`:
/// 保留 `A-Z a-z 0-9 . - * _`,空格转 `+`,其余字节转大写 `%XX`。
fn form_urlencode(input: &str) -> String {
    const HEX: &[u8; 16] = b"0123456789ABCDEF";
    let mut out = String::with_capacity(input.len());
    for &b in input.as_bytes() {
        match b {
            b'a'..=b'z' | b'A'..=b'Z' | b'0'..=b'9' | b'.' | b'-' | b'*' | b'_' => {
                out.push(b as char)
            }
            b' ' => out.push('+'),
            _ => {
                out.push('%');
                out.push(HEX[(b >> 4) as usize] as char);
                out.push(HEX[(b & 0x0f) as usize] as char);
            }
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    const BASE: &str = "http://h/live/index.m3u8";
    const PREFIX: &str = "http://127.0.0.1:8080/proxy?go=live&type=";
    const HEADERS: &str = "&ua=Test%20UA";

    #[test]
    fn rewrites_playlist_lines_and_uri_attributes() {
        let input = concat!(
            "\u{FEFF}#EXTM3U\r\n",
            "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\r\n",
            "#EXT-X-STREAM-INF:BANDWIDTH=1,URI=\"sub.m3u8\"\r\n",
            "seg1.ts\r\n",
            "#EXTINF:10,\r\n",
            "seg2.ts\r\n",
            "#EXT-X-ENDLIST\r\n",
        );
        let out = rewrite_proxy_content(input, BASE, PREFIX, HEADERS);

        let expected = concat!(
            "#EXTM3U\n",
            "#EXT-X-KEY:METHOD=AES-128,URI=\"http://127.0.0.1:8080/proxy?go=live&type=key&ua=Test%20UA&url=http%3A%2F%2Fh%2Flive%2Fkey.bin\"\n",
            "#EXT-X-STREAM-INF:BANDWIDTH=1,URI=\"http://127.0.0.1:8080/proxy?go=live&type=m3u8&ua=Test%20UA&url=http%3A%2F%2Fh%2Flive%2Fsub.m3u8\"\n",
            "http://127.0.0.1:8080/proxy?go=live&type=m3u8&ua=Test%20UA&url=http%3A%2F%2Fh%2Flive%2Fseg1.ts\n",
            "#EXTINF:10,\n",
            "http://127.0.0.1:8080/proxy?go=live&type=media&ua=Test%20UA&url=http%3A%2F%2Fh%2Flive%2Fseg2.ts\n",
            "#EXT-X-ENDLIST\n",
        );
        assert_eq!(out, expected);
    }

    #[test]
    fn variant_without_uri_attr_still_marks_next_line_as_m3u8() {
        let out = rewrite_proxy_content(
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nnext.m3u8\n",
            BASE,
            PREFIX,
            "",
        );
        assert!(out.contains("type=m3u8&url=http%3A%2F%2Fh%2Flive%2Fnext.m3u8"));
    }

    #[test]
    fn special_url_forms_match_kotlin_branches() {
        let out = rewrite_proxy_content(
            "http://cdn/a.ts\n//cdn2/b.ts\n://cdn3/c.ts\ndata:application/x,AAA\nblob:https://h/uuid\n",
            BASE,
            PREFIX,
            "",
        );
        assert!(out.contains("url=http%3A%2F%2Fcdn%2Fa.ts"));
        assert!(out.contains("url=http%3A%2F%2Fcdn2%2Fb.ts"));
        assert!(out.contains("url=http%3A%2F%2Fcdn3%2Fc.ts"));
        assert!(out.contains("data:application/x,AAA\n"));
        assert!(out.contains("blob:https://h/uuid\n"));
    }

    #[test]
    fn absolute_and_special_urls_keep_media_type() {
        let url = join_url(BASE, "http://cdn/a.ts", "media", PREFIX, "");
        assert_eq!(
            url,
            "http://127.0.0.1:8080/proxy?go=live&type=media&url=http%3A%2F%2Fcdn%2Fa.ts"
        );
    }

    #[test]
    fn form_encoding_matches_java_urlencoder() {
        assert_eq!(form_urlencode("a b*c=1&中"), "a+b*c%3D1%26%E4%B8%AD");
    }

    #[test]
    fn illegal_uri_returns_literal_null_like_kotlin_append() {
        assert_eq!(join_url(BASE, "seg 1.ts", "media", PREFIX, ""), "null");
        assert_eq!(join_url("not-uri", "seg.ts", "media", PREFIX, ""), "null");
        assert_eq!(join_url(BASE, "a%zz.ts", "media", PREFIX, ""), "null");
    }

    #[test]
    fn keeps_kotlin_drop_last_line_semantics() {
        // Kotlin: split(regex).dropLast(1) 会丢掉末行(即便输入不以换行结尾)。
        assert_eq!(
            rewrite_proxy_content("#EXTM3U\n#EXT-X-ENDLIST", BASE, PREFIX, ""),
            "#EXTM3U\n"
        );
    }

    #[test]
    fn empty_input_returns_empty_string() {
        assert_eq!(rewrite_proxy_content("", BASE, PREFIX, ""), "");
    }
}
