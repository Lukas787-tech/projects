"""Data model for a single radial menu item (slice)."""

from dataclasses import dataclass, field
from typing import Callable, Optional

from PySide6.QtGui import QColor


@dataclass
class RadialItem:
    id: str
    label: str
    icon_path: str = ""
    icon_text: str = ""  # Fallback emoji/text icon
    color: QColor = field(default_factory=lambda: QColor(108, 99, 255))
    action: Optional[Callable] = None
    children: list["RadialItem"] = field(default_factory=list)
    feature_id: str = ""
    action_id: str = ""

    @property
    def has_children(self) -> bool:
        return len(self.children) > 0


@dataclass
class RingLevel:
    items: list[RadialItem]
    parent_label: str = "Menu"
    parent_icon: str = ""
