fn main() {
    // Tauri embeds its app manifest (which selects Common Controls v6) only
    // into the app binary. Test binaries need it too: without it Windows
    // can't resolve TaskDialogIndirect, imported by the dialog plugin, and
    // `cargo test` fails with STATUS_ENTRYPOINT_NOT_FOUND. So the linker
    // embeds the same manifest into every target instead.
    let windows = tauri_build::WindowsAttributes::new_without_app_manifest();
    tauri_build::try_build(tauri_build::Attributes::new().windows_attributes(windows)).expect("tauri-build failed");

    if std::env::var("CARGO_CFG_TARGET_ENV").as_deref() == Ok("msvc") {
        let manifest = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("windows-app-manifest.xml");
        println!("cargo:rerun-if-changed={}", manifest.display());
        println!("cargo:rustc-link-arg=/MANIFEST:EMBED");
        println!("cargo:rustc-link-arg=/MANIFESTINPUT:{}", manifest.display());
    }
}
