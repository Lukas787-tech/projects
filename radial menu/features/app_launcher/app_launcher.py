"""App Launcher feature - launch apps, favorites, recent."""

import subprocess
import os
import json
from pathlib import Path

from features.base_feature import BaseFeature
from ui.radial_item import RadialItem
from PySide6.QtGui import QColor


# Common Windows apps with their paths
DEFAULT_APPS = [
    ("Notepad", "notepad.exe", "\U0001F4DD"),
    ("Calculator", "calc.exe", "\U0001F5A9"),
    ("Explorer", "explorer.exe", "\U0001F4C1"),
    ("CMD", "cmd.exe", "\u2328"),
    ("Task Manager", "taskmgr.exe", "\U0001F4CA"),
    ("Paint", "mspaint.exe", "\U0001F3A8"),
    ("Settings", "ms-settings:", "\u2699"),
    ("Control Panel", "control.exe", "\U0001F527"),
]


class AppLauncherFeature(BaseFeature):
    id = "app_launcher"
    label = "Apps"
    icon = "\U0001F680"  # Rocket
    color = "#FF6B6B"

    def __init__(self):
        self._recent: list[tuple[str, str, str]] = []
        self._recent_path = Path(os.path.dirname(os.path.dirname(os.path.dirname(
            os.path.abspath(__file__))))) / "data" / "recent_apps.json"

    def on_load(self):
        self._load_recent()

    def _load_recent(self):
        try:
            if self._recent_path.exists():
                with open(self._recent_path, "r") as f:
                    self._recent = json.load(f)
        except Exception:
            self._recent = []

    def _save_recent(self):
        try:
            self._recent_path.parent.mkdir(parents=True, exist_ok=True)
            with open(self._recent_path, "w") as f:
                json.dump(self._recent[-10:], f)
        except Exception:
            pass

    def _launch(self, path: str, name: str, icon: str):
        def _do_launch():
            try:
                if path.startswith("ms-"):
                    os.startfile(path)
                else:
                    subprocess.Popen(path, shell=True)
                entry = [name, path, icon]
                if entry in self._recent:
                    self._recent.remove(entry)
                self._recent.append(entry)
                self._save_recent()
            except Exception:
                pass
        return _do_launch

    def get_items(self) -> list[RadialItem]:
        items = []
        for name, path, icon in DEFAULT_APPS:
            items.append(RadialItem(
                id=f"app_{name.lower().replace(' ', '_')}",
                label=name,
                icon_text=icon,
                color=QColor(self.color),
                action=self._launch(path, name, icon),
                feature_id=self.id,
                action_id=path,
            ))
        return items
