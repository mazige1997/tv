//! M3U8 去广告引擎,完整移植自 `M3u8.kt`(播放期 CPU 热点下沉)。
//!
//! 语义基准为 Kotlin 版 `com.github.tvbox.kt.util.M3u8`(逐行对齐):
//! - [`purify`]        入口:totalSegments 安全检查 / BOM 剥离 / #EXTM3U 校验 / isPlayableMediaPlaylist 回退
//! - [`remove_minority_url`] 前缀统计 → 0.8 阈值 → 域名过滤回退 → `timesNoAd = 15`
//! - [`get`] / [`clean`] / [`scan_ad`] / [`scan`]  广告正则清洗(hosts regex 由 Kotlin 侧解析后传入)
//! - [`clean_common_ad_markers`]  CUE-OUT / CUE-IN / SCTE35 信号 / 广告域名关键词 / 广告 URI 特征
//! - [`clean_discontinuity_groups`] / [`build_discontinuity_groups`] / [`find_main_group`] / [`should_drop_group`]
//! - [`clean_decimal_precision_groups`] / [`clean_frame_rate_groups`]  小数精度与帧率特征(Java BigDecimal 定点复刻)
//! - [`normalize_media_playlist`] / [`is_playable_media_playlist`] / [`keep_vod_end_list`] / [`resolve_uri_line`]
//!
//! 与 Kotlin 版的三处刻意差异(均为改进,不改变可观察清洗行为):
//! 1. Kotlin 的 `currentAdCount` 是全局可变状态,本实现改为 [`PurifyResult::removed_ads`] 返回值;
//! 2. `VideoParseRuler.getHostsRegex()` 的 host→正则映射以参数传入([`resolve_host_regex`]),
//!    Rust 侧不回调 Kotlin;Java HashMap 的迭代顺序本就不确定,Rust 按插入顺序取首个包含命中的 host;
//! 3. 所有 `LOG.i/LOG.e` 不打印,保持纯函数(无副作用、无全局状态、零 GC 停顿)。
//!
//! 关键复刻点:
//! - 帧率特征集:Java `BigDecimal.divide(..., 10, HALF_UP).remainder(ONE).setScale(3..6, HALF_UP)
//!   .stripTrailingZeros()` 以 i128 定点数逐位复刻(含 30/24fps 的 NTSC 1.001 变体);
//!   `HashSet<BigDecimal>` 的 equals(scale 敏感)以 `(unscaled, scale)` 二元组精确对应;
//! - URL 相对地址解析:ExoPlayer `UriUtil.resolve`(getUriIndices + removeDotSegments)逐分支移植;
//! - `#EXT-X-DISCONTINUITY[\s\S]*?(?=#EXT-X-DISCONTINUITY|$)` 分组:按字面量切分,
//!   末组终点为 Java `$` 语义(输入末尾换行符之前)。

use std::borrow::Cow;
use std::collections::{HashMap, HashSet};
use std::sync::OnceLock;

use regex::Regex;
use serde_json::json;

// ---------------------------------------------------------------------------
// 常量(与 M3u8.kt 一致)
// ---------------------------------------------------------------------------

pub const TAG_DISCONTINUITY: &str = "#EXT-X-DISCONTINUITY";
pub const TAG_MEDIA_DURATION: &str = "#EXTINF";
pub const TAG_ENDLIST: &str = "#EXT-X-ENDLIST";
pub const TAG_KEY: &str = "#EXT-X-KEY";
pub const TAG_MAP: &str = "#EXT-X-MAP";
pub const TAG_CUE_OUT: &str = "#EXT-X-CUE-OUT";
pub const TAG_CUE_IN: &str = "#EXT-X-CUE-IN";
pub const TAG_DATERANGE: &str = "#EXT-X-DATERANGE";

const MAX_FRAME_RATE_AD_BLOCK_SIZE: u32 = 12;
const TIMES_NO_AD: u32 = 15;

/// 广告域名特征(常见广告 CDN),与 `AD_DOMAIN_KEYWORDS` 一致。
const AD_DOMAIN_KEYWORDS: [&str; 10] = [
    "adservice",
    "adserver",
    "adsystem",
    "doubleclick",
    "googlesyndication",
    "advertising",
    "2mdn.net",
    "moatads",
    "scorecardresearch",
    "quantserve",
];

/// 广告片段 URI 特征,与 `REGEX_AD_SEGMENT_URI` 一致。
const REGEX_AD_SEGMENT_URI_SRC: &str = r"(?i)(^|[/?&=_.-])(ads?|adv|advert(ise(ment)?)?|commercial|preroll|pre-roll|midroll|mid-roll|postroll|post-roll|sponsor|scte|vast|vmap|interstitial|bumper)([/?&=_.-]|$)";
/// `URI="..."` 属性捕获,与 `REGEX_URI` 一致(非贪婪)。
const REGEX_URI_SRC: &str = r#"URI="(.+?)""#;
/// 时长捕获,与 `REGEX_MEDIA_DURATION` 一致;`(?-u:\b)` 等价 Java 默认 ASCII 词边界。
const REGEX_MEDIA_DURATION_SRC: &str = "#EXTINF:([0-9.]+)(?-u:\\b)";

fn ad_segment_uri_regex() -> &'static Regex {
    static R: OnceLock<Regex> = OnceLock::new();
    R.get_or_init(|| Regex::new(REGEX_AD_SEGMENT_URI_SRC).expect("static regex"))
}

fn uri_attr_regex() -> &'static Regex {
    static R: OnceLock<Regex> = OnceLock::new();
    R.get_or_init(|| Regex::new(REGEX_URI_SRC).expect("static regex"))
}

fn media_duration_regex() -> &'static Regex {
    static R: OnceLock<Regex> = OnceLock::new();
    R.get_or_init(|| Regex::new(REGEX_MEDIA_DURATION_SRC).expect("static regex"))
}

/// 编译用户传入的 Java 正则;`regex` crate 不支持的语法(如环视)返回 None 并跳过。
/// (Kotlin 侧 `Pattern.compile` 遇到非法语法会直接抛异常,Rust 侧选择安全降级。)
fn compile_java_regex(pattern: &str) -> Option<Regex> {
    Regex::new(pattern).ok()
}

// ---------------------------------------------------------------------------
// Java BigDecimal 定点复刻(i128 unscaled + i32 scale)
// ---------------------------------------------------------------------------

/// Java `BigDecimal` 的最小复刻,仅覆盖 M3u8 用到的操作:
/// 字符串构造 / stripTrailingZeros / remainder(ONE) / setScale(HALF_UP) / add / toString。
/// 值相等性(HashSet contains)与 `BigDecimal.equals` 一致:unscaled 与 scale 都相等。
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub(crate) struct Dec {
    unscaled: i128,
    scale: i32,
}

impl Dec {
    fn zero() -> Self {
        Dec { unscaled: 0, scale: 0 }
    }

    fn from_i64(v: i64) -> Self {
        Dec { unscaled: v as i128, scale: 0 }
    }

    /// `compareTo(BigDecimal.ZERO)`:仅比数值,不比 scale。
    fn compare_zero(&self) -> i32 {
        match self.unscaled {
            0 => 0,
            x if x > 0 => 1,
            _ => -1,
        }
    }

    /// 等价 `new BigDecimal(String)`(JDK 8+,接受 `5.` / `.5` / 指数)。
    fn from_str_java(s: &str) -> Option<Self> {
        let b = s.as_bytes();
        let mut i = 0usize;
        let mut neg = false;
        if i < b.len() && (b[i] == b'+' || b[i] == b'-') {
            neg = b[i] == b'-';
            i += 1;
        }
        let sig_start = i;
        let mut int_digits = 0usize;
        while i < b.len() && b[i].is_ascii_digit() {
            i += 1;
            int_digits += 1;
        }
        let mut frac_digits = 0i64;
        if i < b.len() && b[i] == b'.' {
            i += 1;
            let fs = i;
            while i < b.len() && b[i].is_ascii_digit() {
                i += 1;
            }
            frac_digits = (i - fs) as i64;
        }
        // 有效数字至少一位(整数或小数部分)
        if int_digits == 0 && frac_digits == 0 {
            return None;
        }
        let sig_end = i;
        let mut exp: i64 = 0;
        if i < b.len() && (b[i] == b'e' || b[i] == b'E') {
            i += 1;
            let mut eneg = false;
            if i < b.len() && (b[i] == b'+' || b[i] == b'-') {
                eneg = b[i] == b'-';
                i += 1;
            }
            let es = i;
            while i < b.len() && b[i].is_ascii_digit() {
                i += 1;
            }
            if i == es {
                return None;
            }
            let v: i64 = s[es..i].parse().ok()?;
            exp = if eneg { -v } else { v };
        }
        if i != b.len() {
            return None;
        }
        // 极端指数(如 1e-9223372036854775807)下 `frac_digits - exp` 会 i64 溢出:
        // debug 构建直接 panic,release 回绕成错误 scale。checked_sub 统一降级为 None。
        let scale64 = frac_digits.checked_sub(exp)?;
        if scale64 < i32::MIN as i64 || scale64 > i32::MAX as i64 {
            return None;
        }
        let mut unscaled: i128 = 0;
        for &c in &b[sig_start..sig_end] {
            if c.is_ascii_digit() {
                unscaled = unscaled.checked_mul(10)?.checked_add((c - b'0') as i128)?;
            }
        }
        if neg && unscaled != 0 {
            unscaled = -unscaled;
        }
        Some(Dec { unscaled, scale: scale64 as i32 })
    }

    /// 等价 `stripTrailingZeros`(JDK 8+):零归一到 scale 0;非零可穿过 0 产生负 scale(如 1E+2)。
    fn strip_trailing_zeros(mut self) -> Self {
        if self.unscaled == 0 {
            self.scale = 0;
            return self;
        }
        while self.unscaled % 10 == 0 {
            self.unscaled /= 10;
            // scale 逼近 i32::MIN 时继续自减会溢出(Java BigDecimal 在此抛
            // ArithmeticException);这里安全截断,保持"零 panic"契约。
            match self.scale.checked_sub(1) {
                Some(s) => self.scale = s,
                None => break,
            }
        }
        self
    }

    /// 等价 `remainder(BigDecimal.ONE)`:结果 scale 保持 this.scale,符号随被除数(截断取整)。
    fn remainder_one(self) -> Self {
        if self.scale <= 0 {
            // 值本身是整数(或负 scale):余数为 0,保留 scale
            return Dec { unscaled: 0, scale: self.scale };
        }
        if self.scale >= 39 {
            // |value| < 1,余数即自身
            return self;
        }
        let p = 10i128.pow(self.scale as u32);
        let q = self.unscaled / p; // Rust 整除向零截断,与 Java divideToIntegralValue 一致
        Dec { unscaled: self.unscaled - q * p, scale: self.scale }
    }

    /// 等价 `setScale(scale, ROUND_HALF_UP)`(半away-from-zero)。
    fn set_scale_half_up(self, new_scale: u32) -> Self {
        let ns = new_scale as i32;
        if ns >= self.scale {
            let grow = (ns - self.scale) as u32;
            let factor = 10i128.pow(grow.min(38));
            Dec { unscaled: self.unscaled.saturating_mul(factor), scale: ns }
        } else {
            let shrink = (self.scale - ns) as u32;
            let d = 10i128.pow(shrink.min(38));
            let mut q = self.unscaled / d;
            let r = self.unscaled % d;
            if 2 * r.abs() >= d {
                q += if self.unscaled >= 0 { 1 } else { -1 };
            }
            Dec { unscaled: q, scale: ns }
        }
    }

    /// `divide(divisor, 10, ROUND_HALF_UP)`,仅用于帧率特征生成(操作数均为正)。
    fn divide_scale10_half_up(&self, divisor: &Dec) -> Option<Dec> {
        // this = a·10^-sa, div = b·10^-sb,结果 scale 10:
        // unscaled_res = round_half_up(a · 10^(10 - sa + sb) / b)
        let shift = 10i64 - self.scale as i64 + divisor.scale as i64;
        if !(0..=38).contains(&shift) {
            return None;
        }
        let numerator = self.unscaled.checked_mul(10i128.pow(shift as u32))?;
        let denominator = divisor.unscaled;
        if denominator == 0 {
            return None;
        }
        let mut q = numerator / denominator;
        let r = numerator % denominator;
        if 2 * r.abs() >= denominator.abs() {
            q += if (numerator >= 0) == (denominator >= 0) { 1 } else { -1 };
        }
        Some(Dec { unscaled: q, scale: 10 })
    }

    /// 等价 `add`(结果 scale = max(s1, s2))。
    fn add(self, other: &Dec) -> Self {
        let s = self.scale.max(other.scale);
        let a = if s > self.scale {
            self.unscaled.saturating_mul(10i128.pow((s - self.scale).min(38) as u32))
        } else {
            self.unscaled
        };
        let b = if s > other.scale {
            other.unscaled.saturating_mul(10i128.pow((s - other.scale).min(38) as u32))
        } else {
            other.unscaled
        };
        Dec { unscaled: a.saturating_add(b), scale: s }
    }

    /// 等价 `doubleValue`。
    fn to_f64(self) -> f64 {
        if self.scale >= 0 {
            self.unscaled as f64 / 10f64.powi(self.scale)
        } else {
            self.unscaled as f64 * 10f64.powi(-self.scale)
        }
    }

    /// 等价 `BigDecimal.toString()`(plain / scientific 规则,含 adjusted exponent < -6 转 E 记法)。
    fn to_string_java(self) -> String {
        if self.scale == 0 {
            return self.unscaled.to_string();
        }
        let neg = self.unscaled < 0;
        let digits = self.unscaled.unsigned_abs().to_string();
        let precision = digits.len() as i64;
        let scale = self.scale as i64;
        let adjusted = precision - scale - 1;
        let plain = scale > 0 && adjusted >= -6;
        if plain {
            let frac = scale as usize;
            let mut s = String::new();
            if neg {
                s.push('-');
            }
            if frac >= digits.len() {
                s.push_str("0.");
                for _ in 0..(frac - digits.len()) {
                    s.push('0');
                }
                s.push_str(&digits);
            } else {
                let split = digits.len() - frac;
                s.push_str(&digits[..split]);
                s.push('.');
                s.push_str(&digits[split..]);
            }
            s
        } else {
            // 科学计数法:指数必带符号(+ / -)
            let exp_sign = if adjusted >= 0 { "+" } else { "" };
            let mantissa = if digits.len() == 1 {
                digits.clone()
            } else {
                format!("{}.{}", &digits[..1], &digits[1..])
            };
            format!("{}{}E{}{}", if neg { "-" } else { "" }, mantissa, exp_sign, adjusted)
        }
    }
}

/// 等价 `BigDecimal.valueOf(double)` = `new BigDecimal(Double.toString(d))`。
/// 仅服务于帧率基率(30/25/24 与 /1.001 变体),覆盖 Java Double.toString 的
/// 小数区规则(整数必带 `.0`;非整数用最短往返表示,与 Rust `{}` 一致)。
fn java_double_to_string(v: f64) -> String {
    if v == v.trunc() && v.abs() < 1e7 {
        return format!("{:.1}", v);
    }
    let abs = v.abs();
    if abs >= 1e7 || abs < 1e-3 {
        // 本模块取值不会命中;防御性实现:借最短表示拆指数
        let s = format!("{:e}", v); // 如 "2.9970029970029973e1"
        let (mantissa, exp) = s.split_once('e').unwrap_or((s.as_str(), "0"));
        let exp: i32 = exp.parse().unwrap_or(0);
        let sign = if exp >= 0 { "+" } else { "" };
        return format!("{}E{}{}", mantissa, sign, exp);
    }
    format!("{}", v)
}

// ---------------------------------------------------------------------------
// 帧率特征集(30 / 25 / 24 fps + NTSC 1.001 变体)
// ---------------------------------------------------------------------------

#[derive(Debug)]
struct FrameRateFeatures {
    r30: HashSet<(i128, i32)>,
    r25: HashSet<(i128, i32)>,
    r24: HashSet<(i128, i32)>,
}

impl FrameRateFeatures {
    fn get(&self, rate: i32) -> Option<&HashSet<(i128, i32)>> {
        match rate {
            30 => Some(&self.r30),
            25 => Some(&self.r25),
            24 => Some(&self.r24),
            _ => None,
        }
    }
}

