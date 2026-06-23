use std::path::PathBuf;
use std::sync::Mutex;
use sysinfo::{CpuRefreshKind, MemoryRefreshKind, RefreshKind, System};
use tauri::{Emitter, Manager};
use tauri_plugin_global_shortcut::{GlobalShortcutExt, Shortcut, ShortcutState};

#[derive(serde::Serialize, serde::Deserialize, Clone)]
struct SubItem {
    label: String,
    cmd: String,
    args: Vec<String>,
}

#[derive(serde::Serialize, serde::Deserialize, Clone)]
struct MenuAction {
    id: String,
    label: String,
    icon: String,
    cmd: String,
    args: Vec<String>,
    #[serde(rename = "subItems", default)]
    sub_items: Vec<SubItem>,
}

#[derive(serde::Serialize, serde::Deserialize, Clone)]
struct CustomTheme {
    name: String,
    css_vars: String,
}

#[derive(serde::Serialize, serde::Deserialize, Clone)]
struct PluginSetting {
    key: String,
    label: String,
    #[serde(rename = "type")]
    setting_type: String, // "toggle", "text", "number", "color", "select", "slider"
    #[serde(default)]
    default_value: serde_json::Value,
    #[serde(default)]
    options: Vec<String>, // for "select" type
    #[serde(default)]
    min: Option<f64>,
    #[serde(default)]
    max: Option<f64>,
    #[serde(default)]
    step: Option<f64>,
}

#[derive(serde::Serialize, serde::Deserialize, Clone)]
struct PluginKeybinding {
    id: String,
    label: String,
    shortcut: String,
    #[serde(default)]
    action: String, // "command" or "function"
    #[serde(default)]
    value: String, // command to run or JS function name
}

#[derive(serde::Serialize, serde::Deserialize, Clone)]
struct LayoutOverrides {
    #[serde(default)]
    inner_radius: Option<f64>,
    #[serde(default)]
    outer_radius: Option<f64>,
    #[serde(default)]
    sub_inner_radius: Option<f64>,
    #[serde(default)]
    sub_outer_radius: Option<f64>,
    #[serde(default)]
    hub_radius: Option<f64>,
    #[serde(default)]
    icon_radius: Option<f64>,
    #[serde(default)]
    label_radius: Option<f64>,
    #[serde(default)]
    gap_degrees: Option<f64>,
}

#[derive(serde::Serialize, serde::Deserialize, Clone)]
struct Plugin {
    id: String,
    name: String,
    #[serde(default)]
    version: String,
    #[serde(default)]
    author: String,
    #[serde(default)]
    description: String,
    #[serde(default = "default_plugin_enabled")]
    enabled: bool,
    #[serde(default)]
    custom_themes: Vec<CustomTheme>,
    #[serde(default)]
    menu_actions: Vec<MenuAction>,
    #[serde(default)]
    injected_js: String,
    #[serde(default)]
    injected_css: String,
    #[serde(default)]
    hooks: std::collections::HashMap<String, String>, // hook_name -> JS code
    #[serde(default)]
    settings_schema: Vec<PluginSetting>,
    #[serde(default)]
    icon_definitions: std::collections::HashMap<String, String>, // icon_name -> SVG path
    #[serde(default)]
    keybindings: Vec<PluginKeybinding>,
    #[serde(default)]
    layout_overrides: Option<LayoutOverrides>,
}

fn default_plugin_enabled() -> bool {
    true
}

#[derive(serde::Serialize, serde::Deserialize, Clone)]
struct AppSettings {
    hotkey: String,
    #[serde(default = "default_assistant_hotkey")]
    assistant_hotkey: String,
    blur_intensity: u32,
    theme: String,
    scale: f64,
    #[serde(default = "default_anim_speed")]
    animation_speed: f64,
    #[serde(default = "default_bg_opacity")]
    bg_opacity: f64,
    #[serde(default = "default_accent")]
    accent_color: String,
    actions: Vec<MenuAction>,
    #[serde(default)]
    gemini_key: String,
    #[serde(default = "default_autostart")]
    autostart: bool,
    #[serde(default)]
    custom_themes: Vec<CustomTheme>,
}

#[derive(serde::Serialize, serde::Deserialize, Clone, Debug)]
struct ScriptFile {
    name: String,
    content: String,
}

#[derive(serde::Serialize, serde::Deserialize, Clone, Debug)]
struct PlannerEvent {
    id: String,
    title: String,
    description: String,
    date: String, // YYYY-MM-DD
    time: String, // HH:MM
    script: Option<String>,
    completed: bool,
    #[serde(default)]
    last_run: Option<String>,
}

fn default_anim_speed() -> f64 {
    1.0
}
fn default_bg_opacity() -> f64 {
    0.65
}
fn default_accent() -> String {
    "#007aff".to_string()
}
fn default_autostart() -> bool {
    true
}
fn default_assistant_hotkey() -> String {
    "Control+Shift+A".to_string()
}

struct OverlayState(Mutex<bool>);

#[derive(Clone, serde::Serialize)]
struct PositionPayload {
    x: f64,
    y: f64,
}

#[cfg(target_os = "windows")]
fn get_mouse_position() -> Option<(i32, i32)> {
    use windows_sys::Win32::Foundation::POINT;
    use windows_sys::Win32::UI::WindowsAndMessaging::GetCursorPos;
    let mut point = POINT { x: 0, y: 0 };
    unsafe {
        if GetCursorPos(&mut point) != 0 {
            Some((point.x, point.y))
        } else {
            None
        }
    }
}

#[cfg(not(target_os = "windows"))]
fn get_mouse_position() -> Option<(i32, i32)> {
    None
}

#[cfg(target_os = "windows")]
fn get_monitor_for_cursor(
    window: &tauri::WebviewWindow,
    cx: i32,
    cy: i32,
) -> Option<tauri::Monitor> {
    if let Ok(monitors) = window.available_monitors() {
        for monitor in monitors {
            let pos = monitor.position();
            let size = monitor.size();
            let min_x = pos.x;
            let max_x = pos.x + size.width as i32;
            let min_y = pos.y;
            let max_y = pos.y + size.height as i32;

            if cx >= min_x && cx < max_x && cy >= min_y && cy < max_y {
                return Some(monitor);
            }
        }
    }
    None
}

#[cfg(not(target_os = "windows"))]
fn get_monitor_for_cursor(
    _window: &tauri::WebviewWindow,
    _cx: i32,
    _cy: i32,
) -> Option<tauri::Monitor> {
    None
}

fn get_settings_path(app: &tauri::AppHandle) -> PathBuf {
    let mut path = app
        .path()
        .app_data_dir()
        .unwrap_or_else(|_| PathBuf::from("."));
    let _ = std::fs::create_dir_all(&path);
    path.push("settings.json");
    path
}

fn get_plugins_path(app: &tauri::AppHandle) -> PathBuf {
    let mut path = app
        .path()
        .app_data_dir()
        .unwrap_or_else(|_| PathBuf::from("."));
    path.push("plugins");
    let _ = std::fs::create_dir_all(&path);
    path
}

fn get_media_helper_path(app: &tauri::AppHandle) -> PathBuf {
    let mut path = app
        .path()
        .app_data_dir()
        .unwrap_or_else(|_| PathBuf::from("."));
    let _ = std::fs::create_dir_all(&path);
    path.push("media_helper_v2.exe");
    path
}

