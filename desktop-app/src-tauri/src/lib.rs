use std::env;
use std::fs;
use std::path::{Path, PathBuf};

fn special_dir(name: &str) -> Result<PathBuf, String> {
    let home = env::var_os("USERPROFILE")
        .or_else(|| env::var_os("HOME"))
        .ok_or_else(|| "cannot resolve home directory".to_string())?;
    let path = PathBuf::from(home).join(name);
    fs::create_dir_all(&path).map_err(|error| format!("failed to create directory: {error}"))?;
    Ok(path)
}

fn unique_target_path(dir: &Path, file_name: &str) -> PathBuf {
    let source_name = Path::new(file_name)
        .file_name()
        .and_then(|value| value.to_str())
        .unwrap_or(file_name);
    let candidate = dir.join(source_name);
    if !candidate.exists() {
        return candidate;
    }

    let stem = Path::new(source_name)
        .file_stem()
        .and_then(|value| value.to_str())
        .unwrap_or("file");
    let ext = Path::new(source_name)
        .extension()
        .and_then(|value| value.to_str())
        .unwrap_or("");

    for index in 1..1000 {
        let next_name = if ext.is_empty() {
            format!("{stem}-{index}")
        } else {
            format!("{stem}-{index}.{ext}")
        };
        let next = dir.join(next_name);
        if !next.exists() {
            return next;
        }
    }

    candidate
}

fn save_file(source_path: String, file_name: Option<String>, target_dir: PathBuf) -> Result<String, String> {
    let source = PathBuf::from(&source_path);
    if !source.exists() {
        return Err(format!("source file not found: {source_path}"));
    }

    fs::create_dir_all(&target_dir)
        .map_err(|error| format!("failed to create target directory: {error}"))?;

    let name = file_name
        .as_deref()
        .filter(|value| !value.trim().is_empty())
        .unwrap_or_else(|| source.file_name().and_then(|value| value.to_str()).unwrap_or("file"));
    let target = unique_target_path(&target_dir, name);
    fs::copy(&source, &target).map_err(|error| format!("failed to copy file: {error}"))?;
    Ok(target.to_string_lossy().to_string())
}

#[tauri::command]
fn save_download_file(source_path: String, file_name: Option<String>) -> Result<String, String> {
    save_file(source_path, file_name, special_dir("Downloads")?)
}

#[tauri::command]
fn save_document_file(source_path: String, file_name: Option<String>) -> Result<String, String> {
    save_file(source_path, file_name, special_dir("Desktop")?)
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .invoke_handler(tauri::generate_handler![save_download_file, save_document_file])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