/// 等价 `prepareFrameRateFeatures()`。
fn frame_rate_features() -> &'static FrameRateFeatures {
    static F: OnceLock<FrameRateFeatures> = OnceLock::new();
    F.get_or_init(|| {
        let mut f = FrameRateFeatures { r30: HashSet::new(), r25: HashSet::new(), r24: HashSet::new() };
        // createFrameRateFeatures(30, true) / (25, false) / (24, true)
        add_frame_rate_features(&mut f.r30, &java_double_to_string(30.0), 30);
        add_frame_rate_features(&mut f.r30, &java_double_to_string(30.0 / 1.001), 30 * 10);
        add_frame_rate_features(&mut f.r25, &java_double_to_string(25.0), 25);
        add_frame_rate_features(&mut f.r24, &java_double_to_string(24.0), 24);
        add_frame_rate_features(&mut f.r24, &java_double_to_string(24.0 / 1.001), 24 * 10);
        f
    })
}

/// 等价 `addFrameRateFeatures(features, frameRate, maxFrames)`:
/// `BigDecimal(frame).divide(rate, 10, HALF_UP).remainder(ONE)` 后按 scale 3..6 取整、去尾零入集。
fn add_frame_rate_features(features: &mut HashSet<(i128, i32)>, rate_str: &str, max_frames: i64) {
    let rate = match Dec::from_str_java(rate_str) {
        Some(r) => r,
        None => return,
    };
    for frame in 1..=max_frames {
        let divided = match Dec::from_i64(frame).divide_scale10_half_up(&rate) {
            Some(d) => d,
            None => continue,
        };
        let fraction = divided.remainder_one();
        for scale in 3..=6u32 {
            let value = fraction.set_scale_half_up(scale).strip_trailing_zeros();
            if value.compare_zero() != 0 {
                features.insert((value.unscaled, value.scale));
            }
        }
    }
}

/// 等价 `isFrameAligned(duration, frameRate)`:小数部分按 (unscaled, scale) 精确匹配。
fn is_frame_aligned(duration: &Dec, frame_rate: i32) -> bool {
    let features = match frame_rate_features().get(frame_rate) {
        Some(f) => f,
        None => return false,
    };
    let fraction = duration.remainder_one().abs().strip_trailing_zeros();
    features.contains(&(fraction.unscaled, fraction.scale))
}

impl Dec {
    fn abs(self) -> Self {
        Dec { unscaled: self.unscaled.abs(), scale: self.scale }
    }
}

/// 等价 `getExclusiveFrameRate`:仅当恰好属于某一个帧率集合时返回该帧率。
fn get_exclusive_frame_rate(duration: &Dec) -> i32 {
    let is30 = is_frame_aligned(duration, 30);
    let is25 = is_frame_aligned(duration, 25);
    let is24 = is_frame_aligned(duration, 24);
    if is30 && !is25 && !is24 {
        return 30;
    }
    if is25 && !is30 && !is24 {
        return 25;
    }
    if is24 && !is30 && !is25 {
        return 24;
    }
    0
}

// ---------------------------------------------------------------------------
// ExoPlayer UriUtil.resolve 移植(toAbsoluteUrl 依赖)
// ---------------------------------------------------------------------------

const IDX_SCHEME_COLON: usize = 0;
const IDX_PATH: usize = 1;
const IDX_QUERY: usize = 2;
const IDX_FRAGMENT: usize = 3;

type UriIndices = [isize; 4];

/// 等价 ExoPlayer `UriUtil.getUriIndices`(r2.18,纯字符串结构解析)。
fn get_uri_indices(s: &str) -> UriIndices {
    let b = s.as_bytes();
    if b.is_empty() {
        return [-1, 0, 0, 0];
    }
    let len = b.len() as isize;
    let fragment = memchr(b'#', b).map(|i| i as isize).unwrap_or(len);
    let mut query = memchr(b'?', b).map(|i| i as isize).unwrap_or(len);
    if query > fragment {
        // '#' 在 '?' 之前:'?' 属于 fragment
        query = fragment;
    }
    // 第一个 '/' 之后的 ':' 属于 hier-part,不是 scheme 分隔符
    let mut scheme_limit = memchr(b'/', b).map(|i| i as isize).unwrap_or(len);
    if scheme_limit > query {
        scheme_limit = query;
    }
    let mut scheme = memchr(b':', b).map(|i| i as isize).unwrap_or(-1);
    if scheme > scheme_limit {
        scheme = -1;
    }
    let has_authority = scheme + 2 < query
        && b[(scheme + 1) as usize] == b'/'
        && b[(scheme + 2) as usize] == b'/';
    let path = if has_authority {
        let mut p = memchr_from(b'/', b, (scheme + 3) as usize)
            .map(|i| i as isize)
            .unwrap_or(-1);
        if p == -1 || p > query {
            p = query;
        }
        p
    } else {
        scheme + 1
    };
    [scheme, path, query, fragment]
}

/// 等价 ExoPlayer `UriUtil.resolve`:RFC-3986 风格的相对地址解析。
pub fn resolve_uri(base: &str, reference: &str) -> String {
    let ref_idx = get_uri_indices(reference);
    if ref_idx[IDX_SCHEME_COLON] != -1 {
        // 引用是绝对地址:目标即引用(仅做点段清理)
        let uri = reference.to_string();
        return remove_dot_segments(uri, ref_idx[IDX_PATH], ref_idx[IDX_QUERY]);
    }
    let base_idx = get_uri_indices(base);
    if ref_idx[IDX_FRAGMENT] == 0 {
        // 引用为空或仅含 fragment:目标 = base 去掉 fragment + 引用
        return format!("{}{}", &base[..base_idx[IDX_FRAGMENT] as usize], reference);
    }
    if ref_idx[IDX_QUERY] == 0 {
        // 引用以 query 开始:目标 = base 去掉 query/fragment + 引用
        return format!("{}{}", &base[..base_idx[IDX_QUERY] as usize], reference);
    }
    if ref_idx[IDX_PATH] != 0 {
        // 引用含 authority:目标 = base scheme + 引用
        let base_limit = base_idx[IDX_SCHEME_COLON] + 1;
        let uri = format!("{}{}", &base[..base_limit as usize], reference);
        return remove_dot_segments(uri, base_limit + ref_idx[IDX_PATH], base_limit + ref_idx[IDX_QUERY]);
    }
    let rb = reference.as_bytes();
    if rb[ref_idx[IDX_PATH] as usize] == b'/' {
        // 引用路径以 '/' 开头:目标 = base scheme+authority + 引用
        let uri = format!("{}{}", &base[..base_idx[IDX_PATH] as usize], reference);
        return remove_dot_segments(uri, base_idx[IDX_PATH], base_idx[IDX_PATH] + ref_idx[IDX_QUERY]);
    }
    if base_idx[IDX_SCHEME_COLON] + 2 < base_idx[IDX_PATH] && base_idx[IDX_PATH] == base_idx[IDX_QUERY] {
        // base hier-part 仅 authority、路径为空:补一个 '/'
        let uri = format!("{}/{}", &base[..base_idx[IDX_PATH] as usize], reference);
        return remove_dot_segments(uri, base_idx[IDX_PATH], base_idx[IDX_PATH] + ref_idx[IDX_QUERY] + 1);
    }
    // 目标 = base 去掉最后一段 + 引用
    let last_slash = base[..base_idx[IDX_QUERY] as usize]
        .rfind('/')
        .map(|i| i as isize)
        .unwrap_or(-1);
    let base_limit = if last_slash == -1 { base_idx[IDX_PATH] } else { last_slash + 1 };
    let uri = format!("{}{}", &base[..base_limit as usize], reference);
    remove_dot_segments(uri, base_idx[IDX_PATH], base_limit + ref_idx[IDX_QUERY])
}

/// 等价 ExoPlayer `UriUtil.removeDotSegments`(StringBuilder 原地删除;索引均为 ASCII 字符边界)。
fn remove_dot_segments(uri: String, offset: isize, mut limit: isize) -> String {
    let mut b = uri.into_bytes();
    if offset < limit {
        let mut offset = offset as usize;
        if b[offset] == b'/' {
            offset += 1;
        }
        let mut segment_start = offset;
        let mut i = offset;
        while (i as isize) <= limit {
            
            let next_segment_start = if (i as isize) == limit {
                i
            } else if b[i] == b'/' {
                i + 1
            } else {
                i += 1;
                continue;
            };
            if i == segment_start + 1 && b[segment_start] == b'.' {
                // "./" → 删除
                b.drain(segment_start..next_segment_start);
                limit -= (next_segment_start - segment_start) as isize;
                i = segment_start;
            } else if i == segment_start + 2
                && b[segment_start] == b'.'
                && b[segment_start + 1] == b'.'
            {
                // "../" → 连同前一段一起删除
                let prev = byte_rfind_le(&b, b'/', segment_start as isize - 2)
                    .map(|p| p as isize + 1)
                    .unwrap_or(0);
                let remove_from = if prev > offset as isize { prev } else { offset as isize } as usize;
                b.drain(remove_from..next_segment_start);
                limit -= (next_segment_start - remove_from) as isize;
                segment_start = prev as usize;
                i = prev as usize;
            } else {
                i += 1;
                segment_start = i;
            }
        }
    }
    String::from_utf8(b).unwrap_or_else(|e| String::from_utf8_lossy(e.as_bytes()).into_owned())
}

fn memchr(c: u8, b: &[u8]) -> Option<usize> {
    b.iter().position(|&x| x == c)
}

fn memchr_from(c: u8, b: &[u8], from: usize) -> Option<usize> {
    if from >= b.len() {
        return None;
    }
    b[from..].iter().position(|&x| x == c).map(|i| i + from)
}

/// 从 `from`(含)向前找最大的 `c` 位置;等价 Java `lastIndexOf(c, from)`。
fn byte_rfind_le(b: &[u8], c: u8, from: isize) -> Option<usize> {
    if from < 0 {
        return None;
    }
    let start = (from as usize).min(b.len().saturating_sub(1));
    (0..=start).rev().find(|&i| b[i] == c)
}

// ---------------------------------------------------------------------------
// 行级辅助(与 M3u8.kt 私有函数一一对应)
// ---------------------------------------------------------------------------

/// 等价 `getExtInfValueStart`:跳过前导控制字符后匹配 `#EXTINF:`,返回时长起点。
fn get_ext_inf_value_start(line: &str) -> Option<usize> {
    let b = line.as_bytes();
    let len = b.len();
    let mut start = 0usize;
    while start < len && b[start] <= b' ' {
        start += 1;
    }
    if !line[start..].starts_with(TAG_MEDIA_DURATION) {
        return None;
    }
    start += TAG_MEDIA_DURATION.len();
    if start >= len || b[start] != b':' {
        return None;
    }
    start += 1;
    while start < len && b[start] <= b' ' {
        start += 1;
    }
    if start < len {
        Some(start)
    } else {
        None
    }
}

/// 等价 `getExtInfValueEnd`:首个 ','(或行尾),再去掉尾随控制字符。
fn get_ext_inf_value_end(line: &str, start: usize) -> usize {
    let mut end = line[start..]
        .find(',')
        .map(|i| start + i)
        .unwrap_or(line.len());
    let b = line.as_bytes();
    while end > start && b[end - 1] <= b' ' {
        end -= 1;
    }
    end
}

/// 等价 `parseExtInfDuration`:`BigDecimal(sub).stripTrailingZeros()`,失败回退 ZERO。
fn parse_ext_inf_duration(line: &str) -> Dec {
    match get_ext_inf_value_start(line) {
        Some(s) => {
            let e = get_ext_inf_value_end(line, s);
            match Dec::from_str_java(&line[s..e]) {
                Some(d) => d.strip_trailing_zeros(),
                None => Dec::zero(),
            }
        }
        None => Dec::zero(),
    }
}

/// 等价 `getDecimalPrecision`:`#EXTINF` 时长小数位数;非时长行返回 -1,无小数点返回 0。
fn get_decimal_precision(line: &str) -> i32 {
    let start = match get_ext_inf_value_start(line) {
        Some(s) => s,
        None => return -1,
    };
    let end = get_ext_inf_value_end(line, start);
    let dot = line[start..].find('.').map(|i| start + i);
    match dot {
        Some(d) if d < end => (end - d - 1) as i32,
        _ => 0,
    }
}

/// Java `Double.parseDouble`:两端 trim、可选 f/F/d/D 后缀;失败返回 None。
/// (与 Java 的差异:十六进制浮点 `0x1p3` 不支持,m3u8 时长场景不涉及。)
fn java_parse_double(s: &str) -> Option<f64> {
    let t = s.trim();
    if t.is_empty() {
        return None;
    }
    let t = match t.chars().last() {
        Some('f') | Some('F') | Some('d') | Some('D') => &t[..t.len() - 1],
        _ => t,
    };
    t.parse::<f64>().ok()
}

/// 等价 `isDouble(ad)`:可解析为非 0 的 double。
fn is_double(ad: &str) -> bool {
    matches!(java_parse_double(ad), Some(v) if v != 0.0)
}

/// 等价 `hostOf`:要求 http(s) 前缀,返回 authority。
fn host_of(url: &str) -> String {
    if !url.starts_with("http://") && !url.starts_with("https://") {
        return String::new();
    }
    let start = match url.find("://") {
        Some(i) => i + 3,
        None => return String::new(),
    };
    let b = url.as_bytes();
    let mut end = url.len();
    for (i, &c) in b.iter().enumerate().skip(start) {
        if c == b'/' {
            end = i;
            break;
        }
    }
    if end > start {
        url[start..end].to_string()
    } else {
        url[start..].to_string()
    }
}

/// 等价 `pathPrefixOf`:去 query 后取到最后一个 '/'(含)。
fn path_prefix_of(url: &str) -> String {
    let mut clean = url;
    if let Some(q) = clean.find('?') {
        clean = &clean[..q];
    }
    match clean.rfind('/') {
        Some(slash) if slash > 0 => clean[..slash + 1].to_string(),
        _ => String::new(),
    }
}

/// Kotlin `indexOf('/', fromIndex)` 按字符索引;返回字节偏移(ASCII 前缀下两者一致)。
fn index_of_char_from(s: &str, needle: char, from: usize) -> Option<usize> {
    for (char_idx, (bi, ch)) in s.char_indices().enumerate() {
        if char_idx >= from && ch == needle {
            return Some(bi);
        }
    }
    None
}

/// 等价 `toAbsoluteUrl`:http(s) 开头原样返回,其余走 ExoPlayer `UriUtil.resolve`。
fn to_absolute_url(base: &str, url: &str) -> String {
    let line = url.trim();
    if line.is_empty() || line.starts_with("http://") || line.starts_with("https://") {
        return line.to_string();
    }
    resolve_uri(base, line)
}

/// 等价 `shouldResolve`(resolveContent 用):相对地址行或 KEY/MAP 行需要解析。
fn should_resolve(line: &str) -> bool {
    let item = line.trim();
    if item.is_empty() {
        return false;
    }
    (!item.starts_with('#') && !item.starts_with("http")) || has_uri_attribute(item)
}

/// 等价 `resolve(base, line)`。
fn resolve_line(base: &str, line: &str) -> String {
    if has_uri_attribute(line) {
        resolve_uri_line(base, line)
    } else {
        resolve_uri(base, line)
    }
}

/// 等价 `hasUriAttribute`。
fn has_uri_attribute(line: &str) -> bool {
    line.starts_with(TAG_KEY) || line.starts_with(TAG_MAP)
}

/// 等价 `resolveUriLine`:解析 `URI="..."` 属性值为绝对地址(替换全部出现)。
pub fn resolve_uri_line(base: &str, line: &str) -> String {
    if let Some(caps) = uri_attr_regex().captures(line) {
        if let Some(v) = caps.get(1) {
            let value = v.as_str();
            let resolved = resolve_uri(base, value);
            return line.replace(value, &resolved);
        }
    }
    line.to_string()
}

/// 等价 `isMediaUriLine`。
fn is_media_uri_line(line: &str) -> bool {
    !line.is_empty() && !line.starts_with('#')
}

