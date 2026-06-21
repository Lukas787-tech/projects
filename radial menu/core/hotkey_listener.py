"""Global hotkey listener using keyboard library in a QThread."""

import keyboard
from PySide6.QtCore import QThread, Signal
import time


class HotkeyListener(QThread):
    hotkey_triggered = Signal()

    def __init__(self, hotkey: str = "ctrl+q"):
        super().__init__()
        self._hotkey = hotkey
        self._running = True
        self._last_trigger = 0.0
        self._debounce_ms = 300

    def run(self):
        keyboard.add_hotkey(self._hotkey, self._on_hotkey, suppress=True)
        while self._running:
            time.sleep(0.1)
        keyboard.remove_hotkey(self._hotkey)

    def _on_hotkey(self):
        now = time.time() * 1000
        if now - self._last_trigger > self._debounce_ms:
            self._last_trigger = now
            self.hotkey_triggered.emit()

    def stop(self):
        self._running = False
        self.wait(2000)

    def update_hotkey(self, new_hotkey: str):
        try:
            keyboard.remove_hotkey(self._hotkey)
        except (KeyError, ValueError):
            pass
        self._hotkey = new_hotkey
        keyboard.add_hotkey(self._hotkey, self._on_hotkey, suppress=True)
