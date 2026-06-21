"""Event bus for inter-component communication."""

from PySide6.QtCore import QObject, Signal


class EventBus(QObject):
    menu_opened = Signal()
    menu_closed = Signal()
    feature_activated = Signal(str)
    config_changed = Signal()
    clipboard_changed = Signal(str)

    _instance = None

    @classmethod
    def instance(cls) -> "EventBus":
        if cls._instance is None:
            cls._instance = cls()
        return cls._instance
