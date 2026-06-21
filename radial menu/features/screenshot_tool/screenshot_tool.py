"""Screenshot Tool feature - full screen, region, window capture."""

import os
import time
from pathlib import Path

from features.base_feature import BaseFeature
from ui.radial_item import RadialItem
from PySide6.QtGui import QColor, QGuiApplication, QScreen, QPixmap
from PySide6.QtWidgets import QApplication, QWidget, QRubberBand, QFileDialog
from PySide6.QtCore import Qt, QRect, QPoint, QSize, QTimer

import mss
from PIL import Image


class RegionSelector(QWidget):
    """Overlay widget for region screenshot selection."""

    def __init__(self, callback):
        super().__init__()
        self._callback = callback
        self._origin = QPoint()
        self._rubber = QRubberBand(QRubberBand.Shape.Rectangle, self)
        self.setWindowFlags(
            Qt.WindowType.FramelessWindowHint
            | Qt.WindowType.WindowStaysOnTopHint
            | Qt.WindowType.Tool
        )
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground)
        self.setCursor(Qt.CursorShape.CrossCursor)

    def start(self):
        screen = QGuiApplication.primaryScreen()
        if screen:
            self.setGeometry(screen.geometry())
        self.show()

    def paintEvent(self, event):
        from PySide6.QtGui import QPainter
        painter = QPainter(self)
        painter.fillRect(self.rect(), QColor(0, 0, 0, 80))
        painter.end()

    def mousePressEvent(self, event):
        self._origin = event.pos()
        self._rubber.setGeometry(QRect(self._origin, QSize()))
        self._rubber.show()

    def mouseMoveEvent(self, event):
        self._rubber.setGeometry(QRect(self._origin, event.pos()).normalized())

    def mouseReleaseEvent(self, event):
        rect = self._rubber.geometry()
        self._rubber.hide()
        self.hide()
        if rect.width() > 10 and rect.height() > 10:
            QTimer.singleShot(100, lambda: self._callback(rect))
        self.deleteLater()

    def keyPressEvent(self, event):
        if event.key() == Qt.Key.Key_Escape:
            self._rubber.hide()
            self.hide()
            self.deleteLater()


def _get_save_dir() -> Path:
    save_dir = Path(os.path.expanduser("~")) / "Pictures" / "WIUT_Screenshots"
    save_dir.mkdir(parents=True, exist_ok=True)
    return save_dir


def _save_screenshot(img: Image.Image):
    save_dir = _get_save_dir()
    timestamp = time.strftime("%Y%m%d_%H%M%S")
    filepath = save_dir / f"screenshot_{timestamp}.png"
    img.save(str(filepath))
    # Also copy to clipboard
    try:
        from io import BytesIO
        from PySide6.QtGui import QImage
        from PySide6.QtWidgets import QApplication

        buf = BytesIO()
        img.save(buf, format="PNG")
        buf.seek(0)
        qimg = QImage()
        qimg.loadFromData(buf.read())
        app = QApplication.instance()
        if app:
            app.clipboard().setImage(qimg)
    except Exception:
        pass


class ScreenshotToolFeature(BaseFeature):
    id = "screenshot_tool"
    label = "Screenshot"
    icon = "\U0001F4F7"  # Camera
    color = "#A8D8EA"

    def __init__(self):
        self._selector = None

    def _capture_full(self):
        try:
            with mss.mss() as sct:
                monitor = sct.monitors[0]  # All monitors
                screenshot = sct.grab(monitor)
                img = Image.frombytes("RGB", screenshot.size, screenshot.bgra, "raw", "BGRX")
                _save_screenshot(img)
        except Exception:
            pass

    def _capture_region(self):
        def on_region_selected(rect: QRect):
            try:
                with mss.mss() as sct:
                    monitor = {
                        "left": rect.x(),
                        "top": rect.y(),
                        "width": rect.width(),
                        "height": rect.height(),
                    }
                    screenshot = sct.grab(monitor)
                    img = Image.frombytes("RGB", screenshot.size, screenshot.bgra, "raw", "BGRX")
                    _save_screenshot(img)
            except Exception:
                pass

        self._selector = RegionSelector(on_region_selected)
        self._selector.start()

    def _capture_window(self):
        try:
            import ctypes
            hwnd = ctypes.windll.user32.GetForegroundWindow()
            rect_type = ctypes.wintypes.RECT
            rect = rect_type()
            ctypes.windll.user32.GetWindowRect(hwnd, ctypes.byref(rect))
            with mss.mss() as sct:
                monitor = {
                    "left": rect.left,
                    "top": rect.top,
                    "width": rect.right - rect.left,
                    "height": rect.bottom - rect.top,
                }
                screenshot = sct.grab(monitor)
                img = Image.frombytes("RGB", screenshot.size, screenshot.bgra, "raw", "BGRX")
                _save_screenshot(img)
        except Exception:
            pass

    def _open_folder(self):
        try:
            os.startfile(str(_get_save_dir()))
        except Exception:
            pass

    def get_items(self) -> list[RadialItem]:
        return [
            RadialItem(id="ss_full", label="Full Screen", icon_text="\U0001F5B5",
                       color=QColor("#A8D8EA"), action=self._capture_full,
                       feature_id=self.id, action_id="full"),
            RadialItem(id="ss_region", label="Region", icon_text="\u2702",
                       color=QColor("#6C63FF"), action=self._capture_region,
                       feature_id=self.id, action_id="region"),
            RadialItem(id="ss_window", label="Window", icon_text="\U0001F5D4",
                       color=QColor("#45B7D1"), action=self._capture_window,
                       feature_id=self.id, action_id="window"),
            RadialItem(id="ss_folder", label="Open Folder", icon_text="\U0001F4C2",
                       color=QColor("#FFD93D"), action=self._open_folder,
                       feature_id=self.id, action_id="folder"),
        ]
