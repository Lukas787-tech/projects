"""Clipboard Manager feature - clipboard history, paste previous items."""

import json
import os
from pathlib import Path

from features.base_feature import BaseFeature
from ui.radial_item import RadialItem
from PySide6.QtGui import QColor, QGuiApplication
from PySide6.QtWidgets import QApplication


class ClipboardManagerFeature(BaseFeature):
    id = "clipboard_manager"
    label = "Clipboard"
    icon = "\U0001F4CB"  # Clipboard
    color = "#FCBAD3"

    def __init__(self):
        self._history: list[str] = []
        self._max_history = 20
        self._history_path = Path(os.path.dirname(os.path.dirname(os.path.dirname(
            os.path.abspath(__file__))))) / "data" / "clipboard_history.json"
        self._monitoring = False

    def on_load(self):
        self._load_history()
        self._start_monitoring()

    def on_unload(self):
        self._save_history()

    def _load_history(self):
        try:
            if self._history_path.exists():
                with open(self._history_path, "r", encoding="utf-8") as f:
                    self._history = json.load(f)
        except Exception:
            self._history = []

    def _save_history(self):
        try:
            self._history_path.parent.mkdir(parents=True, exist_ok=True)
            with open(self._history_path, "w", encoding="utf-8") as f:
                json.dump(self._history[-self._max_history:], f, ensure_ascii=False)
        except Exception:
            pass

    def _start_monitoring(self):
        app = QApplication.instance()
        if app:
            clipboard = app.clipboard()
            if clipboard:
                clipboard.dataChanged.connect(self._on_clipboard_change)
                self._monitoring = True

    def _on_clipboard_change(self):
        app = QApplication.instance()
        if not app:
            return
        clipboard = app.clipboard()
        if not clipboard:
            return
        text = clipboard.text()
        if text and (not self._history or self._history[-1] != text):
            self._history.append(text)
            if len(self._history) > self._max_history:
                self._history = self._history[-self._max_history:]
            self._save_history()

    def _paste_item(self, text: str):
        def _do_paste():
            app = QApplication.instance()
            if app:
                clipboard = app.clipboard()
                if clipboard:
                    clipboard.setText(text)
        return _do_paste

    def _clear_history(self):
        self._history.clear()
        self._save_history()

    def get_items(self) -> list[RadialItem]:
        items = []
        colors = ["#FCBAD3", "#F38181", "#AA96DA", "#6C63FF", "#45B7D1",
                   "#4ECDC4", "#6BCB77", "#FFD93D", "#FF6B6B", "#4D96FF"]

        # Show most recent items (up to 8) plus a clear button
        recent = list(reversed(self._history[-8:]))
        for i, text in enumerate(recent):
            preview = text[:20].replace("\n", " ")
            if len(text) > 20:
                preview += "..."
            items.append(RadialItem(
                id=f"clip_{i}",
                label=preview,
                icon_text=str(i + 1),
                color=QColor(colors[i % len(colors)]),
                action=self._paste_item(text),
                feature_id=self.id,
                action_id=f"clip_{i}",
            ))

        if not items:
            items.append(RadialItem(
                id="clip_empty",
                label="No History",
                icon_text="\U0001F4CB",
                color=QColor("#AAAAAA"),
                feature_id=self.id,
            ))

        items.append(RadialItem(
            id="clip_clear",
            label="Clear All",
            icon_text="\U0001F5D1",
            color=QColor("#FF4444"),
            action=self._clear_history,
            feature_id=self.id,
            action_id="clear",
        ))
        return items
