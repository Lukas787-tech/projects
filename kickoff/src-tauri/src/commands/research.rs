//! Research and reference triage. Runs report progress through a channel.

use kickoff_core::research::pipeline::{BriefProposal, ResearchProgress};
use kickoff_store::{Reaction, StoredRef};
use tauri::State;
use tauri::ipc::Channel;

use crate::error::CmdResult;
use crate::research::{ResearchRun, ResearchView};
use crate::state::AppState;

#[tauri::command(rename_all = "snake_case")]
pub async fn get_research(state: State<'_, AppState>, project_id: String) -> CmdResult<ResearchView> {
    Ok(state.research.get(&project_id).await?)
}

/// Search for comparables, references and assets from the brief.
#[tauri::command(rename_all = "snake_case")]
pub async fn run_research(
    state: State<'_, AppState>,
    project_id: String,
    on_progress: Channel<ResearchProgress>,
) -> CmdResult<ResearchRun> {
    let progress = |p: ResearchProgress| {
        let _ = on_progress.send(p);
    };
    Ok(state.research.run(&project_id, &progress).await?)
}

/// Mark a reference "more like this" and search for similar ones.
#[tauri::command(rename_all = "snake_case")]
pub async fn more_like_this(
    state: State<'_, AppState>,
    project_id: String,
    ref_id: String,
    on_progress: Channel<ResearchProgress>,
) -> CmdResult<ResearchRun> {
    let progress = |p: ResearchProgress| {
        let _ = on_progress.send(p);
    };
    Ok(state.research.more_like_this(&project_id, &ref_id, &progress).await?)
}

/// Keep or reject a reference; `null` undoes the reaction.
#[tauri::command(rename_all = "snake_case")]
pub async fn react_to_reference(
    state: State<'_, AppState>,
    project_id: String,
    ref_id: String,
    reaction: Option<Reaction>,
) -> CmdResult<StoredRef> {
    Ok(state.research.react(&project_id, &ref_id, reaction).await?)
}

/// The reference's thumbnail as a `data:` URL, or `null`.
#[tauri::command(rename_all = "snake_case")]
pub async fn reference_thumbnail(
    state: State<'_, AppState>,
    project_id: String,
    ref_id: String,
) -> CmdResult<Option<String>> {
    Ok(state.research.thumbnail(&project_id, &ref_id).await?)
}

/// Brief changes suggested by the kept and rejected references.
#[tauri::command(rename_all = "snake_case")]
pub async fn refine_brief_from_references(
    state: State<'_, AppState>,
    project_id: String,
) -> CmdResult<Vec<BriefProposal>> {
    Ok(state.research.refine(&project_id).await?)
}
