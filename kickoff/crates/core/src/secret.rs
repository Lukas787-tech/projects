//! A string secret that never prints itself.

use std::fmt;

use secrecy::{ExposeSecret, SecretString};

/// An API key or other credential held in memory.
///
/// `Debug` and `Display` print `[redacted]`, and the type deliberately does not
/// implement `Serialize`, so a secret cannot end up in logs, IPC payloads or the
/// database by accident. Call [`Secret::expose`] at the single point where the
/// raw value is needed (an HTTP header, the credential store).
#[derive(Clone)]
pub struct Secret(SecretString);

impl Secret {
    pub fn new(value: impl Into<String>) -> Self {
        Self(SecretString::from(value.into()))
    }

    /// The raw value. Keep the borrow short and never log it.
    pub fn expose(&self) -> &str {
        self.0.expose_secret()
    }

    pub fn is_empty(&self) -> bool {
        self.expose().is_empty()
    }
}

impl From<String> for Secret {
    fn from(value: String) -> Self {
        Self::new(value)
    }
}

impl fmt::Debug for Secret {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("Secret([redacted])")
    }
}

impl fmt::Display for Secret {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("[redacted]")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn debug_and_display_hide_the_value() {
        let s = Secret::new("gsk_supersecretvalue1234567890");
        assert!(!format!("{s:?}").contains("supersecret"));
        assert!(!format!("{s}").contains("supersecret"));
        assert_eq!(s.expose(), "gsk_supersecretvalue1234567890");
    }
}