fn compile_media_helper(app: &tauri::AppHandle) -> Result<PathBuf, String> {
    let helper_exe = get_media_helper_path(app);
    if helper_exe.exists() {
        return Ok(helper_exe);
    }

    let mut helper_cs = helper_exe.clone();
    helper_cs.set_extension("cs");

    let source_code = r#"using System;
using System.Threading;
using Windows.Media.Control;
using Windows.Foundation;

class Program {
    static void Main(string[] args) {
        if (args.Length > 0) {
            string cmd = args[0].ToLower();
            if (cmd == "playpause" || cmd == "play" || cmd == "pause" || cmd == "next" || cmd == "prev") {
                ControlMedia(cmd);
            }
        } else {
            PrintCurrentTrack();
        }
    }

    static void PrintCurrentTrack() {
        try {
            var op = GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
            while (op.Status == AsyncStatus.Started) {
                Thread.Sleep(10);
            }
            var manager = op.GetResults();
            if (manager == null) {
                Console.WriteLine("None|None|Closed|0|0");
                return;
            }
            var session = manager.GetCurrentSession();
            if (session == null) {
                Console.WriteLine("None|None|Closed|0|0");
                return;
            }
            var propOp = session.TryGetMediaPropertiesAsync();
            while (propOp.Status == AsyncStatus.Started) {
                Thread.Sleep(10);
            }
            var props = propOp.GetResults();
            if (props == null) {
                Console.WriteLine("None|None|Closed|0|0");
                return;
            }
            var playback = session.GetPlaybackInfo();
            string status = playback != null ? playback.PlaybackStatus.ToString() : "Closed";
            
            double pos = 0;
            double dur = 0;
            try {
                var timeline = session.GetTimelineProperties();
                if (timeline != null) {
                    pos = timeline.Position.TotalMilliseconds;
                    dur = timeline.EndTime.TotalMilliseconds;
                }
            } catch {}

            Console.WriteLine(props.Title + "|" + props.Artist + "|" + status + "|" + pos + "|" + dur);
        } catch (Exception ex) {
            Console.WriteLine("Error: " + ex.Message + "|None|Closed|0|0");
        }
    }

    static void ControlMedia(string action) {
        try {
            var op = GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
            while (op.Status == AsyncStatus.Started) {
                Thread.Sleep(5);
            }
            var manager = op.GetResults();
            if (manager == null) return;
            var session = manager.GetCurrentSession();
            if (session == null) return;
            if (action == "playpause") {
                var playback = session.GetPlaybackInfo();
                if (playback != null && playback.PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing) {
                    session.TryPauseAsync();
                } else {
                    session.TryPlayAsync();
                }
            } else if (action == "play") {
                session.TryPlayAsync();
            } else if (action == "pause") {
                session.TryPauseAsync();
            } else if (action == "next") {
                session.TrySkipNextAsync();
            } else if (action == "prev") {
                session.TrySkipPreviousAsync();
            }
        } catch {}
    }
}
"#;

    std::fs::write(&helper_cs, source_code)
        .map_err(|e| format!("Failed to write media_helper.cs: {:?}", e))?;

    use std::os::windows::process::CommandExt;
    use std::process::Command;

    let output = Command::new(r#"C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe"#)
        .creation_flags(0x08000000) // CREATE_NO_WINDOW
        .args([
            "/noconfig",
            "/r:System.dll",
            "/r:System.Core.dll",
            "/r:System.Runtime.dll",
            "/r:System.Runtime.InteropServices.WindowsRuntime.dll",
            r#"/r:C:\Windows\System32\WinMetadata\Windows.Foundation.winmd"#,
            r#"/r:C:\Windows\System32\WinMetadata\Windows.Media.winmd"#,
            &format!("/out:{}", helper_exe.to_string_lossy()),
            &helper_cs.to_string_lossy(),
        ])
        .output()
        .map_err(|e| format!("Failed to run csc.exe: {:?}", e))?;

    let _ = std::fs::remove_file(&helper_cs);

    if !output.status.success() {
        return Err(format!(
            "Failed to compile C# media helper: {}",
            String::from_utf8_lossy(&output.stderr)
        ));
    }

    Ok(helper_exe)
}

fn get_default_settings() -> AppSettings {
    AppSettings {
        hotkey: "Control+Q".to_string(),
        assistant_hotkey: default_assistant_hotkey(),
        blur_intensity: 25,
        theme: "apple-dark".to_string(),
        scale: 1.0,
        animation_speed: 1.0,
        bg_opacity: 0.65,
        accent_color: "#007aff".to_string(),
        custom_themes: vec![],
        // v2_defaults marker for migration
        actions: vec![
            MenuAction { 
                id: "browser".to_string(), 
                label: "Web Browser".to_string(), 
                icon: "globe".to_string(), 
                cmd: "cmd".to_string(), 
                args: vec!["/C".to_string(), "start".to_string(), "https://google.com".to_string()],
                sub_items: vec![
                    SubItem { label: "Google".to_string(), cmd: "cmd".to_string(), args: vec!["/C".to_string(), "start".to_string(), "https://google.com".to_string()] },
                    SubItem { label: "YouTube".to_string(), cmd: "cmd".to_string(), args: vec!["/C".to_string(), "start".to_string(), "https://youtube.com".to_string()] },
                    SubItem { label: "GitHub".to_string(), cmd: "cmd".to_string(), args: vec!["/C".to_string(), "start".to_string(), "https://github.com".to_string()] },
                    SubItem { label: "Reddit".to_string(), cmd: "cmd".to_string(), args: vec!["/C".to_string(), "start".to_string(), "https://reddit.com".to_string()] },
                    SubItem { label: "ChatGPT".to_string(), cmd: "cmd".to_string(), args: vec!["/C".to_string(), "start".to_string(), "https://chatgpt.com".to_string()] },
                ]
            },
            MenuAction { 
                id: "explorer".to_string(), 
                label: "File Explorer".to_string(), 
                icon: "folder".to_string(), 
                cmd: "explorer.exe".to_string(), 
                args: vec![],
                sub_items: vec![
                    SubItem { label: "Documents".to_string(), cmd: "explorer.exe".to_string(), args: vec!["shell:Personal".to_string()] },
                    SubItem { label: "Downloads".to_string(), cmd: "explorer.exe".to_string(), args: vec!["shell:Downloads".to_string()] },
                    SubItem { label: "Desktop".to_string(), cmd: "explorer.exe".to_string(), args: vec!["shell:Desktop".to_string()] },
                    SubItem { label: "Pictures".to_string(), cmd: "explorer.exe".to_string(), args: vec!["shell:My Pictures".to_string()] },
                    SubItem { label: "Recycle Bin".to_string(), cmd: "explorer.exe".to_string(), args: vec!["shell:RecycleBinFolder".to_string()] },
                ]
            },
            MenuAction { 
                id: "apps".to_string(), 
                label: "Quick Apps".to_string(), 
                icon: "notepad".to_string(), 
                cmd: "notepad.exe".to_string(), 
                args: vec![],
                sub_items: vec![
                    SubItem { label: "Notepad".to_string(), cmd: "notepad.exe".to_string(), args: vec![] },
                    SubItem { label: "Calculator".to_string(), cmd: "calc.exe".to_string(), args: vec![] },
                    SubItem { label: "Task Manager".to_string(), cmd: "taskmgr.exe".to_string(), args: vec![] },
                    SubItem { label: "Snip & Sketch".to_string(), cmd: "explorer.exe".to_string(), args: vec!["ms-screenclip:".to_string()] },
                    SubItem { label: "Paint".to_string(), cmd: "mspaint.exe".to_string(), args: vec![] },
                    SubItem { label: "Screen Lock".to_string(), cmd: "rundll32.exe".to_string(), args: vec!["user32.dll,LockWorkStation".to_string()] },
                ]
            },
            MenuAction { 
                id: "settings".to_string(), 
                label: "Settings".to_string(), 
                icon: "gear".to_string(), 
                cmd: "settings".to_string(), 
                args: vec![],
                sub_items: vec![
                    SubItem { label: "Menu Config".to_string(), cmd: "settings".to_string(), args: vec![] },
                    SubItem { label: "Display".to_string(), cmd: "explorer.exe".to_string(), args: vec!["ms-settings:display".to_string()] },
                    SubItem { label: "Sound".to_string(), cmd: "explorer.exe".to_string(), args: vec!["ms-settings:sound".to_string()] },
                    SubItem { label: "Personalization".to_string(), cmd: "explorer.exe".to_string(), args: vec!["ms-settings:personalization".to_string()] },
                    SubItem { label: "Bluetooth".to_string(), cmd: "explorer.exe".to_string(), args: vec!["ms-settings:bluetooth".to_string()] },
                    SubItem { label: "Wi-Fi".to_string(), cmd: "explorer.exe".to_string(), args: vec!["ms-settings:network-wifi".to_string()] },
                ]
            },
            MenuAction { 
                id: "media".to_string(), 
                label: "Media".to_string(), 
                icon: "music".to_string(), 
                cmd: "media_helper".to_string(), 
                args: vec!["playpause".to_string()],
                sub_items: vec![
                    SubItem { 
                        label: "Play/Pause".to_string(), 
                        cmd: "media_helper".to_string(), 
                        args: vec!["playpause".to_string()] 
                    },
                    SubItem { 
                        label: "Next Track".to_string(), 
                        cmd: "media_helper".to_string(), 
                        args: vec!["next".to_string()] 
                    },
                    SubItem { 
                        label: "Prev Track".to_string(), 
                        cmd: "media_helper".to_string(), 
                        args: vec!["prev".to_string()] 
                    },
                    SubItem { 
                        label: "Mute".to_string(), 
                        cmd: "powershell.exe".to_string(), 
                        args: vec![
                            "-NoProfile".to_string(),
                            "-Command".to_string(),
                            "Add-Type -MemberDefinition '[DllImport(\"user32.dll\")] public static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, uint dwExtraInfo);' -Name Api -Namespace Win32; [Win32.Api]::keybd_event(0xAD, 0, 0, 0); [Win32.Api]::keybd_event(0xAD, 0, 2, 0)".to_string()
                        ] 
                    },
                ]
            },
            MenuAction { 
                id: "cmd".to_string(), 
                label: "Terminal".to_string(), 
                icon: "terminal".to_string(), 
                cmd: "cmd.exe".to_string(), 
                args: vec!["/C".to_string(), "start".to_string(), "cmd.exe".to_string()],
                sub_items: vec![
                    SubItem { label: "Cmd".to_string(), cmd: "cmd.exe".to_string(), args: vec!["/C".to_string(), "start".to_string(), "cmd.exe".to_string()] },
                    SubItem { label: "PowerShell".to_string(), cmd: "powershell.exe".to_string(), args: vec!["-NoExit".to_string()] },
                    SubItem { label: "AI Terminal".to_string(), cmd: "ai_terminal".to_string(), args: vec![] },
                ]
            },
            MenuAction { 
                id: "assistant".to_string(), 
                label: "AI Assistant".to_string(), 
                icon: "sparkle".to_string(), 
                cmd: "assistant".to_string(), 
                args: vec![],
                sub_items: vec![]
            },
        ],
        gemini_key: "".to_string(),
        autostart: true,
    }
}

fn load_settings_internal(app: &tauri::AppHandle) -> Option<AppSettings> {
    let path = get_settings_path(app);
    if path.exists() {
        if let Ok(content) = std::fs::read_to_string(path) {
            if let Ok(settings) = serde_json::from_str::<AppSettings>(&content) {
                return Some(settings);
            }
        }
    }
    None
}

// PLUGIN LOGIC --------------------------------

#[tauri::command]
fn get_plugins(app_handle: tauri::AppHandle) -> Vec<Plugin> {
    let plugins_dir = get_plugins_path(&app_handle);
    let mut plugins = Vec::new();

    if let Ok(entries) = std::fs::read_dir(plugins_dir) {
        for entry in entries.flatten() {
            if let Ok(content) = std::fs::read_to_string(entry.path()) {
                if let Ok(plugin) = serde_json::from_str::<Plugin>(&content) {
                    plugins.push(plugin);
                }
            }
        }
    }
    plugins
}

#[tauri::command]
fn save_plugin(plugin: Plugin, app_handle: tauri::AppHandle) -> Result<(), String> {
    let mut path = get_plugins_path(&app_handle);
    path.push(format!("{}.json", plugin.id));

    let serialized = serde_json::to_string_pretty(&plugin)
        .map_err(|e| format!("Failed to serialize plugin: {:?}", e))?;

    std::fs::write(&path, &serialized).map_err(|e| format!("Failed to write plugin: {:?}", e))?;

    Ok(())
}

#[tauri::command]
fn delete_plugin(id: String, app_handle: tauri::AppHandle) -> Result<(), String> {
    let mut path = get_plugins_path(&app_handle);
    path.push(format!("{}.json", id));

    if path.exists() {
        std::fs::remove_file(path).map_err(|e| format!("Failed to delete: {:?}", e))?;
    }
    Ok(())
}

#[tauri::command]
fn toggle_plugin(id: String, enabled: bool, app_handle: tauri::AppHandle) -> Result<(), String> {
    let mut plugins = get_plugins(app_handle.clone());
    if let Some(plugin) = plugins.iter_mut().find(|p| p.id == id) {
        plugin.enabled = enabled;
        save_plugin(plugin.clone(), app_handle.clone())?;

        // Broadcast that settings/plugins updated so frontend refreshes
        let settings = get_settings(app_handle.clone());
        let _ = app_handle.emit("config-updated", settings);
    }
    Ok(())
}

// Plugin-specific settings storage
fn get_plugin_settings_path(app: &tauri::AppHandle, plugin_id: &str) -> PathBuf {
    let mut path = app
        .path()
        .app_data_dir()
        .unwrap_or_else(|_| PathBuf::from("."));
    path.push("plugin_settings");
    let _ = std::fs::create_dir_all(&path);
    path.push(format!("{}.json", plugin_id));
    path
}

#[tauri::command]
fn get_plugin_settings(
    id: String,
    app_handle: tauri::AppHandle,
) -> Result<serde_json::Value, String> {
    let path = get_plugin_settings_path(&app_handle, &id);
    if path.exists() {
        let content = std::fs::read_to_string(&path)
            .map_err(|e| format!("Failed to read plugin settings: {:?}", e))?;
        serde_json::from_str(&content)
            .map_err(|e| format!("Failed to parse plugin settings: {:?}", e))
    } else {
        Ok(serde_json::json!({}))
    }
}

#[tauri::command]
fn save_plugin_settings(
    id: String,
    settings: serde_json::Value,
    app_handle: tauri::AppHandle,
) -> Result<(), String> {
    let path = get_plugin_settings_path(&app_handle, &id);
    let serialized = serde_json::to_string_pretty(&settings)
        .map_err(|e| format!("Failed to serialize plugin settings: {:?}", e))?;
    std::fs::write(&path, &serialized)
        .map_err(|e| format!("Failed to write plugin settings: {:?}", e))?;
    Ok(())
}

#[tauri::command]
fn export_plugin(id: String, app_handle: tauri::AppHandle) -> Result<String, String> {
    let plugins = get_plugins(app_handle);
    if let Some(plugin) = plugins.into_iter().find(|p| p.id == id) {
        serde_json::to_string_pretty(&plugin)
            .map_err(|e| format!("Failed to serialize plugin: {:?}", e))
    } else {
        Err(format!("Plugin '{}' not found", id))
    }
}

#[tauri::command]
fn import_plugin(json: String, app_handle: tauri::AppHandle) -> Result<String, String> {
    let plugin: Plugin =
        serde_json::from_str(&json).map_err(|e| format!("Invalid plugin JSON: {:?}", e))?;
    let name = plugin.name.clone();
    save_plugin(plugin, app_handle.clone())?;
    let _ = app_handle.emit("plugins-updated", ());
    Ok(format!("Plugin '{}' imported successfully", name))
}

#[tauri::command]
fn show_design_maker(app_handle: tauri::AppHandle) {
    if let Some(window) = app_handle.get_webview_window("design_maker") {
        let _ = window.center();
        let _ = window.unminimize();
        let _ = window.show();
        let _ = window.set_focus();
    }
}
// ---------------------------------------------

fn trigger_menu_open(window: &tauri::WebviewWindow) {
    if let Some((x, y)) = get_mouse_position() {
        let mut positioned = false;

        #[cfg(target_os = "windows")]
        {
            if let Some(monitor) = get_monitor_for_cursor(window, x, y) {
                let m_pos = monitor.position();
                let m_size = monitor.size();

                let _ = window.set_position(tauri::Position::Physical(m_pos.clone()));
                let _ = window.set_size(tauri::Size::Physical(m_size.clone()));

                let local_x = x - m_pos.x;
                let local_y = y - m_pos.y;

                let scale_factor = window.scale_factor().unwrap_or(1.0);
                let logical_x = local_x as f64 / scale_factor;
                let logical_y = local_y as f64 / scale_factor;

                let _ = window.emit(
                    "show-menu",
                    PositionPayload {
                        x: logical_x,
                        y: logical_y,
                    },
                );
                positioned = true;
            }
        }

        if !positioned {
            let scale_factor = window.scale_factor().unwrap_or(1.0);
            let logical_x = x as f64 / scale_factor;
            let logical_y = y as f64 / scale_factor;
            let _ = window.emit(
                "show-menu",
                PositionPayload {
                    x: logical_x,
                    y: logical_y,
                },
            );
        }
    }

    let _ = window.show();
    let _ = window.set_focus();
}

fn register_hotkey(app: &tauri::AppHandle, hotkey_str: &str) -> Result<(), String> {
    use std::str::FromStr;

    let _ = app.global_shortcut().unregister_all();

    match Shortcut::from_str(hotkey_str) {
        Ok(shortcut) => {
            if let Err(e) =
                app.global_shortcut()
                    .on_shortcut(shortcut, move |app_inner, _shortcut, event| {
                        if event.state() == ShortcutState::Pressed {
                            if let Some(window) = app_inner.get_webview_window("main") {
                                trigger_menu_open(&window);
                            }
                        }
                    })
            {
                eprintln!("Failed to register main hotkey: {:?}", e);
            }
        }
        Err(e) => eprintln!("Failed to parse main hotkey: {:?}", e),
    }

    // Register Assistant window toggle shortcut
    let assistant_hotkey = get_settings(app.clone()).assistant_hotkey;
    match Shortcut::from_str(&assistant_hotkey) {
        Ok(ass_shortcut) => {
            if let Err(e) =
                app.global_shortcut()
                    .on_shortcut(ass_shortcut, |app_inner, _shortcut, event| {
                        if event.state() == ShortcutState::Pressed {
                            if let Some(window) = app_inner.get_webview_window("assistant") {
                                let is_visible = window.is_visible().unwrap_or(false);
                                if is_visible {
                                    let _ = window.hide();
                                } else {
                                    let _ = window.show();
                                    let _ = window.set_focus();
                                }
                            }
                        }
                    })
            {
                eprintln!("Failed to register assistant hotkey: {:?}", e);
            }
        }
        Err(e) => eprintln!("Failed to parse assistant hotkey: {:?}", e),
    }

    Ok(())
}

#[tauri::command]
fn get_settings(app_handle: tauri::AppHandle) -> AppSettings {
    let settings = load_settings_internal(&app_handle).unwrap_or_else(get_default_settings);

    settings
}

#[tauri::command]
fn save_settings(settings: AppSettings, app_handle: tauri::AppHandle) -> Result<(), String> {
    let path = get_settings_path(&app_handle);
    let serialized = serde_json::to_string_pretty(&settings)
        .map_err(|e| format!("Failed to serialize settings: {:?}", e))?;

    std::fs::write(&path, &serialized)
        .map_err(|e| format!("Failed to write settings file: {:?}", e))?;

    let _ = register_hotkey(&app_handle, &settings.hotkey);
    let _ = app_handle.emit("config-updated", settings.clone());

    // Set/remove auto-start registry key on Windows (only in release mode)
    #[cfg(target_os = "windows")]
    {
        #[cfg(debug_assertions)]
        {
            // In debug mode, remove any autostart registry key so it doesn't try to run debug build
            let mut cmd = std::process::Command::new("reg");
            cmd.args([
                "delete",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                "/v",
                "TauriRadialMenu",
                "/f",
            ]);
            use std::os::windows::process::CommandExt;
            cmd.creation_flags(0x08000000); // CREATE_NO_WINDOW
            let _ = cmd.status();
        }
        #[cfg(not(debug_assertions))]
        {
            if settings.autostart {
                if let Ok(exe_path) = std::env::current_exe() {
                    let exe_str = exe_path.to_string_lossy();
                    let mut cmd = std::process::Command::new("reg");
                    cmd.args([
                        "add",
                        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                        "/v",
                        "TauriRadialMenu",
                        "/t",
                        "REG_SZ",
                        "/d",
                        &format!("\"{}\"", exe_str),
                        "/f",
                    ]);
                    use std::os::windows::process::CommandExt;
                    cmd.creation_flags(0x08000000); // CREATE_NO_WINDOW
                    let _ = cmd.status();
                }
            } else {
                let mut cmd = std::process::Command::new("reg");
                cmd.args([
                    "delete",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                    "/v",
                    "TauriRadialMenu",
                    "/f",
                ]);
                use std::os::windows::process::CommandExt;
                cmd.creation_flags(0x08000000); // CREATE_NO_WINDOW
                let _ = cmd.status();
            }
        }
    }

    Ok(())
}

#[tauri::command]
fn show_settings(app_handle: tauri::AppHandle) {
    if let Some(window) = app_handle.get_webview_window("settings") {
        let _ = window.show();
        let _ = window.set_focus();
    }
}

#[tauri::command]
fn hide_menu(window: tauri::WebviewWindow) {
    let _ = window.hide();
}

#[cfg(target_os = "windows")]
extern "system" {
    pub fn GetAsyncKeyState(vkey: i32) -> i16;
}

#[cfg(target_os = "windows")]
fn is_key_pressed(vkey: i32) -> bool {
    unsafe { GetAsyncKeyState(vkey) < 0 }
}

#[tauri::command]
fn is_hotkey_held(hotkey: String) -> bool {
    #[cfg(target_os = "windows")]
    {
        let hotkey_lower = hotkey.to_lowercase();
        let has_modifiers = hotkey_lower.contains("control")
            || hotkey_lower.contains("ctrl")
            || hotkey_lower.contains("shift")
            || hotkey_lower.contains("alt")
            || hotkey_lower.contains("menu")
            || hotkey_lower.contains("super")
            || hotkey_lower.contains("win")
            || hotkey_lower.contains("command")
            || hotkey_lower.contains("windows");

        let parts = hotkey.split('+');
        for part in parts {
            let part_lower = part.trim().to_lowercase();
            let is_modifier = matches!(
                part_lower.as_str(),
                "control"
                    | "ctrl"
                    | "shift"
                    | "alt"
                    | "menu"
                    | "super"
                    | "win"
                    | "command"
                    | "windows"
            );

            // If modifiers are present, skip checking non-modifier keys (like 'Q')
            if has_modifiers && !is_modifier {
                continue;
            }

            let vkey = match part_lower.as_str() {
                "control" | "ctrl" => 0x11,                      // VK_CONTROL
                "shift" => 0x10,                                 // VK_SHIFT
                "alt" | "menu" => 0x12,                          // VK_MENU
                "super" | "win" | "command" | "windows" => 0x5B, // VK_LWIN
                "tab" => 0x09,                                   // VK_TAB
                "space" => 0x20,                                 // VK_SPACE
                "escape" | "esc" => 0x1B,                        // VK_ESCAPE
                "enter" | "return" => 0x0D,                      // VK_RETURN
                "up" | "arrowup" => 0x26,
                "down" | "arrowdown" => 0x28,
                "left" | "arrowleft" => 0x25,
                "right" | "arrowright" => 0x27,
                "backspace" => 0x08,
                "capslock" => 0x14,
                s if s.len() == 1 => {
                    let c = s.chars().next().unwrap();
                    if c.is_alphanumeric() {
                        c.to_ascii_uppercase() as i32
                    } else {
                        return false;
                    }
                }
                _ => return false,
            };
            if !is_key_pressed(vkey) {
                return false;
            }
        }
        true
    }
    #[cfg(not(target_os = "windows"))]
    {
        true
    }
}

#[derive(serde::Serialize, serde::Deserialize, Clone)]
struct MediaInfo {
    title: String,
    artist: String,
    status: String,
    position: f64,
    duration: f64,
}

#[derive(serde::Serialize, serde::Deserialize, Clone)]
struct SystemStats {
    cpu_percent: f64,
    ram_used_mb: u64,
    ram_total_mb: u64,
}

#[tauri::command]
fn get_current_media_info(app_handle: tauri::AppHandle) -> Result<MediaInfo, String> {
    let helper_exe = compile_media_helper(&app_handle)?;

    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        use std::process::Command;

        let output = Command::new(&helper_exe)
            .creation_flags(0x08000000) // CREATE_NO_WINDOW
            .output()
            .map_err(|e| format!("Failed to execute media helper: {:?}", e))?;

        let stdout = String::from_utf8_lossy(&output.stdout);
        let trimmed = stdout.trim();
        let parts: Vec<&str> = trimmed.split('|').collect();
        if parts.len() >= 5 {
            let position = parts[3].parse::<f64>().unwrap_or(0.0);
            let duration = parts[4].parse::<f64>().unwrap_or(0.0);
            Ok(MediaInfo {
                title: parts[0].to_string(),
                artist: parts[1].to_string(),
                status: parts[2].to_string(),
                position,
                duration,
            })
        } else if parts.len() >= 3 {
            Ok(MediaInfo {
                title: parts[0].to_string(),
                artist: parts[1].to_string(),
                status: parts[2].to_string(),
                position: 0.0,
                duration: 0.0,
            })
        } else {
            Ok(MediaInfo {
                title: "None".to_string(),
                artist: "None".to_string(),
                status: "Closed".to_string(),
                position: 0.0,
                duration: 0.0,
            })
        }
    }

    #[cfg(not(target_os = "windows"))]
    {
        Ok(MediaInfo {
            title: "Not Supported".to_string(),
            artist: "Not Supported".to_string(),
            status: "Closed".to_string(),
            position: 0.0,
            duration: 0.0,
        })
    }
}

