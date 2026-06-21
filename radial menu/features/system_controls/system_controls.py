"""System Controls feature - volume, brightness, Wi-Fi, Bluetooth."""

import subprocess
import keyboard

from features.base_feature import BaseFeature
from ui.radial_item import RadialItem
from PySide6.QtGui import QColor


def _brightness_up():
    try:
        import screen_brightness_control as sbc
        current = sbc.get_brightness()
        if isinstance(current, list):
            current = current[0]
        sbc.set_brightness(min(100, current + 10))
    except Exception:
        pass


def _brightness_down():
    try:
        import screen_brightness_control as sbc
        current = sbc.get_brightness()
        if isinstance(current, list):
            current = current[0]
        sbc.set_brightness(max(0, current - 10))
    except Exception:
        pass


def _volume_up():
    try:
        from pycaw.pycaw import AudioUtilities, IAudioEndpointVolume
        from comtypes import CLSCTX_ALL
        devices = AudioUtilities.GetSpeakers()
        interface = devices.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None)
        volume = interface.QueryInterface(IAudioEndpointVolume)
        current = volume.GetMasterVolumeLevelScalar()
        volume.SetMasterVolumeLevelScalar(min(1.0, current + 0.1), None)
    except Exception:
        keyboard.send("volume up")


def _volume_down():
    try:
        from pycaw.pycaw import AudioUtilities, IAudioEndpointVolume
        from comtypes import CLSCTX_ALL
        devices = AudioUtilities.GetSpeakers()
        interface = devices.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None)
        volume = interface.QueryInterface(IAudioEndpointVolume)
        current = volume.GetMasterVolumeLevelScalar()
        volume.SetMasterVolumeLevelScalar(max(0.0, current - 0.1), None)
    except Exception:
        keyboard.send("volume down")


def _wifi_toggle():
    try:
        result = subprocess.run(
            ["netsh", "interface", "show", "interface"],
            capture_output=True, text=True, creationflags=subprocess.CREATE_NO_WINDOW,
        )
        if "Wi-Fi" in result.stdout:
            if "Connected" in result.stdout.split("Wi-Fi")[0].split("\n")[-1]:
                subprocess.run(["netsh", "interface", "set", "interface", "Wi-Fi", "disable"],
                               creationflags=subprocess.CREATE_NO_WINDOW)
            else:
                subprocess.run(["netsh", "interface", "set", "interface", "Wi-Fi", "enable"],
                               creationflags=subprocess.CREATE_NO_WINDOW)
    except Exception:
        pass


def _bluetooth_toggle():
    try:
        # Open Bluetooth settings as a fallback - direct BT toggle requires elevation
        import os
        os.startfile("ms-settings:bluetooth")
    except Exception:
        pass


class SystemControlsFeature(BaseFeature):
    id = "system_controls"
    label = "System"
    icon = "\u2699"  # Gear
    color = "#45B7D1"

    def get_items(self) -> list[RadialItem]:
        return [
            RadialItem(id="vol_up", label="Volume Up", icon_text="\U0001F50A",
                       color=QColor("#4ECDC4"), action=_volume_up,
                       feature_id=self.id, action_id="vol_up"),
            RadialItem(id="vol_down", label="Volume Down", icon_text="\U0001F509",
                       color=QColor("#45B7D1"), action=_volume_down,
                       feature_id=self.id, action_id="vol_down"),
            RadialItem(id="bright_up", label="Brightness Up", icon_text="\u2600",
                       color=QColor("#FFD93D"), action=_brightness_up,
                       feature_id=self.id, action_id="bright_up"),
            RadialItem(id="bright_down", label="Brightness Down", icon_text="\U0001F311",
                       color=QColor("#6C63FF"), action=_brightness_down,
                       feature_id=self.id, action_id="bright_down"),
            RadialItem(id="wifi_toggle", label="Wi-Fi Toggle", icon_text="\U0001F4F6",
                       color=QColor("#6BCB77"), action=_wifi_toggle,
                       feature_id=self.id, action_id="wifi_toggle"),
            RadialItem(id="bluetooth", label="Bluetooth", icon_text="\U0001F4E1",
                       color=QColor("#4D96FF"), action=_bluetooth_toggle,
                       feature_id=self.id, action_id="bluetooth"),
        ]
