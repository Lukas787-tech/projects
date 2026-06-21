"""Media Controls feature - play/pause, next, prev, volume."""

import keyboard
from features.base_feature import BaseFeature
from ui.radial_item import RadialItem
from PySide6.QtGui import QColor


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


def _mute_toggle():
    try:
        from pycaw.pycaw import AudioUtilities, IAudioEndpointVolume
        from comtypes import CLSCTX_ALL
        devices = AudioUtilities.GetSpeakers()
        interface = devices.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None)
        volume = interface.QueryInterface(IAudioEndpointVolume)
        muted = volume.GetMute()
        volume.SetMute(not muted, None)
    except Exception:
        keyboard.send("volume mute")


class MediaControlsFeature(BaseFeature):
    id = "media_controls"
    label = "Media"
    icon = "\U0001F3B5"  # Musical note
    color = "#4ECDC4"

    def get_items(self) -> list[RadialItem]:
        return [
            RadialItem(id="play_pause", label="Play / Pause", icon_text="\u23EF",
                       color=QColor("#4ECDC4"), action=lambda: keyboard.send("play/pause media"),
                       feature_id=self.id, action_id="play_pause"),
            RadialItem(id="next_track", label="Next Track", icon_text="\u23ED",
                       color=QColor("#45B7D1"), action=lambda: keyboard.send("next track"),
                       feature_id=self.id, action_id="next_track"),
            RadialItem(id="prev_track", label="Prev Track", icon_text="\u23EE",
                       color=QColor("#6C63FF"), action=lambda: keyboard.send("previous track"),
                       feature_id=self.id, action_id="prev_track"),
            RadialItem(id="vol_up", label="Volume Up", icon_text="\U0001F50A",
                       color=QColor("#6BCB77"), action=_volume_up,
                       feature_id=self.id, action_id="vol_up"),
            RadialItem(id="vol_down", label="Volume Down", icon_text="\U0001F509",
                       color=QColor("#FFD93D"), action=_volume_down,
                       feature_id=self.id, action_id="vol_down"),
            RadialItem(id="mute", label="Mute", icon_text="\U0001F507",
                       color=QColor("#F38181"), action=_mute_toggle,
                       feature_id=self.id, action_id="mute"),
            RadialItem(id="stop", label="Stop", icon_text="\u23F9",
                       color=QColor("#AA96DA"), action=lambda: keyboard.send("stop media"),
                       feature_id=self.id, action_id="stop"),
        ]