/// 等价 `isDiscontinuityTag`(排除 DISCONTINUITY-SEQUENCE)。
fn is_discontinuity_tag(line: &str) -> bool {
    line.starts_with(TAG_DISCONTINUITY) && !line.starts_with("#EXT-X-DISCONTINUITY-SEQUENCE")
}

/// 等价 `isAdBreakStart`。
fn is_ad_break_start(line: &str) -> bool {
    line.starts_with(TAG_CUE_OUT)
}

/// 等价 `isAdSignalTag`。
pub fn is_ad_signal_tag(line: &str) -> bool {
    if line.starts_with("#EXT-OATCLS-SCTE35") {
        return true;
    }
    if line.starts_with("#EXT-X-SCTE35") {
        return true;
    }
    if line.starts_with("#EXT-X-SPLICEPOINT-SCTE35") {
        return true;
    }
    if line.starts_with("#EXT-X-CUE") {
        return true;
    }
    if line.starts_with("#EXT-X-ASSET") {
        return true;
    }
    if line.starts_with("#EXT-X-VMAP-AD-BREAK") {
        return true;
    }
    if line.starts_with("#EXT-X-AD") {
        return true;
    }
    false
}

/// 等价 `isSegmentTag`(注意 DISCONTINUITY-SEQUENCE 排除)。
pub fn is_segment_tag(line: &str) -> bool {
    if line.starts_with("#EXT-X-DISCONTINUITY-SEQUENCE") {
        return false;
    }
    line.starts_with(TAG_MEDIA_DURATION)
        || line.starts_with("#EXT-X-BYTERANGE")
        || line.starts_with("#EXT-X-PROGRAM-DATE-TIME")
        || line.starts_with(TAG_DISCONTINUITY)
        || line.starts_with("#EXT-X-PART")
        || line.starts_with("#EXT-X-PRELOAD-HINT")
}

/// 等价 `isStandaloneAdTag`(DATERANGE 且含广告特征 / X-ASSET-*)。
pub fn is_standalone_ad_tag(line: &str) -> bool {
    if !line.starts_with(TAG_DATERANGE) {
        return false;
    }
    is_ad_like_text(line) || line.contains("X-ASSET-URI") || line.contains("X-ASSET-LIST")
}

/// ASCII 关键词的大小写不敏感子串匹配,零分配。
/// 关键词全为 ASCII;UTF-8 续字节 >= 0x80 不可能等于 ASCII 字母,不会误匹配字符内部。
fn contains_ascii_case_insensitive(haystack: &str, needle: &str) -> bool {
    let h = haystack.as_bytes();
    let n = needle.as_bytes();
    if n.is_empty() {
        return true;
    }
    n.len() <= h.len() && h.windows(n.len()).any(|w| w.eq_ignore_ascii_case(n))
}

/// 等价 `isAdLikeText`(旧实现每行 `to_lowercase()` 分配一个 String,10 万段即 10 万次分配)。
fn is_ad_like_text(line: &str) -> bool {
    [
        "scte", "cue", "interstitial", "vmap", "vast", "advert", "commercial", "ad-", "ad_",
        "ad.", "preroll", "midroll", "postroll", "bumper",
    ]
    .iter()
    .any(|k| contains_ascii_case_insensitive(line, k))
}

/// 等价 `isAdSegmentUri`。
pub fn is_ad_segment_uri(line: &str) -> bool {
    ad_segment_uri_regex().is_match(line)
}

/// 等价 `hasAdDomain`(零分配:不构造整串小写副本)。
pub fn has_ad_domain(url: &str) -> bool {
    AD_DOMAIN_KEYWORDS
        .iter()
        .any(|k| contains_ascii_case_insensitive(url, k))
}

/// 等价 `hasAdSignal`:pending 里是否已有广告信号行。
fn has_ad_signal(pending: &[String]) -> bool {
    pending
        .iter()
        .any(|l| is_ad_break_start(l.trim()) || is_ad_signal_tag(l.trim()))
}

/// 等价 `hasEndList`(`lines()` 原生处理 \r\n,零分配;旧实现每次复制整串)。
pub fn has_end_list(content: &str) -> bool {
    content.lines().any(|raw| raw.trim().starts_with(TAG_ENDLIST))
}

// ---------------------------------------------------------------------------
// Group(不连续组)
// ---------------------------------------------------------------------------

/// 等价 Kotlin `Group`:一个 DISCONTINUITY 块的行集合与统计特征。
#[derive(Clone, Debug, Default)]
pub struct Group {
    pub lines: Vec<String>,
    pub segment_count: u32,
    pub ad_like_count: u32,
    pub total_duration: f64,
    pub host: String,
    pub path_prefix: String,
}

impl Group {
    /// 等价 `Group.add(raw)`(行按原样保存,统计用 trim 后内容)。
    pub fn add(&mut self, raw: &str) {
        self.lines.push(raw.to_string());
        let line = raw.trim();
        if let Some(ds) = get_ext_inf_value_start(line) {
            let de = get_ext_inf_value_end(line, ds);
            if let Some(d) = java_parse_double(&line[ds..de]) {
                self.total_duration += d;
            }
        }
        if line.is_empty() || line.starts_with('#') {
            if is_ad_signal_tag(line) || is_standalone_ad_tag(line) {
                self.ad_like_count += 1;
            }
            return;
        }
        self.segment_count += 1;
        if is_ad_segment_uri(line) || has_ad_domain(line) {
            self.ad_like_count += 1;
        }
        if self.host.is_empty() {
            self.host = host_of(line);
        }
        if self.path_prefix.is_empty() {
            self.path_prefix = path_prefix_of(line);
        }
    }

    /// 等价 `hasMedia`。
    pub fn has_media(&self) -> bool {
        self.segment_count > 0
    }

    /// 等价 `appendTo`(每行后补 '\n')。
    pub fn append_to(&self, sb: &mut String) {
        for line in &self.lines {
            sb.push_str(line);
            sb.push('\n');
        }
    }

    /// 等价 `score`。
    pub fn score(&self) -> f64 {
        if self.total_duration > 0.0 {
            self.total_duration
        } else {
            self.segment_count as f64
        }
    }
}

/// 等价 `buildDiscontinuityGroups`。
pub fn build_discontinuity_groups(lines: &[&str]) -> Vec<Group> {
    let mut groups: Vec<Group> = Vec::new();
    let mut group = Group::default();
    for raw in lines {
        let line = raw.trim();
        if line.starts_with(TAG_DISCONTINUITY) && group.has_media() {
            groups.push(std::mem::take(&mut group));
        }
        group.add(raw);
    }
    if group.has_media() || !group.lines.is_empty() {
        groups.push(group);
    }
    groups
}

/// 等价 `findMainGroup`,返回得分最高的组下标(相同得分取先出现者)。
pub fn find_main_group(groups: &[Group]) -> Option<usize> {
    let mut main: Option<usize> = None;
    for (i, group) in groups.iter().enumerate() {
        if group.segment_count == 0 {
            continue;
        }
        let better = match main {
            None => true,
            Some(m) => group.score() > groups[m].score(),
        };
        if better {
            main = Some(i);
        }
    }
    main
}

/// 等价 `shouldDropGroup`(不含 `group === main` 判断——由调用方按下标跳过主组)。
pub fn should_drop_group(group: &Group, main: &Group) -> bool {
    if group.segment_count == 0 {
        return false;
    }
    let short_group = group.segment_count <= 2
        || (main.total_duration > 0.0
            && group.total_duration > 0.0
            && group.total_duration < main.total_duration * 0.18);
    let different_host = !main.host.is_empty() && !group.host.is_empty() && main.host != group.host;
    let different_path = !main.path_prefix.is_empty()
        && !group.path_prefix.is_empty()
        && main.path_prefix != group.path_prefix;
    let has_ad_feature = group.ad_like_count > 0
        || has_ad_domain(&group.host)
        || is_ad_segment_uri(&group.path_prefix);
    let ad_like = has_ad_feature || different_host || (group.segment_count <= 2 && different_path);
    short_group && ad_like
}

// ---------------------------------------------------------------------------
// 清洗管线
// ---------------------------------------------------------------------------

/// 等价 `M3u8.isAd(regex)`:配置里的规则属于广告正则还是嗅探正则。
pub fn is_ad(regex: &str) -> bool {
    regex.contains(TAG_DISCONTINUITY)
        || regex.contains(TAG_MEDIA_DURATION)
        || regex.contains(TAG_ENDLIST)
        || regex.contains(TAG_KEY)
        || regex.contains(TAG_CUE_OUT)
        || regex.contains(TAG_CUE_IN)
        || regex.contains(TAG_DATERANGE)
        || is_double(regex)
}

/// 等价 `getRegex(tsUrlPre)`:hosts→正则映射中第一个被 tsUrlPre 包含的 host 的正则列表。
/// (Java HashMap 迭代顺序不确定,Rust 按调用方传入顺序取首个命中,行为可复现。)
pub fn resolve_host_regex(ts_url_pre: &str, hosts_regex: &HashMap<String, Vec<String>>) -> Option<Vec<String>> {
    for (host, list) in hosts_regex {
        if !ts_url_pre.contains(host.as_str()) {
            continue;
        }
        return Some(list.clone());
    }
    None
}

/// 等价 `resolveContent`:\r\n 归一为 \n,并解析相对地址行 / KEY-MAP 属性行。
fn resolve_content(ts_url_pre: &str, m3u8_content: &str) -> String {
    let m3u8_content = m3u8_content.replace("\r\n", "\n");
    let mut sb = String::with_capacity(m3u8_content.len() + 32);
    for line in m3u8_content.split('\n') {
        if should_resolve(line) {
            sb.push_str(&resolve_line(ts_url_pre, line.trim()));
        } else {
            sb.push_str(line);
        }
        sb.push('\n');
    }
    sb
}

/// 把多段待删文本的所有出现位置合并成有序、不重叠的区间,再单次线性重建结果。
///
/// 旧实现对每个广告块执行一次 `line.replace(rem, "")`:k 个块 → O(k·N) 全串复制,
/// 数 MB playlist 配数百广告块时内存流量可达 GB 级。这里一次收集所有匹配区间,
/// 排序合并后一遍扫描构造结果,复杂度降为 O(N + k log k),并保持"删除全部出现"语义。
fn remove_ranges_once<'a>(line: &'a str, removals: &[String]) -> Cow<'a, str> {
    if removals.is_empty() {
        return Cow::Borrowed(line);
    }
    let mut ranges: Vec<(usize, usize)> = Vec::new();
    for rem in removals {
        if rem.is_empty() {
            continue;
        }
        // match_indices 产生的边界保证落在 UTF-8 char 边界,后续切片安全。
        for (start, _) in line.match_indices(rem.as_str()) {
            ranges.push((start, start + rem.len()));
        }
    }
    if ranges.is_empty() {
        return Cow::Borrowed(line);
    }
    ranges.sort_unstable_by_key(|r| r.0);
    let mut merged: Vec<(usize, usize)> = Vec::with_capacity(ranges.len());
    for range in ranges {
        if let Some(last) = merged.last_mut() {
            if range.0 <= last.1 {
                last.1 = last.1.max(range.1);
                continue;
            }
        }
        merged.push(range);
    }
    let mut out = String::with_capacity(line.len());
    let mut pos = 0usize;
    for (start, end) in merged {
        out.push_str(&line[pos..start]);
        pos = end;
    }
    out.push_str(&line[pos..]);
    Cow::Owned(out)
}

/// 是否为 Java 原版 DISCONTINUITY 环视规则。
/// `#EXT-X-DISCONTINUITY[\s\S]*?(?=#EXT-X-DISCONTINUITY|$)` 是 TVBox 远程规则中最常见的
/// 广告形态,但 `regex` crate 不支持环视而会编译失败。此类规则用字面量切分降级,
/// 否则引擎会静默跳过规则且不触发 Kotlin 回退,导致广告漏删。
fn is_java_discontinuity_rule(pattern: &str) -> bool {
    pattern.contains(TAG_DISCONTINUITY) && pattern.contains("(?=")
}

/// 收集正则规则命中的广告块(供 clean / scan_ad 共用)。
fn collect_regex_removals(line: &str, tag_ad: &str, ad_count: &mut u32, out: &mut Vec<String>) {
    let pattern = match compile_java_regex(tag_ad) {
        Some(p) => p,
        None => {
            if is_java_discontinuity_rule(tag_ad) {
                for (start, end) in discontinuity_chunks(line) {
                    let group = &line[start..end];
                    out.push(group.replace(TAG_ENDLIST, ""));
                    *ad_count += media_duration_regex().find_iter(group).count() as u32;
                }
            }
            return;
        }
    };
    for m in pattern.find_iter(line) {
        let group = m.as_str();
        out.push(group.replace(TAG_ENDLIST, ""));
        *ad_count += media_duration_regex().find_iter(group).count() as u32;
    }
}

/// 收集 scan 模式要移除的 DISCONTINUITY 组(供 clean / scan 共用)。
fn collect_scan_removals(line: &str, ads: &[String], ad_count: &mut u32, out: &mut Vec<String>) {
    for (start, end) in discontinuity_chunks(line) {
        let group = &line[start..end];
        let group_cleaned = group.replace(TAG_ENDLIST, "");
        let mut ft = Dec::zero();
        let mut lt = Dec::zero();
        let mut t = Dec::zero();
        let mut t_count = 0u32;
        for cap in media_duration_regex().captures_iter(group) {
            // Java:解析失败会抛 NumberFormatException;此处按 0 处理(防御性)
            let parsed = Dec::from_str_java(&cap[1]).unwrap_or_else(Dec::zero);
            if ft.compare_zero() == 0 {
                ft = parsed;
            }
            lt = parsed;
            t = t.add(&lt);
            t_count += 1;
        }
        let ft_str = ft.to_string_java();
        let lt_str = lt.to_string_java();
        let t_str = t.to_string_java();
        'outer: for ad in ads {
            if let Some(ad_clean) = ad.strip_prefix('-') {
                // 匹配组内最后一段时长
                if lt_str.starts_with(ad_clean) {
                    out.push(group_cleaned.clone());
                    *ad_count += t_count;
                    break 'outer;
                }
            } else {
                // 匹配首段时长或广告总时长
                if ft_str.starts_with(ad.as_str()) || t_str.starts_with(ad.as_str()) {
                    out.push(group_cleaned.clone());
                    *ad_count += t_count;
                    break 'outer;
                }
            }
        }
    }
}

/// 等价 `clean(line, ads)`:含 DISCONTINUITY/EXTINF 的规则走 scanAd,数字规则触发 scan 模式。
///
/// 性能优化:所有规则在原串上匹配,待删区间合并后一次重建;无命中返回 Cow::Borrowed 零拷贝。
pub fn clean<'a>(line0: &'a str, ads: &[String], ad_count: &mut u32) -> Cow<'a, str> {
    let mut need_remove: Vec<String> = Vec::new();
    let mut scan_mode = false;
    for ad in ads {
        if ad.contains(TAG_DISCONTINUITY) || ad.contains(TAG_MEDIA_DURATION) {
            collect_regex_removals(line0, ad, ad_count, &mut need_remove);
        } else if is_double(ad) {
            scan_mode = true;
        }
    }
    if scan_mode {
        collect_scan_removals(line0, ads, ad_count, &mut need_remove);
    }
    remove_ranges_once(line0, &need_remove)
}

/// Java `$`(无 MULTILINE)在输入末尾换行符之前也匹配;返回该位置。
fn dollar_position(s: &str) -> usize {
    let n = s.len();
    if s.ends_with("\r\n") {
        return n - 2;
    }
    for t in ["\n", "\r", "\u{85}", "\u{2028}", "\u{2029}"] {
        if s.ends_with(t) {
            return n - t.len();
        }
    }
    n
}

