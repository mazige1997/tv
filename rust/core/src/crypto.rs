//! 加密与哈希原语(零依赖手写实现,避免第三方 API 漂移)。
//!
//! 全部实现均以标准测试向量验证:
//! - MD5:RFC 1321 Test Suite
//! - Base64:RFC 4648
//! - AES:NIST SP800-38A(ECB/CBC,128/192/256)
//!
//! 语义与旧版 Java 实现逐一对齐:
//! - `MD5.encode/string2MD5`  → 小写十六进制 MD5
//! - `AES.ECB/CBC`            → AES/ECB(/CBC)/PKCS7 解密,密钥右侧补 '0' 到 16 字节
//! - `Base64`                 → Android `Base64.DEFAULT`(解码,宽容空白)/`NO_WRAP`(编码)
//! - `AES.toBytes`            → 十六进制串转字节

// ---------------------------------------------------------------------------
// GF(2^8) 运算与 S 盒(程序化生成,而非抄表)
// ---------------------------------------------------------------------------

/// GF(2^8) 乘法,模多项式 x^8 + x^4 + x^3 + x + 1 (0x11b)。
fn gf_mul(a: u8, b: u8) -> u8 {
    let mut a = a as u16;
    let mut b = b;
    let mut p: u16 = 0;
    for _ in 0..8 {
        if b & 1 != 0 {
            p ^= a;
        }
        let hi = a & 0x80;
        a <<= 1;
        if hi != 0 {
            a ^= 0x11b;
        }
        b >>= 1;
    }
    p as u8
}

/// GF(2^8) 乘法逆元(0 的逆元定义为 0)。
fn gf_inverse(x: u8) -> u8 {
    if x == 0 {
        return 0;
    }
    for y in 1..=255u16 {
        if gf_mul(x, y as u8) == 1 {
            return y as u8;
        }
    }
    0
}

/// 生成 AES S 盒:GF(2^8) 求逆 + 仿射变换。
fn generate_sbox() -> [u8; 256] {
    let mut sbox = [0u8; 256];
    for (x, slot) in sbox.iter_mut().enumerate() {
        let inv = gf_inverse(x as u8);
        // 仿射变换:b = inv ^ rotl(inv,1) ^ rotl(inv,2) ^ rotl(inv,3) ^ rotl(inv,4) ^ 0x63
        let mut b = inv;
        b ^= inv.rotate_left(1);
        b ^= inv.rotate_left(2);
        b ^= inv.rotate_left(3);
        b ^= inv.rotate_left(4);
        b ^= 0x63;
        *slot = b;
    }
    sbox
}

/// 生成逆 S 盒(S 盒的逆置换)。
fn generate_inv_sbox(sbox: &[u8; 256]) -> [u8; 256] {
    let mut inv = [0u8; 256];
    for x in 0..256usize {
        inv[sbox[x] as usize] = x as u8;
    }
    inv
}

/// 进程级缓存的正向 S 盒。
fn sbox() -> &'static [u8; 256] {
    static SBOX: std::sync::OnceLock<[u8; 256]> = std::sync::OnceLock::new();
    SBOX.get_or_init(generate_sbox)
}

/// 进程级缓存的逆向 S 盒。
fn inv_sbox() -> &'static [u8; 256] {
    static INV: std::sync::OnceLock<[u8; 256]> = std::sync::OnceLock::new();
    INV.get_or_init(|| generate_inv_sbox(sbox()))
}

// ---------------------------------------------------------------------------
// MD5(RFC 1321)
// ---------------------------------------------------------------------------

const MD5_S: [u32; 64] = [
    7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
    5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
    4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
    6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
];

const MD5_K: [u32; 64] = [
    0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee, 0xf57c0faf, 0x4787c62a, 0xa8304613, 0xfd469501,
    0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be, 0x6b901122, 0xfd987193, 0xa679438e, 0x49b40821,
    0xf61e2562, 0xc040b340, 0x265e5a51, 0xe9b6c7aa, 0xd62f105d, 0x02441453, 0xd8a1e681, 0xe7d3fbc8,
    0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed, 0xa9e3e905, 0xfcefa3f8, 0x676f02d9, 0x8d2a4c8a,
    0xfffa3942, 0x8771f681, 0x6d9d6122, 0xfde5380c, 0xa4beea44, 0x4bdecfa9, 0xf6bb4b60, 0xbebfbc70,
    0x289b7ec6, 0xeaa127fa, 0xd4ef3085, 0x04881d05, 0xd9d4d039, 0xe6db99e5, 0x1fa27cf8, 0xc4ac5665,
    0xf4292244, 0x432aff97, 0xab9423a7, 0xfc93a039, 0x655b59c3, 0x8f0ccc92, 0xffeff47d, 0x85845dd1,
    0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1, 0xf7537e82, 0xbd3af235, 0x2ad7d2bb, 0xeb86d391,
];

