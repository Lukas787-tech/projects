"""Configuration manager - loads, saves, and validates JSON config."""

import json
import os
from pathlib import Path
from typing import Any

from PySide6.QtCore import QObject, Signal

from core.theme import Theme


DEFAULT_CONFIG = {
    "version": 1,
    "hotkey": "ctrl+q",
    "menu_position": "screen_center",
    "close_on_action": True,
    "theme": Theme().to_dict(),
    "features": [
        {
            "id": "app_launcher",
            "enabled": True,
            "order": 0,
            "color": "#FF6B6B",
            "config": {"favorites": [], "max_recent": 10},
        },
        {
            "id": "media_controls",
            "enabled": True,
            "order": 1,
            "color": "#4ECDC4",
            "config": {},
        },
        {
            "id": "system_controls",
            "enabled": True,
            "order": 2,
            "color": "#45B7D1",
            "config": {},
        },
        {
            "id": "power_options",
            "enabled": True,
            "order": 3,
            "color": "#F38181",
            "config": {},
        },
        {
            "id": "file_search",
            "enabled": True,
            "order": 4,
            "color": "#AA96DA",
            "config": {"search_dirs": ["C:\\Users"]},
        },
        {
            "id": "clipboard_manager",
            "enabled": True,
            "order": 5,
            "color": "#FCBAD3",
            "config": {"max_history": 20},
        },
        {
            "id": "screenshot_tool",
            "enabled": True,
            "order": 6,
            "color": "#A8D8EA",
            "config": {"save_dir": ""},
        },
        {
            "id": "quick_notes",
            "enabled": True,
            "order": 7,
            "color": "#FFD93D",
            "config": {},
        },
        {
            "id": "calculator",
            "enabled": True,
            "order": 8,
            "color": "#6BCB77",
            "config": {},
        },
        {
            "id": "network_toggle",
            "enabled": True,
            "order": 9,
            "color": "#4D96FF",
            "config": {},
        },
    ],
}


class ConfigManager(QObject):
    config_changed = Signal()

    _instance = None

    def __init__(self, config_path: str | None = None):
        super().__init__()
        if config_path is None:
            self._config_path = Path(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))) / "config.json"
        else:
            self._config_path = Path(config_path)
        self._config: dict[str, Any] = {}
        self._load()

    @classmethod
    def instance(cls, config_path: str | None = None) -> "ConfigManager":
        if cls._instance is None:
            cls._instance = cls(config_path)
        return cls._instance

    def _load(self):
        if self._config_path.exists():
            try:
                with open(self._config_path, "r", encoding="utf-8") as f:
                    self._config = json.load(f)
                self._validate()
            except (json.JSONDecodeError, OSError):
                self._config = dict(DEFAULT_CONFIG)
                self._save()
        else:
            self._config = dict(DEFAULT_CONFIG)
            self._save()

    def _validate(self):
        for key in DEFAULT_CONFIG:
            if key not in self._config:
                self._config[key] = DEFAULT_CONFIG[key]

    def _save(self):
        self._config_path.parent.mkdir(parents=True, exist_ok=True)
        with open(self._config_path, "w", encoding="utf-8") as f:
            json.dump(self._config, f, indent=2)

    def save(self):
        self._save()
        self.config_changed.emit()

    def get(self, key: str, default: Any = None) -> Any:
        return self._config.get(key, default)

    def set(self, key: str, value: Any):
        self._config[key] = value

    def get_theme(self) -> Theme:
        return Theme.from_config(self._config.get("theme", {}))

    def get_features(self) -> list[dict]:
        features = self._config.get("features", DEFAULT_CONFIG["features"])
        return sorted([f for f in features if f.get("enabled", True)], key=lambda f: f.get("order", 0))

    def get_all_features(self) -> list[dict]:
        return self._config.get("features", DEFAULT_CONFIG["features"])

    def get_feature_config(self, feature_id: str) -> dict:
        for f in self._config.get("features", []):
            if f["id"] == feature_id:
                return f.get("config", {})
        return {}

    def set_feature_config(self, feature_id: str, config: dict):
        for f in self._config.get("features", []):
            if f["id"] == feature_id:
                f["config"] = config
                break
        self._save()

    @property
    def hotkey(self) -> str:
        return self._config.get("hotkey", "ctrl+q")

    @property
    def menu_position(self) -> str:
        return self._config.get("menu_position", "screen_center")

    @property
    def close_on_action(self) -> bool:
        return self._config.get("close_on_action", True)

    @property
    def raw(self) -> dict:
        return self._config