/// 复刻 `REGEX_X_DISCONTINUITY`(`#EXT-X-DISCONTINUITY[\s\S]*?(?=#EXT-X-DISCONTINUITY|$)`)
/// 的逐次 find():每个分组从 DISCONTINUITY 字面量开始,到下一个字面量(不含)或
/// Java `$` 位置(末尾换行前)结束。
fn discontinuity_chunks(s: &str) -> Vec<(usize, usize)> {
    let mut positions: Vec<usize> = Vec::new();
    let mut from = 0usize;
    while let Some(off) = s[from..].find(TAG_DISCONTINUITY) {
        let p = from + off;
        positions.push(p);
        from = p + TAG_DISCONTINUITY.len();
    }
    let dollar = dollar_position(s);
    let mut chunks = Vec::with_capacity(positions.len());
    for i in 0..positions.len() {
        let start = positions[i];
        let end = if i + 1 < positions.len() { positions[i + 1] } else { dollar };
        if end > start {
            chunks.push((start, end));
        }
    }
    chunks
}

/// 等价 `scanAd(line, TAG_AD)`:TAG_AD 作为正则匹配区域,整块移除并按块内 EXTINF 计数。
pub fn scan_ad<'a>(line0: &'a str, tag_ad: &str, ad_count: &mut u32) -> Cow<'a, str> {
    let mut need_remove: Vec<String> = Vec::new();
    collect_regex_removals(line0, tag_ad, ad_count, &mut need_remove);
    remove_ranges_once(line0, &need_remove)
}

/// 等价 `scan(line, ads)`:按 DISCONTINUITY 分组,首段时长 / 总时长与规则前缀匹配则整组移除。
pub fn scan<'a>(line0: &'a str, ads: &[String], ad_count: &mut u32) -> Cow<'a, str> {
    let mut need_remove: Vec<String> = Vec::new();
    collect_scan_removals(line0, ads, ad_count, &mut need_remove);
    remove_ranges_once(line0, &need_remove)
}

fn flush_pending(sb: &mut String, pending: &mut Vec<String>) {
    for line in pending.drain(..) {
        sb.push_str(&line);
        sb.push('\n');
    }
}

fn flush_pending_split(sb: &mut String, pending: &mut Vec<String>, linesplit: &str) {
    for line in pending.drain(..) {
        sb.push_str(&line);
        sb.push_str(linesplit);
    }
}

/// 等价 `cleanCommonAdMarkers`:CUE-OUT/CUE-IN 广告段、SCTE35 信号、独立 DATERANGE 广告标签、
/// 广告 URI 特征与广告域名一次性清洗。
pub fn clean_common_ad_markers<'a>(line: &'a str, ad_count: &mut u32) -> Cow<'a, str> {
    let mut sb = String::with_capacity(line.len());
    let mut pending: Vec<String> = Vec::new();
    let mut in_ad_break = false;
    let mut changed = false;

    let mut lines: Vec<&str> = line.split('\n').collect();
    if !lines.is_empty() {
        lines.pop(); // Kotlin split 后 dropLast(1)
    }
    for raw in lines {
        let item = raw.trim();
        if item.is_empty() {
            if pending.is_empty() {
                sb.push_str(raw);
                sb.push('\n');
            } else {
                pending.push(raw.to_string());
            }
            continue;
        }
        if item.starts_with('#') {
            if item.starts_with(TAG_CUE_IN) && (in_ad_break || has_ad_signal(&pending)) {
                in_ad_break = false;
                pending.clear();
                changed = true;
                continue;
            }
            if is_ad_break_start(item) {
                flush_pending(&mut sb, &mut pending);
                in_ad_break = true;
                pending.push(raw.to_string());
                changed = true;
                continue;
            }
            if in_ad_break {
                pending.push(raw.to_string());
                changed = true;
                continue;
            }
            if is_standalone_ad_tag(item) {
                flush_pending(&mut sb, &mut pending);
                *ad_count += 1;
                changed = true;
                continue;
            }
            if is_segment_tag(item) || is_ad_signal_tag(item) {
                pending.push(raw.to_string());
            } else {
                flush_pending(&mut sb, &mut pending);
                sb.push_str(raw);
                sb.push('\n');
            }
            continue;
        }

        // URL 行:检查广告打断状态 / pending 广告信号 / URL 特征 / 域名特征
        if in_ad_break || has_ad_signal(&pending) || is_ad_segment_uri(item) || has_ad_domain(item) {
            pending.clear();
            *ad_count += 1;
            changed = true;
            continue;
        }
        flush_pending(&mut sb, &mut pending);
        sb.push_str(raw);
        sb.push('\n');
    }
    if !in_ad_break {
        flush_pending(&mut sb, &mut pending);
    }
    if changed {
        Cow::Owned(sb)
    } else {
        // 无广告是绝大多数真实请求的热路径:零拷贝返回借用。
        Cow::Borrowed(line)
    }
}

/// 插入序计数表:统计用,取最大值时与"首次出现顺序"严格大于语义一致。
#[derive(Debug, Default)]
struct CountMap {
    map: HashMap<String, u32>,
    order: Vec<String>,
}

impl CountMap {
    fn bump(&mut self, key: &str) {
        match self.map.get_mut(key) {
            Some(v) => *v += 1,
            None => {
                self.map.insert(key.to_string(), 1);
                self.order.push(key.to_string());
            }
        }
    }

    fn get(&self, key: &str) -> Option<u32> {
        self.map.get(key).copied()
    }

    fn len(&self) -> usize {
        self.map.len()
    }

    fn max_entry(&self) -> Option<(&str, u32)> {
        let mut best: Option<(&str, u32)> = None;
        for key in &self.order {
            let v = self.map[key];
            let better = match best {
                None => true,
                Some((_, bv)) => v > bv,
            };
            if better {
                best = Some((key.as_str(), v));
            }
        }
        best
    }

    fn max_percent(&self) -> f64 {
        let mut max_times = 0u64;
        let mut total = 0u64;
        for v in self.map.values() {
            if *v as u64 > max_times {
                max_times = *v as u64;
            }
            total += *v as u64;
        }
        max_times as f64 / total as f64
    }
}

/// 等价 `removeMinorityUrl`:媒体路径前缀统计 → 0.8 阈值 → 域名过滤回退。
/// 移除比例超过 30% 视为误判,重置计数并返回 None。
fn remove_minority_url(ts_url_pre: &str, m3u8content: &str, ad_count: &mut u32) -> Option<String> {
    let linesplit = if m3u8content.contains("\r\n") { "\r\n" } else { "\n" };
    let lines: Vec<&str> = if linesplit == "\r\n" {
        m3u8content.split("\r\n").collect()
    } else {
        m3u8content.split('\n').collect()
    };

    let total_segments = lines
        .iter()
        .filter(|l| !l.is_empty() && !l.starts_with('#'))
        .count() as u32;

    // 第一遍:统计"扩展名前 4 字符"归一化前缀
    let mut pre_url_map = CountMap::default();
    for line in &lines {
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let absolute_url = to_absolute_url(ts_url_pre, line);
        // Kotlin `lastIndexOf('.')` 返回 UTF-16 码元下标,`ilast <= 4` 也是字符语义。
        // 旧实现用字节下标,含中文的相对 URL(如 "中中.ts",点号字节位 6/UTF-16 位 2)
        // 会被误判为有效前缀,与 Kotlin 分叉。同时保留字节位供安全切片。
        let (dot_byte, dot_utf16) = match last_dot_positions(&absolute_url) {
            Some(p) => p,
            None => continue,
        };
        if dot_utf16 <= 4 {
            continue;
        }
        let pre_url = utf16_backed_prefix(&absolute_url, dot_byte, 4);
        pre_url_map.bump(&pre_url);
    }
    if pre_url_map.len() <= 1 {
        return None;
    }

    let mut domain_filtering = false;
    if pre_url_map.max_percent() < 0.8 {
        // 回退:主域名过滤
        pre_url_map = CountMap::default();
        for line in &lines {
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            let absolute_url = to_absolute_url(ts_url_pre, line);
            if !absolute_url.starts_with("http://") && !absolute_url.starts_with("https://") {
                return None;
            }
            let ifirst = match index_of_char_from(&absolute_url, '/', 9) {
                Some(i) if i > 0 => i,
                _ => continue,
            };
            let pre_url = absolute_url[..ifirst].to_string();
            pre_url_map.bump(&pre_url);
        }
        if pre_url_map.len() <= 1 {
            return None;
        }
        if pre_url_map.max_percent() < 0.8 {
            return None;
        }
        let mut all_domains_exceed_threshold = true;
        for count in pre_url_map.map.values() {
            if *count <= TIMES_NO_AD {
                all_domains_exceed_threshold = false;
                break;
            }
        }
        if all_domains_exceed_threshold {
            return None;
        }
        domain_filtering = true;
    }

    // 保留出现最多的媒体前缀(或域名)
    let (max_times_pre_url, max_times) = {
        let e = pre_url_map.max_entry()?;
        (e.0.to_string(), e.1)
    };
    if max_times == 0 {
        return None;
    }

    let mut filtered = String::with_capacity(m3u8content.len() + 32);
    let mut pending_segment_tags: Vec<String> = Vec::new();
    for item_line in &lines {
        let item = item_line.trim();
        if item.is_empty() {
            if pending_segment_tags.is_empty() {
                filtered.push_str(item_line);
                filtered.push_str(linesplit);
            } else {
                pending_segment_tags.push(item_line.to_string());
            }
            continue;
        }
        if item.starts_with('#') {
            let output = if has_uri_attribute(item) {
                resolve_uri_line(ts_url_pre, item_line)
            } else {
                item_line.to_string()
            };
            if is_segment_tag(item) {
                pending_segment_tags.push(output);
            } else {
                flush_pending_split(&mut filtered, &mut pending_segment_tags, linesplit);
                filtered.push_str(&output);
                filtered.push_str(linesplit);
            }
            continue;
        }

        let absolute_url = to_absolute_url(ts_url_pre, item_line);
        if should_keep_media_url(&absolute_url, domain_filtering, &max_times_pre_url, &pre_url_map) {
            flush_pending_split(&mut filtered, &mut pending_segment_tags, linesplit);
            filtered.push_str(&absolute_url);
            filtered.push_str(linesplit);
        } else {
            pending_segment_tags.clear();
            *ad_count += 1;
        }
    }

    // 安全检查:移除比例过高视为误判
    if total_segments > 0 && (*ad_count as f64) > total_segments as f64 * 0.3 {
        *ad_count = 0;
        return None;
    }

    Some(normalize_media_playlist(&filtered))
}

/// 返回最后一个 '.' 的 (UTF-8 字节位置, UTF-16 码元位置),对齐 Kotlin
/// `String.lastIndexOf('.')` 的字符语义,同时给出可安全切片的字节边界。
fn last_dot_positions(s: &str) -> Option<(usize, usize)> {
    let mut last = None;
    let mut utf16_idx = 0usize;
    for (byte_idx, ch) in s.char_indices() {
        if ch == '.' {
            last = Some((byte_idx, utf16_idx));
        }
        utf16_idx += ch.len_utf16();
    }
    last
}

/// 等价 Kotlin `substring(0, ilast - 4)`:按 UTF-16 码元从 '.' 向前回退 n 个,
/// 再在合法的 UTF-8 char 边界切片(多字节字符不会 panic)。
fn utf16_backed_prefix(s: &str, dot_byte: usize, n: usize) -> String {
    let before = &s[..dot_byte];
    let keep = before.encode_utf16().count().saturating_sub(n);
    if keep == 0 {
        return String::new();
    }
    let mut acc = 0usize;
    let mut end = 0usize;
    for (bi, ch) in before.char_indices() {
        acc += ch.len_utf16();
        end = bi + ch.len_utf8();
        if acc >= keep {
            break;
        }
    }
    before[..end].to_string()
}

/// 等价 `shouldKeepMediaUrl`。
fn should_keep_media_url(
    absolute_url: &str,
    domain_filtering: bool,
    max_times_pre_url: &str,
    pre_url_map: &CountMap,
) -> bool {
    if !domain_filtering {
        return absolute_url.starts_with(max_times_pre_url);
    }
    let domain = match index_of_char_from(absolute_url, '/', 9) {
        Some(i) if i > 0 => &absolute_url[..i],
        _ => absolute_url,
    };
    let cnt = pre_url_map.get(domain);
    domain == max_times_pre_url || cnt.is_some_and(|c| c > TIMES_NO_AD)
}

/// 等价 `getAdSegmentLimit`:总时长分档(≤30min→18, ≤60→24, ≤90→30, 其余 36)。
fn get_ad_segment_limit(m3u8_content: &str) -> u32 {
    let mut total_duration = Dec::zero();
    for raw in m3u8_content.split('\n') {
        total_duration = total_duration.add(&parse_ext_inf_duration(raw));
    }
    let total_minutes = total_duration.to_f64() / 60.0;
    if total_minutes <= 30.0 {
        18
    } else if total_minutes <= 60.0 {
        24
    } else if total_minutes <= 90.0 {
        30
    } else {
        36
    }
}

#[derive(Debug, Default)]
struct PrecisionStats {
    total: u32,
    mismatched: u32,
}

/// 等价 `cleanDecimalPrecisionGroups`:主时长精度 → 全不匹配的短块移除。
pub fn clean_decimal_precision_groups<'a>(m3u8_content: &'a str, ad_count: &mut u32) -> Cow<'a, str> {
    let lines: Vec<&str> = m3u8_content.split('\n').collect();
    let groups = build_discontinuity_groups(&lines);
    if groups.len() < 2 {
        return Cow::Borrowed(m3u8_content);
    }

    let mut precision_counts: Vec<(i32, u32)> = Vec::new();
    let mut total_segments = 0u32;
    for group in &groups {
        for raw in &group.lines {
            let precision = get_decimal_precision(raw);
            if precision < 0 {
                continue;
            }
            total_segments += 1;
            match precision_counts.iter_mut().find(|(p, _)| *p == precision) {
                Some((_, c)) => *c += 1,
                None => precision_counts.push((precision, 1)),
            }
        }
    }
    if total_segments < 8 || precision_counts.len() < 2 {
        return Cow::Borrowed(m3u8_content);
    }

    let mut major_precision = -1i32;
    let mut major_count = 0u32;
    for (p, c) in &precision_counts {
        if *c > major_count {
            major_precision = *p;
            major_count = *c;
        }
    }
    if major_precision < 0 || (major_count as f64) / (total_segments as f64) < 0.7 {
        return Cow::Borrowed(m3u8_content);
    }

    let mut remove_groups = vec![false; groups.len()];
    let mut removable_segments = 0u32;
    for (i, group) in groups.iter().enumerate() {
        if i == groups.len() - 1 || group.segment_count == 0 || group.segment_count > MAX_FRAME_RATE_AD_BLOCK_SIZE {
            continue;
        }
        let mut stats = PrecisionStats::default();
        for raw in &group.lines {
            let precision = get_decimal_precision(raw);
            if precision < 0 {
                continue;
            }
            stats.total += 1;
            if precision != major_precision {
                stats.mismatched += 1;
            }
        }
        if stats.total > 0 && stats.mismatched == stats.total {
            remove_groups[i] = true;
            removable_segments += group.segment_count;
        }
    }

    if removable_segments == 0
        || removable_segments > get_ad_segment_limit(m3u8_content)
        || (removable_segments as f64) > total_segments as f64 * 0.3
    {
        return Cow::Borrowed(m3u8_content);
    }

    let mut sb = String::with_capacity(m3u8_content.len());
    for (i, group) in groups.iter().enumerate() {
        if remove_groups[i] {
            *ad_count += group.segment_count;
        } else {
            group.append_to(&mut sb);
        }
    }
    Cow::Owned(normalize_media_playlist(&sb))
}

#[derive(Debug, Default)]
struct FrameRateStats {
    matched: u32,
    mismatched: u32,
}

/// 等价 `findDominantFrameRate`:30/25/24 中唯一的计数最大者(且 ≥ 2)。
fn find_dominant_frame_rate(groups: &[Group]) -> i32 {
    let (mut count30, mut count25, mut count24) = (0u32, 0u32, 0u32);
    for group in groups {
        for raw in &group.lines {
            if get_ext_inf_value_start(raw).is_none() {
                continue;
            }
            let frame_rate = get_exclusive_frame_rate(&parse_ext_inf_duration(raw));
            if frame_rate == 30 {
                count30 += 1;
            } else if frame_rate == 25 {
                count25 += 1;
            } else if frame_rate == 24 {
                count24 += 1;
            }
        }
    }
    let max = count30.max(count25).max(count24);
    if max < 2 {
        return 0;
    }
    let winners = (count30 == max) as u32 + (count25 == max) as u32 + (count24 == max) as u32;
    if winners != 1 {
        return 0;
    }
    if count30 == max {
        30
    } else if count25 == max {
        25
    } else {
        24
    }
}

