//! Secret redaction for logs and secret scanning for generated files.
//!
//! Two layers:
//! - **Known values:** every secret the key library loads is registered here, so
//!   its exact text is scrubbed wherever it appears.
//! - **Patterns:** common API-key shapes (`sk-…`, `gsk_…`, `AIza…`, `Bearer …`,
//!   `api_key=…`) are scrubbed even if the value was never registered.

use std::borrow::Cow;
use std::io::{self, Write};
use std::sync::{OnceLock, RwLock};

use regex::Regex;
use tracing_subscriber::fmt::MakeWriter;

use crate::Secret;

pub const REDACTED: &str = "[redacted]";

/// Registered values shorter than this are ignored; they would cause false hits.
const MIN_KNOWN_LEN: usize = 8;

struct Pattern {
    regex: Regex,
    /// Replacement; `${1}` keeps a non-secret prefix such as `Bearer `.
    replacement: &'static str,
    /// Strong patterns are specific enough to flag in generated files.
    strong: bool,
}

pub struct Redactor {
    known: RwLock<Vec<String>>,
    patterns: Vec<Pattern>,
}

impl Default for Redactor {
    fn default() -> Self {
        Self::new()
    }
}

impl Redactor {
    pub fn new() -> Self {
        let p = |re: &str, replacement: &'static str, strong: bool| Pattern {
            regex: Regex::new(re).expect("valid redaction regex"),
            replacement,
            strong,
        };
        Self {
            known: RwLock::new(Vec::new()),
            patterns: vec![
                p(r"\bsk-ant-[A-Za-z0-9_\-]{16,}", REDACTED, true),
                p(r"\bsk-(?:or-v1-|proj-)?[A-Za-z0-9_\-]{20,}", REDACTED, true),
                p(r"\bgsk_[A-Za-z0-9]{20,}", REDACTED, true),
                p(r"\bAIza[0-9A-Za-z_\-]{30,}", REDACTED, true),
                p(r"\btvly-[A-Za-z0-9_\-]{16,}", REDACTED, true),
                p(r"(?i)\b(bearer\s+)[A-Za-z0-9._~+/=\-]{12,}", "${1}[redacted]", false),
                p(
                    r#"(?i)((?:api[_-]?key|x-api-key|token|secret|password|x-subscription-token)["']?\s*[:=]\s*["']?)[A-Za-z0-9._~+/\-]{12,}"#,
                    "${1}[redacted]",
                    false,
                ),
            ],
        }
    }

    /// Start scrubbing this secret's exact value.
    pub fn register(&self, secret: &Secret) {
        let value = secret.expose();
        if value.len() < MIN_KNOWN_LEN {
            return;
        }
        let mut known = self.known.write().unwrap_or_else(|e| e.into_inner());
        if !known.iter().any(|k| k == value) {
            known.push(value.to_owned());
            // Longest first, so a secret containing another is scrubbed whole.
            known.sort_by_key(|k| std::cmp::Reverse(k.len()));
        }
    }

    /// Stop tracking a secret (after it is deleted from the key library).
    pub fn forget(&self, secret: &Secret) {
        let mut known = self.known.write().unwrap_or_else(|e| e.into_inner());
        known.retain(|k| k != secret.expose());
    }

    /// Replace every known secret and secret-shaped token with `[redacted]`.
    pub fn redact<'a>(&self, text: &'a str) -> Cow<'a, str> {
        let mut out = Cow::Borrowed(text);
        {
            let known = self.known.read().unwrap_or_else(|e| e.into_inner());
            for value in known.iter() {
                if out.contains(value.as_str()) {
                    out = Cow::Owned(out.replace(value.as_str(), REDACTED));
                }
            }
        }
        for pattern in &self.patterns {
            if pattern.regex.is_match(&out) {
                out = Cow::Owned(pattern.regex.replace_all(&out, pattern.replacement).into_owned());
            }
        }
        out
    }

    /// Whether `text` contains a registered secret or a strongly key-shaped token.
    /// Used to refuse writing generated files that would leak a key.
    pub fn contains_secret(&self, text: &str) -> bool {
        let known = self.known.read().unwrap_or_else(|e| e.into_inner());
        known.iter().any(|k| text.contains(k.as_str()))
            || self.patterns.iter().any(|p| p.strong && p.regex.is_match(text))
    }
}

/// The process-wide redactor used by logging and the key library.
pub fn global() -> &'static Redactor {
    static GLOBAL: OnceLock<Redactor> = OnceLock::new();
    GLOBAL.get_or_init(Redactor::new)
}

/// Wraps a `tracing` writer so every formatted log line is redacted first.
pub struct RedactingMakeWriter<M> {
    inner: M,
}

impl<M> RedactingMakeWriter<M> {
    pub fn new(inner: M) -> Self {
        Self { inner }
    }
}

impl<'a, M: MakeWriter<'a>> MakeWriter<'a> for RedactingMakeWriter<M> {
    type Writer = RedactingWriter<M::Writer>;

    fn make_writer(&'a self) -> Self::Writer {
        RedactingWriter { inner: self.inner.make_writer() }
    }
}

pub struct RedactingWriter<W> {
    inner: W,
}

impl<W: Write> Write for RedactingWriter<W> {
    // `tracing-subscriber` formats a whole event before writing it, so each call
    // carries complete lines and a secret is never split across calls.
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        let text = String::from_utf8_lossy(buf);
        self.inner.write_all(global().redact(&text).as_bytes())?;
        Ok(buf.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        self.inner.flush()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn scrubs_registered_values() {
        let r = Redactor::new();
        r.register(&Secret::new("plain-looking-value-123"));
        assert_eq!(r.redact("key is plain-looking-value-123!"), "key is [redacted]!");
    }

    #[test]
    fn ignores_short_registered_values() {
        let r = Redactor::new();
        r.register(&Secret::new("abc"));
        assert_eq!(r.redact("abcdef"), "abcdef");
    }

    #[test]
    fn scrubs_key_shapes() {
        let r = Redactor::new();
        let cases = [
            ("using gsk_abcdefghijklmnopqrstuvwx now", "using [redacted] now"),
            ("Authorization: Bearer abc.def-ghi_jkl123", "Authorization: Bearer [redacted]"),
            ("api_key=\"abcdefghijklmnop\"", "api_key=\"[redacted]\""),
            ("AIzaSyA1234567890abcdefghijklmnopqrstu", "[redacted]"),
            ("sk-or-v1-0123456789abcdef0123", "[redacted]"),
        ];
        for (input, expected) in cases {
            assert_eq!(r.redact(input), expected, "input: {input}");
        }
    }

    #[test]
    fn leaves_ordinary_text_alone() {
        let r = Redactor::new();
        let text = "Set TAVILY_API_KEY in your environment; see docs/PROVIDERS.md";
        assert!(matches!(r.redact(text), Cow::Borrowed(_)));
        assert!(!r.contains_secret(text));
    }

    #[test]
    fn contains_secret_flags_known_and_strong_patterns() {
        let r = Redactor::new();
        r.register(&Secret::new("my-fish-audio-key-0001"));
        assert!(r.contains_secret("FISH=my-fish-audio-key-0001"));
        assert!(r.contains_secret("export KEY=gsk_abcdefghijklmnopqrstuvwx"));
        assert!(!r.contains_secret("api_key = your-key-goes-here"));
    }

    #[test]
    fn forget_stops_scrubbing() {
        let r = Redactor::new();
        let s = Secret::new("temporary-secret-xyz");
        r.register(&s);
        r.forget(&s);
        assert_eq!(r.redact("temporary-secret-xyz"), "temporary-secret-xyz");
    }
}