#[tauri::command]
fn control_media(action: String, app_handle: tauri::AppHandle) -> Result<(), String> {
    let helper_exe = compile_media_helper(&app_handle)?;
    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        use std::process::Command;
        let _ = Command::new(&helper_exe)
            .creation_flags(0x08000000) // CREATE_NO_WINDOW
            .arg(&action)
            .spawn();
    }
    Ok(())
}

struct SystemMonitor(Mutex<System>);

#[tauri::command]
fn get_system_stats(state: tauri::State<'_, SystemMonitor>) -> Result<SystemStats, String> {
    let mut sys = state.0.lock().unwrap();
    sys.refresh_cpu_specifics(CpuRefreshKind::everything());
    sys.refresh_memory();

    let cpus = sys.cpus();
    let cpu_percent = if !cpus.is_empty() {
        cpus.iter().map(|c| c.cpu_usage()).sum::<f32>() / cpus.len() as f32
    } else {
        0.0
    };

    let ram_used = sys.used_memory() / 1024 / 1024; // MB
    let ram_total = sys.total_memory() / 1024 / 1024; // MB

    Ok(SystemStats {
        cpu_percent: cpu_percent as f64,
        ram_used_mb: ram_used as u64,
        ram_total_mb: ram_total as u64,
    })
}