/// 等价 `getFrameRateStats`。
fn get_frame_rate_stats(group: &Group, master_frame_rate: i32) -> FrameRateStats {
    let mut stats = FrameRateStats::default();
    for raw in &group.lines {
        if get_ext_inf_value_start(raw).is_none() {
            continue;
        }
        let frame_rate = get_exclusive_frame_rate(&parse_ext_inf_duration(raw));
        if frame_rate == master_frame_rate {
            stats.matched += 1;
        } else if frame_rate != 0 {
            stats.mismatched += 1;
        }
    }
    stats
}

/// 等价 `cleanFrameRateGroups`:主帧率 → 帧率特征不符的短块移除。
pub fn clean_frame_rate_groups<'a>(m3u8_content: &'a str, ad_count: &mut u32) -> Cow<'a, str> {
    let lines: Vec<&str> = m3u8_content.split('\n').collect();
    let groups = build_discontinuity_groups(&lines);
    if groups.len() < 2 {
        return Cow::Borrowed(m3u8_content);
    }

    let master_frame_rate = find_dominant_frame_rate(&groups);
    if master_frame_rate == 0 {
        return Cow::Borrowed(m3u8_content);
    }

    let mut removable_segments = 0u32;
    let mut remove_groups = vec![false; groups.len()];
    for (i, group) in groups.iter().enumerate() {
        if i == groups.len() - 1 || group.segment_count == 0 || group.segment_count > MAX_FRAME_RATE_AD_BLOCK_SIZE {
            continue;
        }
        let stats = get_frame_rate_stats(group, master_frame_rate);
        if stats.mismatched > 0 && stats.mismatched >= stats.matched {
            remove_groups[i] = true;
            removable_segments += group.segment_count;
        }
    }

    let segment_limit = get_ad_segment_limit(m3u8_content);
    if removable_segments == 0 || removable_segments > segment_limit {
        return Cow::Borrowed(m3u8_content);
    }

    let mut sb = String::with_capacity(m3u8_content.len());
    for (i, group) in groups.iter().enumerate() {
        if remove_groups[i] {
            *ad_count += group.segment_count;
        } else {
            group.append_to(&mut sb);
        }
    }
    Cow::Owned(normalize_media_playlist(&sb))
}

/// 等价 `cleanDiscontinuityGroups`:≥3 组时,丢弃"短且广告相似"的组。
pub fn clean_discontinuity_groups<'a>(m3u8_content: &'a str, ad_count: &mut u32) -> Cow<'a, str> {
    let lines: Vec<&str> = m3u8_content.split('\n').collect();
    let groups = build_discontinuity_groups(&lines);
    if groups.len() < 3 {
        return Cow::Borrowed(m3u8_content);
    }
    let main_idx = match find_main_group(&groups) {
        Some(m) if groups[m].segment_count >= 3 => m,
        _ => return Cow::Borrowed(m3u8_content),
    };

    let mut sb = String::with_capacity(m3u8_content.len());
    let mut changed = false;
    for (i, group) in groups.iter().enumerate() {
        if i != main_idx && should_drop_group(group, &groups[main_idx]) {
            *ad_count += group.segment_count;
            changed = true;
            continue;
        }
        group.append_to(&mut sb);
    }
    if changed {
        Cow::Owned(sb)
    } else {
        Cow::Borrowed(m3u8_content)
    }
}

/// 等价 `normalizeMediaPlaylist`:去前导空行、合并连续 DISCONTINUITY、
/// 不在 ENDLIST 前悬挂 DISCONTINUITY。
pub fn normalize_media_playlist(content: &str) -> String {
    let mut sb = String::with_capacity(content.len());
    let mut seen_media = false;
    let mut has_pending_discontinuity = false;
    let mut pending_discontinuity = String::new();
    let replaced = content.replace("\r\n", "\n");
    let mut lines: Vec<&str> = replaced.split('\n').collect();
    if !lines.is_empty() {
        lines.pop(); // Kotlin split 后 dropLast(1)
    }
    for raw in lines {
        let item = raw.trim();
        if is_discontinuity_tag(item) {
            if seen_media && !has_pending_discontinuity {
                pending_discontinuity = raw.to_string();
                has_pending_discontinuity = true;
            }
            continue;
        }
        if has_pending_discontinuity {
            if item.is_empty() {
                continue;
            }
            if !item.starts_with(TAG_ENDLIST) {
                sb.push_str(&pending_discontinuity);
                sb.push('\n');
            }
            has_pending_discontinuity = false;
        }
        if item.is_empty() && sb.is_empty() {
            continue;
        }
        sb.push_str(raw);
        sb.push('\n');
        if is_media_uri_line(item) {
            seen_media = true;
        }
    }
    sb
}

/// 等价 `isPlayableMediaPlaylist`。
pub fn is_playable_media_playlist(content: &str) -> bool {
    if !content.starts_with("#EXTM3U") {
        return false;
    }
    let mut media_count = 0u32;
    let mut pending_ext_inf = false;
    for raw in content.replace("\r\n", "\n").split('\n') {
        let line = raw.trim();
        if line.is_empty() {
            continue;
        }
        if line.starts_with(TAG_MEDIA_DURATION) {
            if pending_ext_inf {
                return false;
            }
            pending_ext_inf = true;
        } else if is_media_uri_line(line) {
            media_count += 1;
            pending_ext_inf = false;
        } else if line.starts_with(TAG_ENDLIST) && pending_ext_inf {
            return false;
        }
    }
    media_count > 0 && !pending_ext_inf
}

/// 等价 `keepVodEndList`:原片有 ENDLIST 而结果丢失时补回。
pub fn keep_vod_end_list(original: &str, result: &str) -> String {
    if !has_end_list(original) || has_end_list(result) {
        return result.to_string();
    }
    let mut r = result.to_string();
    if !r.ends_with('\n') {
        r.push('\n');
    }
    r.push_str(TAG_ENDLIST);
    r.push('\n');
    r
}

/// 等价 `get(tsUrlPre, m3u8Content)`(hosts regex 由参数传入,不再回调 Kotlin):
/// resolveContent → clean(ads) → cleanCommonAdMarkers → 小数精度 / 帧率特征(仅 ENDLIST+DISCONTINUITY)
/// → cleanDiscontinuityGroups。返回 (净化内容, 移除条数)。
pub fn get(ts_url_pre: &str, m3u8_content: &str, ads: Option<&[String]>) -> (String, u32) {
    let mut ad_count = 0u32;
    // resolve_content 按 Kotlin 语义重建行尾,产生首个 owned buffer;
    // 其后各阶段用 Cow 串联,只有真正发生修改时才分配新串,
    // 无广告的常见路径由 4~6 次全量拷贝降为末尾 1 次。
    let resolved = resolve_content(ts_url_pre, m3u8_content);
    let cleaned = match ads.filter(|l| !l.is_empty()) {
        Some(list) => clean(&resolved, list, &mut ad_count),
        None => Cow::Borrowed(resolved.as_str()),
    };
    let common = clean_common_ad_markers(cleaned.as_ref(), &mut ad_count);
    let decimal = if has_end_list(common.as_ref()) && common.as_ref().contains(TAG_DISCONTINUITY) {
        Some(clean_decimal_precision_groups(common.as_ref(), &mut ad_count))
    } else {
        None
    };
    let refined = match decimal.as_ref() {
        Some(d) => clean_frame_rate_groups(d.as_ref(), &mut ad_count),
        None => common,
    };
    let result = clean_discontinuity_groups(refined.as_ref(), &mut ad_count);
    (result.into_owned(), ad_count)
}

/// 净化结果:Kotlin 全局 `currentAdCount` 的返回值化改进。
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PurifyResult {
    pub content: String,
    pub removed_ads: u32,
}

/// 等价 `M3u8.purify(tsUrlPre, m3u8content)`(纯函数,无全局状态)。
/// 返回 None 表示不该代理(空内容 / 非 #EXTM3U)。
pub fn purify(ts_url_pre: &str, content: &str, ads: Option<&[String]>) -> Option<PurifyResult> {
    if content.is_empty() {
        return None;
    }
    let m3u8content = match content.strip_prefix('\u{FEFF}') {
        Some(c) => c,
        None => content,
    };
    if !m3u8content.starts_with("#EXTM3U") {
        return None;
    }

    // 最终安全检查用的总段数
    let linesplit = if m3u8content.contains("\r\n") { "\r\n" } else { "\n" };
    let lines: Vec<&str> = if linesplit == "\r\n" {
        m3u8content.split("\r\n").collect()
    } else {
        m3u8content.split('\n').collect()
    };
    let total_segments = lines
        .iter()
        .filter(|l| !l.is_empty() && !l.starts_with('#'))
        .count() as u32;

    let mut ad_count: u32 = 0;
    let filtered = remove_minority_url(ts_url_pre, m3u8content, &mut ad_count);
    let (mut result, get_count) = if filtered.is_some() && ad_count > 0 {
        get(ts_url_pre, filtered.as_deref().expect("checked above"), ads)
    } else {
        get(ts_url_pre, m3u8content, ads)
    };
    ad_count += get_count;
    result = keep_vod_end_list(m3u8content, &result);

    // 最终安全检查:移除比例过高则回退原内容
    if total_segments > 0 && (ad_count as f64) > total_segments as f64 * 0.5 {
        ad_count = 0;
        result = m3u8content.to_string();
    }
    if ad_count > 0 && !is_playable_media_playlist(&result) {
        ad_count = 0;
        result = m3u8content.to_string();
    }
    Some(PurifyResult { content: result, removed_ads: ad_count })
}

/// JNI 契约封装:adsJson 为 `["regex", ...]` JSON 数组(空串表示 None);
/// 成功返回 `{"content":"...","removedAds":N}` 字符串,不代理时返回 None(Kotlin 侧回退)。
pub fn purify_to_json(ts_url_pre: &str, content: &str, ads_json: &str) -> Option<String> {
    let ads: Option<Vec<String>> = if ads_json.trim().is_empty() {
        None
    } else {
        serde_json::from_str::<Vec<String>>(ads_json).ok()
    };
    let result = purify(ts_url_pre, content, ads.as_deref())?;
    Some(json!({ "content": result.content, "removedAds": result.removed_ads }).to_string())
}

// ---------------------------------------------------------------------------
// 测试(标准向量;与 Kotlin 版语义逐分支对照)
// ---------------------------------------------------------------------------
#[cfg(test)]
mod tests {
    use super::*;

    // ---------------- i128 定点 BigDecimal 复刻(Java 探针对照) ----------------

    #[test]
    fn dec_parse_matches_java() {
        // JDK 探针:new BigDecimal(s).stripTrailingZeros() → toString/scale
        let cases = [
            ("5.", "5", 0), // JDK 接受尾点
            (".5", "0.5", 1),
            ("1e1", "1E+1", -1),
            ("1E+2", "1E+2", -2),
            ("-0.0", "0", 0),
            ("5.000", "5", 0),
            ("10.04", "10.04", 2),
        ];
        for (input, expect_str, expect_scale) in cases {
            let d = Dec::from_str_java(input).expect(input).strip_trailing_zeros();
            assert_eq!(d.to_string_java(), expect_str, "input={}", input);
            assert_eq!(d.scale, expect_scale, "input={}", input);
        }
        assert_eq!(
            Dec::from_str_java("5.0").unwrap().strip_trailing_zeros().to_string_java(),
            "5"
        );
        // 非法输入
        assert!(Dec::from_str_java("").is_none());
        assert!(Dec::from_str_java("abc").is_none());
        assert!(Dec::from_str_java("1e").is_none());
        assert!(Dec::from_str_java("1.2.3").is_none());
    }

    /// 极端指数不能让 `frac_digits - exp` 溢出(debug panic / release 回绕)。
    #[test]
    fn dec_parse_extreme_exponent_no_overflow() {
        for bad in [
            "1.123e-9223372036854775807",
            "1.123e9223372036854775807",
            "0.1e-9223372036854775808",
            "1e-9223372036854775807",
        ] {
            assert!(Dec::from_str_java(bad).is_none(), "极端指数应安全降级: {}", bad);
        }
        // 经完整净化管线(EXTINF 解析 → 时长累加 → 精度/帧率分组)仍零 panic。
        let playlist = concat!(
            "#EXTM3U\n",
            "#EXTINF:1.123e-9223372036854775807,\nhttp://a.com/v/s0.ts\n",
            "#EXT-X-DISCONTINUITY\n",
            "#EXTINF:1.0,\nhttp://a.com/ad/x.ts\n",
            "#EXT-X-ENDLIST\n",
        );
        let r = purify("http://a.com/index.m3u8", playlist, None).expect("valid playlist");
        assert!(r.content.starts_with("#EXTM3U"));
    }

    #[test]
    fn dec_add_and_to_string() {
        // Java: BigDecimal.ZERO.add("5.0").add("4.50") → "9.50"
        let t = Dec::zero()
            .add(&Dec::from_str_java("5.0").unwrap())
            .add(&Dec::from_str_java("4.50").unwrap());
        assert_eq!(t.to_string_java(), "9.50");
        // ZERO.add("10") → "10"
        assert_eq!(Dec::zero().add(&Dec::from_str_java("10").unwrap()).to_string_java(), "10");
        // 前导零:new BigDecimal("005.0") → unscaled 50 scale 1
        let d = Dec::from_str_java("005.0").unwrap();
        assert_eq!((d.unscaled, d.scale), (50, 1));
        // "0.00" → plain 输出
        assert_eq!(Dec { unscaled: 0, scale: 2 }.to_string_java(), "0.00");
        // 0.04 → plain;1E-7 → 科学计数;负值
        assert_eq!(Dec::from_str_java("0.04").unwrap().to_string_java(), "0.04");
        assert_eq!(Dec::from_str_java("0.0000001").unwrap().to_string_java(), "1E-7");
        assert_eq!(Dec::from_str_java("-0.04").unwrap().to_string_java(), "-0.04");
    }

    #[test]
    fn java_double_to_string_matches_jdk() {
        assert_eq!(java_double_to_string(30.0), "30.0");
        assert_eq!(java_double_to_string(25.0), "25.0");
        assert_eq!(java_double_to_string(24.0), "24.0");
        // JDK 探针:Double.toString(30/1.001) 等
        assert_eq!(java_double_to_string(30.0 / 1.001), "29.970029970029973");
        assert_eq!(java_double_to_string(24.0 / 1.001), "23.976023976023978");
        assert_eq!(java_double_to_string(25.0 / 1.001), "24.97502497502498");
    }

    #[test]
    fn frame_rate_features_match_jdk() {
        // JDK 探针:feature sizes 30=1044 25=24 24=929
        let f = frame_rate_features();
        assert_eq!(f.r30.len(), 1044, "30fps 特征数");
        assert_eq!(f.r25.len(), 24, "25fps 特征数");
        assert_eq!(f.r24.len(), 929, "24fps 特征数");
        // 集合成员(含 scale 敏感的 equals 语义):
        let has = |set: &HashSet<(i128, i32)>, u: i128, s: i32| set.contains(&(u, s));
        assert!(
            has(&f.r30, 4, 2) && has(&f.r25, 4, 2) && !has(&f.r24, 4, 2),
            "0.04 同时属于 30/25 集合"
        );
        assert!(has(&f.r30, 34, 3) && !has(&f.r25, 34, 3), "0.034 属于 30 集合(NTSC frame 31)");
        assert!(has(&f.r25, 16, 2) && !has(&f.r30, 16, 2), "0.16 属于 25 集合");
        assert!(has(&f.r30, 1, 2) && has(&f.r24, 1, 2), "0.01 属于 30 与 24 集合(互斥失效)");
        // scale 敏感:0.040 (40,3) 不在集合中
        assert!(!has(&f.r25, 40, 3) && !has(&f.r30, 40, 3));
    }

