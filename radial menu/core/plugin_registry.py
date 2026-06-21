"""Plugin registry - discovers and registers feature modules."""

from typing import Optional
from PySide6.QtGui import QColor

from features.base_feature import BaseFeature
from ui.radial_item import RadialItem
from core.config_manager import ConfigManager


class PluginRegistry:
    def __init__(self, config: ConfigManager):
        self._config = config
        self._features: dict[str, BaseFeature] = {}

    def discover(self):
        """Import and register all built-in feature modules."""
        from features.power_options.power_options import PowerOptionsFeature
        from features.media_controls.media_controls import MediaControlsFeature
        from features.app_launcher.app_launcher import AppLauncherFeature
        from features.system_controls.system_controls import SystemControlsFeature
        from features.network_toggle.network_toggle import NetworkToggleFeature
        from features.clipboard_manager.clipboard_manager import ClipboardManagerFeature
        from features.screenshot_tool.screenshot_tool import ScreenshotToolFeature
        from features.quick_notes.quick_notes import QuickNotesFeature
        from features.calculator.calculator import CalculatorFeature
        from features.file_search.file_search import FileSearchFeature

        feature_classes = {
            "power_options": PowerOptionsFeature,
            "media_controls": MediaControlsFeature,
            "app_launcher": AppLauncherFeature,
            "system_controls": SystemControlsFeature,
            "network_toggle": NetworkToggleFeature,
            "clipboard_manager": ClipboardManagerFeature,
            "screenshot_tool": ScreenshotToolFeature,
            "quick_notes": QuickNotesFeature,
            "calculator": CalculatorFeature,
            "file_search": FileSearchFeature,
        }

        for feature_conf in self._config.get_features():
            fid = feature_conf["id"]
            if fid in feature_classes:
                instance = feature_classes[fid]()
                instance.on_load()
                self._features[fid] = instance

    def build_menu(self) -> list[RadialItem]:
        """Build the top-level radial menu items from registered features."""
        items = []
        for feature_conf in self._config.get_features():
            fid = feature_conf["id"]
            feature = self._features.get(fid)
            if feature is None:
                continue

            color = QColor(feature_conf.get("color", feature.color))
            children = feature.get_items()

            item = RadialItem(
                id=fid,
                label=feature.label,
                icon_text=feature.icon,
                color=color,
                children=children,
                feature_id=fid,
            )
            items.append(item)
        return items

    def execute(self, feature_id: str, action_id: str):
        feature = self._features.get(feature_id)
        if feature:
            feature.execute(action_id)

    def get_feature(self, feature_id: str) -> Optional[BaseFeature]:
        return self._features.get(feature_id)

    def shutdown(self):
        for feature in self._features.values():
            feature.on_unload()
