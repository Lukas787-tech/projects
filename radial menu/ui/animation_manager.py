"""Animation manager for radial menu open/close and transitions."""

from PySide6.QtCore import (
    QEasingCurve,
    QObject,
    QPropertyAnimation,
    QParallelAnimationGroup,
    Property,
    Signal,
)


class AnimationManager(QObject):
    scale_changed = Signal(float)
    opacity_changed = Signal(float)
    transition_finished = Signal()

    def __init__(self, parent=None, duration_ms: int = 200):
        super().__init__(parent)
        self._scale = 0.0
        self._opacity = 0.0
        self._duration = duration_ms

        self._scale_anim = QPropertyAnimation(self, b"scale_value")
        self._scale_anim.setDuration(self._duration)
        self._scale_anim.setEasingCurve(QEasingCurve.Type.OutCubic)

        self._opacity_anim = QPropertyAnimation(self, b"opacity_value")
        self._opacity_anim.setDuration(self._duration)
        self._opacity_anim.setEasingCurve(QEasingCurve.Type.OutCubic)

        self._group = QParallelAnimationGroup()
        self._group.addAnimation(self._scale_anim)
        self._group.addAnimation(self._opacity_anim)
        self._group.finished.connect(self.transition_finished.emit)

    def _get_scale(self) -> float:
        return self._scale

    def _set_scale(self, val: float):
        self._scale = val
        self.scale_changed.emit(val)

    scale_value = Property(float, _get_scale, _set_scale)

    def _get_opacity(self) -> float:
        return self._opacity

    def _set_opacity(self, val: float):
        self._opacity = val
        self.opacity_changed.emit(val)

    opacity_value = Property(float, _get_opacity, _set_opacity)

    def animate_open(self):
        self._group.stop()
        self._scale_anim.setStartValue(0.7)
        self._scale_anim.setEndValue(1.0)
        self._scale_anim.setDuration(self._duration)
        self._scale_anim.setEasingCurve(QEasingCurve.Type.OutCubic)

        self._opacity_anim.setStartValue(0.0)
        self._opacity_anim.setEndValue(1.0)
        self._opacity_anim.setDuration(self._duration)
        self._opacity_anim.setEasingCurve(QEasingCurve.Type.OutCubic)

        self._group.start()

    def animate_close(self, callback=None):
        self._group.stop()
        self._scale_anim.setStartValue(self._scale)
        self._scale_anim.setEndValue(0.7)
        self._scale_anim.setDuration(int(self._duration * 0.75))
        self._scale_anim.setEasingCurve(QEasingCurve.Type.InCubic)

        self._opacity_anim.setStartValue(self._opacity)
        self._opacity_anim.setEndValue(0.0)
        self._opacity_anim.setDuration(int(self._duration * 0.75))
        self._opacity_anim.setEasingCurve(QEasingCurve.Type.InCubic)

        if callback:
            self._group.finished.connect(callback)
        self._group.start()

    def animate_sub_ring_in(self):
        self._group.stop()
        self._scale_anim.setStartValue(0.8)
        self._scale_anim.setEndValue(1.0)
        self._scale_anim.setDuration(int(self._duration * 0.8))
        self._scale_anim.setEasingCurve(QEasingCurve.Type.OutBack)

        self._opacity_anim.setStartValue(0.3)
        self._opacity_anim.setEndValue(1.0)
        self._opacity_anim.setDuration(int(self._duration * 0.8))
        self._opacity_anim.setEasingCurve(QEasingCurve.Type.OutCubic)

        self._group.start()

    @property
    def scale(self) -> float:
        return self._scale

    @property
    def opacity(self) -> float:
        return self._opacity

    def set_duration(self, ms: int):
        self._duration = ms
