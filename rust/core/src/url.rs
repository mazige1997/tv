//! URL 工具,移植自 `HtmlParser.joinUrl`(内部使用 java.net.URL 解析)。

/// 相对地址解析,等价 `new URL(new URL(parent), child).toExternalForm()`。
/// parent 为空或解析失败时,与 Java 行为一致地返回 child(Java 失败时 q 保持 parent)。
pub fn join_url(parent: &str, child: &str) -> String {
    if parent.trim().is_empty() {
        return child.to_string();
    }
    match url::Url::parse(parent.trim()) {
        Ok(base) => match base.join(child.trim()) {
            Ok(resolved) => resolved.to_string(),
            // Java:join 失败(MalformedURLException)→ 返回 parent
            Err(_) => parent.trim().to_string(),
        },
        Err(_) => parent.trim().to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn join_basic() {
        assert_eq!(join_url("http://a.com/x/y.m3u8", "1.ts"), "http://a.com/x/1.ts");
        assert_eq!(join_url("http://a.com/x/y.m3u8", "/z/1.ts"), "http://a.com/z/1.ts");
        assert_eq!(join_url("http://a.com/x/y.m3u8", "http://b.com/1.ts"), "http://b.com/1.ts");
        assert_eq!(join_url("http://a.com/x/", "../1.ts"), "http://a.com/1.ts");
    }

    #[test]
    fn join_empty_parent() {
        assert_eq!(join_url("", "http://a.com/1.ts"), "http://a.com/1.ts");
    }

    // ---------------- 健壮性:畸形 parent / child 零 panic ----------------

    /// 空 parent / 纯空白 parent → 返回 child(与 Java `new URL(parent)` 失败语义一致)。
    #[test]
    fn robust_join_empty_and_whitespace_parent() {
        assert_eq!(join_url("", ""), "");
        assert_eq!(join_url("", "1.ts"), "1.ts");
        assert_eq!(join_url("   ", "http://a.com/1.ts"), "http://a.com/1.ts");
        assert_eq!(join_url("\t\r\n", "x"), "x");
        // parent 非法 URL(非 scheme 形态)→ 原样返回
        assert_eq!(join_url("//", "1.ts"), "//");
        assert_eq!(join_url("/", "1.ts"), "/");
        assert_eq!(join_url("?a=1&b=2", "1.ts"), "?a=1&b=2");
        assert_eq!(join_url("#frag", "1.ts"), "#frag");
        assert_eq!(join_url("http://", "1.ts"), "http://", "空 host 解析失败 → 原样返回");
        assert_eq!(join_url("://bad", "1.ts"), "://bad");
        assert_eq!(join_url("中文协议://x", "1.ts"), "中文协议://x");
    }

    /// 空 child / 全 query / fragment 混合 child。
    #[test]
    fn robust_join_edge_children() {
        // 空 child:WHATWG 语义保留 base(与 Java URL(URL(parent), "") ≈ parent 一致量级)
        assert_eq!(join_url("http://a.com/x/y.m3u8", ""), "http://a.com/x/y.m3u8");
        // 纯 query child:替换 base 的 query
        assert_eq!(join_url("http://a.com/x/y.m3u8?tok=1", "?z=9"), "http://a.com/x/y.m3u8?z=9");
        assert_eq!(join_url("http://a.com/x/y.m3u8#old", "?z=9"), "http://a.com/x/y.m3u8?z=9");
        // fragment-only child:保留 base 全部 + 换 fragment
        assert_eq!(join_url("http://a.com/x/y.m3u8", "#f"), "http://a.com/x/y.m3u8#f");
        assert_eq!(join_url("http://a.com/x/y.m3u8?tok=1#old", "#f"), "http://a.com/x/y.m3u8?tok=1#f");
        // scheme-relative child
        assert_eq!(join_url("http://a.com/x/y.m3u8", "//b.com/z.ts"), "http://b.com/z.ts");
        // 绝对地址 child 原样返回
        assert_eq!(join_url("http://a.com/x/y.m3u8", "https://b.com/z.ts"), "https://b.com/z.ts");
        // parent 自带 fragment + 相对 child → fragment 丢弃,替换最后一段路径(Java 同)
        assert_eq!(join_url("http://a.com/x/y.m3u8#old", "1.ts"), "http://a.com/x/1.ts");
        // 畸形 child:非法字符等不 panic(WHATWG 解析宽松)
        let _ = join_url("http://a.com/x/", "htt p://中 文");
        let _ = join_url("http://a.com/x/", "\u{FFFD}\u{FFFD}");
        let _ = join_url("\u{FFFD}", "\u{FFFD}");
    }
}
