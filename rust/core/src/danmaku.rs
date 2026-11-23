//! 弹幕处理纯计算内核,移植自 Kotlin 的 `Danmu.kt` / `DanmakuApi.kt` 纯函数簇。
//!
//! 分两部分:
//! - **XML 解析**([`parse_danmu_xml`]):播放期热路径,弹幕 XML 可达数 MB。
//!   手写零依赖标签扫描器(不引入 quick-xml),一次遍历完成;严格模式解析失败
//!   自动回退正则式 tag 模式,完整复刻 `Danmu.fromXml` 的三段式容错语义。
//! - **搜索/规范化**([`comment_json_to_xml`] 等):弹幕搜索期的 JSON→XML、
//!   参数规范化纯函数,基于 serde_json 一次解析。
//!
//! 设计约束:与 Kotlin 回退实现**逐字节对齐**(实体处理、时间过滤、颜色规范化),
//! 使 Rust 不可用时可无缝回退;所有公开入口对任意输入零 panic。

use serde_json::{json, Value};

// ============================================================================
// 第一部分:弹幕 XML 解析
// ============================================================================

/// 单条弹幕:param 为 XML `<d p="...">` 的属性串,text 为文本。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DanmuItem {
    pub param: String,
    pub text: String,
}

/// 等价 `Danmu.fromXml(xml, startPosition)`。
///
/// 三段式语义(与 Kotlin 一致):
/// 1. `startPosition > 0` 时先用 **tag 模式**扫描原始 XML,命中即返回(快速路径);
/// 2. 修复非法实体 `&` 后走 **严格 XML 解析**,命中即返回;
/// 3. 严格解析失败 → 用 tag 模式扫描原始 XML 兜底。
///
/// 返回 JSON 数组 `[{"p":"...","t":"..."}, ...]`;无弹幕返回 `[]`。
/// 注:Kotlin 的 `CancelChecker` 属平台取消语义,在 JNI 调用方按块检查,Rust 侧纯函数为一次性计算。
pub fn parse_danmu_xml(xml: &str, start_position: i64) -> String {
    let items = parse_items(xml, start_position);
    serialize_items(&items)
}

/// 内部核心:返回结构化弹幕列表,便于单元测试直接断言。
fn parse_items(xml: &str, start_position: i64) -> Vec<DanmuItem> {
    if xml.is_empty() {
        return Vec::new();
    }
    let position = start_position.max(0);

    // 阶段 1:startPosition>0 时 tag 模式快速路径
    if position > 0 {
        let found: Vec<DanmuItem> = scan_by_tag(xml)
            .into_iter()
            .filter(|it| get_time(&it.param) >= position)
            .collect();
        if !found.is_empty() {
            return found;
        }
    }

    // 阶段 2:修复非法实体 + 严格解析
    let fixed = escape_illegal_entities(xml);
    let found: Vec<DanmuItem> = scan_strict(fixed.as_str())
        .into_iter()
        .filter(|it| position <= 0 || get_time(&it.param) >= position)
        .collect();
    if !found.is_empty() {
        return found;
    }

    // 阶段 3:tag 模式兜底(原始 XML)
    scan_by_tag(xml)
        .into_iter()
        .filter(|it| position <= 0 || get_time(&it.param) >= position)
        .collect()
}

fn serialize_items(items: &[DanmuItem]) -> String {
    let mut sb = String::with_capacity(items.len() * 64 + 2);
    sb.push('[');
    for (i, it) in items.iter().enumerate() {
        if i > 0 {
            sb.push(',');
        }
        // 手写 JSON 转义,避免中间 Value 分配
        sb.push_str("{\"p\":");
        push_json_string(&mut sb, &it.param);
        sb.push_str(",\"t\":");
        push_json_string(&mut sb, &it.text);
        sb.push('}');
    }
    sb.push(']');
    sb
}

fn push_json_string(out: &mut String, s: &str) {
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => {
                use std::fmt::Write;
                let _ = write!(out, "\\u{:04x}", c as u32);
            }
            c => out.push(c),
        }
    }
    out.push('"');
}

/// 等价 `Danmu.getTime`:取首个逗号前的秒数 ×1000 转毫秒,失败为 i64::MIN。
fn get_time(param: &str) -> i64 {
    let end = match param.find(',') {
        Some(e) if e > 0 => e,
        _ => return i64::MIN,
    };
    let head = &param[..end];
    // Java Float.parseFloat:支持 "NaN"/"Infinity" 等;Rust f64::parse 亦支持
    // Java (float)→(long) 对 NaN 取 0,对越界饱和 —— f64 as i64 语义一致(饱和、NaN→0)
    head.parse::<f64>()
        .map(|v| (v * 1000.0) as i64)
        .unwrap_or(i64::MIN)
}

