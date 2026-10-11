//! Timestamp helpers. Everything is stored as RFC 3339 UTC text.

use time::OffsetDateTime;
use time::format_description::well_known::Rfc3339;

pub fn now_rfc3339() -> String {
    OffsetDateTime::now_utc().format(&Rfc3339).expect("RFC 3339 formatting cannot fail for UTC")
}

/// Today's date in the user's local time zone (`YYYY-MM-DD`), which is how
/// free-tier daily quotas are shown. Falls back to UTC if the offset is unknown.
pub fn local_day() -> String {
    let now = OffsetDateTime::now_local().unwrap_or_else(|_| OffsetDateTime::now_utc());
    let d = now.date();
    format!("{:04}-{:02}-{:02}", d.year(), u8::from(d.month()), d.day())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn formats() {
        assert!(now_rfc3339().ends_with('Z'));
        let day = local_day();
        assert_eq!(day.len(), 10);
        assert_eq!(&day[4..5], "-");
    }
}
