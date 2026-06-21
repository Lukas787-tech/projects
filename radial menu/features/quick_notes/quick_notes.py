"""Quick Notes feature - mini notepad with local save."""

import json
import os
import time
from pathlib import Path

from features.base_feature import BaseFeature
from ui.radial_item import RadialItem
from PySide6.QtGui import QColor, QFont
from PySide6.QtWidgets import (
    QWidget, QVBoxLayout, QTextEdit, QPushButton, QHBoxLayout,
    QLabel, QListWidget, QListWidgetItem, QStackedWidget,
)
from PySide6.QtCore import Qt


class NoteEditorPopup(QWidget):
    """Popup window for editing notes."""

    def __init__(self, notes_dir: Path, note_name: str = ""):
        super().__init__()
        self._notes_dir = notes_dir
        self._note_name = note_name

        self.setWindowTitle("Quick Note" if not note_name else note_name)
        self.setWindowFlags(
            Qt.WindowType.WindowStaysOnTopHint | Qt.WindowType.Tool
        )
        self.setMinimumSize(400, 300)
        self.setStyleSheet("""
            QWidget { background-color: #1a1a2e; color: #e0e0f0; }
            QTextEdit {
                background-color: #16213e; color: #e0e0f0;
                border: 1px solid #333366; border-radius: 8px;
                padding: 10px; font-size: 13px;
                selection-background-color: #6C63FF;
            }
            QPushButton {
                background-color: #6C63FF; color: white;
                border: none; border-radius: 6px;
                padding: 8px 16px; font-weight: bold;
            }
            QPushButton:hover { background-color: #5A52E0; }
            QPushButton#delete { background-color: #F38181; }
            QPushButton#delete:hover { background-color: #E06060; }
        """)

        layout = QVBoxLayout(self)
        self._editor = QTextEdit()
        self._editor.setFont(QFont("Consolas", 12))
        layout.addWidget(self._editor)

        btn_layout = QHBoxLayout()
        save_btn = QPushButton("Save")
        save_btn.clicked.connect(self._save)
        btn_layout.addWidget(save_btn)

        close_btn = QPushButton("Close")
        close_btn.setObjectName("delete")
        close_btn.clicked.connect(self.close)
        btn_layout.addWidget(close_btn)
        layout.addLayout(btn_layout)

        self._load()

    def _load(self):
        if self._note_name:
            path = self._notes_dir / f"{self._note_name}.txt"
            if path.exists():
                self._editor.setPlainText(path.read_text(encoding="utf-8"))

    def _save(self):
        self._notes_dir.mkdir(parents=True, exist_ok=True)
        if not self._note_name:
            self._note_name = time.strftime("note_%Y%m%d_%H%M%S")
            self.setWindowTitle(self._note_name)
        path = self._notes_dir / f"{self._note_name}.txt"
        path.write_text(self._editor.toPlainText(), encoding="utf-8")


class QuickNotesFeature(BaseFeature):
    id = "quick_notes"
    label = "Notes"
    icon = "\U0001F4DD"  # Memo
    color = "#FFD93D"

    def __init__(self):
        self._notes_dir = Path(os.path.dirname(os.path.dirname(os.path.dirname(
            os.path.abspath(__file__))))) / "data" / "notes"
        self._popups: list[NoteEditorPopup] = []

    def _new_note(self):
        popup = NoteEditorPopup(self._notes_dir)
        popup.show()
        self._popups.append(popup)

    def _open_note(self, name: str):
        def _do_open():
            popup = NoteEditorPopup(self._notes_dir, name)
            popup.show()
            self._popups.append(popup)
        return _do_open

    def _open_notes_folder(self):
        try:
            self._notes_dir.mkdir(parents=True, exist_ok=True)
            os.startfile(str(self._notes_dir))
        except Exception:
            pass

    def get_items(self) -> list[RadialItem]:
        items = [
            RadialItem(id="note_new", label="New Note", icon_text="\u2795",
                       color=QColor("#FFD93D"), action=self._new_note,
                       feature_id=self.id, action_id="new"),
        ]

        # List existing notes (up to 6)
        self._notes_dir.mkdir(parents=True, exist_ok=True)
        colors = ["#FCBAD3", "#AA96DA", "#6C63FF", "#45B7D1", "#4ECDC4", "#6BCB77"]
        note_files = sorted(self._notes_dir.glob("*.txt"), key=os.path.getmtime, reverse=True)[:6]
        for i, f in enumerate(note_files):
            name = f.stem
            items.append(RadialItem(
                id=f"note_{name}",
                label=name[:15],
                icon_text="\U0001F4C4",
                color=QColor(colors[i % len(colors)]),
                action=self._open_note(name),
                feature_id=self.id,
                action_id=name,
            ))

        items.append(RadialItem(
            id="note_folder", label="Open Folder", icon_text="\U0001F4C2",
            color=QColor("#AA96DA"), action=self._open_notes_folder,
            feature_id=self.id, action_id="folder",
        ))
        return items