/// 等价 `Danmu.escapeIllegalEntities`:把非法 `&` 转义为 `&amp;`。
/// 仅当检测到首个非法实体时才分配新串,合法 XML 零拷贝返回(调用方持有的仍是 &str)。
fn escape_illegal_entities(xml: &str) -> CowStr<'_> {
    let chars: Vec<char> = xml.chars().collect();
    let len = chars.len();
    let mut first_illegal = None;
    for i in 0..len {
        if chars[i] == '&' && !is_legal_entity(&chars, i + 1) {
            first_illegal = Some(i);
            break;
        }
    }
    let first = match first_illegal {
        Some(v) => v,
        None => return CowStr::Borrowed(xml),
    };

    let mut sb = String::with_capacity(xml.len() + 16);
    sb.extend(chars[..first].iter());
    for i in first..len {
        let ch = chars[i];
        if ch == '&' && !is_legal_entity(&chars, i + 1) {
            sb.push_str("&amp;");
        } else {
            sb.push(ch);
        }
    }
    CowStr::Owned(sb)
}

/// 轻量 Cow,避免为 Cow<'a, str> 引入额外泛型扩散(本模块内部使用)。
enum CowStr<'a> {
    Borrowed(&'a str),
    Owned(String),
}

impl CowStr<'_> {
    fn as_str(&self) -> &str {
        match self {
            CowStr::Borrowed(s) => s,
            CowStr::Owned(s) => s,
        }
    }
}

/// 等价 `Danmu.isLegalEntity`:`&` 后 10 字符内须有 `;`,且实体名合法。
fn is_legal_entity(chars: &[char], start: usize) -> bool {
    if start >= chars.len() {
        return false;
    }
    let end = match chars[start..].iter().position(|&c| c == ';') {
        Some(off) => start + off,
        None => return false,
    };
    if end - start > 10 {
        return false;
    }
    let entity: String = chars[start..end].iter().collect();
    match entity.as_str() {
        "amp" | "lt" | "gt" | "quot" | "apos" => return true,
        _ => {}
    }
    if let Some(hex) = entity.strip_prefix("#x").or_else(|| entity.strip_prefix("#X")) {
        return is_hex_number(hex);
    }
    if let Some(dec) = entity.strip_prefix('#') {
        return is_decimal_number(dec);
    }
    false
}

fn is_decimal_number(text: &str) -> bool {
    !text.is_empty() && text.chars().all(|c| c.is_ascii_digit())
}

fn is_hex_number(text: &str) -> bool {
    !text.is_empty() && text.chars().all(|c| c.is_ascii_hexdigit())
}

/// 等价 `Danmu.decodeXmlString`:正则回退路径下对 param 的实体解码。
fn decode_xml_string(text: &str) -> String {
    text.replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&gt;", ">")
        .replace("&lt;", "<")
}

// ----------------------------------------------------------------------------
// 手写标签扫描器:一次遍历提取 <d ...>text</d>,大小写不敏感。
// 相比 regex crate 的优势:支持任意属性值、无回溯、无 DOTALL 灾难、UTF-8 安全。
// ----------------------------------------------------------------------------

/// `XmlPullParser` 风格严格解析:属性名与文本均做完整实体解码(含数字实体)。
fn scan_strict(xml: &str) -> Vec<DanmuItem> {
    scan_tags(xml, DecodeMode::Strict)
}

/// Java 正则 `<d ... p=['"]...['"] ...>text</d>` 的等价手写扫描(DOTALL/IGNORECASE)。
/// param 走 [`decode_xml_string`],text 保持原文(与 Kotlin `matcher.group(3)` 一致)。
fn scan_by_tag(xml: &str) -> Vec<DanmuItem> {
    scan_tags(xml, DecodeMode::Tag)
}

#[derive(Clone, Copy)]
enum DecodeMode {
    Strict,
    Tag,
}

