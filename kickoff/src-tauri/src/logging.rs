//! Log setup: daily rolling files in the app's log folder, every line redacted.

use std::path::Path;

use kickoff_core::redact::RedactingMakeWriter;
use tracing_appender::non_blocking::WorkerGuard;
use tracing_appender::rolling::{Builder, Rotation};
use tracing_subscriber::layer::SubscriberExt;
use tracing_subscriber::util::SubscriberInitExt;
use tracing_subscriber::{EnvFilter, fmt};

/// Keep this many daily log files.
const MAX_LOG_FILES: usize = 7;

/// Start logging. Keep the returned guard alive for the life of the app so
/// buffered lines are flushed on exit. Override the level with `KICKOFF_LOG`.
pub fn init(log_dir: &Path) -> Option<WorkerGuard> {
    let filter = EnvFilter::try_from_env("KICKOFF_LOG").unwrap_or_else(|_| EnvFilter::new("info"));

    let file = Builder::new()
        .rotation(Rotation::DAILY)
        .filename_prefix("kickoff")
        .filename_suffix("log")
        .max_log_files(MAX_LOG_FILES)
        .build(log_dir);

    let stderr = fmt::layer().with_writer(RedactingMakeWriter::new(std::io::stderr));

    let (file_layer, guard, file_error) = match file {
        Ok(appender) => {
            let (writer, guard) = tracing_appender::non_blocking(appender);
            let layer = fmt::layer().with_ansi(false).with_writer(RedactingMakeWriter::new(writer));
            (Some(layer), Some(guard), None)
        }
        Err(e) => (None, None, Some(e)),
    };

    tracing_subscriber::registry().with(filter).with(file_layer).with(stderr).try_init().ok();
    if let Some(e) = file_error {
        tracing::error!(error = %e, "could not open log directory; logging to stderr only");
    }
    guard
}