// ==========================================
// DYNAMIC CUSTOM MODULES SYSTEM
// ==========================================
#[derive(serde::Serialize, serde::Deserialize, Clone, Default)]
struct CustomModule {
    id: String,
    name: String,
    description: String,
    html: String,
    css: String,
    js: String,
    poll_interval_ms: u64,
    enabled: bool,
}

fn get_modules_dir(app_handle: &tauri::AppHandle) -> std::path::PathBuf {
    let mut path = app_handle
        .path()
        .app_data_dir()
        .unwrap_or_else(|_| std::path::PathBuf::from("."));
    path.push("modules");
    let _ = std::fs::create_dir_all(&path);
    path
}

#[tauri::command]
fn get_custom_modules(app_handle: tauri::AppHandle) -> Result<Vec<CustomModule>, String> {
    let dir = get_modules_dir(&app_handle);
    let mut modules = Vec::new();

    if let Ok(entries) = std::fs::read_dir(dir) {
        for entry in entries.flatten() {
            if entry.path().extension().map_or(false, |e| e == "json") {
                if let Ok(content) = std::fs::read_to_string(entry.path()) {
                    if let Ok(module) = serde_json::from_str::<CustomModule>(&content) {
                        modules.push(module);
                    }
                }
            }
        }
    }
    Ok(modules)
}