/// 单遍扫描所有 `<d ...>text</d>` 片段。
///
/// 解析规则(对齐 Java 正则 `.*?` 非贪婪):
/// - 起始标签必须形如 `<d` + 至少一个空白 + 属性区 + `>`;
/// - 属性区中查找 `p` 属性(词边界、大小写不敏感),值为单引号或双引号包裹;
/// - 文本区截止到最近的 `</d>`(大小写不敏感,允许 `</d >` 之外的严格闭合)。
fn scan_tags(xml: &str, mode: DecodeMode) -> Vec<DanmuItem> {
    let bytes = xml.as_bytes();
    let n = bytes.len();
    let mut out = Vec::new();
    let mut i = 0usize;

    while i < n {
        // 寻找 "<d" 且后随空白
        if bytes[i] == b'<' && i + 2 < n && (bytes[i + 1] == b'd' || bytes[i + 1] == b'D') && is_ws(bytes[i + 2]) {
            // 属性区到 '>' 结束(引号内的 '>' 不算结束)
            let mut j = i + 2;
            let mut quote = 0u8;
            let mut attrs_end = None;
            while j < n {
                let b = bytes[j];
                if quote != 0 {
                    if b == quote {
                        quote = 0;
                    }
                } else if b == b'\'' || b == b'"' {
                    quote = b;
                } else if b == b'>' {
                    attrs_end = Some(j);
                    break;
                }
                j += 1;
            }
            if let Some(gt) = attrs_end {
                let attrs = &xml[i + 2..gt];
                if let Some(p_raw) = find_p_attr(attrs) {
                    // 文本截止到最近的 "</d>"
                    let text_start = gt + 1;
                    if let Some(close) = find_close_d(bytes, text_start) {
                        let text_raw = &xml[text_start..close];
                        let param = match mode {
                            DecodeMode::Strict => decode_xml_entities(&p_raw, true),
                            DecodeMode::Tag => decode_xml_string(&p_raw),
                        };
                        let text = match mode {
                            DecodeMode::Strict => decode_xml_entities(text_raw, false),
                            DecodeMode::Tag => text_raw.to_string(),
                        };
                        if !param.is_empty() && !text.is_empty() {
                            out.push(DanmuItem { param, text });
                        }
                        i = close + 4; // 跳过 "</d>"
                        continue;
                    }
                }
            }
        }
        i += 1;
    }
    out
}

#[inline]
fn is_ws(b: u8) -> bool {
    matches!(b, b' ' | b'\t' | b'\r' | b'\n' | 0x0c)
}

/// 在属性区查找 `p`(独立属性名,大小写不敏感),返回引号内的值。
fn find_p_attr(attrs: &str) -> Option<String> {
    let bytes = attrs.as_bytes();
    let n = bytes.len();
    let mut i = 0usize;
    while i < n {
        while i < n && is_ws(bytes[i]) {
            i += 1;
        }
        let name_start = i;
        while i < n && (bytes[i].is_ascii_alphanumeric() || bytes[i] == b'-' || bytes[i] == b'_') {
            i += 1;
        }
        let name = &attrs[name_start..i];
        while i < n && is_ws(bytes[i]) {
            i += 1;
        }
        if i < n && bytes[i] == b'=' {
            i += 1;
            while i < n && is_ws(bytes[i]) {
                i += 1;
            }
            if i < n && (bytes[i] == b'\'' || bytes[i] == b'"') {
                let q = bytes[i];
                i += 1;
                let vs = i;
                while i < n && bytes[i] != q {
                    i += 1;
                }
                let value = &attrs[vs..i];
                if name.eq_ignore_ascii_case("p") {
                    return Some(value.to_string());
                }
                if i < n {
                    i += 1;
                }
                continue;
            }
            // 无引号属性值:跳过至空白
            let vs = i;
            while i < n && !is_ws(bytes[i]) {
                i += 1;
            }
            if name.eq_ignore_ascii_case("p") {
                return Some(attrs[vs..i].to_string());
            }
        }
        // 无 '=' 的布尔属性,继续下一个
        if name_start == i {
            i += 1;
        }
    }
    None
}

/// 从 text_start 起查找最近的 `</d>`(大小写不敏感),返回 `<` 的字节下标。
fn find_close_d(bytes: &[u8], text_start: usize) -> Option<usize> {
    let n = bytes.len();
    let mut i = text_start;
    while i + 3 < n {
        if bytes[i] == b'<'
            && bytes[i + 1] == b'/'
            && (bytes[i + 2] == b'd' || bytes[i + 2] == b'D')
            && bytes[i + 3] == b'>'
        {
            return Some(i);
        }
        i += 1;
    }
    None
}

/// 实体完整解码(严格模式):处理 5 个命名实体 + `&#d;` / `&#xh;`。
/// 单遍扫描,避免 `replace` 链式多次分配与二次解码问题。
fn decode_xml_entities(text: &str, _attr: bool) -> String {
    if !text.as_bytes().contains(&b'&') {
        return text.to_string();
    }
    let mut sb = String::with_capacity(text.len());
    let bytes = text.as_bytes();
    let mut i = 0usize;
    let mut last = 0usize;
    while i < bytes.len() {
        if bytes[i] == b'&' {
            if let Some((entity_len, decoded)) = parse_entity_at(text, i) {
                sb.push_str(&text[last..i]);
                sb.push_str(&decoded);
                i += entity_len;
                last = i;
                continue;
            }
        }
        i += next_char_len(bytes[i]);
    }
    sb.push_str(&text[last..]);
    sb
}