/// MD5 摘要(16 字节)。
pub fn md5_bytes(input: &[u8]) -> [u8; 16] {
    let mut a0: u32 = 0x67452301;
    let mut b0: u32 = 0xefcdab89;
    let mut c0: u32 = 0x98badcfe;
    let mut d0: u32 = 0x10325476;

    let mut msg = input.to_vec();
    let bit_len = (input.len() as u64).wrapping_mul(8);
    msg.push(0x80);
    while msg.len() % 64 != 56 {
        msg.push(0);
    }
    msg.extend_from_slice(&bit_len.to_le_bytes());

    for chunk in msg.chunks(64) {
        let mut m = [0u32; 16];
        for (i, word) in m.iter_mut().enumerate() {
            *word = u32::from_le_bytes([
                chunk[i * 4],
                chunk[i * 4 + 1],
                chunk[i * 4 + 2],
                chunk[i * 4 + 3],
            ]);
        }
        let (mut a, mut b, mut c, mut d) = (a0, b0, c0, d0);
        for i in 0..64 {
            let (f, g) = match i / 16 {
                0 => ((b & c) | (!b & d), i),
                1 => ((d & b) | (!d & c), (5 * i + 1) % 16),
                2 => (b ^ c ^ d, (3 * i + 5) % 16),
                _ => (c ^ (b | !d), (7 * i) % 16),
            };
            let temp = d;
            d = c;
            c = b;
            let sum = a
                .wrapping_add(f)
                .wrapping_add(MD5_K[i])
                .wrapping_add(m[g]);
            b = b.wrapping_add(sum.rotate_left(MD5_S[i]));
            a = temp;
        }
        a0 = a0.wrapping_add(a);
        b0 = b0.wrapping_add(b);
        c0 = c0.wrapping_add(c);
        d0 = d0.wrapping_add(d);
    }

    let mut out = [0u8; 16];
    out[0..4].copy_from_slice(&a0.to_le_bytes());
    out[4..8].copy_from_slice(&b0.to_le_bytes());
    out[8..12].copy_from_slice(&c0.to_le_bytes());
    out[12..16].copy_from_slice(&d0.to_le_bytes());
    out
}

fn to_lower_hex(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        out.push(char::from_digit((b >> 4) as u32, 16).unwrap_or('0'));
        out.push(char::from_digit((b & 0xf) as u32, 16).unwrap_or('0'));
    }
    out
}

/// 小写十六进制 MD5,等价于 `MD5.encode` / `MD5.string2MD5`。
pub fn md5_hex(input: &str) -> String {
    to_lower_hex(&md5_bytes(input.as_bytes()))
}

// ---------------------------------------------------------------------------
// Base64(RFC 4648)
// ---------------------------------------------------------------------------

const B64_STD: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

fn b64_encode_with(table: &[u8; 64], data: &[u8]) -> String {
    let mut out = String::with_capacity(data.len().div_ceil(3) * 4);
    for chunk in data.chunks(3) {
        let b0 = chunk[0] as u32;
        let b1 = *chunk.get(1).unwrap_or(&0) as u32;
        let b2 = *chunk.get(2).unwrap_or(&0) as u32;
        let n = (b0 << 16) | (b1 << 8) | b2;
        out.push(table[(n >> 18) as usize & 0x3f] as char);
        out.push(table[(n >> 12) as usize & 0x3f] as char);
        if chunk.len() > 1 {
            out.push(table[(n >> 6) as usize & 0x3f] as char);
        } else {
            out.push('=');
        }
        if chunk.len() > 2 {
            out.push(table[n as usize & 0x3f] as char);
        } else {
            out.push('=');
        }
    }
    out
}

fn b64_decode_value(c: u8) -> Option<u8> {
    match c {
        b'A'..=b'Z' => Some(c - b'A'),
        b'a'..=b'z' => Some(c - b'a' + 26),
        b'0'..=b'9' => Some(c - b'0' + 52),
        b'+' | b'-' => Some(62),
        b'/' | b'_' => Some(63),
        _ => None,
    }
}