#[tauri::command]
fn save_custom_module(module: CustomModule, app_handle: tauri::AppHandle) -> Result<(), String> {
    let dir = get_modules_dir(&app_handle);
    let path = dir.join(format!("{}.json", module.id));

    let content = serde_json::to_string_pretty(&module).map_err(|e| e.to_string())?;
    std::fs::write(path, content).map_err(|e| e.to_string())?;

    if let Some(overlay) = app_handle.get_webview_window("overlay") {
        let _ = overlay.emit("custom-modules-updated", ());
    }

    Ok(())
}

#[tauri::command]
fn delete_custom_module(id: String, app_handle: tauri::AppHandle) -> Result<(), String> {
    let dir = get_modules_dir(&app_handle);
    let path = dir.join(format!("{}.json", id));

    if path.exists() {
        let _ = std::fs::remove_file(path);
    }

    if let Some(overlay) = app_handle.get_webview_window("overlay") {
        let _ = overlay.emit("custom-modules-updated", ());
    }

    Ok(())
}

// ==========================================
// SEARCH FILES (recursive grep for AI)
// ==========================================
#[derive(serde::Serialize)]
struct SearchResult {
    path: String,
    line_number: usize,
    line_content: String,
}

#[tauri::command]
fn search_files(
    directory: String,
    pattern: String,
    max_results: Option<usize>,
) -> Result<Vec<SearchResult>, String> {
    let max = max_results.unwrap_or(50);
    let mut results = Vec::new();
    let dir_path = std::path::Path::new(&directory);
    if !dir_path.exists() || !dir_path.is_dir() {
        return Err(format!("Directory '{}' does not exist", directory));
    }
    search_files_recursive(dir_path, &pattern, &mut results, max)?;
    Ok(results)
}

fn search_files_recursive(
    dir: &std::path::Path,
    pattern: &str,
    results: &mut Vec<SearchResult>,
    max: usize,
) -> Result<(), String> {
    if results.len() >= max {
        return Ok(());
    }
    let pattern_lower = pattern.to_lowercase();
    if let Ok(entries) = std::fs::read_dir(dir) {
        for entry in entries.flatten() {
            if results.len() >= max {
                return Ok(());
            }
            let path = entry.path();
            if path.is_dir() {
                let name = path.file_name().unwrap_or_default().to_string_lossy();
                // Skip common non-searchable directories
                if name == "node_modules"
                    || name == ".git"
                    || name == "target"
                    || name == "__pycache__"
                {
                    continue;
                }
                search_files_recursive(&path, pattern, results, max)?;
            } else if path.is_file() {
                // Skip binary files by extension
                let ext = path
                    .extension()
                    .unwrap_or_default()
                    .to_string_lossy()
                    .to_lowercase();
                let binary_exts = [
                    "exe", "dll", "so", "dylib", "bin", "obj", "o", "png", "jpg", "jpeg", "gif",
                    "ico", "mp3", "mp4", "zip", "tar", "gz",
                ];
                if binary_exts.contains(&ext.as_str()) {
                    continue;
                }

                if let Ok(content) = std::fs::read_to_string(&path) {
                    for (i, line) in content.lines().enumerate() {
                        if results.len() >= max {
                            return Ok(());
                        }
                        if line.to_lowercase().contains(&pattern_lower) {
                            results.push(SearchResult {
                                path: path.to_string_lossy().to_string(),
                                line_number: i + 1,
                                line_content: line.to_string(),
                            });
                        }
                    }
                }
            }
        }
    }
    Ok(())
}