/// 在 `text[pos]` 为 '&' 时解析实体,返回 (消耗字节数, 解码字符串)。
fn parse_entity_at(text: &str, pos: usize) -> Option<(usize, String)> {
    let rest = &text[pos + 1..];
    let semi = rest.find(';')?;
    // 实体名必须紧邻,且长度受限(与 isLegalEntity 的 10 字符上限一致)
    if semi == 0 || semi > 10 {
        return None;
    }
    let name = &rest[..semi];
    let decoded = match name {
        "amp" => "&",
        "lt" => "<",
        "gt" => ">",
        "quot" => "\"",
        "apos" => "'",
        _ => {
            if let Some(hex) = name.strip_prefix("#x").or_else(|| name.strip_prefix("#X")) {
                let cp = u32::from_str_radix(hex, 16).ok()?;
                return char::from_u32(cp).map(|c| (semi + 2, c.to_string()));
            }
            if let Some(dec) = name.strip_prefix('#') {
                let cp: u32 = dec.parse().ok()?;
                return char::from_u32(cp).map(|c| (semi + 2, c.to_string()));
            }
            return None;
        }
    };
    Some((semi + 2, decoded.to_string()))
}

#[inline]
fn next_char_len(b: u8) -> usize {
    if b < 0x80 {
        1
    } else if b < 0xE0 {
        2
    } else if b < 0xF0 {
        3
    } else {
        4
    }
}

// ============================================================================
// 第二部分:弹幕搜索纯函数簇(移植自 DanmakuApi.kt)
// ============================================================================

/// 等价 `DanmakuApi.escapeXml`。
pub fn escape_xml(text: &str) -> String {
    if text.is_empty() {
        return String::new();
    }
    let mut sb = String::with_capacity(text.len() + 16);
    for c in text.chars() {
        match c {
            '&' => sb.push_str("&amp;"),
            '"' => sb.push_str("&quot;"),
            '\'' => sb.push_str("&apos;"),
            '>' => sb.push_str("&gt;"),
            '<' => sb.push_str("&lt;"),
            c => sb.push(c),
        }
    }
    sb
}

fn is_color_value(value: &str) -> bool {
    if value.is_empty() {
        return false;
    }
    let text = value.trim();
    if text.starts_with('#') || text.starts_with("0x") || text.starts_with("0X") {
        return true;
    }
    match text.parse::<i64>() {
        Ok(v) => (0..=0x00ff_ffff).contains(&v),
        Err(_) => false,
    }
}

/// 等价 `DanmakuApi.normalizeColor`:#/0x 前缀十六进制 → 十进制串。
pub fn normalize_color(color: &str) -> String {
    if color.is_empty() {
        return "16777215".to_string();
    }
    let text = color.trim();
    if let Some(hex) = text.strip_prefix('#').or_else(|| text.strip_prefix("0x").or_else(|| text.strip_prefix("0X"))) {
        if let Ok(v) = i64::from_str_radix(hex, 16) {
            return v.to_string();
        }
    }
    text.to_string()
}

/// 等价 `DanmakuApi.normalizeDanmakuParam`。
pub fn normalize_danmaku_param(param: &str) -> String {
    let values: Vec<&str> = param.split(',').collect();
    if values.len() < 4 {
        return param.to_string();
    }
    let (time, typ) = (values[0], values[1]);
    let (mut size, mut color) = (values[2], values[3]);
    if is_color_value(size) {
        color = size;
        size = "25";
    } else if !is_color_value(color) {
        color = "16777215";
    }
    format!("{time},{typ},{size},{}", normalize_color(color))
}

/// 等价 `DanmakuApi.commentJsonToXml`:comments/data 数组 → `<i><d ...>...</d></i>`。
pub fn comment_json_to_xml(body: &str) -> String {
    let obj: Value = match serde_json::from_str(body) {
        Ok(v) => v,
        Err(_) => return String::new(),
    };
    let comments = obj.get("comments").or_else(|| obj.get("data"));
    let arr = match comments.and_then(Value::as_array) {
        Some(a) if !a.is_empty() => a,
        _ => return String::new(),
    };

    let mut sb = String::with_capacity(arr.len() * 96 + 8);
    sb.push_str("<i>");
    for item in arr {
        let Some(map) = item.as_object() else { continue };
        let param = opt_string(map, "p");
        let mut text = opt_string(map, "m");
        if text.is_empty() {
            text = opt_string(map, "text");
        }
        if param.is_empty() || text.is_empty() {
            continue;
        }
        sb.push_str("<d p=\"");
        sb.push_str(&escape_xml(&normalize_danmaku_param(&param)));
        sb.push_str("\">");
        sb.push_str(&escape_xml(&text));
        sb.push_str("</d>");
    }
    sb.push_str("</i>");
    sb
}

fn opt_string(map: &serde_json::Map<String, Value>, key: &str) -> String {
    match map.get(key) {
        Some(Value::String(s)) => s.clone(),
        Some(Value::Number(n)) => n.to_string(),
        Some(Value::Bool(b)) => b.to_string(),
        _ => String::new(),
    }
}