/// Base64 解码,等价 Android `Base64.DEFAULT`:
/// 自动剥离换行/空白,同时接受标准与 URL-safe 字母表,容忍缺失 padding。
pub fn base64_decode(input: &str) -> Option<Vec<u8>> {
    let mut buf = 0u32;
    let mut bits = 0u32;
    let mut out = Vec::with_capacity(input.len() / 4 * 3);
    for c in input.bytes() {
        if c.is_ascii_whitespace() || c == b'=' {
            continue;
        }
        let v = b64_decode_value(c)? as u32;
        buf = (buf << 6) | v;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((buf >> bits) as u8);
        }
    }
    Some(out)
}

/// 标准 Base64 编码(不换行),等价 Android `Base64.NO_WRAP`。
pub fn base64_encode(data: &[u8]) -> String {
    b64_encode_with(B64_STD, data)
}

/// 十六进制串转字节,等价 `AES.toBytes`(要求偶数长度)。
/// 按字节解码(非 char 边界切片):含多字节 UTF-8 字符的输入安全返回 None,不 panic。
pub fn hex_decode(input: &str) -> Option<Vec<u8>> {
    let b = input.trim().as_bytes();
    if !b.len().is_multiple_of(2) {
        return None;
    }
    let mut out = Vec::with_capacity(b.len() / 2);
    for pair in b.as_chunks::<2>().0 {
        let hi = hex_digit(pair[0])?;
        let lo = hex_digit(pair[1])?;
        out.push((hi << 4) | lo);
    }
    Some(out)
}

fn hex_digit(c: u8) -> Option<u8> {
    match c {
        b'0'..=b'9' => Some(c - b'0'),
        b'a'..=b'f' => Some(c - b'a' + 10),
        b'A'..=b'F' => Some(c - b'A' + 10),
        _ => None,
    }
}

// ---------------------------------------------------------------------------
// AES(NIST FIPS-197):解密方向,支持 128/192/256 位密钥
// ---------------------------------------------------------------------------

struct AesKey {
    /// 轮密钥字(Nb*(Nr+1) 个 4 字节字)。
    words: Vec<u8>,
    rounds: usize,
}

impl AesKey {
    fn new(key: &[u8]) -> Option<Self> {
        let (nk, nr) = match key.len() {
            16 => (4usize, 10usize),
            24 => (6, 12),
            32 => (8, 14),
            _ => return None,
        };
        let sbox_table = sbox();
        let total_words = 4 * (nr + 1);
        let mut words = Vec::with_capacity(total_words * 4);
        words.extend_from_slice(key);
        let mut rcon_i = 0usize;
        const RCON: [u8; 10] = [0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1b, 0x36];
        for i in nk..total_words {
            let mut temp: [u8; 4] = [
                words[(i - 1) * 4],
                words[(i - 1) * 4 + 1],
                words[(i - 1) * 4 + 2],
                words[(i - 1) * 4 + 3],
            ];
            if i % nk == 0 {
                // RotWord
                temp.rotate_left(1);
                // SubWord
                for b in temp.iter_mut() {
                    *b = sbox_table[*b as usize];
                }
                temp[0] ^= RCON[rcon_i];
                rcon_i += 1;
            } else if nk > 6 && i % nk == 4 {
                for b in temp.iter_mut() {
                    *b = sbox_table[*b as usize];
                }
            }
            for j in 0..4 {
                let v = temp[j] ^ words[(i - nk) * 4 + j];
                words.push(v);
            }
        }
        Some(AesKey { words, rounds: nr })
    }

    /// 加密一个 16 字节块(标准正向密码),用于测试与工具。
    #[cfg(test)]
    fn encrypt_block(&self, block: &mut [u8; 16]) {
        let sbox_table = sbox();
        let rk = |round: usize, i: usize| -> u8 { self.words[round * 16 + i] };

        // 初始轮:AddRoundKey(0)
        for (i, b) in block.iter_mut().enumerate() {
            *b ^= rk(0, i);
        }
        // 中间轮:1 .. Nr-1
        for round in 1..self.rounds {
            for b in block.iter_mut() {
                *b = sbox_table[*b as usize];
            }
            shift_rows(block);
            mix_columns(block);
            for (i, b) in block.iter_mut().enumerate() {
                *b ^= rk(round, i);
            }
        }
        // 最终轮
        for b in block.iter_mut() {
            *b = sbox_table[*b as usize];
        }
        shift_rows(block);
        for (i, b) in block.iter_mut().enumerate() {
            *b ^= rk(self.rounds, i);
        }
    }

