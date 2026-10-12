//! Provider presets and clients.

pub mod anthropic;
pub mod factory;
pub mod http;
pub mod ollama;
pub mod openai_compat;
pub mod presets;
mod schema;

pub use factory::{FactoryError, build_llm};
pub use presets::{Presets, PresetsError};