// ==========================================
// EDIT FILE CONTENT (find/replace for AI)
// ==========================================
#[tauri::command]
fn edit_file_content(path: String, find: String, replace: String) -> Result<String, String> {
    let file_path = std::path::Path::new(&path);
    if !file_path.exists() {
        return Err(format!("File '{}' does not exist", path));
    }
    let content =
        std::fs::read_to_string(file_path).map_err(|e| format!("Failed to read file: {:?}", e))?;

    if !content.contains(&find) {
        return Err(format!("Could not find the target text in '{}'", path));
    }

    let new_content = content.replacen(&find, &replace, 1);
    std::fs::write(file_path, &new_content)
        .map_err(|e| format!("Failed to write file: {:?}", e))?;

    Ok(format!("Successfully edited {}", path))
}

#[derive(serde::Serialize)]
struct AppStateSnapshot {
    settings: AppSettings,
    custom_modules: Vec<CustomModule>,
    plugins: Vec<Plugin>,
}

#[tauri::command]
fn get_app_state(app_handle: tauri::AppHandle) -> Result<AppStateSnapshot, String> {
    let settings = get_settings(app_handle.clone());
    let custom_modules = get_custom_modules(app_handle.clone()).unwrap_or_default();
    let plugins = get_plugins(app_handle);
    Ok(AppStateSnapshot {
        settings,
        custom_modules,
        plugins,
    })
}

struct MergedSubItem {
    label: String,
    cmd: String,
    args: Vec<String>,
}

struct MergedAction {
    id: String,
    cmd: String,
    args: Vec<String>,
    sub_items: Vec<MergedSubItem>,
}

fn get_merged_actions_internal(app_handle: &tauri::AppHandle) -> Vec<MergedAction> {
    let mut merged = Vec::new();
    let mut existing_ids = std::collections::HashSet::new();

    // 1. Add core actions
    if let Some(settings) = load_settings_internal(app_handle) {
        for action in settings.actions {
            existing_ids.insert(action.id.clone());
            merged.push(MergedAction {
                id: action.id,
                cmd: action.cmd,
                args: action.args,
                sub_items: action
                    .sub_items
                    .into_iter()
                    .map(|s| MergedSubItem {
                        label: s.label,
                        cmd: s.cmd,
                        args: s.args,
                    })
                    .collect(),
            });
        }
    }

    // 2. Add plugin actions
    let plugins = get_plugins(app_handle.clone());
    for plugin in plugins {
        if plugin.enabled {
            for action in plugin.menu_actions {
                if !existing_ids.contains(&action.id) {
                    existing_ids.insert(action.id.clone());
                    merged.push(MergedAction {
                        id: action.id,
                        cmd: action.cmd,
                        args: action.args,
                        sub_items: action
                            .sub_items
                            .into_iter()
                            .map(|s| MergedSubItem {
                                label: s.label,
                                cmd: s.cmd,
                                args: s.args,
                            })
                            .collect(),
                    });
                }
            }
        }
    }

    merged
}

fn run_action_command(cmd: &str, args: &[String], app_handle: &tauri::AppHandle) {
    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        let mut cmd_str = cmd.to_string();
        if cmd_str == "media_helper" {
            if let Ok(helper_path) = compile_media_helper(app_handle) {
                cmd_str = helper_path.to_string_lossy().to_string();
            }
        }
        let mut resolved_cmd = cmd_str.clone();
        if resolved_cmd.to_lowercase() == "powershell.exe"
            || resolved_cmd.to_lowercase() == "powershell"
        {
            resolved_cmd =
                "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe".to_string();
        }
        let mut command = std::process::Command::new(&resolved_cmd);
        if cmd_str.contains("powershell") || cmd == "media_helper" {
            command.creation_flags(0x08000000); // CREATE_NO_WINDOW
        }
        if cmd_str == "cmd.exe" && args.is_empty() {
            command.args(["/C", "start", "cmd.exe"]);
        } else {
            command.args(args);
        }
        let _ = command.spawn();
    }
}

#[tauri::command]
fn execute_action(action_id: String, app_handle: tauri::AppHandle) {
    if action_id == "settings" {
        show_settings(app_handle);
    } else if action_id == "assistant" {
        show_assistant(app_handle);
    } else if action_id == "ai_terminal" {
        show_ai_terminal(app_handle);
    } else {
        let merged = get_merged_actions_internal(&app_handle);
        if let Some(action) = merged.iter().find(|a| a.id == action_id) {
            run_action_command(&action.cmd, &action.args, &app_handle);
        }
    }
}

#[tauri::command]
fn execute_sub_action(primary_index: usize, sub_index: usize, app_handle: tauri::AppHandle) {
    let merged = get_merged_actions_internal(&app_handle);
    if let Some(primary) = merged.get(primary_index) {
        if let Some(sub) = primary.sub_items.get(sub_index) {
            if sub.cmd == "settings" {
                show_settings(app_handle);
            } else if sub.cmd == "assistant" {
                show_assistant(app_handle);
            } else if sub.cmd == "ai_terminal" {
                show_ai_terminal(app_handle);
            } else {
                run_action_command(&sub.cmd, &sub.args, &app_handle);
            }
        }
    }
}

fn get_scripts_dir(app: &tauri::AppHandle) -> PathBuf {
    let mut path = app
        .path()
        .app_data_dir()
        .unwrap_or_else(|_| PathBuf::from("."));
    path.push("scripts");
    let _ = std::fs::create_dir_all(&path);
    path
}

fn get_events_path(app: &tauri::AppHandle) -> PathBuf {
    let mut path = app
        .path()
        .app_data_dir()
        .unwrap_or_else(|_| PathBuf::from("."));
    let _ = std::fs::create_dir_all(&path);
    path.push("events.json");
    path
}

#[tauri::command]
fn get_scripts(app_handle: tauri::AppHandle) -> Result<Vec<ScriptFile>, String> {
    let dir = get_scripts_dir(&app_handle);
    let mut scripts = Vec::new();
    if let Ok(entries) = std::fs::read_dir(dir) {
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_file() {
                if let Some(name) = path.file_name().and_then(|n| n.to_str()) {
                    if let Ok(content) = std::fs::read_to_string(&path) {
                        scripts.push(ScriptFile {
                            name: name.to_string(),
                            content,
                        });
                    }
                }
            }
        }
    }
    Ok(scripts)
}

#[tauri::command]
fn save_script(name: String, content: String, app_handle: tauri::AppHandle) -> Result<(), String> {
    let sanitized_name = name.replace("..", "").replace("/", "").replace("\\", "");
    if sanitized_name.is_empty() {
        return Err("Invalid script name".to_string());
    }
    let mut path = get_scripts_dir(&app_handle);
    path.push(sanitized_name);
    std::fs::write(path, content).map_err(|e| format!("Failed to write script: {:?}", e))?;
    Ok(())
}

#[tauri::command]
fn delete_script(name: String, app_handle: tauri::AppHandle) -> Result<(), String> {
    let sanitized_name = name.replace("..", "").replace("/", "").replace("\\", "");
    let mut path = get_scripts_dir(&app_handle);
    path.push(sanitized_name);
    if path.exists() {
        std::fs::remove_file(path).map_err(|e| format!("Failed to delete script: {:?}", e))?;
    }
    Ok(())
}

#[tauri::command]
fn get_events(app_handle: tauri::AppHandle) -> Result<Vec<PlannerEvent>, String> {
    let path = get_events_path(&app_handle);
    if path.exists() {
        if let Ok(content) = std::fs::read_to_string(path) {
            if let Ok(events) = serde_json::from_str::<Vec<PlannerEvent>>(&content) {
                return Ok(events);
            }
        }
    }
    Ok(Vec::new())
}