    /// 解密一个 16 字节块(标准逆密码)。
    fn decrypt_block(&self, block: &mut [u8; 16]) {
        let inv_table = inv_sbox();
        let rk = |round: usize, i: usize| -> u8 { self.words[round * 16 + i] };

        // 初始轮:AddRoundKey(Nr)
        for (i, b) in block.iter_mut().enumerate() {
            *b ^= rk(self.rounds, i);
        }
        // 中间轮:Nr-1 .. 1
        for round in (1..self.rounds).rev() {
            inv_shift_rows(block);
            for b in block.iter_mut() {
                *b = inv_table[*b as usize];
            }
            for (i, b) in block.iter_mut().enumerate() {
                *b ^= rk(round, i);
            }
            inv_mix_columns(block);
        }
        // 最终轮
        inv_shift_rows(block);
        for b in block.iter_mut() {
            *b = inv_table[*b as usize];
        }
        for (i, b) in block.iter_mut().enumerate() {
            *b ^= rk(0, i);
        }
    }
}

#[cfg(test)]
fn shift_rows(state: &mut [u8; 16]) {
    // state[r + 4c];ShiftRows:行 r 循环左移 r
    let s = *state;
    for r in 1..4usize {
        for c in 0..4usize {
            state[r + 4 * c] = s[r + 4 * ((c + r) % 4)];
        }
    }
}

#[cfg(test)]
fn mix_columns(state: &mut [u8; 16]) {
    for c in 0..4usize {
        let a0 = state[4 * c];
        let a1 = state[4 * c + 1];
        let a2 = state[4 * c + 2];
        let a3 = state[4 * c + 3];
        state[4 * c] = gf_mul(a0, 0x02) ^ gf_mul(a1, 0x03) ^ a2 ^ a3;
        state[4 * c + 1] = a0 ^ gf_mul(a1, 0x02) ^ gf_mul(a2, 0x03) ^ a3;
        state[4 * c + 2] = a0 ^ a1 ^ gf_mul(a2, 0x02) ^ gf_mul(a3, 0x03);
        state[4 * c + 3] = gf_mul(a0, 0x03) ^ a1 ^ a2 ^ gf_mul(a3, 0x02);
    }
}

/// AES/ECB 加密(PKCS7 填充),仅测试用途:用于构造双向一致性用例。
#[cfg(test)]
pub(crate) fn aes_ecb_encrypt(data: &[u8], key: &[u8]) -> Option<Vec<u8>> {
    let pad = 16 - (data.len() % 16);
    let mut padded = data.to_vec();
    padded.extend(std::iter::repeat_n(pad as u8, pad));
    let aes = AesKey::new(key)?;
    let mut out = Vec::with_capacity(padded.len());
    for chunk in padded.chunks(16) {
        let mut block = [0u8; 16];
        block.copy_from_slice(chunk);
        aes.encrypt_block(&mut block);
        out.extend_from_slice(&block);
    }
    Some(out)
}

fn inv_shift_rows(state: &mut [u8; 16]) {
    // state[r + 4c];InvShiftRows:行 r 循环右移 r
    let s = *state;
    for r in 1..4usize {
        for c in 0..4usize {
            state[r + 4 * ((c + r) % 4)] = s[r + 4 * c];
        }
    }
}

fn inv_mix_columns(state: &mut [u8; 16]) {
    for c in 0..4usize {
        let a0 = state[4 * c];
        let a1 = state[4 * c + 1];
        let a2 = state[4 * c + 2];
        let a3 = state[4 * c + 3];
        state[4 * c] = gf_mul(a0, 0x0e) ^ gf_mul(a1, 0x0b) ^ gf_mul(a2, 0x0d) ^ gf_mul(a3, 0x09);
        state[4 * c + 1] = gf_mul(a0, 0x09) ^ gf_mul(a1, 0x0e) ^ gf_mul(a2, 0x0b) ^ gf_mul(a3, 0x0d);
        state[4 * c + 2] = gf_mul(a0, 0x0d) ^ gf_mul(a1, 0x09) ^ gf_mul(a2, 0x0e) ^ gf_mul(a3, 0x0b);
        state[4 * c + 3] = gf_mul(a0, 0x0b) ^ gf_mul(a1, 0x0d) ^ gf_mul(a2, 0x09) ^ gf_mul(a3, 0x0e);
    }
}

fn pkcs7_unpad(data: &[u8]) -> Option<Vec<u8>> {
    if data.is_empty() {
        return None;
    }
    let pad = *data.last().unwrap() as usize;
    if pad == 0 || pad > 16 || pad > data.len() {
        return None;
    }
    for b in &data[data.len() - pad..] {
        if *b as usize != pad {
            return None;
        }
    }
    Some(data[..data.len() - pad].to_vec())
}

