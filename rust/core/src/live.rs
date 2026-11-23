//! 直播源解析,移植自 `TxtSubscribe`(txt / m3u / json 三种格式)。
//!
//! 输出两种形态:
//! - [`parse_to_json`]:完整数组 `[{group, channels:[{name, urls, logo, epg, ...}]}]`,保留全部元数据
//! - [`parse_simple`]:折叠形态 `{group: {channel: [url, ...]}}`,等价 `TxtSubscribe.parse`

use serde_json::{json, Map, Value};

pub const DEFAULT_GROUP_NAME: &str = "直播";
const LEGACY_DEFAULT_GROUP_NAME: &str = "Ungrouped";

/// 规范分组名,等价 `TxtSubscribe.normalizeGroupName`。
pub fn normalize_group_name(name: Option<&str>) -> String {
    let name = match name {
        Some(n) => n.trim(),
        None => return DEFAULT_GROUP_NAME.to_string(),
    };
    if name.is_empty() || name.eq_ignore_ascii_case(LEGACY_DEFAULT_GROUP_NAME) {
        DEFAULT_GROUP_NAME.to_string()
    } else {
        name.to_string()
    }
}

fn is_url(url: &str) -> bool {
    !url.is_empty()
        && (url.starts_with("http") || url.starts_with("rtp") || url.starts_with("rtsp") || url.starts_with("rtmp"))
}

/// 等价 `DefaultConfig.safeJsonString`。
fn safe_string(obj: &Value, key: &str, default: &str) -> String {
    match obj.get(key) {
        Some(Value::Object(o)) => Value::Object(o.clone()).to_string().trim().to_string(),
        Some(Value::Array(a)) => Value::Array(a.clone()).to_string().trim().to_string(),
        Some(Value::String(s)) => s.trim().to_string(),
        Some(Value::Number(n)) => n.to_string().trim().to_string(),
        Some(Value::Bool(b)) => b.to_string().trim().to_string(),
        _ => default.to_string(),
    }
}

fn put_if_not_empty(obj: &mut Map<String, Value>, key: &str, value: &str) {
    if !value.is_empty() {
        obj.insert(key.to_string(), Value::String(value.to_string()));
    }
}

fn get_after_last_comma(line: &str) -> String {
    // 等价正则 `.*,(.+?)$`:取最后一个逗号之后的内容
    match line.rfind(',') {
        Some(idx) => line[idx + 1..].trim().to_string(),
        None => String::new(),
    }
}

fn get_quoted(line: &str, key: &str) -> String {
    // 等价 `key="(.*?)"` 非贪婪匹配:取首个 key=" 值 "
    let needle = format!("{key}=\"");
    if let Some(start) = line.find(&needle) {
        let rest = &line[start + needle.len()..];
        if let Some(end) = rest.find('"') {
            return rest[..end].trim().to_string();
        }
    }
    String::new()
}

fn get_value(line: &str, key: &str) -> String {
    let needle = format!("{key}=");
    match line.find(&needle) {
        Some(idx) => line[idx + needle.len()..].trim().replace('"', ""),
        None => String::new(),
    }
}

fn is_setting_line(line: &str) -> bool {
    line.starts_with("ua")
        || line.starts_with("parse")
        || line.starts_with("click")
        || line.starts_with("player")
        || line.starts_with("header")
        || line.starts_with("format")
        || line.starts_with("origin")
        || line.starts_with("referer")
        || line.starts_with("#EXTHTTP:")
        || line.starts_with("#EXTVLCOPT:")
        || line.starts_with("#KODIPROP:")
}

fn try_parse_json_object(text: &str) -> Option<Value> {
    serde_json::from_str::<Value>(text)
        .ok()
        .filter(|v| v.as_object().is_some())
}

