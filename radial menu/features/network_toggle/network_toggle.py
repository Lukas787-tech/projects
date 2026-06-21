"""Network Toggle feature - Wi-Fi on/off, connection info."""

import subprocess
import psutil

from features.base_feature import BaseFeature
from ui.radial_item import RadialItem
from PySide6.QtGui import QColor


def _enable_wifi():
    try:
        subprocess.run(
            ["netsh", "interface", "set", "interface", "Wi-Fi", "enable"],
            creationflags=subprocess.CREATE_NO_WINDOW,
        )
    except Exception:
        pass


def _disable_wifi():
    try:
        subprocess.run(
            ["netsh", "interface", "set", "interface", "Wi-Fi", "disable"],
            creationflags=subprocess.CREATE_NO_WINDOW,
        )
    except Exception:
        pass


def _show_connection_info():
    try:
        import os
        os.startfile("ms-settings:network-status")
    except Exception:
        pass


def _show_wifi_networks():
    try:
        import os
        os.startfile("ms-settings:network-wifi")
    except Exception:
        pass


def _flush_dns():
    try:
        subprocess.run(
            ["ipconfig", "/flushdns"],
            creationflags=subprocess.CREATE_NO_WINDOW,
        )
    except Exception:
        pass


def _open_network_connections():
    try:
        subprocess.Popen("ncpa.cpl", shell=True)
    except Exception:
        pass


class NetworkToggleFeature(BaseFeature):
    id = "network_toggle"
    label = "Network"
    icon = "\U0001F310"  # Globe
    color = "#4D96FF"

    def get_items(self) -> list[RadialItem]:
        return [
            RadialItem(id="wifi_on", label="Wi-Fi On", icon_text="\U0001F4F6",
                       color=QColor("#6BCB77"), action=_enable_wifi,
                       feature_id=self.id, action_id="wifi_on"),
            RadialItem(id="wifi_off", label="Wi-Fi Off", icon_text="\U0001F4F5",
                       color=QColor("#F38181"), action=_disable_wifi,
                       feature_id=self.id, action_id="wifi_off"),
            RadialItem(id="wifi_networks", label="Wi-Fi Networks", icon_text="\U0001F4E1",
                       color=QColor("#45B7D1"), action=_show_wifi_networks,
                       feature_id=self.id, action_id="wifi_networks"),
            RadialItem(id="conn_info", label="Network Status", icon_text="\u2139",
                       color=QColor("#6C63FF"), action=_show_connection_info,
                       feature_id=self.id, action_id="conn_info"),
            RadialItem(id="flush_dns", label="Flush DNS", icon_text="\U0001F504",
                       color=QColor("#FFD93D"), action=_flush_dns,
                       feature_id=self.id, action_id="flush_dns"),
            RadialItem(id="net_connections", label="Connections", icon_text="\U0001F517",
                       color=QColor("#AA96DA"), action=_open_network_connections,
                       feature_id=self.id, action_id="net_connections"),
        ]