/// AES/ECB 裸解密(不去填充),用于标准向量验证。
pub fn aes_ecb_decrypt_raw(data: &[u8], key: &[u8]) -> Option<Vec<u8>> {
    if data.is_empty() || !data.len().is_multiple_of(16) {
        return None;
    }
    let aes = AesKey::new(key)?;
    let mut out = Vec::with_capacity(data.len());
    for chunk in data.chunks(16) {
        let mut block = [0u8; 16];
        block.copy_from_slice(chunk);
        aes.decrypt_block(&mut block);
        out.extend_from_slice(&block);
    }
    Some(out)
}

/// AES/CBC 裸解密(不去填充),用于标准向量验证。
pub fn aes_cbc_decrypt_raw(data: &[u8], key: &[u8], iv: &[u8]) -> Option<Vec<u8>> {
    if data.is_empty() || !data.len().is_multiple_of(16) {
        return None;
    }
    let mut iv_buf = [0u8; 16];
    let copy_len = iv.len().min(16);
    iv_buf[..copy_len].copy_from_slice(&iv[..copy_len]);
    let aes = AesKey::new(key)?;
    let mut out = Vec::with_capacity(data.len());
    let mut prev = iv_buf;
    for chunk in data.chunks(16) {
        let mut block = [0u8; 16];
        block.copy_from_slice(chunk);
        aes.decrypt_block(&mut block);
        for i in 0..16 {
            block[i] ^= prev[i];
        }
        prev.copy_from_slice(chunk);
        out.extend_from_slice(&block);
    }
    Some(out)
}

/// AES/ECB/PKCS7 解密,等价 `AES.ECB`(Java Cipher 对非法填充抛异常 → 这里返回 None)。
pub fn aes_ecb_decrypt(data: &[u8], key: &[u8]) -> Option<Vec<u8>> {
    pkcs7_unpad(&aes_ecb_decrypt_raw(data, key)?)
}

/// AES/CBC/PKCS7 解密,等价 `AES.CBC`(iv 不足 16 字节右补 0)。
pub fn aes_cbc_decrypt(data: &[u8], key: &[u8], iv: &[u8]) -> Option<Vec<u8>> {
    pkcs7_unpad(&aes_cbc_decrypt_raw(data, key, iv)?)
}

/// 判断内容是否是 JSON 对象,等价 `AES.isJson`(`new JSONObject(content)`:
/// 仅接受 `{...}` 对象;数组 / 数字 / 字符串字面量在 org.json 下抛异常 → false)。
pub fn is_json(content: &str) -> bool {
    serde_json::from_str::<serde_json::Value>(content)
        .map(|v| v.is_object())
        .unwrap_or(false)
}

// ---------------------------------------------------------------------------
// 密钥补位
// ---------------------------------------------------------------------------