/// 等价 `buildSetting`:行内全局设置(ua/parse/click/header/format/origin/referer...)。
fn build_setting(line: &str) -> Value {
    let mut obj = Map::new();
    if line.starts_with("ua") {
        put_if_not_empty(&mut obj, "ua", &get_value(line, "ua"));
    }
    if line.starts_with("parse") {
        put_if_not_empty(&mut obj, "parse", &get_value(line, "parse"));
    }
    if line.starts_with("click") {
        put_if_not_empty(&mut obj, "click", &get_value(line, "click"));
    }
    if line.starts_with("header") {
        let value = get_value(line, "header");
        if let Some(header) = try_parse_json_object(&value) {
            obj.insert("header".to_string(), header);
        }
    }
    if line.starts_with("format") {
        put_if_not_empty(&mut obj, "format", &get_value(line, "format"));
    }
    if line.starts_with("origin") {
        put_if_not_empty(&mut obj, "origin", &get_value(line, "origin"));
    }
    if line.starts_with("referer") {
        put_if_not_empty(&mut obj, "referer", &get_value(line, "referer"));
    }
    if line.starts_with("#EXTHTTP:") {
        if let Some(header) = line.split("#EXTHTTP:").nth(1).and_then(|s| try_parse_json_object(s.trim())) {
            obj.insert("header".to_string(), header);
        }
    }
    if line.starts_with("#EXTVLCOPT:") {
        if line.contains("http-user-agent") {
            put_if_not_empty(&mut obj, "ua", &get_value(line, "http-user-agent"));
        }
        if line.contains("http-origin") {
            put_if_not_empty(&mut obj, "origin", &get_value(line, "http-origin"));
        }
        if line.contains("http-referrer") {
            put_if_not_empty(&mut obj, "referer", &get_value(line, "http-referrer"));
        }
    }
    if line.starts_with("#KODIPROP:") && line.contains("manifest_type=") {
        put_if_not_empty(&mut obj, "format", &get_value(line, "manifest_type"));
    }
    Value::Object(obj)
}

/// 等价 `buildMeta`:EXTINF / EXTM3U 行的媒体元数据。
fn build_meta(line: &str) -> Value {
    let mut obj = Map::new();
    put_if_not_empty(&mut obj, "logo", &get_quoted(line, "tvg-logo"));
    put_if_not_empty(&mut obj, "epg", &get_quoted(line, "tvg-url"));
    put_if_not_empty(&mut obj, "tvg-id", &get_quoted(line, "tvg-id"));
    put_if_not_empty(&mut obj, "tvg-name", &get_quoted(line, "tvg-name"));
    put_if_not_empty(&mut obj, "tvg-chno", &get_quoted(line, "tvg-chno"));
    put_if_not_empty(&mut obj, "ua", &get_quoted(line, "http-user-agent"));
    let catchup = get_quoted(line, "catchup");
    let source = get_quoted(line, "catchup-source");
    let replace = get_quoted(line, "catchup-replace");
    if !catchup.is_empty() || !source.is_empty() || !replace.is_empty() {
        let mut catchup_obj = Map::new();
        put_if_not_empty(&mut catchup_obj, "type", &catchup);
        put_if_not_empty(&mut catchup_obj, "source", &source);
        put_if_not_empty(&mut catchup_obj, "replace", &replace);
        obj.insert("catchup".to_string(), Value::Object(catchup_obj));
    }
    Value::Object(obj)
}

/// 等价 `parseHeaderString`:m3u 地址行 `url|k=v&k=v` 的参数部分。
fn parse_header_string(text: &str) -> Value {
    let mut header = Map::new();
    for param in text.split('&') {
        if !param.contains('=') {
            continue;
        }
        let mut parts = param.splitn(2, '=');
        let k = parts.next().unwrap_or("").trim().replace('"', "");
        let v = parts.next().unwrap_or("").trim().replace('"', "");
        header.insert(k, Value::String(v));
    }
    let mut wrapper = Map::new();
    if !header.is_empty() {
        wrapper.insert("header".to_string(), Value::Object(header));
    }
    Value::Object(wrapper)
}

