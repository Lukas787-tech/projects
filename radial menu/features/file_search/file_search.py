"""File Search feature - quick file search with threaded os.walk."""

import os
from pathlib import Path

from features.base_feature import BaseFeature
from ui.radial_item import RadialItem
from PySide6.QtGui import QColor, QFont
from PySide6.QtWidgets import (
    QWidget, QVBoxLayout, QLineEdit, QListWidget, QListWidgetItem,
    QLabel, QPushButton, QHBoxLayout,
)
from PySide6.QtCore import Qt, QThread, Signal


class SearchWorker(QThread):
    result_found = Signal(str)
    search_done = Signal()

    def __init__(self, query: str, search_dirs: list[str], max_results: int = 50):
        super().__init__()
        self._query = query.lower()
        self._search_dirs = search_dirs
        self._max_results = max_results
        self._running = True
        self._count = 0

    def run(self):
        for search_dir in self._search_dirs:
            if not self._running:
                break
            try:
                for root, dirs, files in os.walk(search_dir):
                    if not self._running:
                        break
                    # Skip hidden/system dirs
                    dirs[:] = [d for d in dirs if not d.startswith(".") and d not in
                               ("node_modules", "__pycache__", ".git", "AppData")]
                    for fname in files:
                        if not self._running:
                            break
                        if self._query in fname.lower():
                            full = os.path.join(root, fname)
                            self.result_found.emit(full)
                            self._count += 1
                            if self._count >= self._max_results:
                                self._running = False
                                break
            except (PermissionError, OSError):
                continue
        self.search_done.emit()

    def stop(self):
        self._running = False


class FileSearchPopup(QWidget):
    def __init__(self, search_dirs: list[str]):
        super().__init__()
        self._search_dirs = search_dirs
        self._worker = None

        self.setWindowTitle("File Search")
        self.setWindowFlags(
            Qt.WindowType.WindowStaysOnTopHint | Qt.WindowType.Tool
        )
        self.setMinimumSize(500, 400)
        self.setStyleSheet("""
            QWidget { background-color: #1a1a2e; color: #e0e0f0; }
            QLineEdit {
                background-color: #16213e; color: #e0e0f0;
                border: 1px solid #333366; border-radius: 8px;
                padding: 10px; font-size: 14px;
                selection-background-color: #6C63FF;
            }
            QListWidget {
                background-color: #16213e; color: #e0e0f0;
                border: 1px solid #333366; border-radius: 8px;
                padding: 4px; font-size: 12px;
            }
            QListWidget::item { padding: 6px; border-radius: 4px; }
            QListWidget::item:hover { background-color: #2a2a4e; }
            QListWidget::item:selected { background-color: #6C63FF; }
            QLabel { color: #888; font-size: 11px; }
        """)

        layout = QVBoxLayout(self)
        self._input = QLineEdit()
        self._input.setPlaceholderText("Type to search files...")
        self._input.textChanged.connect(self._on_text_changed)
        self._input.returnPressed.connect(self._open_selected)
        layout.addWidget(self._input)

        self._status = QLabel("Type at least 3 characters to search")
        layout.addWidget(self._status)

        self._results = QListWidget()
        self._results.itemDoubleClicked.connect(self._on_item_double_clicked)
        layout.addWidget(self._results)

    def _on_text_changed(self, text: str):
        if self._worker:
            self._worker.stop()
            self._worker.wait(1000)
            self._worker = None

        self._results.clear()
        if len(text) < 3:
            self._status.setText("Type at least 3 characters to search")
            return

        self._status.setText("Searching...")
        self._worker = SearchWorker(text, self._search_dirs)
        self._worker.result_found.connect(self._add_result)
        self._worker.search_done.connect(self._on_done)
        self._worker.start()

    def _add_result(self, path: str):
        item = QListWidgetItem(path)
        item.setData(Qt.ItemDataRole.UserRole, path)
        self._results.addItem(item)
        self._status.setText(f"Found {self._results.count()} files...")

    def _on_done(self):
        count = self._results.count()
        self._status.setText(f"Search complete - {count} results")

    def _on_item_double_clicked(self, item: QListWidgetItem):
        path = item.data(Qt.ItemDataRole.UserRole)
        if path:
            try:
                os.startfile(path)
            except Exception:
                pass

    def _open_selected(self):
        item = self._results.currentItem()
        if item:
            self._on_item_double_clicked(item)

    def closeEvent(self, event):
        if self._worker:
            self._worker.stop()
            self._worker.wait(1000)
        super().closeEvent(event)


class FileSearchFeature(BaseFeature):
    id = "file_search"
    label = "Search"
    icon = "\U0001F50D"  # Magnifying glass
    color = "#AA96DA"

    def __init__(self):
        self._popup = None

    def _open_search(self):
        search_dirs = [os.path.expanduser("~")]
        self._popup = FileSearchPopup(search_dirs)
        self._popup.show()
        self._popup._input.setFocus()

    def _open_explorer_search(self):
        try:
            import subprocess
            subprocess.Popen(["explorer", "search-ms:"])
        except Exception:
            pass

    def _open_run_dialog(self):
        try:
            import subprocess
            subprocess.Popen("rundll32 shell32.dll,#61", shell=True)
        except Exception:
            pass

    def get_items(self) -> list[RadialItem]:
        return [
            RadialItem(id="search_files", label="File Search", icon_text="\U0001F50D",
                       color=QColor("#AA96DA"), action=self._open_search,
                       feature_id=self.id, action_id="files"),
            RadialItem(id="search_explorer", label="Explorer Search", icon_text="\U0001F4C1",
                       color=QColor("#45B7D1"), action=self._open_explorer_search,
                       feature_id=self.id, action_id="explorer"),
            RadialItem(id="search_run", label="Run Dialog", icon_text="\u25B6",
                       color=QColor("#6BCB77"), action=self._open_run_dialog,
                       feature_id=self.id, action_id="run"),
        ]