    #[test]
    fn exclusive_frame_rate_matches_jdk() {
        // JDK 探针:getExclusiveFrameRate(new BigDecimal(d).stripTrailingZeros())
        let rate_of =
            |d: &str| get_exclusive_frame_rate(&Dec::from_str_java(d).unwrap().strip_trailing_zeros());
        assert_eq!(rate_of("10.034"), 30);
        assert_eq!(rate_of("10.160"), 25);
        assert_eq!(rate_of("10.04"), 0); // 0.04 同时在 30/25 集合 → 互斥失败
        assert_eq!(rate_of("10.01"), 0); // 0.01 同时在 30/24 集合 → 互斥失败
        assert_eq!(rate_of("5.0"), 0); // 整数小数部分为 0
        assert_eq!(rate_of("10.0"), 0);
    }

    // ---------------- ExoPlayer UriUtil.resolve 移植 ----------------

    #[test]
    fn resolve_uri_rfc_cases() {
        assert_eq!(resolve_uri("http://a/x/y.m3u8", "1.ts"), "http://a/x/1.ts");
        assert_eq!(resolve_uri("http://a/x/y.m3u8", "/z.ts"), "http://a/z.ts");
        assert_eq!(resolve_uri("http://a/x/y.m3u8", "../1.ts"), "http://a/1.ts");
        assert_eq!(resolve_uri("http://a/x/y.m3u8", "http://b/1.ts"), "http://b/1.ts");
        assert_eq!(resolve_uri("http://a/x/y.m3u8?tok=1", "?x=2"), "http://a/x/y.m3u8?x=2");
        assert_eq!(resolve_uri("http://a/x/y.m3u8", "#frag"), "http://a/x/y.m3u8#frag");
        // base 无路径(仅 authority):补 '/'
        assert_eq!(resolve_uri("http://a", "1.ts"), "http://a/1.ts");
        // base 为空
        assert_eq!(resolve_uri("", "x.ts"), "x.ts");
        // base 带目录尾斜杠(VodController.getBasePath 的输出形态)
        assert_eq!(resolve_uri("http://a/b/", "seg1.ts"), "http://a/b/seg1.ts");
        assert_eq!(resolve_uri("http://a/b/", "../c.ts"), "http://a/c.ts");
    }

    // ---------------- isAd / isDouble ----------------

    #[test]
    fn is_ad_classification() {
        assert!(is_ad("#EXT-X-DISCONTINUITY[\\s\\S]*?#EXT-X-DISCONTINUITY"));
        assert!(is_ad("#EXTINF:([0-9.]+),"));
        assert!(is_ad("2.0"));
        assert!(!is_ad("0.0"));
        assert!(!is_ad("abc"));
        assert!(is_ad("#EXT-X-CUE-OUT"));
        assert!(is_ad("#EXT-X-ENDLIST"));
        assert!(is_ad("-5.0")); // 负数规则
        assert!(is_double("5f")); // Java Double.parseDouble 接受后缀
        assert!(!is_double(" 0.0 "));
    }

    // ---------------- 1. 无广告 m3u8 → 原样返回,removedAds=0 ----------------

    #[test]
    fn purify_no_ads_passthrough() {
        let input = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n#EXTINF:10.0,\nhttp://a.com/v/1.ts\n#EXTINF:10.0,\nhttp://a.com/v/2.ts\n#EXT-X-ENDLIST\n";
        let r = purify("http://a.com/v/index.m3u8", input, None).expect("valid playlist");
        assert_eq!(r.removed_ads, 0);
        // Kotlin 语义:resolveContent 以 Regex.split(保留尾部空串)逐行补 "\n",
        // 输入以 \n 结尾时净化输出多一个尾随空行
        assert_eq!(r.content, format!("{}\n", input));
    }

    // ---------------- 2. minority URL 过滤(主前缀 + 少量异前缀) ----------------

    #[test]
    fn purify_minority_url_filtered() {
        let mut input = String::from("#EXTM3U\n#EXT-X-TARGETDURATION:10\n");
        for i in 1..=10 {
            input.push_str(&format!("#EXTINF:10.0,\nhttp://cdn.com/vod/video1/seg{}.ts\n", i));
        }
        input.push_str("#EXTINF:5.0,\nhttp://ad.cdn.com/promo/x1.ts\n");
        input.push_str("#EXTINF:5.0,\nhttp://ad.cdn.com/promo/x2.ts\n");
        input.push_str("#EXT-X-ENDLIST\n");

        let r = purify("http://cdn.com/vod/video1/index.m3u8", &input, None).expect("should purify");
        assert_eq!(r.removed_ads, 2);
        assert!(!r.content.contains("ad.cdn.com"));
        assert_eq!(r.content.matches("seg").count(), 10);
        assert_eq!(r.content.matches("#EXTINF").count(), 10);
        // 精确对照:广告段与其 EXTINF 一并丢弃,主段保留;
        // 尾部空行:输入尾行 + normalize 保留 + resolveContent 追加(与 Kotlin 一致)
        let mut expected = String::from("#EXTM3U\n#EXT-X-TARGETDURATION:10\n");
        for i in 1..=10 {
            expected.push_str(&format!("#EXTINF:10.0,\nhttp://cdn.com/vod/video1/seg{}.ts\n", i));
        }
        expected.push_str("#EXT-X-ENDLIST\n\n\n");
        assert_eq!(r.content, expected);
        for i in 1..=10 {
            assert!(r.content.contains(&format!("http://cdn.com/vod/video1/seg{}.ts", i)));
        }
    }

    // ---------------- 3. CUE-OUT / CUE-IN 广告段清除 ----------------

    #[test]
    fn purify_cue_out_in_removed() {
        let input = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10.0,\nhttp://a.com/vod/1.ts\n#EXT-OATCLS-SCTE35:started\n#EXT-X-CUE-OUT:30\n#EXTINF:10.0,\nhttp://a.com/ads/1.ts\n#EXTINF:10.0,\nhttp://a.com/ads/2.ts\n#EXT-X-CUE-IN\n#EXTINF:10.0,\nhttp://a.com/vod/2.ts\n#EXT-X-ENDLIST\n";
        let r = purify("http://a.com/vod/1.m3u8", input, None).expect("valid");
        assert_eq!(r.removed_ads, 2);
        // 与 Kotlin cleanCommonAdMarkers 逐行对照:CUE-OUT 前的 OATCLS 已 flush 保留,
        // CUE-OUT 自身在 pending 中随广告段一起丢弃,CUE-IN 被吞掉;
        // 输入以 \n 结尾 → 尾部多一个 resolveContent 追加的空行
        let expected = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10.0,\nhttp://a.com/vod/1.ts\n#EXT-OATCLS-SCTE35:started\n#EXTINF:10.0,\nhttp://a.com/vod/2.ts\n#EXT-X-ENDLIST\n\n";
        assert_eq!(r.content, expected);
    }

    // ---------------- 4. 不连续组短块丢弃(differentPath) ----------------

    #[test]
    fn purify_discontinuity_group_dropped_by_path() {
        let mut input = String::from("#EXTM3U\n#EXT-X-ENDLIST\n");
        for i in 1..=5 {
            input.push_str(&format!("#EXTINF:10.0,\nhttp://a.com/main/s{}.ts\n", i));
        }
        input.push_str("#EXT-X-DISCONTINUITY\n");
        input.push_str("#EXTINF:10.0,\nhttp://a.com/other/o1.ts\n");
        input.push_str("#EXTINF:10.0,\nhttp://a.com/other/o2.ts\n");
        input.push_str("#EXT-X-DISCONTINUITY\n");
        input.push_str("#EXTINF:10.0,\nhttp://a.com/more/m1.ts\n");
        // removeMinorityUrl:同域 → 域名统计 map size=1 → None(不裁剪)
        // cleanDiscontinuityGroups:3 组,主组 = main 块;other(2段)/more(1段)
        // 均满足 shortGroup(≤2) && differentPath → 丢弃
        let r = purify("http://a.com/index.m3u8", &input, None).expect("valid");
        assert_eq!(r.removed_ads, 3);
        assert!(!r.content.contains("/other/"));
        assert!(!r.content.contains("/more/"));
        for i in 1..=5 {
            assert!(r.content.contains(&format!("http://a.com/main/s{}.ts", i)));
        }
        assert_eq!(r.content.matches("#EXT-X-DISCONTINUITY").count(), 0);
    }

    // ---------------- 5. ENDLIST 保留(scan 移除组内 ENDLIST → keepVodEndList 补回) ----------------

    #[test]
    fn purify_endlist_preserved() {
        // 输入不以 \n 结尾:resolveContent 输出恰以单个 \n 收尾,scan 的末组止于
        // Java $ 位置(ENDLIST 处)→ groupCleaned 连续可匹配 → 组内 ENDLIST 被剥离
        let input = "#EXTM3U\n#EXTINF:10.0,\nhttp://a.com/v/1.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:5.0,\nhttp://a.com/v/2.ts\n#EXT-X-ENDLIST";
        let ads = vec!["5.0".to_string()];
        let r = purify("http://a.com/v/1.m3u8", input, Some(&ads)).expect("valid");
        assert_eq!(r.removed_ads, 1);
        // scan 按"值替换"移除 groupCleaned(= 末组去掉 ENDLIST 的文本,恰为组前缀):
        // 广告组被移除,ENDLIST 因前缀替换而留在原位 —— 与 Kotlin 字符串替换行为逐字一致
        let expected = "#EXTM3U\n#EXTINF:10.0,\nhttp://a.com/v/1.ts\n#EXT-X-ENDLIST\n";
        assert_eq!(r.content, expected);
    }

    // ---------------- 6. ads regex 匹配清除(scanAd) ----------------

    #[test]
    fn purify_ads_regex_scan_ad() {
        let input = "#EXTM3U\n#EXT-X-ENDLIST\n#EXTINF:10.0,\nhttp://a.com/v/1.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:10.0,\nhttp://a.com/ad/1.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:10.0,\nhttp://a.com/v/2.ts\n#EXT-X-ENDLIST\n";
        let ads = vec!["#EXT-X-DISCONTINUITY[\\s\\S]*?#EXT-X-DISCONTINUITY".to_string()];
        let r = purify("http://a.com/index.m3u8", input, Some(&ads)).expect("valid");
        assert_eq!(r.removed_ads, 1);
        assert!(!r.content.contains("ad/1.ts"));
        let expected = "#EXTM3U\n#EXT-X-ENDLIST\n#EXTINF:10.0,\nhttp://a.com/v/1.ts\n\n#EXTINF:10.0,\nhttp://a.com/v/2.ts\n#EXT-X-ENDLIST\n\n";
        assert_eq!(r.content, expected);
    }

    /// 单次重建必须等价于旧的逐次 replace:多出现、重叠区间、无命中借用。
    #[test]
    fn remove_ranges_once_equivalent_to_sequential_replace() {
        let line = "abcXdefXghi";
        // Xdef@3..7 与 defX@4..8 重叠 → 合并为 3..8
        let removals = vec!["Xdef".to_string(), "defX".to_string()];
        assert_eq!(remove_ranges_once(line, &removals), "abcghi");
        // 同一文本多处出现必须全部删除
        let line2 = "a--b--c";
        assert_eq!(remove_ranges_once(line2, &["--".to_string()]), "abc");
        // 无命中 → Cow::Borrowed(零拷贝)
        assert!(matches!(remove_ranges_once(line, &["zzz".to_string()]), Cow::Borrowed(_)));
        assert!(matches!(remove_ranges_once(line, &[]), Cow::Borrowed(_)));
    }

    /// 零分配的 ASCII 大小写不敏感匹配:不得因多字节 UTF-8 产生误判。
    #[test]
    fn ascii_case_insensitive_contains_no_false_positive_on_utf8() {
        assert!(contains_ascii_case_insensitive("HTTP://DoubleClick.NET/x", "doubleclick"));
        assert!(contains_ascii_case_insensitive("广告 SCTE-35 信号", "scte"));
        assert!(!contains_ascii_case_insensitive("中文内容", "cue"));
        assert!(!contains_ascii_case_insensitive("aDz", "ad-"));
    }

    /// Java 环视版 DISCONTINUITY 规则(regex crate 不支持)必须按字面量切分降级,
    /// 不能静默跳过导致广告漏删。
    #[test]
    fn purify_java_lookahead_discontinuity_rule() {
        let input = "#EXTM3U\n#EXTINF:10.0,\nhttp://a.com/v/1.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:10.0,\nhttp://a.com/ad/x.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:10.0,\nhttp://a.com/v/2.ts\n";
        let ad = "#EXT-X-DISCONTINUITY[\\s\\S]*?(?=#EXT-X-DISCONTINUITY|$)";
        assert!(is_java_discontinuity_rule(ad));
        // 直接验证规则本身(不经 purify 的 0.5 移除比例安全网,该网会回退整份内容)。
        let mut c = 0;
        let out = scan_ad(input, ad, &mut c);
        assert_eq!(c, 2, "环视规则的两块 DISCONTINUITY 必须被删除并按 EXTINF 计数");
        assert!(!out.contains("ad/x.ts"), "广告段必须被删除(而非静默保留)");
        assert!(out.contains("v/1.ts"), "DISCONTINUITY 之前的正常段保留");
    }

    /// scan 模式:负数规则按组内末段时长匹配。
    #[test]
    fn scan_negative_rule_matches_last_duration() {
        let input = "#EXTM3U\n#EXT-X-DISCONTINUITY\n#EXTINF:8.0,\nhttp://a.com/1.ts\n#EXTINF:5.0,\nhttp://a.com/2.ts\n";
        let ads = vec!["-5.0".to_string()];
        let mut c = 0;
        let out = scan(input, &ads, &mut c);
        assert_eq!(c, 2);
        assert_eq!(out, "#EXTM3U\n\n");
    }

    /// scan 模式:总时长前缀匹配(tStr.startsWith)。
    #[test]
    fn scan_total_duration_prefix() {
        let input = "#EXTM3U\n#EXT-X-DISCONTINUITY\n#EXTINF:2.0,\nhttp://a.com/1.ts\n#EXTINF:3.0,\nhttp://a.com/2.ts\n";
        let ads = vec!["5.0".to_string()];
        let mut c = 0;
        let out = scan(input, &ads, &mut c);
        assert_eq!(c, 2); // ft "2.0" 不匹配,t = "5.0" 匹配 → 整组移除
        assert_eq!(out, "#EXTM3U\n\n");
    }

    // ---------------- 7. 大 playlist 性能 smoke(#[ignore] bench 式) ----------------

    fn build_large_playlist(segments: usize) -> String {
        let mut s = String::from("#EXTM3U\n#EXT-X-TARGETDURATION:10\n");
        for i in 0..segments {
            s.push_str(&format!("#EXTINF:10.0,\nhttp://a.com/v/seg{}.ts\n", i));
        }
        s.push_str("#EXT-X-ENDLIST\n");
        s
    }

    #[test]
    fn purify_large_playlist_smoke() {
        let input = build_large_playlist(1000);
        let r = purify("http://a.com/v/index.m3u8", &input, None).expect("valid");
        assert_eq!(r.removed_ads, 0);
        assert_eq!(r.content.matches("#EXTINF").count(), 1000);
    }

    #[test]
    // bench 式性能冒烟:仅 release 构建(cargo test --release)参与,断言 2000 段 < 100ms;
    // debug 构建无优化,耗时无意义,自动忽略
    #[cfg_attr(debug_assertions, ignore)]
    fn purify_large_playlist_under_100ms() {
        let input = build_large_playlist(2000);
        let start = std::time::Instant::now();
        let r = purify("http://a.com/v/index.m3u8", &input, None).expect("valid");
        let elapsed = start.elapsed();
        assert_eq!(r.removed_ads, 0);
        assert!(elapsed.as_millis() < 100, "2000 段耗时 {:?} 超过 100ms", elapsed);
    }

    // ---------------- 8. 与 Kotlin 语义对照:关键分支逐个断言 ----------------

    /// purify 入口:BOM 剥离 / 非 #EXTM3U / 空内容。
    #[test]
    fn purify_entry_checks() {
        assert!(purify("http://a.com/", "", None).is_none(), "空内容 → None");
        assert!(
            purify("http://a.com/", "#EXTM3U...nope", None).is_some(),
            "#EXTM3U 开头即接受"
        );
        let body = "#EXTM3U\n#EXT-X-ENDLIST\n";
        let r = purify("http://a.com/", &format!("\u{FEFF}{}", body), None).expect("BOM 剥离后有效");
        assert_eq!(r.content, format!("{}\n", body), "BOM 被剥离");
        assert!(!r.content.contains('\u{FEFF}'));
    }