fn merge_meta(dst: &mut Map<String, Value>, src: &Value) {
    if let Some(map) = src.as_object() {
        for (k, v) in map {
            dst.insert(k.clone(), v.clone());
        }
    }
}

fn is_empty_value(value: &Value) -> bool {
    match value {
        Value::Null => true,
        Value::String(s) => s.trim().is_empty(),
        Value::Array(a) => a.is_empty(),
        Value::Object(o) => o.is_empty(),
        _ => false,
    }
}

fn find_or_create_group<'a>(result: &'a mut Vec<Value>, name: &str) -> &'a mut Map<String, Value> {
    let name = normalize_group_name(Some(name));
    let existing = result.iter().position(|g| safe_string(g, "group", "") == name);
    let idx = match existing {
        Some(i) => i,
        None => {
            result.push(json!({"group": name, "channels": []}));
            result.len() - 1
        }
    };
    result[idx].as_object_mut().expect("group is object")
}

fn contains_url(urls: &[Value], url: &str) -> bool {
    urls.iter().any(|u| u.as_str() == Some(url))
}

fn add_channel(group: &mut Map<String, Value>, channel: &mut Map<String, Value>) {
    let channels = group
        .entry("channels".to_string())
        .or_insert_with(|| Value::Array(Vec::new()));
    if !channels.is_array() {
        *channels = Value::Array(Vec::new());
    }
    let name = safe_string(&Value::Object(channel.clone()), "name", "");
    if name.is_empty() {
        if let Value::Array(arr) = channels {
            arr.push(Value::Object(channel.clone()));
        }
        return;
    }
    if let Value::Array(arr) = channels {
        let existing_idx = arr.iter().position(|c| safe_string(c, "name", "") == name);
        match existing_idx {
            None => arr.push(Value::Object(channel.clone())),
            Some(idx) => merge_channel(arr[idx].as_object_mut().expect("channel object"), channel),
        }
    }
}

fn merge_channel(dst: &mut Map<String, Value>, src: &Map<String, Value>) {
    // mergeUrls
    if let Some(Value::Array(src_urls)) = src.get("urls") {
        let dst_urls = dst
            .entry("urls".to_string())
            .or_insert_with(|| Value::Array(Vec::new()));
        if !dst_urls.is_array() {
            *dst_urls = Value::Array(Vec::new());
        }
        if let Value::Array(arr) = dst_urls {
            for u in src_urls {
                if let Some(url) = u.as_str() {
                    let url = url.trim();
                    if is_url(url) && !contains_url(arr, url) {
                        arr.push(Value::String(url.to_string()));
                    }
                }
            }
        }
    }
    // 其余键:dst 缺失或为空时覆盖
    for (k, v) in src {
        if k == "urls" {
            continue;
        }
        let missing_or_empty = match dst.get(k) {
            None => true,
            Some(existing) => is_empty_value(existing),
        };
        if missing_or_empty {
            dst.insert(k.clone(), v.clone());
        }
    }
}

fn parse_json_input(text: &str) -> Option<Vec<Value>> {
    let value: Value = serde_json::from_str(text).ok()?;
    let array = value.as_array()?;
    let mut result: Vec<Value> = Vec::new();
    for group_element in array {
        let group_obj = group_element.as_object()?;
        let raw_name = group_obj.get("group").and_then(|v| v.as_str()).unwrap_or("");
        let group_name = if raw_name.is_empty() {
            group_obj.get("name").and_then(|v| v.as_str()).unwrap_or(DEFAULT_GROUP_NAME)
        } else {
            raw_name
        };
        let group = find_or_create_group(&mut result, group_name);
        let channels = group_obj
            .get("channels")
            .or_else(|| group_obj.get("channel"))
            .and_then(|v| v.as_array());
        if let Some(channels) = channels {
            for channel_element in channels {
                let channel_obj = match channel_element.as_object() {
                    Some(o) => o,
                    None => continue,
                };
                let mut out_channel = Map::new();
                for key in [
                    "name", "urls", "logo", "epg", "ua", "click", "format", "origin", "referer",
                    "tvg-id", "tvg-name", "tvg-chno", "parse", "header", "catchup",
                    "catchup-source", "catchup-replace",
                ] {
                    if let Some(v) = channel_obj.get(key) {
                        out_channel.insert(key.to_string(), v.clone());
                    }
                }
                add_channel(group, &mut out_channel);
            }
        }
    }
    Some(result)
}

