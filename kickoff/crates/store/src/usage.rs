//! Per-provider daily usage for the free-tier meter.

use rusqlite::params;
use serde::Serialize;
use ts_rs::TS;

use crate::{Database, Result};

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct UsageDelta {
    pub calls: u32,
    pub prompt_tokens: u32,
    pub completion_tokens: u32,
    /// Provider-specific unit: TTS bytes, STT audio seconds, search credits.
    pub units: u64,
    pub rate_limited: u32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, TS)]
#[ts(export)]
pub struct UsageDay {
    pub provider_id: String,
    pub day: String,
    #[ts(type = "number")]
    pub calls: u64,
    #[ts(type = "number")]
    pub prompt_tokens: u64,
    #[ts(type = "number")]
    pub completion_tokens: u64,
    #[ts(type = "number")]
    pub units: u64,
    #[ts(type = "number")]
    pub rate_limited: u64,
}

impl Database {
    pub fn record_usage(&self, provider_id: &str, day: &str, delta: UsageDelta) -> Result<()> {
        self.conn().execute(
            "INSERT INTO usage_daily (provider_id, day, calls, prompt_tokens, completion_tokens, units, rate_limited)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)
             ON CONFLICT(provider_id, day) DO UPDATE SET
               calls = calls + excluded.calls,
               prompt_tokens = prompt_tokens + excluded.prompt_tokens,
               completion_tokens = completion_tokens + excluded.completion_tokens,
               units = units + excluded.units,
               rate_limited = rate_limited + excluded.rate_limited",
            params![
                provider_id,
                day,
                delta.calls,
                delta.prompt_tokens,
                delta.completion_tokens,
                delta.units as i64,
                delta.rate_limited
            ],
        )?;
        Ok(())
    }

    /// Usage rows for one day, all providers.
    pub fn usage_for_day(&self, day: &str) -> Result<Vec<UsageDay>> {
        let conn = self.conn();
        let mut stmt = conn.prepare(
            "SELECT provider_id, day, calls, prompt_tokens, completion_tokens, units, rate_limited
             FROM usage_daily WHERE day = ?1 ORDER BY provider_id",
        )?;
        let rows = stmt
            .query_map(params![day], |r| {
                Ok(UsageDay {
                    provider_id: r.get(0)?,
                    day: r.get(1)?,
                    calls: r.get::<_, i64>(2)? as u64,
                    prompt_tokens: r.get::<_, i64>(3)? as u64,
                    completion_tokens: r.get::<_, i64>(4)? as u64,
                    units: r.get::<_, i64>(5)? as u64,
                    rate_limited: r.get::<_, i64>(6)? as u64,
                })
            })?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        Ok(rows)
    }

    /// Sum of `units` for a provider over days starting with `month` (`YYYY-MM`).
    pub fn units_for_month(&self, provider_id: &str, month: &str) -> Result<u64> {
        let total: i64 = self.conn().query_row(
            "SELECT COALESCE(SUM(units), 0) FROM usage_daily WHERE provider_id = ?1 AND day LIKE ?2 || '-%'",
            params![provider_id, month],
            |r| r.get(0),
        )?;
        Ok(total as u64)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accumulates_per_day() {
        let db = Database::open_in_memory().unwrap();
        let call = UsageDelta { calls: 1, prompt_tokens: 100, completion_tokens: 20, ..Default::default() };
        db.record_usage("gemini", "2026-10-11", call).unwrap();
        db.record_usage("gemini", "2026-10-11", call).unwrap();
        db.record_usage("gemini", "2026-10-11", UsageDelta { rate_limited: 1, ..Default::default() }).unwrap();
        db.record_usage("fish_audio", "2026-10-11", UsageDelta { units: 1200, ..Default::default() }).unwrap();
        db.record_usage("fish_audio", "2026-10-02", UsageDelta { units: 300, ..Default::default() }).unwrap();
        db.record_usage("gemini", "2026-10-10", call).unwrap();

        let today = db.usage_for_day("2026-10-11").unwrap();
        let gemini = today.iter().find(|u| u.provider_id == "gemini").unwrap();
        assert_eq!((gemini.calls, gemini.prompt_tokens, gemini.rate_limited), (2, 200, 1));
        assert_eq!(db.units_for_month("fish_audio", "2026-10").unwrap(), 1500);
    }
}