    /// 点号位置与前缀截断必须与 Kotlin 的 UTF-16 字符语义一致(非字节)。
    #[test]
    fn remove_minority_url_utf16_dot_index() {
        assert_eq!(last_dot_positions("seg.ts"), Some((3, 3)));
        assert_eq!(last_dot_positions("中中.ts"), Some((6, 2)), "中文:字节位 6 / UTF-16 位 2");
        assert_eq!(last_dot_positions("😀.ts"), Some((4, 2)), "emoji:字节位 4 / UTF-16 位 2");
        assert_eq!(last_dot_positions("no-dot"), None);
        let url = "http://a.com/v/segment.ts";
        let (dot_byte, _) = last_dot_positions(url).unwrap();
        assert_eq!(utf16_backed_prefix(url, dot_byte, 4), "http://a.com/v/seg");
        assert_eq!(utf16_backed_prefix("中中.ts", 6, 4), "");

        // 端到端:无 base 时中文相对 URL 的点号 UTF-16 位 ≤ 4,Kotlin 会跳过统计;
        // Rust 不得把它统计成空前缀后当作少数派删除。
        let mut input = String::from("#EXTM3U\n#EXT-X-ENDLIST\n");
        for _ in 0..10 {
            input.push_str("#EXTINF:10.0,\nhttp://a.com/x/segment.ts\n");
        }
        input.push_str("#EXTINF:10.0,\n中中.ts\n");
        let r = purify("", &input, None).expect("valid playlist");
        assert_eq!(r.removed_ads, 0, "中文相对 URL 不应被误判为广告");
    }

    /// removeMinorityUrl:前缀 map ≤ 1 → 不裁剪(None)。
    #[test]
    fn remove_minority_url_single_prefix_noop() {
        let input = "#EXTM3U\n#EXTINF:10.0,\nhttp://a.com/1.ts\n#EXTINF:10.0,\nhttp://a.com/2.ts\n#EXT-X-ENDLIST\n";
        let mut c = 0;
        assert!(remove_minority_url("http://a.com/index.m3u8", input, &mut c).is_none());
        assert_eq!(c, 0);
    }

    /// removeMinorityUrl:前缀 maxPercent < 0.8 → 回退域名过滤;域名 map size ≤ 1 → None。
    #[test]
    fn remove_minority_url_domain_fallback_single_domain() {
        let input = "#EXTM3U\n#EXTINF:10.0,\nhttp://a.com/main/s001.ts\n#EXTINF:10.0,\nhttp://a.com/main/s002.ts\n#EXTINF:10.0,\nhttp://a.com/other/o001.ts\n#EXTINF:10.0,\nhttp://a.com/more/m001.ts\n#EXT-X-ENDLIST\n";
        let mut c = 0;
        assert!(remove_minority_url("http://a.com/index.m3u8", input, &mut c).is_none());
        assert_eq!(c, 0);
    }

    /// timesNoAd=15:域名计数 > 15 的少数域名保留,≤ 15 的丢弃。
    #[test]
    fn remove_minority_url_times_no_ad_domain_keep() {
        let mut input = String::from("#EXTM3U\n#EXT-X-ENDLIST\n");
        for i in 0..100 {
            input.push_str(&format!("#EXTINF:10.0,\nhttp://main.com/x/p{:03}.ts\n", i));
        }
        for i in 0..20 {
            input.push_str(&format!("#EXTINF:10.0,\nhttp://main.com/x/dir2/q{:03}.ts\n", i));
        }
        for i in 0..20 {
            input.push_str(&format!("#EXTINF:10.0,\nhttp://zzz.net/y/r{:03}.ts\n", i));
        }
        for i in 0..10 {
            input.push_str(&format!("#EXTINF:10.0,\nhttp://few.org/z/t{:03}.ts\n", i));
        }
        let r = purify("http://main.com/x/index.m3u8", &input, None).expect("valid");
        assert_eq!(r.removed_ads, 10);
        assert!(r.content.contains("zzz.net"), "计数 > 15 的少数域名应保留(timesNoAd=15)");
        assert!(!r.content.contains("few.org"), "计数 ≤ 15 的少数域名应丢弃");
        assert_eq!(r.content.matches("main.com").count(), 120);
    }

    /// 相对地址段在 removeMinorityUrl 输出中被解析为绝对地址。
    #[test]
    fn remove_minority_url_resolves_relative_lines() {
        let mut input = String::from("#EXTM3U\n");
        for i in 0..6 {
            input.push_str(&format!("#EXTINF:10.0,\nhttp://a.com/x/seg{}.ts\n", i));
        }
        // 相对段解析后与主段同前缀域;6:1 → 0.857 ≥ 0.8 → 前缀模式,全部保留
        input.push_str("#EXTINF:10.0,\nrel.ts\n");
        input.push_str("#EXT-X-ENDLIST\n");
        let mut c = 0;
        let out =
            remove_minority_url("http://a.com/x/index.m3u8", &input, &mut c).expect("should filter");
        assert_eq!(c, 0, "相对段解析后与主前缀一致 → 保留");
        assert!(out.contains("http://a.com/x/rel.ts"), "相对段被解析为绝对地址");
    }

    /// URI 属性行(#EXT-X-KEY / #EXT-X-MAP)在 removeMinorityUrl 中被解析。
    #[test]
    fn remove_minority_url_resolves_key_map_uri() {
        let mut input = String::from("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.pub\"\n");
        for i in 0..6 {
            input.push_str(&format!("#EXTINF:10.0,\nhttp://a.com/x/seg{}.ts\n", i));
        }
        // 一条异前缀段:前缀统计 6:1 → 0.857 ≥ 0.8 → 前缀模式,异前缀段被过滤
        input.push_str("#EXTINF:10.0,\nhttp://b.com/y/z1.ts\n");
        input.push_str("#EXT-X-ENDLIST\n");
        let mut c = 0;
        let out =
            remove_minority_url("http://a.com/x/index.m3u8", &input, &mut c).expect("should filter");
        assert_eq!(c, 1, "异前缀段被过滤");
        assert!(out.contains("http://a.com/x/key.pub"), "KEY URI 解析为绝对地址");
        assert!(!out.contains("b.com"));
    }

    /// removeMinorityUrl 内部 0.3 安全检查:误判率过高 → 重置计数并放弃裁剪。
    #[test]
    fn remove_minority_url_suspicious_ratio_aborts() {
        let mut input = String::from("#EXTM3U\n");
        for i in 1..=5 {
            input.push_str(&format!("#EXTINF:10.0,\nhttp://a.com/main/s{}.ts\n", i));
        }
        for i in 1..=3 {
            input.push_str(&format!("#EXTINF:10.0,\nhttp://b.com/ads/x{}.ts\n", i));
        }
        input.push_str("#EXT-X-ENDLIST\n");
        let mut c = 0;
        assert!(remove_minority_url("http://a.com/index.m3u8", &input, &mut c).is_none());
        assert_eq!(c, 0, "中止时计数必须归零");
        // purify 层面:中止后走 get(原内容),b.com/ads 段被 cleanCommonAdMarkers
        // 的广告 URI 特征清除("ads" 边界命中),计数来自 ccm 而非 removeMinorityUrl
        let r = purify("http://a.com/index.m3u8", &input, None).expect("valid");
        assert!(!r.content.contains("b.com"));
        assert_eq!(r.removed_ads, 3);
    }

    /// purify 0.5 安全检查:scan 清除超过总段数一半 → 回退原内容、计数归零。
    #[test]
    fn purify_half_removed_safety_resets() {
        let mut input = String::from("#EXTM3U\n#EXTINF:8.0,\nhttp://a.com/1.ts\n");
        for i in 2..=7 {
            input.push_str(&format!("#EXT-X-DISCONTINUITY\n#EXTINF:10.0,\nhttp://a.com/{}.ts\n", i));
        }
        input.push_str("#EXT-X-DISCONTINUITY\n#EXTINF:8.0,\nhttp://a.com/8.ts\n");
        let ads = vec!["10.0".to_string()];
        let r = purify("http://a.com/index.m3u8", &input, Some(&ads)).expect("valid");
        assert_eq!(r.removed_ads, 0, "超过 50% 视为误判,计数归零");
        assert_eq!(r.content, input, "回退原始内容");
    }

    /// removeMinorityUrl 返回 Some 但计数为 0 → get 走"原始内容"而非归一化后的过滤产物。
    #[test]
    fn purify_zero_count_discards_normalized_variant() {
        let mut input = String::from("#EXTM3U\n#EXT-X-ENDLIST\n");
        for i in 1..=4 {
            input.push_str(&format!("#EXTINF:10.0,\nhttp://a.com/x/seg{}.ts\n", i));
        }
        input.push_str("#EXT-X-DISCONTINUITY\n#EXT-X-DISCONTINUITY\n");
        input.push_str("#EXTINF:10.0,\nhttp://a.com/x/y/seg5.ts\n");
        input.push_str("#EXT-X-ENDLIST\n");
        let r = purify("http://a.com/x/index.m3u8", &input, None).expect("valid");
        assert_eq!(r.removed_ads, 0);
        // 计数为 0 时使用原始内容(非归一化产物):双重 DISCONTINUITY 未被折叠;
        // 尾部多一个 resolveContent 追加的空行
        assert_eq!(r.content, format!("{}\n", input), "计数为 0 时使用原始内容");
    }

    /// isPlayableMediaPlaylist 语义(Kotlin 回退分支的判据)。
    #[test]
    fn is_playable_media_playlist_semantics() {
        assert!(is_playable_media_playlist(
            "#EXTM3U\n#EXTINF:10.0,\nhttp://a/1.ts\n#EXT-X-ENDLIST\n"
        ));
        assert!(!is_playable_media_playlist(
            "#EXTM3U\n#EXTINF:10.0,\n#EXTINF:10.0,\nhttp://a/1.ts\n"
        ), "连续 EXTINF 非法");
        assert!(!is_playable_media_playlist("#EXTM3U\n#EXTINF:10.0,\n#EXT-X-ENDLIST\n"),
            "EXTINF 悬挂在 ENDLIST 前");
        assert!(!is_playable_media_playlist("#EXTM3U\n"));
        assert!(!is_playable_media_playlist("http://a/1.ts"));
    }

    /// cleanCommonAdMarkers:独立 DATERANGE 广告标签与信号/段标签分类。
    #[test]
    fn ccm_standalone_daterange_and_signals() {
        let input = "#EXTM3U\n#EXT-X-DATERANGE:ID=spot1\n#EXT-X-DATERANGE:X-ASSET-URI=\"http://ad/x.ts\"\n#EXTINF:10.0,\nhttp://a.com/v/1.ts\n";
        let mut c = 0;
        let out = clean_common_ad_markers(input, &mut c);
        assert_eq!(c, 1, "仅第二条 DATERANGE 命中广告特征");
        assert!(out.contains("ID=spot1"), "无广告特征的 DATERANGE 原样保留");
        assert!(!out.contains("X-ASSET-URI"));

        assert!(is_ad_segment_uri("http://a.com/ads/1.ts"));
        assert!(is_ad_segment_uri("http://ad.cdn.com/x.ts"));
        assert!(is_ad_segment_uri("http://x.com/vast_manifest.ts"));
        assert!(!is_ad_segment_uri("http://a.com/v/1.ts"));
        assert!(!is_ad_segment_uri("http://a.com/adjust.ts"));
        assert!(has_ad_domain("http://pubads.g.doubleclick.net/x"));
        assert!(!has_ad_domain("http://a.com/v/1.ts"));
        assert!(is_ad_signal_tag("#EXT-OATCLS-SCTE35:x"));
        assert!(is_ad_signal_tag("#EXT-X-CUE-IN"));
        assert!(!is_ad_signal_tag("#EXT-X-DISCONTINUITY-SEQUENCE:2"));
        assert!(is_segment_tag("#EXT-X-DISCONTINUITY"));
        assert!(!is_segment_tag("#EXT-X-DISCONTINUITY-SEQUENCE:2"));
        assert!(is_segment_tag("#EXTINF:10.0,"));
    }

    /// CUE-IN 之后紧跟广告段(无 CUE-OUT):pending 信号 → 段清除。
    #[test]
    fn ccm_cue_in_signal_catches_following_segment() {
        let input = "#EXTM3U\n#EXT-X-CUE-IN\n#EXTINF:10.0,\nhttp://a.com/ad/1.ts\n#EXTINF:10.0,\nhttp://a.com/v/1.ts\n";
        let mut c = 0;
        let out = clean_common_ad_markers(input, &mut c);
        // CUE-IN:inAdBreak=false 且 pending 空 → 落到 isAdSignalTag → pending=[CUE-IN]
        // ad/1.ts:hasAdSignal(pending) → 清除;v/1.ts:pending 已清 → 保留
        assert_eq!(c, 1);
        assert!(!out.contains("CUE-IN"), "CUE-IN 留在 pending 中随广告段清除");
        assert!(!out.contains("ad/1.ts"));
        assert!(out.contains("v/1.ts"));
    }

    /// cleanDecimalPrecisionGroups:主精度 0.7 阈值、全不匹配短块。
    #[test]
    fn decimal_precision_groups_removed() {
        let mut input = String::from("#EXTM3U\n#EXT-X-ENDLIST\n");
        for i in 1..=5 {
            input.push_str(&format!("#EXTINF:10.0,\nhttp://a.com/s{}.ts\n", i));
        }
        input.push_str("#EXT-X-DISCONTINUITY\n");
        for i in 1..=4 {
            input.push_str(&format!("#EXTINF:10.000,\nhttp://a.com/a{}.ts\n", i));
        }
        input.push_str("#EXT-X-DISCONTINUITY\n");
        for i in 1..=5 {
            input.push_str(&format!("#EXTINF:10.0,\nhttp://a.com/b{}.ts\n", i));
        }
        input.push_str("#EXT-X-ENDLIST\n");
        let mut c = 0;
        let out = clean_decimal_precision_groups(&input, &mut c);
        assert_eq!(c, 4, "精度 3 的 4 段全不匹配 → 整块移除");
        assert!(!out.contains("a1.ts"), "精度异常块被移除");
        assert!(out.contains("s1.ts") && out.contains("b1.ts"));
        assert!(out.contains("#EXT-X-ENDLIST"));
    }

    /// cleanFrameRateGroups:30fps(NTSC 特征)主组 + 25fps 短组 → 移除。
    #[test]
    fn frame_rate_groups_removed() {
        // 10.034 → 30fps(0.034 ∈ 30 集合);10.160 → 25fps(0.16 ∈ 25 集合);
        // 两者精度同为 3,小数精度检查因精度种类唯一而跳过
        let mut input = String::from("#EXTM3U\n#EXT-X-ENDLIST\n");
        for i in 1..=5 {
            input.push_str(&format!("#EXTINF:10.034,\nhttp://a.com/s{}.ts\n", i));
        }
        input.push_str("#EXT-X-DISCONTINUITY\n");
        for i in 1..=4 {
            input.push_str(&format!("#EXTINF:10.160,\nhttp://a.com/a{}.ts\n", i));
        }
        input.push_str("#EXT-X-DISCONTINUITY\n");
        for i in 1..=5 {
            input.push_str(&format!("#EXTINF:10.034,\nhttp://a.com/b{}.ts\n", i));
        }
        input.push_str("#EXT-X-ENDLIST\n");
        let mut c = 0;
        let out = clean_frame_rate_groups(&input, &mut c);
        assert_eq!(c, 4, "25fps 异帧率块被移除");
        assert!(!out.contains("a1.ts"));
        assert!(out.contains("s1.ts") && out.contains("b1.ts"));
    }

    /// get() 管线:ENDLIST+DISCONTINUITY 才启用小数精度/帧率检查。
    #[test]
    fn get_enables_group_checks_only_with_endlist_and_discontinuity() {
        let mut input = String::from("#EXTM3U\n");
        for i in 1..=5 {
            input.push_str(&format!("#EXTINF:10.0,\nhttp://a.com/s{}.ts\n", i));
        }
        input.push_str("#EXT-X-DISCONTINUITY\n");
        for i in 1..=4 {
            input.push_str(&format!("#EXTINF:10.000,\nhttp://a.com/a{}.ts\n", i));
        }
        input.push_str("#EXT-X-DISCONTINUITY\n");
        for i in 1..=5 {
            input.push_str(&format!("#EXTINF:10.0,\nhttp://a.com/b{}.ts\n", i));
        }
        let (_, c) = get("http://a.com/index.m3u8", &input, None);
        assert_eq!(c, 0, "无 ENDLIST 时小数精度/帧率检查不启用");
    }

