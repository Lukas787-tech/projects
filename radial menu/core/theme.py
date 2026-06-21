"""Theme configuration and color palettes for the radial menu."""

from dataclasses import dataclass, field
from PySide6.QtGui import QColor


@dataclass
class Theme:
    name: str = "dark_glass"
    bg_color: QColor = field(default_factory=lambda: QColor(20, 20, 30, 180))
    bg_color_hex: str = "#141420B4"
    accent_color: QColor = field(default_factory=lambda: QColor(108, 99, 255))
    accent_color_hex: str = "#6C63FF"
    glow_color: QColor = field(default_factory=lambda: QColor(108, 99, 255, 170))
    glow_color_hex: str = "#6C63FFAA"
    text_color: QColor = field(default_factory=lambda: QColor(240, 240, 255))
    separator_color: QColor = field(default_factory=lambda: QColor(255, 255, 255, 30))
    hover_color: QColor = field(default_factory=lambda: QColor(108, 99, 255, 80))
    center_color: QColor = field(default_factory=lambda: QColor(30, 30, 45, 200))
    blur_radius: int = 20
    ring_inner_radius: int = 80
    ring_outer_radius: int = 220
    sub_ring_shrink: int = 20
    animation_duration_ms: int = 200
    font_family: str = "Segoe UI"
    font_size: int = 11
    icon_size: int = 32

    @classmethod
    def from_config(cls, data: dict) -> "Theme":
        theme = cls()
        if not data:
            return theme
        theme.name = data.get("name", theme.name)
        if "bg_color" in data:
            theme.bg_color_hex = data["bg_color"]
            theme.bg_color = QColor(data["bg_color"])
        if "accent_color" in data:
            theme.accent_color_hex = data["accent_color"]
            theme.accent_color = QColor(data["accent_color"])
        if "glow_color" in data:
            theme.glow_color_hex = data["glow_color"]
            theme.glow_color = QColor(data["glow_color"])
        if "blur_radius" in data:
            theme.blur_radius = data["blur_radius"]
        if "ring_inner_radius" in data:
            theme.ring_inner_radius = data["ring_inner_radius"]
        if "ring_outer_radius" in data:
            theme.ring_outer_radius = data["ring_outer_radius"]
        if "sub_ring_shrink" in data:
            theme.sub_ring_shrink = data["sub_ring_shrink"]
        if "animation_duration_ms" in data:
            theme.animation_duration_ms = data["animation_duration_ms"]
        if "font_family" in data:
            theme.font_family = data["font_family"]
        if "font_size" in data:
            theme.font_size = data["font_size"]
        if "icon_size" in data:
            theme.icon_size = data["icon_size"]
        return theme

    def to_dict(self) -> dict:
        return {
            "name": self.name,
            "bg_color": self.bg_color_hex,
            "accent_color": self.accent_color_hex,
            "glow_color": self.glow_color_hex,
            "blur_radius": self.blur_radius,
            "ring_inner_radius": self.ring_inner_radius,
            "ring_outer_radius": self.ring_outer_radius,
            "sub_ring_shrink": self.sub_ring_shrink,
            "animation_duration_ms": self.animation_duration_ms,
            "font_family": self.font_family,
            "font_size": self.font_size,
            "icon_size": self.icon_size,
        }


THEMES = {
    "dark_glass": Theme(),
    "midnight_blue": Theme(
        name="midnight_blue",
        bg_color=QColor(10, 15, 40, 190),
        accent_color=QColor(0, 150, 255),
        glow_color=QColor(0, 150, 255, 170),
        hover_color=QColor(0, 150, 255, 80),
    ),
    "neon_green": Theme(
        name="neon_green",
        bg_color=QColor(10, 20, 10, 190),
        accent_color=QColor(0, 255, 128),
        glow_color=QColor(0, 255, 128, 170),
        hover_color=QColor(0, 255, 128, 80),
    ),
    "rose_gold": Theme(
        name="rose_gold",
        bg_color=QColor(30, 15, 20, 190),
        accent_color=QColor(255, 110, 130),
        glow_color=QColor(255, 110, 130, 170),
        hover_color=QColor(255, 110, 130, 80),
    ),
}
