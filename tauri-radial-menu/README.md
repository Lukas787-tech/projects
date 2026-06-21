# Tauri Radial Menu

A highly customizable, performant, vanilla HTML/CSS/JS Radial Menu built with Tauri v2.

## Overview
This application provides a fast, system-wide radial menu accessible via a global shortcut. It features:
- **Main Radial Menu**: Quick access to apps, folders, and scripts.
- **Design Maker**: A visual theme builder to create custom color palettes and glassmorphism effects.
- **AI Terminal & Assistant**: Built-in AI tools.
- **Project Board**: A Kanban-style task manager.
- **Media Controls**: Built-in media playback integration.

## Prerequisites
- [Node.js](https://nodejs.org/) (v16+)
- [Rust](https://www.rust-lang.org/tools/install) (latest stable)
- Tauri dependencies for your OS (e.g., MSVC C++ build tools on Windows)

## Launch & Development

1. **Install Dependencies**
   Navigate to the project folder and install Node dependencies:
   ```bash
   npm install
   ```

2. **Run in Development Mode**
   Start the Tauri development server (this will automatically compile the Rust backend and launch the app):
   ```bash
   npm run tauri dev
   ```

3. **Build for Production**
   To create a standalone executable for your system:
   ```bash
   npm run tauri build
   ```
   The compiled executable will be located in `src-tauri/target/release/`.

## Usage
- **Global Shortcut**: Once the application is running in the background, press the configured global shortcut (customizable in the Settings window) to summon the radial menu at your mouse cursor.
- **Settings**: Open the settings menu from the radial interface to configure slots, custom scripts, and change the active hotkey.
- **Custom Themes**: Use the "Design Maker" window to visually create, test, and save custom aesthetic themes.

## Architecture
- **Frontend**: Vanilla HTML, CSS, and JS (No heavy frameworks, ensuring instant startup times).
- **Backend**: Rust via Tauri, providing deep OS integration, file system access, and global keyboard hooks.