#[tauri::command]
fn save_events(events: Vec<PlannerEvent>, app_handle: tauri::AppHandle) -> Result<(), String> {
    let path = get_events_path(&app_handle);
    let serialized = serde_json::to_string_pretty(&events)
        .map_err(|e| format!("Failed to serialize events: {:?}", e))?;
    std::fs::write(path, serialized).map_err(|e| format!("Failed to write events: {:?}", e))?;
    Ok(())
}

#[tauri::command]
fn run_terminal_command(cmd: String) -> Result<String, String> {
    use std::process::Command;
    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        let output = Command::new("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe")
            .args(["-NoProfile", "-Command", &cmd])
            .creation_flags(0x08000000) // CREATE_NO_WINDOW
            .output()
            .map_err(|e| format!("Failed to execute process: {:?}", e))?;

        let stdout = String::from_utf8_lossy(&output.stdout).into_owned();
        let stderr = String::from_utf8_lossy(&output.stderr).into_owned();

        if output.status.success() {
            Ok(stdout)
        } else {
            Err(format!(
                "Code {:?}\nStdout: {}\nStderr: {}",
                output.status.code(),
                stdout,
                stderr
            ))
        }
    }
    #[cfg(not(target_os = "windows"))]
    {
        let output = Command::new("sh")
            .args(["-c", &cmd])
            .output()
            .map_err(|e| format!("Failed to execute process: {:?}", e))?;

        let stdout = String::from_utf8_lossy(&output.stdout).into_owned();
        let stderr = String::from_utf8_lossy(&output.stderr).into_owned();

        if output.status.success() {
            Ok(stdout)
        } else {
            Err(format!(
                "Code {:?}\nStdout: {}\nStderr: {}",
                output.status.code(),
                stdout,
                stderr
            ))
        }
    }
}

#[tauri::command]
fn spawn_terminal_command(
    id: String,
    cmd: String,
    app_handle: tauri::AppHandle,
) -> Result<(), String> {
    use std::process::Command;
    use tauri::Emitter;

    #[cfg(target_os = "windows")]
    let mut command = {
        use std::os::windows::process::CommandExt;
        let mut c = Command::new("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe");
        c.args(["-NoProfile", "-Command", &cmd]);
        c.creation_flags(0x08000000); // CREATE_NO_WINDOW
        c
    };

    #[cfg(not(target_os = "windows"))]
    let mut command = {
        let mut c = Command::new("sh");
        c.args(["-c", &cmd]);
        c
    };

    command.stdout(std::process::Stdio::piped());
    command.stderr(std::process::Stdio::piped());

    let mut child = command
        .spawn()
        .map_err(|e| format!("Failed to spawn process: {:?}", e))?;

    let stdout = child.stdout.take().ok_or("Failed to capture stdout")?;
    let stderr = child.stderr.take().ok_or("Failed to capture stderr")?;

    let app_clone1 = app_handle.clone();
    let id_clone1 = id.clone();
    std::thread::spawn(move || {
        use std::io::{BufRead, BufReader};
        let reader = BufReader::new(stdout);
        for line in reader.lines() {
            if let Ok(l) = line {
                let _ = app_clone1.emit(&format!("terminal-out-{}", id_clone1), l);
            }
        }
    });

    let app_clone2 = app_handle.clone();
    let id_clone2 = id.clone();
    std::thread::spawn(move || {
        use std::io::{BufRead, BufReader};
        let reader = BufReader::new(stderr);
        for line in reader.lines() {
            if let Ok(l) = line {
                let _ = app_clone2.emit(&format!("terminal-err-{}", id_clone2), l);
            }
        }
    });

    std::thread::spawn(move || {
        let status = child.wait().unwrap();
        let _ = app_handle.emit(&format!("terminal-exit-{}", id), status.code().unwrap_or(1));
    });

    Ok(())
}

#[tauri::command]
fn write_file(path: String, content: String) -> Result<(), String> {
    let p = std::path::Path::new(&path);
    if let Some(parent) = p.parent() {
        std::fs::create_dir_all(parent)
            .map_err(|e| format!("Failed to create directories: {:?}", e))?;
    }
    std::fs::write(p, content).map_err(|e| format!("Failed to write file: {:?}", e))?;
    Ok(())
}

#[tauri::command]
fn create_directory(path: String) -> Result<(), String> {
    std::fs::create_dir_all(path).map_err(|e| format!("Failed to create directory: {:?}", e))?;
    Ok(())
}

#[tauri::command]
fn read_file(path: String) -> Result<String, String> {
    std::fs::read_to_string(path).map_err(|e| format!("Failed to read file: {:?}", e))
}

#[tauri::command]
fn list_directory(path: String) -> Result<Vec<String>, String> {
    let entries =
        std::fs::read_dir(path).map_err(|e| format!("Failed to read directory: {:?}", e))?;
    let mut files = Vec::new();
    for entry in entries.flatten() {
        if let Some(name) = entry.file_name().to_str() {
            let file_type = if entry.file_type().map(|t| t.is_dir()).unwrap_or(false) {
                "DIR: "
            } else {
                "FILE: "
            };
            files.push(format!("{}{}", file_type, name));
        }
    }
    Ok(files)
}

#[tauri::command]
fn show_assistant(app_handle: tauri::AppHandle) {
    println!("DEBUG: show_assistant called!");
    if let Some(window) = app_handle.get_webview_window("assistant") {
        println!("DEBUG: Found assistant window!");
        let _ = window.center();
        let _ = window.unminimize();
        let r1 = window.show();
        let r2 = window.set_focus();
        println!("DEBUG: show result: {:?}, focus result: {:?}", r1, r2);
    } else {
        println!("DEBUG: Assistant window NOT found in app_handle!");
    }
}

#[tauri::command]
fn show_project_board(app_handle: tauri::AppHandle) -> String {
    println!("DEBUG: show_project_board called!");
    if let Some(window) = app_handle.get_webview_window("project_board") {
        println!("DEBUG: Found project board window!");
        let _ = window.center();
        let _ = window.unminimize();
        let _ = window.show();
        let _ = window.set_focus();
        "SUCCESS".to_string()
    } else {
        println!("DEBUG: project board window NOT found in app_handle!");
        "NOT_FOUND".to_string()
    }
}

#[tauri::command]
fn show_ai_terminal(app_handle: tauri::AppHandle) {
    println!("DEBUG: show_ai_terminal called!");
    if let Some(window) = app_handle.get_webview_window("ai_terminal") {
        println!("DEBUG: Found ai_terminal window!");
        let r1 = window.show();
        let r2 = window.set_focus();
        println!("DEBUG: show result: {:?}, focus result: {:?}", r1, r2);
    } else {
        println!("DEBUG: ai_terminal window NOT found in app_handle!");
    }
}

#[cfg(target_os = "windows")]
fn get_foreground_window_title() -> Option<String> {
    use windows_sys::Win32::UI::WindowsAndMessaging::{GetForegroundWindow, GetWindowTextW};
    unsafe {
        let hwnd = GetForegroundWindow();
        if hwnd == 0 {
            return None;
        }
        let mut buffer = [0u16; 512];
        let len = GetWindowTextW(hwnd, buffer.as_mut_ptr(), buffer.len() as i32);
        if len > 0 {
            let title = String::from_utf16_lossy(&buffer[..len as usize]);
            Some(title)
        } else {
            None
        }
    }
}

#[tauri::command]
fn get_active_app() -> String {
    #[cfg(target_os = "windows")]
    {
        if let Some(title) = get_foreground_window_title() {
            let title_lower = title.to_lowercase();
            if title_lower.contains("minecraft") {
                return "Minecraft".to_string();
            } else if title_lower.contains("visual studio code")
                || title_lower.contains("vscode")
                || title_lower.contains("code - ")
            {
                return "VSCode".to_string();
            } else if title_lower.contains("notepad") {
                return "Notepad".to_string();
            } else if title_lower.contains("browser")
                || title_lower.contains("chrome")
                || title_lower.contains("edge")
                || title_lower.contains("firefox")
            {
                return "Browser".to_string();
            }
            return title;
        }
    }
    "None".to_string()
}