fn group_index(result: &mut Vec<Value>, name: &str) -> usize {
    let name = normalize_group_name(Some(name));
    let pos = result.iter().position(|g| safe_string(g, "group", "") == name);
    match pos {
        Some(i) => i,
        None => {
            result.push(json!({"group": name, "channels": []}));
            result.len() - 1
        }
    }
}

fn parse_m3u(text: &str) -> Vec<Value> {
    let mut result: Vec<Value> = Vec::new();
    let normalized = text.replace("\r\n", "\n").replace('\r', "");
    let mut current_group: Option<usize> = None;
    let mut pending_channel: Option<Map<String, Value>> = None;
    let mut pending_meta = Map::new();

    for raw in normalized.split('\n') {
        let line = raw.trim();
        if line.is_empty() {
            continue;
        }
        if line.starts_with("#EXTM3U") {
            merge_meta(&mut pending_meta, &build_meta(line));
            continue;
        }
        if is_setting_line(line) {
            merge_meta(&mut pending_meta, &build_setting(line));
            continue;
        }
        if line.starts_with("#EXTINF") || line.contains("#EXTINF") {
            let idx = group_index(&mut result, &get_quoted(line, "group-title"));
            current_group = Some(idx);
            let mut channel = Map::new();
            put_if_not_empty(&mut channel, "name", &get_after_last_comma(line));
            merge_meta(&mut channel, &build_meta(line));
            merge_meta(&mut channel, &Value::Object(pending_meta.clone()));
            pending_meta = Map::new();
            pending_channel = Some(channel);
            continue;
        }
        if line.starts_with('#') {
            continue;
        }
        if current_group.is_none() {
            current_group = Some(group_index(&mut result, DEFAULT_GROUP_NAME));
        }
        let mut channel = pending_channel.take().unwrap_or_default();
        let mut parts = line.splitn(2, '|');
        let url = parts.next().unwrap_or("").trim().to_string();
        if !is_url(&url) {
            pending_channel = Some(channel);
            continue;
        }
        if let Some(extra) = parts.next() {
            merge_meta(&mut pending_meta, &parse_header_string(extra));
        }
        merge_meta(&mut channel, &Value::Object(pending_meta.clone()));
        let urls = channel
            .entry("urls".to_string())
            .or_insert_with(|| Value::Array(Vec::new()));
        if let Value::Array(arr) = urls {
            if !contains_url(arr, &url) {
                arr.push(Value::String(url));
            }
        }
        pending_meta = Map::new();
        let group = result[current_group.expect("group set above")].as_object_mut().expect("group object");
        add_channel(group, &mut channel);
    }
    result
}

fn parse_txt(text: &str) -> Vec<Value> {
    let mut result: Vec<Value> = Vec::new();
    let normalized = text.replace("\r\n", "\n").replace('\r', "");
    let mut current_group: Option<usize> = None;
    let mut pending_meta = Map::new();

    for raw in normalized.split('\n') {
        let line = raw.trim();
        if line.is_empty() {
            continue;
        }
        if line.starts_with('#') {
            if is_setting_line(line) {
                merge_meta(&mut pending_meta, &build_setting(line));
            }
            continue;
        }
        if line.contains("#genre#") {
            let group_name = line.split(',').next().unwrap_or("").trim();
            current_group = Some(group_index(&mut result, group_name));
            pending_meta = Map::new();
            continue;
        }
        let mut split = line.splitn(2, ',');
        let name = split.next().unwrap_or("").trim();
        let url_part = match split.next() {
            Some(u) => u.trim(),
            None => continue,
        };
        if current_group.is_none() {
            current_group = Some(group_index(&mut result, DEFAULT_GROUP_NAME));
        }
        let mut channel = Map::new();
        put_if_not_empty(&mut channel, "name", name);
        merge_meta(&mut channel, &Value::Object(pending_meta.clone()));
        let mut urls: Vec<Value> = Vec::new();
        for part in url_part.split('#') {
            let url = part.trim();
            if is_url(url) && !contains_url(&urls, url) {
                urls.push(Value::String(url.to_string()));
            }
        }
        if urls.is_empty() {
            continue;
        }
        channel.insert("urls".to_string(), Value::Array(urls));
        pending_meta = Map::new();
        let group = result[current_group.expect("group set above")].as_object_mut().expect("group object");
        add_channel(group, &mut channel);
    }
    result
}

