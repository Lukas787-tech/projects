//! Voice commands: settings, installs, recording and speech output.

use kickoff_core::{VoiceRef, VoiceSettings};
use tauri::State;
use tauri::ipc::Channel;

use crate::error::CmdResult;
use crate::state::AppState;
use crate::voice::{
    DownloadProgress, FishCheckResult, FishVoicePage, PiperVoiceView, RecordingEvent, SystemVoiceView, Transcript,
    VoiceOverview,
};

fn progress(channel: Channel<DownloadProgress>) -> impl FnMut(u64, Option<u64>) + Send {
    // Report at most every 256 KiB so the UI isn't flooded.
    let mut last = 0u64;
    move |received, total| {
        if received - last >= 256 * 1024 || Some(received) == total {
            last = received;
            let _ = channel.send(DownloadProgress { received, total });
        }
    }
}

#[tauri::command(rename_all = "snake_case")]
pub async fn get_voice_overview(state: State<'_, AppState>) -> CmdResult<VoiceOverview> {
    Ok(state.voice.overview().await?)
}

#[tauri::command(rename_all = "snake_case")]
pub async fn save_voice_settings(state: State<'_, AppState>, settings: VoiceSettings) -> CmdResult<VoiceOverview> {
    Ok(state.voice.save_settings(settings).await?)
}

#[tauri::command(rename_all = "snake_case")]
pub async fn download_whisper_model(
    state: State<'_, AppState>,
    name: String,
    on_progress: Channel<DownloadProgress>,
) -> CmdResult<VoiceOverview> {
    state.voice.download_whisper_model(&name, &mut progress(on_progress)).await?;
    Ok(state.voice.overview().await?)
}

#[tauri::command(rename_all = "snake_case")]
pub async fn delete_whisper_model(state: State<'_, AppState>, name: String) -> CmdResult<VoiceOverview> {
    state.voice.delete_whisper_model(&name).await?;
    Ok(state.voice.overview().await?)
}

#[tauri::command(rename_all = "snake_case")]
pub async fn install_piper(
    state: State<'_, AppState>,
    on_progress: Channel<DownloadProgress>,
) -> CmdResult<VoiceOverview> {
    state.voice.install_piper(&mut progress(on_progress)).await?;
    Ok(state.voice.overview().await?)
}

#[tauri::command(rename_all = "snake_case")]
pub fn list_piper_voices(state: State<'_, AppState>) -> Vec<PiperVoiceView> {
    state.voice.piper_voices()
}

#[tauri::command(rename_all = "snake_case")]
pub async fn download_piper_voice(
    state: State<'_, AppState>,
    key: String,
    on_progress: Channel<DownloadProgress>,
) -> CmdResult<VoiceOverview> {
    state.voice.download_piper_voice(&key, &mut progress(on_progress)).await?;
    Ok(state.voice.overview().await?)
}

#[tauri::command(rename_all = "snake_case")]
pub async fn list_system_voices(state: State<'_, AppState>) -> CmdResult<Vec<SystemVoiceView>> {
    Ok(state.voice.system_voices().await?)
}

#[tauri::command(rename_all = "snake_case")]
pub async fn check_fish_audio(state: State<'_, AppState>) -> CmdResult<FishCheckResult> {
    Ok(state.voice.check_fish().await?)
}

#[tauri::command(rename_all = "snake_case")]
pub async fn search_fish_voices(
    state: State<'_, AppState>,
    query: Option<String>,
    page: u32,
) -> CmdResult<FishVoicePage> {
    Ok(state.voice.fish_voices(query.as_deref(), page).await?)
}

/// Speak a short sample with one engine (and optionally a voice to try).
#[tauri::command(rename_all = "snake_case")]
pub async fn preview_voice(state: State<'_, AppState>, engine: String, voice: Option<VoiceRef>) -> CmdResult<()> {
    Ok(state.voice.preview(&engine, voice).await?)
}

#[tauri::command(rename_all = "snake_case")]
pub async fn start_recording(state: State<'_, AppState>, on_event: Channel<RecordingEvent>) -> CmdResult<()> {
    Ok(state
        .voice
        .start_recording(move |e| {
            let _ = on_event.send(e);
        })
        .await?)
}

#[tauri::command(rename_all = "snake_case")]
pub async fn stop_recording(state: State<'_, AppState>) -> CmdResult<Transcript> {
    Ok(state.voice.stop_recording().await?)
}

#[tauri::command(rename_all = "snake_case")]
pub fn cancel_recording(state: State<'_, AppState>) {
    state.voice.cancel_recording();
}

#[tauri::command(rename_all = "snake_case")]
pub fn stop_speaking(state: State<'_, AppState>) {
    state.voice.stop_speaking();
}
