//! Provider presets and clients.

pub mod anthropic;
pub mod assets;
pub mod factory;
pub mod http;
pub mod ollama;
pub mod openai_compat;
pub mod presets;
mod schema;
pub mod search;
pub mod sources;

pub use factory::{FactoryError, build_llm};
pub use presets::{Presets, PresetsError};
pub use search::{SearchBuildError, build_search};
pub use sources::{SourceCatalog, SourcesError};