fn first_string(map: &serde_json::Map<String, Value>, keys: &[&str]) -> String {
    for k in keys {
        let v = opt_string(map, k);
        if !v.is_empty() {
            return v;
        }
    }
    String::new()
}

/// 等价 `DanmakuApi.extractNumber`:提取所有数字字符组成整数,失败 -1。
pub fn extract_number(text: &str) -> i64 {
    if text.is_empty() {
        return -1;
    }
    let digits: String = text.chars().filter(|c| c.is_ascii_digit()).collect();
    if digits.is_empty() {
        return -1;
    }
    digits.parse::<i64>().unwrap_or(-1)
}

/// 等价 `DanmakuApi.parseEpisodeNumber`:先 parseFloat→int,失败走 extractNumber。
pub fn parse_episode_number(value: &str) -> i64 {
    if value.is_empty() {
        return -1;
    }
    match value.parse::<f64>() {
        Ok(v) => v as i64,
        Err(_) => extract_number(value),
    }
}

fn search_result(name: String, url: String, built_in: bool) -> Value {
    json!({ "name": name, "url": url, "builtIn": built_in })
}

fn append_search_results(out: &mut Vec<Value>, arr: &[Value]) {
    for item in arr {
        let Some(map) = item.as_object() else { continue };
        let url = opt_string(map, "url").trim().to_string();
        if url.is_empty() {
            continue;
        }
        let name = first_string(map, &["name", "title", "episode", "vod_name"]);
        out.push(search_result(name, url, false));
    }
}

/// 等价 `DanmakuApi.parseSearchList` → JSON 数组 `[{name,url,builtIn}, ...]`。
pub fn parse_search_list(body: &str) -> String {
    let mut out = Vec::new();
    let text = body.trim();
    if text.is_empty() {
        return "[]".to_string();
    }
    let value: Value = match serde_json::from_str(text) {
        Ok(v) => v,
        Err(_) => return "[]".to_string(),
    };
    match &value {
        Value::Array(arr) => append_search_results(&mut out, arr),
        Value::Object(map) => {
            let items = map.get("data").or_else(|| map.get("list")).or_else(|| map.get("results"));
            if let Some(arr) = items.and_then(Value::as_array) {
                append_search_results(&mut out, arr);
            } else {
                let url = opt_string(map, "url").trim().to_string();
                if !url.is_empty() {
                    let name = first_string(map, &["name", "title"]);
                    out.push(search_result(name, url, false));
                }
            }
        }
        _ => {}
    }
    Value::Array(out).to_string()
}

fn append_builtin_results(out: &mut Vec<Value>, base_url: &str, map: &serde_json::Map<String, Value>, anime_name: &str) {
    let Some(episodes) = map.get("episodes").and_then(Value::as_array) else { return };
    for ep in episodes {
        let Some(em) = ep.as_object() else { continue };
        let id = first_string(em, &["episodeId", "id"]);
        if id.is_empty() {
            continue;
        }
        let ep_name = first_string(em, &["episodeTitle", "title", "name"]);
        let mut name = if anime_name.is_empty() {
            ep_name
        } else {
            format!("{anime_name} {ep_name}")
        };
        if name.is_empty() {
            name = id.clone();
        }
        let url = format!("{base_url}/api/v2/comment/{id}?format=json");
        out.push(search_result(name, url, true));
    }
}

fn append_builtin_anime(out: &mut Vec<Value>, base_url: &str, animes: Option<&Vec<Value>>) {
    let Some(animes) = animes else { return };
    for anime in animes {
        let Some(map) = anime.as_object() else { continue };
        let anime_name = first_string(map, &["animeTitle", "title", "name"]);
        append_builtin_results(out, base_url, map, &anime_name);
        if let Some(bangumi) = map.get("bangumi").and_then(Value::as_object) {
            append_builtin_results(out, base_url, bangumi, &anime_name);
        }
    }
}

/// 等价 `DanmakuApi.parseBuiltinSearchList` → JSON 数组。
pub fn parse_builtin_search_list(body: &str, base_url: &str) -> String {
    let mut out = Vec::new();
    let obj: Value = match serde_json::from_str(body) {
        Ok(v) => v,
        Err(_) => return "[]".to_string(),
    };
    let Some(map) = obj.as_object() else { return "[]".to_string() };
    append_builtin_results(&mut out, base_url, map, "");
    if let Some(bangumi) = map.get("bangumi").and_then(Value::as_object) {
        let name = first_string(bangumi, &["animeTitle", "title", "name"]);
        append_builtin_results(&mut out, base_url, bangumi, &name);
    }
    append_builtin_anime(&mut out, base_url, map.get("animes").and_then(Value::as_array));
    append_builtin_anime(&mut out, base_url, map.get("anime").and_then(Value::as_array));
    append_builtin_anime(&mut out, base_url, map.get("data").and_then(Value::as_array));
    Value::Array(out).to_string()
}