    /// resolve_host_regex:host 包含匹配取首个命中(等价 Kotlin getRegex)。
    #[test]
    fn resolve_host_regex_first_contains_match() {
        let mut hosts = HashMap::new();
        hosts.insert("a.com".to_string(), vec!["2.0".to_string()]);
        hosts.insert("b.com".to_string(), vec!["3.0".to_string(), "5.0".to_string()]);
        assert_eq!(
            resolve_host_regex("http://b.com/x/index.m3u8", &hosts),
            Some(vec!["3.0".to_string(), "5.0".to_string()])
        );
        assert_eq!(resolve_host_regex("http://c.com/index.m3u8", &hosts), None);
        assert_eq!(
            resolve_host_regex("http://a.com/index.m3u8", &hosts),
            Some(vec!["2.0".to_string()])
        );
    }

    /// purify_to_json:JNI JSON 契约。
    #[test]
    fn purify_to_json_contract() {
        // 不以 \n 结尾:ads=["5.0"] 时 scan 末组剥离 ENDLIST 并移除广告段
        let input = "#EXTM3U\n#EXT-X-ENDLIST\n#EXTINF:10.0,\nhttp://a.com/v/1.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:5.0,\nhttp://a.com/v/2.ts\n#EXT-X-ENDLIST";
        // 空串 adsJson → None ads:无广告特征命中 → removedAds=0,内容保留
        let out = purify_to_json("http://a.com/v/index.m3u8", input, "").expect("valid");
        let v: serde_json::Value = serde_json::from_str(&out).unwrap();
        assert_eq!(v["removedAds"], 0);
        assert!(v["content"].as_str().expect("content").starts_with("#EXTM3U"));
        assert!(v["content"].as_str().unwrap().contains("2.ts"));
        // JSON 数组 ads 参数:scan 移除广告段 + keepVodEndList 补回 ENDLIST
        let out2 =
            purify_to_json("http://a.com/v/index.m3u8", input, r#"["5.0"]"#).expect("valid");
        let v2: serde_json::Value = serde_json::from_str(&out2).unwrap();
        assert_eq!(v2["removedAds"], 1);
        assert!(!v2["content"].as_str().unwrap().contains("2.ts"));
        assert!(v2["content"].as_str().unwrap().contains("#EXT-X-ENDLIST"));
        // 非 m3u8 → None
        assert!(purify_to_json("http://a.com/", "hello", "").is_none());
    }

    /// normalizeMediaPlaylist:合并连续 DISCONTINUITY、ENDLIST 前悬挂丢弃、前导空行去除。
    #[test]
    fn normalize_media_playlist_semantics() {
        let input = "\n#EXTM3U\n#EXTINF:10.0,\nhttp://a/1.ts\n#EXT-X-DISCONTINUITY\n#EXT-X-DISCONTINUITY\n#EXTINF:10.0,\nhttp://a/2.ts\n#EXT-X-DISCONTINUITY\n#EXT-X-ENDLIST\n";
        let out = normalize_media_playlist(input);
        assert!(out.starts_with("#EXTM3U"), "前导空行去除");
        assert_eq!(out.matches("#EXT-X-DISCONTINUITY").count(), 1, "连续 DISCONTINUITY 合并");
        assert!(out.ends_with("#EXT-X-ENDLIST\n"), "ENDLIST 前的 DISCONTINUITY 悬挂丢弃");
        assert!(out.contains("http://a/1.ts") && out.contains("http://a/2.ts"));
    }

    /// keepVodEndList / hasEndList。
    #[test]
    fn keep_vod_end_list_semantics() {
        let original = "#EXTM3U\n#EXT-X-ENDLIST\n";
        let result_no_endlist = "#EXTM3U\n#EXTINF:10.0,\nhttp://a/1.ts\n";
        assert_eq!(
            keep_vod_end_list(original, result_no_endlist),
            "#EXTM3U\n#EXTINF:10.0,\nhttp://a/1.ts\n#EXT-X-ENDLIST\n",
            "原片有 ENDLIST 而结果丢失 → 补回"
        );
        let result_with_endlist = "#EXTM3U\n#EXT-X-ENDLIST\n";
        assert_eq!(keep_vod_end_list(original, result_with_endlist), result_with_endlist);
        let live = "#EXTM3U\n#EXTINF:10.0,\nhttp://a/1.ts\n";
        assert_eq!(keep_vod_end_list(live, result_no_endlist), result_no_endlist);
        assert!(has_end_list("#EXTM3U\r\n#EXT-X-ENDLIST\r\n"));
        assert!(!has_end_list("#EXTM3U\n"));
    }

    /// discontinuity_chunks:Java `$` 语义(末组止于末尾换行前)与中组边界。
    #[test]
    fn discontinuity_chunk_boundaries() {
        let s = "#EXTM3U\n#EXTINF:8.0,\nhttp://a.com/1.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:10.0,\nhttp://a.com/2.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:10.0,\nhttp://a.com/3.ts\n#EXT-X-ENDLIST\n";
        let chunks = discontinuity_chunks(s);
        assert_eq!(chunks.len(), 2);
        assert_eq!(
            &s[chunks[0].0..chunks[0].1],
            "#EXT-X-DISCONTINUITY\n#EXTINF:10.0,\nhttp://a.com/2.ts\n",
            "中组止于下一个 DISCONTINUITY(不含)"
        );
        assert_eq!(
            &s[chunks[1].0..chunks[1].1],
            "#EXT-X-DISCONTINUITY\n#EXTINF:10.0,\nhttp://a.com/3.ts\n#EXT-X-ENDLIST",
            "末组止于 Java $ 位置(末尾换行前)"
        );
    }

    /// getDecimalPrecision / parseExtInfDuration 辅助函数。
    #[test]
    fn extinf_helpers() {
        assert_eq!(get_decimal_precision("#EXTINF:10.0,"), 1);
        assert_eq!(get_decimal_precision("#EXTINF:10.000,"), 3);
        assert_eq!(get_decimal_precision("#EXTINF:10,"), 0);
        assert_eq!(get_decimal_precision("#EXT-X-DISCONTINUITY"), -1);
        assert_eq!(get_decimal_precision("#EXTINF: 10.50 ,x"), 2);
        assert_eq!(parse_ext_inf_duration("#EXTINF:10.0,").to_string_java(), "1E+1");
        assert_eq!(parse_ext_inf_duration("#EXTINF:10.034,").to_string_java(), "10.034");
        assert_eq!(parse_ext_inf_duration("#EXTINF:xx,").to_string_java(), "0");
    }

    /// 与 Kotlin 对照:scanAd 与 ccm 叠加计数的组合场景。
    #[test]
    fn purify_kotlin_branch_matrix() {
        // 前缀统计 3:1 → 0.75 < 0.8 → 域名回退 → 单域名 size=1 → removeMinorityUrl None
        let input = "#EXTM3U\n#EXT-X-ENDLIST\n#EXTINF:10.0,\nhttp://a.com/1.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:10.0,\nhttp://a.com/2.ts\n#EXTINF:10.0,\nhttp://a.com/4.ts\n#EXTINF:10.0,\nhttp://a.com/doubleclick.ts";
        let ads = vec!["#EXT-X-DISCONTINUITY[\\s\\S]*?#EXTINF:10.0,".to_string()];
        let r = purify("http://a.com/index.m3u8", input, Some(&ads)).expect("valid");
        // scanAd 匹配 "DISC + 紧随的 EXTINF:10.0," 移除 1 次(2.ts 的 EXTINF 被吞,URL 孤儿保留);
        // ccm 按 doubleclick 域名特征清除该段(+1)→ 共 2;2 ≤ 0.5×4 不触发安全回退
        assert_eq!(r.removed_ads, 2);
        assert!(!r.content.contains("doubleclick"));
        assert!(r.content.contains("1.ts") && r.content.contains("2.ts") && r.content.contains("4.ts"));
        assert_eq!(r.content.matches("#EXTINF").count(), 2, "仅 1.ts / 4.ts 的 EXTINF 保留");
    }

    // ---------------- 健壮性:恶意 / 畸形输入零 panic ----------------

    /// 无 #EXTM3U 头 / 空 content / 纯空白 / 只有 BOM → None(Kotlin 回退)。
    #[test]
    fn robust_purify_no_header_and_empty() {
        assert!(purify("http://a.com/", "", None).is_none(), "空串");
        assert!(purify("http://a.com/", "   \r\n ", None).is_none(), "纯空白");
        assert!(purify("http://a.com/", "hello world", None).is_none(), "无头");
        assert!(purify("http://a.com/", "HTTP://not-m3u8", None).is_none(), "大小写敏感");
        assert!(purify("http://a.com/", "\u{FEFF}#EXT-X-ENDLIST\n", None).is_none(), "BOM+无头");
        assert!(purify("http://a.com/", "\u{FEFF}", None).is_none(), "仅 BOM");
        // purify_to_json 同契约:None → JNI null
        assert!(purify_to_json("http://a.com/", "", "").is_none());
        assert!(purify_to_json("http://a.com/", "plain text", "").is_none());
    }

    /// 只有注释行 / 标签行、无媒体段:返回 Some 且内容保留、计数 0。
    #[test]
    fn robust_purify_only_comments() {
        let input = "#EXTM3U\n# 这是注释\n#EXT-X-VERSION:3\n#EXT-X-ENDLIST\n";
        let r = purify("http://a.com/", input, None).expect("注释行不阻止净化");
        assert_eq!(r.removed_ads, 0);
        assert!(r.content.contains("# 这是注释"));
        assert!(r.content.contains("#EXT-X-ENDLIST"));
        // 全空行 + 头
        let r2 = purify("http://a.com/", "#EXTM3U\n\n\n\n", None).expect("Some");
        assert_eq!(r2.removed_ads, 0);
    }

    /// 畸形内容:EXTINF 悬挂、空 duration、超长垃圾行、U+FFFD、截断标签 → Some 且零 panic。
    #[test]
    fn robust_purify_malformed_content() {
        let cases = [
            "#EXTM3U\n#EXTINF:\n#EXTINF:\nhttp://a.com/1.ts\n",
            "#EXTM3U\n#EXTINF:abc,\nhttp://a.com/1.ts\n#EXT-X-ENDLIST\n",
            "#EXTM3U\n#EXT-X-DISCONTINUITY\n#EXT-X-DISCONTINUITY\n#EXT-X-DISCONTINUITY\n",
            "#EXTM3U\n#EXT-X-ENDLIST\n",
            "#EXTM3U\n#EXT-X-KEY:METHOD=NONE,URI=\n#EXT-X-MAP:URI=\"\nhttp://a.com/1.ts\n",
            "#EXTM3U\n#EXTINF:-1,\nhttp://a.com/1.ts|&&&=\n#EXT-X-DISCONTINUITY\u{FFFD}\n\u{FFFD}\u{FFFD}\n",
            "#EXTM3U\n#EXT-X-DATERANGE:\n#EXT-X-CUE-OUT\n#EXT-X-CUE-IN\nhttp://a.com/1.ts\n",
        ];
        for content in cases {
            let r = purify("http://a.com/base/index.m3u8", content, None);
            assert!(r.is_some(), "畸形内容仍应返回 Some: {:?}", content);
            // JSON 契约保持:content / removedAds 字段存在
            if let Some(json) = purify_to_json("http://a.com/base/index.m3u8", content, "") {
                let v: serde_json::Value =
                    serde_json::from_str(&json).expect("purify_to_json 输出必须是合法 JSON");
                assert!(v.get("content").is_some() && v.get("removedAds").is_some());
            }
        }
        // 超长垃圾行(10 万字符)
        let long_junk = format!("#EXTM3U\n#EXT-X-ENDLIST\n{}\n", "x".repeat(100_000));
        let r = purify("http://a.com/", &long_junk, None).expect("Some");
        assert_eq!(r.removed_ads, 0);
    }

    /// 变态广告正则:嵌套量词 / 环视(编译失败跳过)/ 巨量重复 / 全贪婪——
    /// regex 引擎线性时间,必须全部完成且零 panic、无灾难性回溯。
    #[test]
    fn robust_purify_evil_ads_regex() {
        let mut input = String::from("#EXTM3U\n#EXT-X-ENDLIST\n");
        for i in 1..=6 {
            input.push_str(&format!("#EXTINF:10.0,\nhttp://a.com/v/seg{}.ts\n", i));
        }
        input.push_str("#EXT-X-DISCONTINUITY\n#EXTINF:5.0,\nhttp://a.com/ad/x.ts\n");
        let evil_ads = vec![
            "(a+)+$".to_string(),                       // 经典灾难性回溯形态
            "((a)*)*b".to_string(),                     // 嵌套星号
            "(a|aa)*c".to_string(),
            "(x+x+)+y".to_string(),
            "(?=unclosed-lookahead)".to_string(),       // regex crate 不支持环视 → 编译失败 → 跳过
            "a{100000}{100000}".to_string(),            // 超量重复 → 编译失败 → 跳过
            ".*.*.*.*10.0".to_string(),                 // 全贪婪堆叠
            "[\\s\\S]*?(".to_string(),                  // 括号不闭合 → 编译失败
            "#EXT-X-DISCONTINUITY[\\s\\S]*?(?=#EXT-X-DISCONTINUITY|$)".to_string(), // 环视版(Java 原版形态)
            "10.0".to_string(),                         // 数字规则 → scan 模式
        ];
        let r = purify("http://a.com/index.m3u8", &input, Some(&evil_ads));
        assert!(r.is_some(), "变态正则必须完成并返回 Some");
        let r = r.unwrap();
        // u32 上 >= 0 恒成立,改为校验净化结果仍是合法 playlist 且保留 ENDLIST。
        assert!(r.content.starts_with("#EXTM3U"), "净化结果必须是合法 playlist");
        assert!(r.content.contains("#EXT-X-ENDLIST"), "ENDLIST 必须保留");
        // 无效 adsJson(非法 JSON / 非字符串数组)→ 当作无规则,零 panic
        for bad_ads in ["not json", "{\"a\":1}", "[1,2,3]", "[\"10.0\", 42]", "["] {
            let out = purify_to_json("http://a.com/index.m3u8", &input, bad_ads);
            assert!(out.is_some(), "invalid adsJson {:?}", bad_ads);
            let v: serde_json::Value = serde_json::from_str(&out.unwrap()).unwrap();
            assert!(v.get("content").is_some() && v.get("removedAds").is_some());
        }
        // 空 adsJson → 无规则
        assert!(purify_to_json("http://a.com/index.m3u8", &input, "").is_some());
        assert!(purify_to_json("http://a.com/index.m3u8", &input, "   ").is_some());
    }

    /// 100MB 量级 playlist 性能冒烟(#[ignore]:大内存,常规 CI 不跑)。
    #[test]
    #[ignore = "大输入性能冒烟:100MB m3u8"]
    fn robust_purify_100mb_smoke() {
        // ~130 万段 × ~78 字节/段 ≈ 100MB
        let mut input = String::with_capacity(110 * 1024 * 1024);
        input.push_str("#EXTM3U\n#EXT-X-TARGETDURATION:10\n");
        while input.len() < 100 * 1024 * 1024 {
            let i = input.len();
            input.push_str("#EXTINF:10.000001,\nhttp://a.com/vod/path/seg12345678901234.ts\n");
            let _ = i;
        }
        input.push_str("#EXT-X-ENDLIST\n");
        let start = std::time::Instant::now();
        let r = purify("http://a.com/vod/path/index.m3u8", &input, None).expect("valid");
        let elapsed = start.elapsed();
        assert_eq!(r.removed_ads, 0);
        assert!(r.content.contains("#EXT-X-ENDLIST"));
        assert!(elapsed.as_secs() < 300, "100MB 净化耗时 {:?} 异常", elapsed);
    }
}
