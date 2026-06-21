"""Abstract base class for all radial menu features."""

from abc import ABC, abstractmethod
from ui.radial_item import RadialItem


class BaseFeature(ABC):
    id: str = ""
    label: str = ""
    icon: str = ""
    color: str = "#FFFFFF"

    @abstractmethod
    def get_items(self) -> list[RadialItem]:
        """Return sub-menu items for this feature (or empty for leaf)."""
        ...

    def execute(self, action_id: str) -> None:
        """Perform the action identified by action_id."""
        pass

    def on_load(self) -> None:
        """Called once at startup."""
        pass

    def on_unload(self) -> None:
        """Called on shutdown."""
        pass
