//! Project and interview commands. Turns stream through a Tauri channel.

use std::sync::Mutex;

use kickoff_store::Project;
use serde_json::Value;
use tauri::State;
use tauri::ipc::Channel;

use crate::error::CmdResult;
use crate::interview::{InterviewEvent, InterviewView, ProjectCard};
use crate::state::AppState;
use crate::voice::ReplySpeech;

#[tauri::command(rename_all = "snake_case")]
pub async fn create_project(state: State<'_, AppState>) -> CmdResult<Project> {
    Ok(state.interview.create_project().await?)
}

#[tauri::command(rename_all = "snake_case")]
pub async fn list_projects(state: State<'_, AppState>) -> CmdResult<Vec<ProjectCard>> {
    Ok(state.interview.list().await?)
}

#[tauri::command(rename_all = "snake_case")]
pub async fn delete_project(state: State<'_, AppState>, project_id: String) -> CmdResult<()> {
    Ok(state.interview.delete(&project_id).await?)
}

#[tauri::command(rename_all = "snake_case")]
pub async fn get_interview(state: State<'_, AppState>, project_id: String) -> CmdResult<InterviewView> {
    Ok(state.interview.get(&project_id).await?)
}

/// Send the user's message; the producer's reply streams through `on_event`.
#[tauri::command(rename_all = "snake_case")]
pub async fn send_message(
    state: State<'_, AppState>,
    project_id: String,
    text: String,
    input_mode: String,
    on_event: Channel<InterviewEvent>,
) -> CmdResult<InterviewView> {
    let mode = if input_mode == "voice" { "voice" } else { "text" };
    let speech = Mutex::new(state.voice.begin_reply().await);
    let emit = |e: InterviewEvent| forward(&on_event, &speech, e);
    let result = state.interview.send(&project_id, &text, mode, &emit).await;
    finish_speech(speech);
    Ok(result?)
}

/// Retry the producer's reply after a failure.
#[tauri::command(rename_all = "snake_case")]
pub async fn continue_interview(
    state: State<'_, AppState>,
    project_id: String,
    on_event: Channel<InterviewEvent>,
) -> CmdResult<InterviewView> {
    let speech = Mutex::new(state.voice.begin_reply().await);
    let emit = |e: InterviewEvent| forward(&on_event, &speech, e);
    let result = state.interview.continue_turn(&project_id, &emit).await;
    finish_speech(speech);
    Ok(result?)
}

/// Pass a turn event to the UI and, when replies are read aloud, feed the
/// producer's words to the voice as they stream in.
fn forward(channel: &Channel<InterviewEvent>, speech: &Mutex<Option<ReplySpeech>>, event: InterviewEvent) {
    if let InterviewEvent::Delta { text } = &event
        && let Some(speech) = speech.lock().unwrap_or_else(|p| p.into_inner()).as_mut()
    {
        speech.push(text);
    }
    let _ = channel.send(event);
}

fn finish_speech(speech: Mutex<Option<ReplySpeech>>) {
    if let Some(speech) = speech.into_inner().unwrap_or_else(|p| p.into_inner()) {
        speech.finish();
    }
}

#[tauri::command(rename_all = "snake_case")]
pub async fn edit_brief_field(
    state: State<'_, AppState>,
    project_id: String,
    field: String,
    value: Value,
) -> CmdResult<InterviewView> {
    Ok(state.interview.edit_field(&project_id, &field, &value).await?)
}

#[tauri::command(rename_all = "snake_case")]
pub async fn revisit_field(state: State<'_, AppState>, project_id: String, field: String) -> CmdResult<InterviewView> {
    Ok(state.interview.revisit(&project_id, &field).await?)
}

#[tauri::command(rename_all = "snake_case")]
pub async fn finish_interview(state: State<'_, AppState>, project_id: String) -> CmdResult<InterviewView> {
    Ok(state.interview.finish(&project_id).await?)
}