/// 等价 `DanmakuApi.parseUrl`:从 JSON 或裸 URL 中提取 url。
pub fn parse_url(body: &str) -> String {
    if body.is_empty() {
        return String::new();
    }
    let text = body.trim();
    if text.starts_with('[') {
        if let Ok(arr) = serde_json::from_str::<Vec<Value>>(text) {
            for item in arr {
                if let Some(map) = item.as_object() {
                    let url = opt_string(map, "url").trim().to_string();
                    if !url.is_empty() {
                        return url;
                    }
                }
            }
        }
    } else if text.starts_with('{') {
        if let Ok(map) = serde_json::from_str::<serde_json::Map<String, Value>>(text) {
            return opt_string(&map, "url").trim().to_string();
        }
    } else if text.starts_with("http") || text.starts_with("file") {
        return text.to_string();
    }
    String::new()
}

// ============================================================================
// 单元测试:对照 Kotlin 语义
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;

    fn items(json: &str) -> Vec<DanmuItem> {
        let v: Value = serde_json::from_str(json).unwrap();
        v.as_array()
            .unwrap()
            .iter()
            .map(|o| DanmuItem {
                param: o["p"].as_str().unwrap().to_string(),
                text: o["t"].as_str().unwrap().to_string(),
            })
            .collect()
    }

    #[test]
    fn parse_basic_xml() {
        let xml = r#"<i><d p="1.5,1,25,16777215,0,0,abc,0">hello</d><d p="2.0,1,25,0,0,0,def,0">world</d></i>"#;
        let got = parse_danmu_xml(xml, 0);
        assert_eq!(items(&got), vec![
            DanmuItem { param: "1.5,1,25,16777215,0,0,abc,0".into(), text: "hello".into() },
            DanmuItem { param: "2.0,1,25,0,0,0,def,0".into(), text: "world".into() },
        ]);
    }

    #[test]
    fn parse_single_quotes_and_uppercase_tags() {
        let xml = r#"<I><D P='3.0,1,25,16777215,0,0,x,0'>hi</D></I>"#;
        let got = parse_danmu_xml(xml, 0);
        assert_eq!(items(&got), vec![
            DanmuItem { param: "3.0,1,25,16777215,0,0,x,0".into(), text: "hi".into() },
        ]);
    }

    #[test]
    fn parse_empty_and_non_xml() {
        assert_eq!(parse_danmu_xml("", 0), "[]");
        assert_eq!(parse_danmu_xml("not xml at all", 0), "[]");
        assert_eq!(parse_danmu_xml("<i></i>", 0), "[]");
    }

    #[test]
    fn start_position_filters_by_time() {
        let xml = r#"<i><d p="1.0,1,25,16777215,0,0,a,0">a</d><d p="5.5,1,25,16777215,0,0,b,0">b</d><d p="10.0,1,25,16777215,0,0,c,0">c</d></i>"#;
        let got = parse_danmu_xml(xml, 3000);
        let g = items(&got);
        assert_eq!(g.len(), 2, "仅 >=3000ms 的弹幕保留: {:?}", g);
        assert_eq!(g[0].text, "b");
        assert_eq!(g[1].text, "c");
    }

    #[test]
    fn get_time_semantics() {
        assert_eq!(get_time("1.5,1,25"), 1500);
        assert_eq!(get_time("0,1"), 0);
        assert_eq!(get_time("bad,1"), i64::MIN);
        assert_eq!(get_time(""), i64::MIN);
        assert_eq!(get_time("1.5"), i64::MIN); // 无逗号
    }

    #[test]
    fn escape_illegal_entities_basic() {
        let xml = r#"<d p="1,1,25,0,0,0,a,0">a & b & c</d>"#;
        let fixed = escape_illegal_entities(xml);
        assert_eq!(fixed.as_str(), r#"<d p="1,1,25,0,0,0,a,0">a &amp; b &amp; c</d>"#);
    }

    #[test]
    fn escape_illegal_entities_keeps_legal() {
        let xml = r#"<d p="1,1,25,0,0,0,a,0">a &amp; b &lt; c &#65; &#x41;</d>"#;
        let fixed = escape_illegal_entities(xml);
        assert_eq!(fixed.as_str(), xml, "合法实体不应改动");
    }

    #[test]
    fn illegal_entity_then_parse_recovers_text() {
        // 非法 & 会被修复为 &amp;,严格解析后 text 解码回 &
        let xml = r#"<i><d p="1,1,25,0,0,0,a,0">A & B</d></i>"#;
        let got = parse_danmu_xml(xml, 0);
        assert_eq!(items(&got)[0].text, "A & B");
    }

    #[test]
    fn named_and_numeric_entities_decoded() {
        let xml = r#"<i><d p="1,1,25,0,0,0,a,0">&lt;tag&gt; &amp; &#65; &#x42;</d></i>"#;
        let got = parse_danmu_xml(xml, 0);
        assert_eq!(items(&got)[0].text, "<tag> & A B");
    }

    #[test]
    fn param_entities_decoded() {
        let xml = r#"<i><d p="1,1,25,0,0,0,a&amp;b,0">t</d></i>"#;
        let got = parse_danmu_xml(xml, 0);
        assert_eq!(items(&got)[0].param, "1,1,25,0,0,0,a&b,0");
    }

    #[test]
    fn multibyte_and_json_escaping() {
        let xml = "<i><d p=\"1,1,25,0,0,0,a,0\">中文\"引号\\反斜\n换行</d></i>";
        let got = parse_danmu_xml(xml, 0);
        let parsed: Value = serde_json::from_str(&got).unwrap();
        assert_eq!(parsed[0]["t"].as_str().unwrap(), "中文\"引号\\反斜\n换行");
    }

    #[test]
    fn no_panic_on_fuzz_like_inputs() {
        let cases = [
            "<d",
            "<d >",
            "<d p='",
            "<d p=\"x\"></d",
            "<d p=\"x\">>",
            "&",
            "&#",
            "&#x",
            "&&&&",
            "<d p=\"1,1,25,0,0,0,a,0\">text</d></d>",
            "<d p=\"\" >t</d>",
            "<d p=\"1,2,3,4\">",
            "\0\0\0",
        ];
        for c in cases {
            let _ = parse_danmu_xml(c, 0);
            let _ = parse_danmu_xml(c, 5000);
        }
    }

    // ---- 搜索纯函数簇 ----

    #[test]
    fn escape_xml_all_entities() {
        assert_eq!(escape_xml(""), "");
        assert_eq!(escape_xml("a&b\"c'd>e<f"), "a&amp;b&quot;c&apos;d&gt;e&lt;f");
    }

    #[test]
    fn normalize_color_cases() {
        assert_eq!(normalize_color(""), "16777215");
        assert_eq!(normalize_color("#FF00AA"), "16711850");
        assert_eq!(normalize_color("0x10"), "16");
        assert_eq!(normalize_color("0X10"), "16");
        assert_eq!(normalize_color("  ff  "), "ff");
    }

    #[test]
    fn normalize_param_color_in_size() {
        // size 位放颜色 → color=size, size=25
        assert_eq!(normalize_danmaku_param("1,1,#FF0000,0"), "1,1,25,16711680");
        // Kotlin isColorValue("25")==true(25 落在 0..0xffffff)→ color 被 size 覆盖,size="25"
        // 这是 Kotlin 基准的真实语义(参数为 time,mode,color,size 顺序时触发交换),Rust 严格对齐
        assert_eq!(normalize_danmaku_param("1,1,25,notcolor"), "1,1,25,25");
        // 不足 4 段 → 原样
        assert_eq!(normalize_danmaku_param("1,1,25"), "1,1,25");
    }

    #[test]
    fn comment_json_to_xml_basic() {
        let body = r#"{"comments":[{"p":"1,1,25,16777215","m":"hello <b>"},{"p":"2,1,25,0","text":"world & co"},{"p":"","m":"skip"}]}"#;
        let xml = comment_json_to_xml(body);
        // isColorValue("25")==true → color 被 size 覆盖,故两条的 color 位均为 25(Kotlin 基准语义)
        assert_eq!(xml, "<i><d p=\"1,1,25,25\">hello &lt;b&gt;</d><d p=\"2,1,25,25\">world &amp; co</d></i>");
    }

    #[test]
    fn comment_json_to_xml_empty_and_bad() {
        assert_eq!(comment_json_to_xml("bad"), "");
        assert_eq!(comment_json_to_xml("{}"), "");
        assert_eq!(comment_json_to_xml(r#"{"comments":[]}"#), "");
    }

    #[test]
    fn extract_number_and_episode() {
        assert_eq!(extract_number("第12集"), 12);
        assert_eq!(extract_number("abc"), -1);
        assert_eq!(extract_number(""), -1);
        assert_eq!(parse_episode_number("12.7"), 12);
        assert_eq!(parse_episode_number("第3集"), 3);
    }

    #[test]
    fn parse_search_list_shapes() {
        let arr = r#"[{"name":"A","url":"http://a"},{"title":"B","url":" http://b "}]"#;
        let got: Value = serde_json::from_str(&parse_search_list(arr)).unwrap();
        assert_eq!(got.as_array().unwrap().len(), 2);
        assert_eq!(got[0]["name"], "A");
        assert_eq!(got[1]["url"], "http://b");

        let obj = r#"{"data":[{"name":"X","url":"u1"}]}"#;
        let got: Value = serde_json::from_str(&parse_search_list(obj)).unwrap();
        assert_eq!(got[0]["name"], "X");

        let single = r#"{"name":"S","url":" http://s "}"#;
        let got: Value = serde_json::from_str(&parse_search_list(single)).unwrap();
        assert_eq!(got[0]["url"], "http://s");
    }

    #[test]
    fn parse_builtin_search_list_nested() {
        let body = r#"{"animes":[{"animeTitle":"番剧","episodes":[{"episodeId":"e1","episodeTitle":"第1话"}]}]}"#;
        let got: Value = serde_json::from_str(&parse_builtin_search_list(body, "http://api")).unwrap();
        let arr = got.as_array().unwrap();
        assert_eq!(arr.len(), 1);
        assert_eq!(arr[0]["name"], "番剧 第1话");
        assert_eq!(arr[0]["url"], "http://api/api/v2/comment/e1?format=json");
        assert_eq!(arr[0]["builtIn"], true);
    }

    #[test]
    fn parse_url_shapes() {
        assert_eq!(parse_url("http://x/y"), "http://x/y");
        assert_eq!(parse_url(r#"{"url":" http://a "}"#), "http://a");
        assert_eq!(parse_url(r#"[{"url":"http://b"}]"#), "http://b");
        assert_eq!(parse_url("garbage"), "");
    }

    #[test]
    fn mixed_real_world_xml() {
        // 混合:合法实体、非法实体(需修复)、中文、单双引号、大小写、换行
        let xml = "<i>\n\
            <d p=\"0.5,1,25,16777215,0,0,a,0\">第一段 &amp; 你好</d>\n\
            <d p='6.2,1,25,16777215,0,0,b,0'>含 & 非法实体</d>\n\
            <D P=\"9.0,1,25,16777215,0,0,c,0\">第三段 &lt;tag&gt;</D>\n\
            </i>";
        let got = parse_danmu_xml(xml, 0);
        let g = items(&got);
        assert_eq!(g.len(), 3, "三条都应解析: {:?}", g);
        assert_eq!(g[0].text, "第一段 & 你好");
        assert_eq!(g[1].text, "含 & 非法实体");
        assert_eq!(g[2].text, "第三段 <tag>");
        // 时间过滤:仅 >=6000ms
        let g2 = items(&parse_danmu_xml(xml, 6000));
        assert_eq!(g2.len(), 2);
        assert_eq!(g2[0].text, "含 & 非法实体");
    }

    fn build_danmaku_xml(count: u32) -> String {
        let mut xml = String::with_capacity(count as usize * 96 + 8);
        xml.push_str("<i>");
        for i in 0..count {
            xml.push_str("<d p=\"");
            xml.push_str(&i.to_string());
            xml.push_str(".0,1,25,16777215,0,0,id");
            xml.push_str(&i.to_string());
            xml.push_str(",0\">弹幕内容 &amp; test</d>");
        }
        xml.push_str("</i>");
        xml
    }

    /// 常规性能回归守卫(随普通测试套件运行,独占 CPU 时稳定)。
    /// 2000 条弹幕一次遍历解析,远快于 Kotlin 的 DOM/正则回退。
    #[test]
    fn parse_2000_danmaku_under_100ms() {
        let xml = build_danmaku_xml(2000);
        let start = std::time::Instant::now();
        let out = parse_danmu_xml(&xml, 0);
        let elapsed = start.elapsed();
        assert_eq!(serde_json::from_str::<Value>(&out).unwrap().as_array().unwrap().len(), 2000);
        assert!(elapsed.as_millis() < 100, "2000 条弹幕耗时 {:?} 超过 100ms", elapsed);
    }

    /// 10 万条弹幕 ≈ 6MB 的大输入健壮性冒烟(#[ignore]:大内存,常规 CI 不跑)。
    /// 与项目其它 ignored smoke 一致只校验正确性、不设计时阈值 ——
    /// 多 smoke 并行时 CPU 竞争会让计时断言假阳性。
    #[test]
    #[ignore]
    fn robust_large_danmaku_xml_smoke() {
        let xml = build_danmaku_xml(100_000);
        let out = parse_danmu_xml(&xml, 0);
        assert_eq!(serde_json::from_str::<Value>(&out).unwrap().as_array().unwrap().len(), 100_000);
        // 带时间过滤的大输入亦须零 panic 且结果单调
        let filtered = parse_danmu_xml(&xml, 50_000);
        let n = serde_json::from_str::<Value>(&filtered).unwrap().as_array().unwrap().len();
        assert!(n > 0 && n < 100_000, "时间过滤后应保留部分弹幕: {}", n);
    }
}
