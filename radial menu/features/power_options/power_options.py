"""Power Options feature - shutdown, restart, sleep, hibernate, lock, sign out."""

import ctypes
import subprocess
import os

from features.base_feature import BaseFeature
from ui.radial_item import RadialItem
from PySide6.QtGui import QColor


class PowerOptionsFeature(BaseFeature):
    id = "power_options"
    label = "Power"
    icon = "\u23FB"  # Power symbol
    color = "#F38181"

    def get_items(self) -> list[RadialItem]:
        color = QColor(self.color)
        return [
            RadialItem(id="shutdown", label="Shutdown", icon_text="\u23FB",
                       color=QColor("#FF4444"), action=self._shutdown, feature_id=self.id, action_id="shutdown"),
            RadialItem(id="restart", label="Restart", icon_text="\u21BB",
                       color=QColor("#FF8844"), action=self._restart, feature_id=self.id, action_id="restart"),
            RadialItem(id="sleep", label="Sleep", icon_text="\U0001F319",
                       color=QColor("#6C63FF"), action=self._sleep, feature_id=self.id, action_id="sleep"),
            RadialItem(id="hibernate", label="Hibernate", icon_text="\u2744",
                       color=QColor("#45B7D1"), action=self._hibernate, feature_id=self.id, action_id="hibernate"),
            RadialItem(id="lock", label="Lock", icon_text="\U0001F512",
                       color=QColor("#FFD93D"), action=self._lock, feature_id=self.id, action_id="lock"),
            RadialItem(id="signout", label="Sign Out", icon_text="\U0001F6AA",
                       color=QColor("#AA96DA"), action=self._signout, feature_id=self.id, action_id="signout"),
        ]

    def _shutdown(self):
        os.system("shutdown /s /t 5")

    def _restart(self):
        os.system("shutdown /r /t 5")

    def _sleep(self):
        ctypes.windll.PowrProf.SetSuspendState(0, 1, 0)

    def _hibernate(self):
        ctypes.windll.PowrProf.SetSuspendState(1, 1, 0)

    def _lock(self):
        ctypes.windll.user32.LockWorkStation()

    def _signout(self):
        os.system("shutdown /l")