#[cfg(target_os = "windows")]
fn get_local_date_time() -> (String, String) {
    use windows_sys::Win32::Foundation::SYSTEMTIME;
    use windows_sys::Win32::System::SystemInformation::GetLocalTime;

    let mut st = SYSTEMTIME {
        wYear: 0,
        wMonth: 0,
        wDayOfWeek: 0,
        wDay: 0,
        wHour: 0,
        wMinute: 0,
        wSecond: 0,
        wMilliseconds: 0,
    };
    unsafe {
        GetLocalTime(&mut st);
    }
    let date = format!("{:04}-{:02}-{:02}", st.wYear, st.wMonth, st.wDay);
    let time = format!("{:02}:{:02}", st.wHour, st.wMinute);
    (date, time)
}

#[cfg(not(target_os = "windows"))]
fn get_local_date_time() -> (String, String) {
    ("2026-06-03".to_string(), "12:00".to_string())
}

fn run_script_file(script_name: &str, app_handle: &tauri::AppHandle) {
    let mut path = get_scripts_dir(app_handle);
    path.push(script_name);
    if !path.exists() {
        return;
    }

    let path_str = path.to_string_lossy().to_string();

    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        use std::process::Command;

        let mut command = if path_str.ends_with(".ps1") {
            let mut c =
                Command::new("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe");
            c.args([
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                &path_str,
            ]);
            c
        } else if path_str.ends_with(".bat") || path_str.ends_with(".cmd") {
            let mut c = Command::new("cmd.exe");
            c.args(["/C", &path_str]);
            c
        } else if path_str.ends_with(".js") {
            let mut c = Command::new("node");
            c.arg(&path_str);
            c
        } else {
            Command::new(&path_str)
        };

        command.creation_flags(0x08000000); // CREATE_NO_WINDOW
        let _ = command.spawn();
    }
}

fn trigger_notification(title: &str, body: &str) {
    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        use std::process::Command;
        let script = format!(
            "[void] [System.Reflection.Assembly]::LoadWithPartialName('System.Windows.Forms'); \
             $objNotification = New-Object System.Windows.Forms.NotifyIcon; \
             $objNotification.Icon = [System.Drawing.SystemIcons]::Information; \
             $objNotification.BalloonTipIcon = 'Info'; \
             $objNotification.BalloonTipTitle = '{}'; \
             $objNotification.BalloonTipText = '{}'; \
             $objNotification.Visible = $True; \
             $objNotification.ShowBalloonTip(5000);",
            title.replace("'", "''"),
            body.replace("'", "''")
        );
        let mut c = Command::new("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe");
        c.args(["-NoProfile", "-WindowStyle", "Hidden", "-Command", &script]);
        c.creation_flags(0x08000000); // CREATE_NO_WINDOW
        let _ = c.spawn();
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_autostart::Builder::new().build())
        .plugin(tauri_plugin_global_shortcut::Builder::new().build())
        .plugin(tauri_plugin_opener::init())
        .manage(SystemMonitor(Mutex::new(System::new_with_specifics(
            RefreshKind::new()
                .with_cpu(CpuRefreshKind::everything())
                .with_memory(MemoryRefreshKind::everything()),
        ))))
        .manage(OverlayState(Mutex::new(false)))
        .setup(|app| {
            let app_handle = app.handle().clone();
            let settings = get_settings(app_handle.clone());
            let _ = register_hotkey(&app_handle, &settings.hotkey);

            // Set/remove auto-start registry key on Windows based on setting (only in release mode)
            #[cfg(target_os = "windows")]
            {
                #[cfg(debug_assertions)]
                {
                    // Clean up registry keys pointing to debug executables
                    let mut cmd = std::process::Command::new("reg");
                    cmd.args([
                        "delete",
                        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                        "/v",
                        "TauriRadialMenu",
                        "/f",
                    ]);
                    use std::os::windows::process::CommandExt;
                    cmd.creation_flags(0x08000000); // CREATE_NO_WINDOW
                    let _ = cmd.status();
                }
                #[cfg(not(debug_assertions))]
                {
                    if settings.autostart {
                        if let Ok(exe_path) = std::env::current_exe() {
                            let exe_str = exe_path.to_string_lossy();
                            let mut cmd = std::process::Command::new("reg");
                            cmd.args([
                                "add",
                                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                                "/v",
                                "TauriRadialMenu",
                                "/t",
                                "REG_SZ",
                                "/d",
                                &format!("\"{}\"", exe_str),
                                "/f",
                            ]);
                            use std::os::windows::process::CommandExt;
                            cmd.creation_flags(0x08000000); // CREATE_NO_WINDOW
                            let _ = cmd.status();
                        }
                    } else {
                        let mut cmd = std::process::Command::new("reg");
                        cmd.args([
                            "delete",
                            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                            "/v",
                            "TauriRadialMenu",
                            "/f",
                        ]);
                        use std::os::windows::process::CommandExt;
                        cmd.creation_flags(0x08000000); // CREATE_NO_WINDOW
                        let _ = cmd.status();
                    }
                }
            }

            // Delete old settings file so defaults reload with sub-items
            let s_path = get_settings_path(&app_handle);
            if s_path.exists() {
                if let Ok(content) = std::fs::read_to_string(&s_path) {
                    // Force migration if missing v2 defaults (Quick Apps, ChatGPT, assistant, etc.)
                    if !content.contains("media_helper")
                        || !content.contains("Quick Apps")
                        || !content.contains("assistant")
                    {
                        let _ = std::fs::remove_file(&s_path);
                        let defaults = get_default_settings();
                        if let Ok(serialized) = serde_json::to_string_pretty(&defaults) {
                            let _ = std::fs::write(&s_path, &serialized);
                        }
                    }
                }
            }

            if let Some(settings_window) = app.get_webview_window("settings") {
                let settings_window_clone = settings_window.clone();
                settings_window.on_window_event(move |event| {
                    if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                        api.prevent_close();
                        let _ = settings_window_clone.hide();
                    }
                });
            }

            // Start automation scheduler background thread
            use tauri::Emitter;
            let app_handle_scheduler = app.handle().clone();
            std::thread::spawn(move || loop {
                std::thread::sleep(std::time::Duration::from_secs(60));

                let (curr_date, curr_time) = get_local_date_time();

                if let Ok(mut events) = get_events(app_handle_scheduler.clone()) {
                    let mut updated = false;
                    for event in &mut events {
                        if !event.completed && event.script.is_some() {
                            let is_due = event.date < curr_date
                                || (event.date == curr_date && event.time <= curr_time);
                            if is_due {
                                if let Some(ref script_name) = event.script {
                                    run_script_file(script_name, &app_handle_scheduler);
                                    trigger_notification(
                                        &format!("Task Automated: {}", event.title),
                                        &format!(
                                            "Successfully triggered script '{}'.",
                                            script_name
                                        ),
                                    );
                                }
                                event.completed = true;
                                event.last_run = Some(format!("{} {}", curr_date, curr_time));
                                updated = true;
                            }
                        }
                    }

                    if updated {
                        let _ = save_events(events, app_handle_scheduler.clone());
                        let _ = app_handle_scheduler.emit("events-updated", ());
                    }
                }
            });

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            get_settings,
            save_settings,
            show_settings,
            hide_menu,
            execute_action,
            execute_sub_action,
            get_current_media_info,
            get_system_stats,
            is_hotkey_held,
            control_media,
            get_scripts,
            save_script,
            delete_script,
            get_events,
            save_events,
            show_assistant,
            show_project_board,
            show_ai_terminal,
            show_design_maker,
            get_plugins,
            save_plugin,
            delete_plugin,
            toggle_plugin,
            get_plugin_settings,
            save_plugin_settings,
            export_plugin,
            import_plugin,
            run_terminal_command,
            spawn_terminal_command,
            write_file,
            create_directory,
            read_file,
            list_directory,
            get_custom_modules,
            save_custom_module,
            delete_custom_module,
            search_files,
            edit_file_content,
            get_app_state,
            get_active_app
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