/// 完整解析(txt / m3u / json 自动识别),返回 JSON 数组字符串。
pub fn parse_to_json(text: &str) -> String {
    let array = parse_to_array(text);
    Value::Array(array).to_string()
}

fn parse_to_array(text: &str) -> Vec<Value> {
    // 与原版 Kotlin 的 isLiveJsonContent 对齐:去掉 UTF-8 BOM 后再判断形态
    let text = text.trim().trim_start_matches('\u{FEFF}').trim();
    if text.is_empty() {
        return Vec::new();
    }
    if text.starts_with('[') {
        if let Some(array) = parse_json_input(text) {
            return array;
        }
    }
    if text.starts_with("#EXTM3U") {
        return parse_m3u(text);
    }
    parse_txt(text)
}

/// 等价 `TxtSubscribe.parse`:折叠为 `{group: {channel: [urls]}}`。
/// 与 Kotlin 端一致:无有效频道的分组不写入结果(`channelMap.isNotEmpty()` 判断)。
pub fn parse_simple(text: &str) -> String {
    let array = parse_to_array(text);
    let mut out = Map::new();
    for group in array {
        let group_name = safe_string(&group, "group", DEFAULT_GROUP_NAME);
        let channels = group.get("channels").and_then(|v| v.as_array());
        let mut channel_map = Map::new();
        if let Some(channels) = channels {
            for channel in channels {
                let name = safe_string(channel, "name", "Unnamed");
                let mut urls: Vec<Value> = Vec::new();
                if let Some(Value::Array(src)) = channel.get("urls") {
                    for u in src {
                        if let Some(url) = u.as_str() {
                            let url = url.trim();
                            if is_url(url) && !contains_url(&urls, url) {
                                urls.push(Value::String(url.to_string()));
                            }
                        }
                    }
                }
                if !urls.is_empty() {
                    channel_map.insert(name, Value::Array(urls));
                }
            }
        }
        if channel_map.is_empty() {
            continue;
        }
        let entry = out
            .entry(group_name)
            .or_insert_with(|| Value::Object(Map::new()));
        if let Some(existing) = entry.as_object_mut() {
            for (name, urls) in channel_map {
                existing.insert(name, urls);
            }
        }
    }
    Value::Object(out).to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn txt_basic() {
        let src = "央视频道,#genre#\r\nCCTV1,http://a.com/1.m3u8#http://b.com/1.m3u8\r\nCCTV2,http://a.com/2.m3u8\r\n";
        let out = parse_simple(src);
        let v: Value = serde_json::from_str(&out).unwrap();
        let cctv1 = v["央视频道"]["CCTV1"].as_array().unwrap();
        assert_eq!(cctv1.len(), 2);
        assert_eq!(cctv1[0], "http://a.com/1.m3u8");
        assert!(v["央视频道"]["CCTV2"].is_array());
    }

    #[test]
    fn txt_ungrouped_normalized() {
        let src = "Ungrouped,#genre#\nCHC,http://a.com/chc.m3u8\n";
        let v: Value = serde_json::from_str(&parse_simple(src)).unwrap();
        assert!(v.get("直播").is_some(), "Ungrouped 应归一化为 直播");
    }

    #[test]
    fn m3u_with_meta() {
        let src = "#EXTM3U\n\
                   #EXTINF:-1 tvg-id=\"cctv1\" tvg-logo=\"http://logo/1.png\" group-title=\"央视\",CCTV1\n\
                   http://a.com/1.m3u8|User-Agent=\"test\"\n\
                   #EXTINF:-1 group-title=\"央视\",CCTV2\n\
                   http://a.com/2.m3u8\n";
        let out = parse_to_json(src);
        let v: Value = serde_json::from_str(&out).unwrap();
        assert_eq!(v[0]["group"], "央视");
        let ch1 = &v[0]["channels"][0];
        assert_eq!(ch1["name"], "CCTV1");
        assert_eq!(ch1["logo"], "http://logo/1.png");
        assert_eq!(ch1["urls"][0], "http://a.com/1.m3u8");
        assert_eq!(ch1["header"]["User-Agent"], "test");
        assert_eq!(v[0]["channels"][1]["name"], "CCTV2");
    }

    #[test]
    fn json_input_normalized() {
        let src = r#"[{"name":"卫视","channel":[{"name":"东方卫视","urls":["http://a.com/dt.m3u8"]}]}]"#;
        let v: Value = serde_json::from_str(&parse_to_json(src)).unwrap();
        assert_eq!(v[0]["group"], "卫视");
        assert_eq!(v[0]["channels"][0]["urls"][0], "http://a.com/dt.m3u8");
    }

    #[test]
    fn json_input_with_bom_normalized() {
        let src = "\u{FEFF}[{\"name\":\"卫视\",\"channel\":[{\"name\":\"东方卫视\",\"urls\":[\"http://a.com/dt.m3u8\"]}]}]";
        let v: Value = serde_json::from_str(&parse_to_json(src)).unwrap();
        assert_eq!(v[0]["group"], "卫视");
        assert_eq!(v[0]["channels"][0]["urls"][0], "http://a.com/dt.m3u8");
    }

    #[test]
    fn same_name_channels_merged() {
        let src = "组,#genre#\nCCTV1,http://a.com/1.m3u8\nCCTV1,http://b.com/1.m3u8\n";
        let v: Value = serde_json::from_str(&parse_simple(src)).unwrap();
        let urls = v["组"]["CCTV1"].as_array().unwrap();
        assert_eq!(urls.len(), 2);
    }

    #[test]
    fn global_setting_applies_to_next_channels() {
        let src = "#EXTM3U\nua=Mozilla/5.0\n#EXTINF:-1 group-title=\"g\",CH\nhttp://a.com/1\n";
        let v: Value = serde_json::from_str(&parse_to_json(src)).unwrap();
        assert_eq!(v[0]["channels"][0]["ua"], "Mozilla/5.0");
    }

    #[test]
    fn normalize_group() {
        assert_eq!(normalize_group_name(Some(" Ungrouped ")), DEFAULT_GROUP_NAME);
        assert_eq!(normalize_group_name(Some("")), DEFAULT_GROUP_NAME);
        assert_eq!(normalize_group_name(Some("央")), "央");
        assert_eq!(normalize_group_name(None), DEFAULT_GROUP_NAME);
    }

    // ---------------- 健壮性:恶意 / 畸形输入零 panic(畸形输入返回空而非崩溃) ----------------

    /// 空串 / 纯空白:两种形态都返回空容器,不 panic。
    #[test]
    fn robust_empty_and_whitespace() {
        for bad in ["", " ", "\t \r\n  ", "\u{3000}", "\u{FEFF}"] {
            assert_eq!(parse_to_json(bad), "[]", "parseLive 空输入 {:?}", bad);
            assert_eq!(parse_simple(bad), "{}", "parseLiveSimple 空输入 {:?}", bad);
        }
    }

    /// 无频道行(仅注释 / 设置行 / 裸分隔符)→ 空结果。
    #[test]
    fn robust_no_channel_lines() {
        let cases = [
            "#EXTM3U\n",
            "#EXTM3U\n# comment only\n#EXTVLCOPT:http-user-agent=ua\n",
            "组1,#genre#\n组2,#genre#\n",
            "ua=Mozilla/5.0\nheader={\"X\":\"1\"}\n",
            "CCTV1\nCCTV2\n",                       // 无逗号 → 无 url 部分
            "CCTV1,not-a-url\nCCTV2,ftp://x\n",     // url 全部不合法
            "#EXTM3U\n#EXTINF:-1,\n#EXTINF:-1,\n",  // EXTINF 悬挂无 URL
        ];
        for src in cases {
            let v: Value = serde_json::from_str(&parse_to_json(src)).expect("合法 JSON 数组");
            assert!(v.as_array().unwrap().iter().all(|g| {
                g.get("channels").and_then(|c| c.as_array()).is_none_or(|c| c.is_empty())
            }), "无频道行不应产生频道: {:?}", src);
            assert_eq!(parse_simple(src), "{}", "折叠形态应为空: {:?}", src);
        }
    }

    /// 单行 10 万字符(超长行):正常返回,不 panic、不截断 panic。
    #[test]
    fn robust_super_long_line() {
        let long_url = format!("http://a.com/{}", "x".repeat(100_000));
        let src = format!("组,#genre#\nCCTV1,{}\n", long_url);
        let v: Value = serde_json::from_str(&parse_simple(&src)).unwrap();
        let urls = v["组"]["CCTV1"].as_array().unwrap();
        assert_eq!(urls.len(), 1);
        assert_eq!(urls[0].as_str().unwrap().len(), long_url.len());

        // 无逗号 / 无 URL 的 10 万字符行同样安全
        let junk = "a".repeat(100_000);
        assert_eq!(parse_to_json(&junk), "[]");
        let junk2 = format!("#EXTM3U\n#EXTINF:-1 {}\n", "中".repeat(50_000));
        let v2: Value = serde_json::from_str(&parse_to_json(&junk2)).unwrap();
        assert!(v2.as_array().unwrap().iter().all(|g| {
            g.get("channels").and_then(|c| c.as_array()).is_none_or(|c| c.is_empty())
        }), "EXTINF 悬挂无 URL → 无频道");
    }

    /// 半截 UTF-8 边界字节:JNI 侧 to_string_lossy 会把非法字节替换为 U+FFFD,
    /// 核心层收到的是含 U+FFFD 的合法 UTF-8。多字节字符夹在行尾 / 引号内 / URL 中
    /// (等价"字节截断落在字符中间"的产物)必须零 panic。
    #[test]
    fn robust_half_utf8_replacement_chars() {
        let cases = [
            "组\u{FFFD},#genre#\nCCTV1,http://a.com/1.m3u8\n",               // 组名含替换符
            "\u{FFFD}\u{FFFD}\u{FFFD}\n",                                    // 全替换符行
            "#EXTM3U\n#EXTINF:-1 tvg-logo=\"http://a/\u{FFFD}.png\" group-title=\"央\",CCTV1\nhttp://a.com/1.m3u8\n",
            "央视,#genre#\n央\u{FFFD}视,http://a.com/中\u{FFFD}文.ts\n",      // URL 含替换符
            "#EXTM3U\n#EXTINF:-1 tvg-name=\"央\u{FFFD},CCTV1\nhttp://a.com/1.m3u8\n", // 引号未闭合且含替换符
            "#EXTVLCOPT:http-user-agent=安\u{FFFD}\n#EXTM3U\n#EXTINF:-1,g\nhttp://a.com/1.m3u8\n",
        ];
        for src in cases {
            let _ = parse_to_json(src);  // 只要零 panic 且为合法 JSON
            let out = parse_to_json(src);
            assert!(serde_json::from_str::<Value>(&out).is_ok(), "输出必须是合法 JSON: {:?}", src);
            let _ = parse_simple(src);
        }
    }

    /// 成对不闭合的括号 / 引号 / 分隔符:截断的 JSON、未闭合引号、未闭合花括号 header。
    #[test]
    fn robust_unclosed_brackets_and_delimiters() {
        let cases = [
            // 截断 JSON 数组:解析失败回退 txt 解析 → 空结果
            "[{\"group\":\"央视\",\"channels\":[{\"name\":\"CCTV1\"}",
            "[{\"group\":",
            "[",
            // JSON 对象(非数组)开头:与 Kotlin 一致回退 txt
            "{\"group\":\"央视\",\"channels\":[]}",
            // m3u:group-title 引号未闭合(两个引号才闭合一个 capture)
            "#EXTM3U\n#EXTINF:-1 group-title=\"央视,CCTV1\nhttp://a.com/1.m3u8\n",
            // header 花括号未闭合 → try_parse_json_object 失败 → 忽略
            "header={\"X\":\"1\n#EXTM3U\n#EXTINF:-1,g\nhttp://a.com/1.m3u8\n",
            "#EXTHTTP:{\"X\":\"1\n#EXTM3U\n#EXTINF:-1,g\nhttp://a.com/1.m3u8\n",
            // url|参数部分 & = 不闭合
            "#EXTM3U\n#EXTINF:-1,g\nhttp://a.com/1.m3u8|User-Agent=\n",
            "#EXTM3U\n#EXTINF:-1,g\nhttp://a.com/1.m3u8|&&&===\n",
            // txt:#genre# 后无逗号、逗号后无内容、URL 的 # 分隔符悬挂
            "组1\nCCTV1,\nCCTV2,http://a.com/1.m3u8#\n",
        ];
        for src in cases {
            let out = parse_to_json(src);
            assert!(serde_json::from_str::<Value>(&out).is_ok(), "输出必须是合法 JSON 数组: {:?}", src);
            let simple = parse_simple(src);
            assert!(serde_json::from_str::<Value>(&simple).is_ok(), "折叠输出必须是合法 JSON: {:?}", src);
        }
    }

    /// parse_simple 与 Kotlin TxtSubscribe.parse 对齐:空频道分组不写入结果。
    #[test]
    fn parse_simple_skips_empty_groups() {
        let src = "空组,#genre#\n有频道,#genre#\nCCTV1,http://a.com/1.m3u8\n";
        let v: Value = serde_json::from_str(&parse_simple(src)).unwrap();
        assert!(v.get("空组").is_none(), "无频道的分组不应出现(Kotlin channelMap.isNotEmpty())");
        assert!(v.get("有频道").is_some());
    }

    /// 10 万行量级性能冒烟(#[ignore]:大输入,常规 CI 不跑)。
    #[test]
    #[ignore = "大输入性能冒烟:10 万行直播源"]
    fn robust_100k_lines_smoke() {
        let mut src = String::with_capacity(4 * 1024 * 1024);
        src.push_str("央视,#genre#\n");
        for i in 0..100_000 {
            src.push_str(&format!("CCTV{},http://a.com/path/seg{}.m3u8\n", i % 64, i));
        }
        let start = std::time::Instant::now();
        let simple = parse_simple(&src);
        let full = parse_to_json(&src);
        let elapsed = start.elapsed();
        let v: Value = serde_json::from_str(&simple).unwrap();
        let total: usize = v.as_object().unwrap().values()
            .map(|c| c.as_object().unwrap().values().map(|a| a.as_array().unwrap().len()).sum::<usize>())
            .sum();
        assert_eq!(total, 100_000, "折叠形态 URL 总数");
        let arr: Value = serde_json::from_str(&full).unwrap();
        assert_eq!(arr.as_array().unwrap().len(), 1, "完整形态仅一个分组");
        assert_eq!(arr.as_array().unwrap()[0]["channels"].as_array().unwrap().len(), 64, "频道按名去重 64 个");
        assert!(elapsed.as_secs() < 30, "10 万行解析耗时 {:?} 异常", elapsed);
    }
}