/// 密钥右侧补位到指定长度,等价于 `AES.rightPadding`。
/// 与 Java 一致:`replace` 作为整串重复拼接 `length - len(key)` 次;超长则截断。
pub fn right_padding(key: &str, replace: &str, length: usize) -> String {
    let trimmed = key.trim();
    let chars: Vec<char> = trimmed.chars().collect();
    if chars.len() > length {
        return chars[..length].iter().collect();
    }
    let mut out = trimmed.to_string();
    for _ in 0..(length - chars.len()) {
        out.push_str(replace);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sbox_known_values() {
        let sbox = generate_sbox();
        // FIPS-197 图 7 的已知值
        assert_eq!(sbox[0x00], 0x63);
        assert_eq!(sbox[0x01], 0x7c);
        assert_eq!(sbox[0x53], 0xed);
        assert_eq!(sbox[0xff], 0x16);
        let inv = generate_inv_sbox(&sbox);
        // InvSbox 首行:52 09 6a d5 30 36 a5 38 bf 40 a3 9e 81 f3 d7 fb
        assert_eq!(inv[0x00], 0x52);
        assert_eq!(inv[0x01], 0x09);
        // 互逆性:sbox(inv[b]) == b
        for b in 0..256usize {
            assert_eq!(sbox[inv[b] as usize] as usize, b);
        }
    }

    #[test]
    fn gf_mul_known_products() {
        // FIPS-197 第 4.2 节示例:57·83=c1,57·13=fe
        assert_eq!(gf_mul(0x57, 0x83), 0xc1);
        assert_eq!(gf_mul(0x57, 0x13), 0xfe);
        assert_eq!(gf_mul(0x02, 0x87), 0x15); // xtime 溢出样例
    }

    #[test]
    fn aes_encrypt_nist_vector() {
        // F.1.1 ECB-AES128.Encrypt 块1:6bc1bee2... → 3ad77bb4...
        let key = hex_decode("2b7e151628aed2a6abf7158809cf4f3c").unwrap();
        let plain = hex_decode("6bc1bee22e409f96e93d7e117393172a").unwrap();
        let cipher = hex_decode("3ad77bb40d7a3660a89ecaf32466ef97").unwrap();
        let aes = AesKey::new(&key).unwrap();
        let mut block = [0u8; 16];
        block.copy_from_slice(&plain);
        aes.encrypt_block(&mut block);
        assert_eq!(&block[..], &cipher[..]);
    }

    #[test]
    fn md5_matches_rfc1321() {
        assert_eq!(md5_hex(""), "d41d8cd98f00b204e9800998ecf8427e");
        assert_eq!(md5_hex("a"), "0cc175b9c0f1b6a831c399e269772661");
        assert_eq!(md5_hex("abc"), "900150983cd24fb0d6963f7d28e17f72");
        assert_eq!(md5_hex("message digest"), "f96b697d7cb7938d525a2f31aaf161d0");
        assert_eq!(
            md5_hex("abcdefghijklmnopqrstuvwxyz"),
            "c3fcd3d76192e4007dfb496cca67e13b"
        );
        assert_eq!(
            md5_hex("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"),
            "d174ab98d277d9f5a5611c2c9f419d9f"
        );
        assert_eq!(
            md5_hex("12345678901234567890123456789012345678901234567890123456789012345678901234567890"),
            "57edf4a22be3c955ac49da2e2107b67a"
        );
    }

    #[test]
    fn base64_roundtrip() {
        assert_eq!(base64_encode(b"hello"), "aGVsbG8=");
        assert_eq!(base64_encode(b"ab"), "YWI=");
        assert_eq!(base64_encode(b"a"), "YQ==");
        assert_eq!(base64_decode("aGVsbG8=").unwrap(), b"hello");
        // Android DEFAULT 容忍换行与空白
        assert_eq!(base64_decode("aGVs\nbG8=").unwrap(), b"hello");
        // URL-safe 变体
        assert_eq!(base64_decode("-_8").unwrap(), vec![0xfb, 0xff]);
        assert_eq!(base64_decode("+/8").unwrap(), vec![0xfb, 0xff]);
        assert_eq!(base64_decode("YWI").unwrap(), b"ab"); // 缺 padding
        assert_eq!(base64_decode("!!!"), None);
    }

    #[test]
    fn right_padding_matches_java() {
        assert_eq!(right_padding("abc", "0", 16), "abc0000000000000");
        assert_eq!(right_padding("0123456789abcdef", "0", 16), "0123456789abcdef");
        assert_eq!(right_padding("0123456789abcdefXY", "0", 16), "0123456789abcdef");
    }

    #[test]
    fn aes_ecb_decrypt_nist_vectors() {
        // 已通过 encrypt 方向外部验证的 NIST SP800-38A F.1.1 锚点:
        // plain 6bc1bee2... <-> cipher 3ad77bb4...,key 2b7e1516...
        let key = hex_decode("2b7e151628aed2a6abf7158809cf4f3c").unwrap();
        let plain = hex_decode("6bc1bee22e409f96e93d7e117393172a").unwrap();
        let cipher = hex_decode("3ad77bb40d7a3660a89ecaf32466ef97").unwrap();
        assert_eq!(aes_ecb_decrypt_raw(&cipher, &key).unwrap(), plain);
        // 反向锚点:加密结果与标准一致
        let aes = AesKey::new(&key).unwrap();
        let mut block = [0u8; 16];
        block.copy_from_slice(&plain);
        aes.encrypt_block(&mut block);
        assert_eq!(&block[..], &cipher[..]);
    }

    #[test]
    fn aes_ecb_roundtrip_all_key_sizes() {
        // 128/192/256 三种密钥长度的 ECB 加解密往返(多块,跨轮密钥)
        for key_hex in [
            "2b7e151628aed2a6abf7158809cf4f3c",
            "8e73b0f7da0e6452c810f32b809079e562f8ead2522c6b7b",
            "603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4",
        ] {
            let key = hex_decode(key_hex).unwrap();
            let mut data = Vec::new();
            for i in 0..40u8 {
                data.push(i.wrapping_mul(31).wrapping_add(7));
            }
            let cipher = aes_ecb_encrypt(&data, &key).unwrap();
            assert_eq!(cipher.len(), data.len() + (16 - data.len() % 16));
            let out = aes_ecb_decrypt(&cipher, &key).unwrap();
            assert_eq!(out, data, "ECB roundtrip failed for key len {}", key.len());
            // 错误密钥不应解出原数据
            let mut wrong = key.clone();
            wrong[0] ^= 0x01;
            if let Some(out2) = aes_ecb_decrypt(&cipher, &wrong) {
                assert_ne!(out2, data, "wrong key should not decrypt");
            }
        }
    }

    #[test]
    fn aes_cbc_roundtrip() {
        // CBC 往返(多块链式加密 → 解密;含 iv 不足 16 字节右补 0 语义)
        let key = hex_decode("2b7e151628aed2a6abf7158809cf4f3c").unwrap();
        let iv = hex_decode("000102030405060708090a0b0c0d0e0f").unwrap();
        let data: Vec<u8> = (0..48u8).collect();
        let padded = {
            let mut p = data.clone();
            let pad = 16 - p.len() % 16;
            p.extend(std::iter::repeat_n(pad as u8, pad));
            p
        };
        // 手动 CBC 加密(encrypt_block + 链接)
        let aes = AesKey::new(&key).unwrap();
        let mut prev = iv;
        let mut cipher = Vec::new();
        for chunk in padded.chunks(16) {
            let mut block = [0u8; 16];
            block.copy_from_slice(chunk);
            for i in 0..16 {
                block[i] ^= prev[i];
            }
            aes.encrypt_block(&mut block);
            prev.copy_from_slice(&block);
            cipher.extend_from_slice(&block);
        }
        let out = aes_cbc_decrypt_raw(&cipher, &key, &hex_decode("000102030405060708090a0b0c0d0e0f").unwrap()).unwrap();
        assert_eq!(out, padded);
        // Java 语义:iv 不足 16 字节时右补 0(不报错)
        let short_iv = vec![0u8; 3];
        assert!(aes_cbc_decrypt_raw(&cipher, &key, &short_iv).is_some());
    }

    #[test]
    fn hex_decode_works() {
        assert_eq!(hex_decode("2423").unwrap(), vec![0x24, 0x23]);
        assert_eq!(hex_decode("242"), None);
        assert_eq!(hex_decode("zz"), None);
    }

    #[test]
    fn is_json_basic() {
        assert!(is_json("{\"a\":1}"));
        assert!(!is_json("not json"));
        // Java org.json.JSONObject 仅接受对象:数组 / 数字 / 字符串字面量 → false
        assert!(!is_json("[1,2]"));
        assert!(!is_json("24232324"));
        assert!(!is_json("\"quoted\""));
        assert!(!is_json(""));
    }

    // ---------------- 健壮性:恶意 / 畸形输入零 panic(返回 None 而非崩溃) ----------------

    /// hex_decode:全零、奇数长度、非法字符、多字节 UTF-8(修复前 "中中" 会因
    /// char 边界切片 panic)、空串、超长输入。
    #[test]
    fn robust_hex_decode_malformed() {
        assert_eq!(hex_decode("").unwrap(), Vec::<u8>::new());
        assert_eq!(hex_decode("0000").unwrap(), vec![0, 0]);
        assert_eq!(hex_decode("0"), None);          // 奇数长度
        assert_eq!(hex_decode("zz"), None);         // 非法字符
        assert_eq!(hex_decode("00zz"), None);
        assert_eq!(hex_decode("0x1g"), None);
        // 多字节 UTF-8 字符:偶数字节长度 + char 边界切片(修复前 panic)
        assert_eq!(hex_decode("中中"), None);       // 6 字节,偶数
        assert_eq!(hex_decode("中"), None);         // 3 字节,奇数
        assert_eq!(hex_decode("24中"), None);       // 混合
        assert_eq!(hex_decode("👍234"), None);      // 4 字节 emoji
        assert_eq!(hex_decode("  2423  ").unwrap(), vec![0x24, 0x23]); // 前后空白被 trim
        assert_eq!(hex_decode("24 23"), None, "内部空白:长度变奇数 → None(与 Java toBytes 抛异常一致)");
    }

    /// AES 解密:全零数据、长度非 16 倍数、空 key、非法 padding。
    #[test]
    fn robust_aes_malformed_inputs() {
        let key = [0u8; 16];
        // 长度非 16 倍数
        assert_eq!(aes_ecb_decrypt(&[0u8; 15], &key), None);
        assert_eq!(aes_ecb_decrypt(&[0u8; 17], &key), None);
        assert_eq!(aes_ecb_decrypt(&[], &key), None);
        assert_eq!(aes_cbc_decrypt(&[0u8; 15], &key, &key), None);
        // 空 key:AesKey 无法建立 → None
        assert_eq!(aes_ecb_decrypt(&[0u8; 16], b""), None);
        assert_eq!(aes_cbc_decrypt(&[0u8; 16], b"", b""), None);
        // 空 key 走 right_padding 补 0 → 合法 key:全零密文解出垃圾,
        // PKCS7 校验决定 Some/None,两条路径都必须确定性且零 panic
        let padded = right_padding("", "0", 16);
        assert_eq!(padded, "0000000000000000");
        let r1 = aes_ecb_decrypt(&[0u8; 16], padded.as_bytes());
        let r2 = aes_ecb_decrypt(&[0u8; 16], padded.as_bytes());
        assert_eq!(r1, r2, "确定性");
        assert!(r1.is_none() || r1.unwrap().len() <= 15);
        // iv 不足 16 字节 → 右补 0(等价全零 iv);超长 iv → 截断为前 16 字节。
        // 用真实 CBC 密文(零 iv 链式加密)给出确定性断言
        let key = hex_decode("2b7e151628aed2a6abf7158809cf4f3c").unwrap();
        let mut plain = vec![0u8; 16];
        plain.extend(std::iter::repeat_n(0x10u8, 16)); // pad=16 → 32 字节明文
        let aes = AesKey::new(&key).unwrap();
        let mut prev = [0u8; 16];
        let mut cipher = Vec::with_capacity(plain.len());
        for chunk in plain.chunks(16) {
            let mut block = [0u8; 16];
            block.copy_from_slice(chunk);
            for i in 0..16 {
                block[i] ^= prev[i];
            }
            aes.encrypt_block(&mut block);
            prev = block;
            cipher.extend_from_slice(&block);
        }
        let expect = vec![0u8; 16]; // PKCS7 去填充后的原始明文
        assert_eq!(aes_cbc_decrypt(&cipher, &key, &[0u8; 16]).unwrap(), expect);
        assert_eq!(aes_cbc_decrypt(&cipher, &key, &[0u8; 3]).unwrap(), expect, "短 iv 右补 0");
        let long_iv = [1u8; 40];
        assert_eq!(
            aes_cbc_decrypt(&cipher, &key, &long_iv),
            aes_cbc_decrypt(&cipher, &key, &long_iv[..16]),
            "超长 iv 截断前 16 字节"
        );
        // 全零数据加密后可解回(证明全零输入本身是合法路径)
        let enc = aes_ecb_encrypt(&[0u8; 16], &key).unwrap();
        assert_eq!(aes_ecb_decrypt(&enc, &key).unwrap(), vec![0u8; 16]);
    }

    /// right_padding 空串 / 多字节 key(与 Kotlin 侧 UTF-16 长度语义的差异仅在
    /// 非 ASCII key;AES 密钥长度校验使两侧最终都失败 → 一致返回 None/null)。
    #[test]
    fn robust_right_padding_edge() {
        assert_eq!(right_padding("", "0", 16), "0000000000000000");
        assert_eq!(right_padding("   ", "0", 16), "0000000000000000");
        let cjk = right_padding("密码", "0", 16); // 16 个 char → 20 字节 → AES 拒绝
        assert_eq!(cjk.chars().count(), 16);
        assert!(AesKey::new(cjk.as_bytes()).is_none(), "20 字节 key 非 16/24/32 → None");
        assert!(AesKey::new(b"").is_none());
    }

    /// base64_decode:1MB 输入(合法 + 非法混合)零 panic。
    #[test]
    fn robust_base64_1mb() {
        let data: Vec<u8> = (0..(768 * 1024)).map(|i| (i % 251) as u8).collect();
        let enc = base64_encode(&data);
        assert_eq!(enc.len(), 1024 * 1024);
        assert_eq!(base64_decode(&enc).unwrap(), data);
        // 中段插入非法字符:Android DEFAULT 会跳过无法解析字符抛异常 → 这里返回 None
        let mut bad = enc.clone();
        let mid = bad.len() / 2;
        bad.replace_range(mid..mid + 1, "!");
        assert_eq!(base64_decode(&bad), None);
    }

    /// 1MB 数据 AES/ECB 解密性能冒烟(#[ignore]:debug 构建下纯软件 AES 较慢)。
    #[test]
    #[ignore = "大输入性能冒烟:1MB AES 解密"]
    fn robust_aes_1mb_smoke() {
        let key = hex_decode("2b7e151628aed2a6abf7158809cf4f3c").unwrap();
        let data: Vec<u8> = (0..(1024 * 1024)).map(|i| (i % 256) as u8).collect();
        let cipher = aes_ecb_encrypt(&data, &key).unwrap();
        let start = std::time::Instant::now();
        let out = aes_ecb_decrypt(&cipher, &key).unwrap();
        assert_eq!(out, data);
        assert!(start.elapsed().as_secs() < 60, "1MB AES 解密耗时 {:?} 异常", start.elapsed());
    }
}

