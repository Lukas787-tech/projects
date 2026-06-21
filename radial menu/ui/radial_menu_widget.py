"""Main radial menu widget - QPainter rendering, hit-testing, navigation."""

import math
from PySide6.QtCore import Qt, QRectF, QPointF, QTimer
from PySide6.QtGui import (
    QColor,
    QFont,
    QPainter,
    QPainterPath,
    QPen,
    QBrush,
    QRadialGradient,
    QLinearGradient,
    QCursor,
    QGuiApplication,
)
from PySide6.QtWidgets import QWidget, QApplication

from ui.radial_item import RadialItem, RingLevel
from ui.animation_manager import AnimationManager
from ui.glassmorphism import (
    capture_screen_region,
    create_frosted_background,
    create_ring_clip_path,
    create_glow_pixmap,
)
from core.theme import Theme
from core.events import EventBus


class RadialMenuWidget(QWidget):
    def __init__(self, theme: Theme, parent=None):
        super().__init__(parent)
        self.theme = theme
        self._nav_stack: list[RingLevel] = []
        self._current_items: list[RadialItem] = []
        self._hovered_index: int = -1
        self._center = QPointF(0, 0)
        self._inner_radius = theme.ring_inner_radius
        self._outer_radius = theme.ring_outer_radius
        self._bg_pixmap = None
        self._is_visible = False
        self._closing = False

        self._anim = AnimationManager(self, theme.animation_duration_ms)
        self._anim.scale_changed.connect(self._on_anim_update)
        self._anim.opacity_changed.connect(self._on_anim_update)
        self._anim.transition_finished.connect(self._on_close_finished)

        # Glow cache
        self._glow_cache: dict[str, object] = {}

        self.setWindowFlags(
            Qt.WindowType.FramelessWindowHint
            | Qt.WindowType.WindowStaysOnTopHint
            | Qt.WindowType.Tool
        )
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground)
        self.setAttribute(Qt.WidgetAttribute.WA_ShowWithoutActivating, False)
        self.setMouseTracking(True)
        self.setCursor(Qt.CursorShape.ArrowCursor)

    def set_items(self, items: list[RadialItem]):
        self._current_items = items
        self._nav_stack = [RingLevel(items=items, parent_label="Menu")]
        self._hovered_index = -1
        self.update()

    def toggle(self):
        if self._is_visible:
            self.hide_menu()
        else:
            self.show_menu()

    def show_menu(self):
        if self._closing:
            return
        screen = QGuiApplication.primaryScreen()
        if screen is None:
            return
        geom = screen.geometry()
        self.setGeometry(geom)
        self._center = QPointF(geom.width() / 2, geom.height() / 2)

        # Reset nav
        if self._nav_stack:
            root = self._nav_stack[0]
            self._nav_stack = [root]
            self._current_items = root.items
        self._hovered_index = -1

        # Capture background for blur
        self._bg_pixmap = capture_screen_region(geom)

        self._is_visible = True
        self.show()
        self.activateWindow()
        self.setFocus()
        self._anim.animate_open()
        EventBus.instance().menu_opened.emit()

    def hide_menu(self):
        if not self._is_visible or self._closing:
            return
        self._closing = True
        self._anim.animate_close()

    def _on_close_finished(self):
        if self._closing:
            self._closing = False
            self._is_visible = False
            self.hide()
            EventBus.instance().menu_closed.emit()

    def _on_anim_update(self, _=None):
        self.update()

    # --- Painting ---

    def paintEvent(self, event):
        if not self._current_items:
            return

        painter = QPainter(self)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)

        scale = self._anim.scale
        opacity = self._anim.opacity

        if opacity <= 0:
            painter.end()
            return

        cx = self._center.x()
        cy = self._center.y()

        # Dim overlay
        overlay = QColor(0, 0, 0, int(100 * opacity))
        painter.fillRect(self.rect(), overlay)

        painter.save()
        painter.translate(cx, cy)
        painter.scale(scale, scale)
        painter.setOpacity(opacity)

        n = len(self._current_items)
        inner_r = self._inner_radius
        outer_r = self._outer_radius

        # Frosted glass background ring
        self._paint_glass_ring(painter, inner_r, outer_r, n)

        # Slices
        for i, item in enumerate(self._current_items):
            self._paint_slice(painter, i, n, inner_r, outer_r, item)

        # Separators
        self._paint_separators(painter, n, inner_r, outer_r)

        # Icons and labels
        for i, item in enumerate(self._current_items):
            self._paint_icon_and_label(painter, i, n, inner_r, outer_r, item)

        # Center circle
        self._paint_center(painter, inner_r)

        painter.restore()
        painter.end()

    def _paint_glass_ring(self, painter: QPainter, inner_r, outer_r, n):
        ring_path = self._make_ring_path(inner_r, outer_r)
        gradient = create_frosted_background(0, 0, outer_r, self.theme.bg_color)
        painter.save()
        painter.setClipPath(ring_path)

        # Blurred background if available
        if self._bg_pixmap:
            cx = self._center.x()
            cy = self._center.y()
            src_rect = QRectF(
                cx - outer_r, cy - outer_r, outer_r * 2, outer_r * 2
            )
            dst_rect = QRectF(-outer_r, -outer_r, outer_r * 2, outer_r * 2)
            painter.drawPixmap(dst_rect, self._bg_pixmap, src_rect)

        painter.setBrush(QBrush(gradient))
        painter.setPen(Qt.PenStyle.NoPen)
        painter.drawPath(ring_path)

        # Glass border
        border_pen = QPen(QColor(255, 255, 255, 40), 1.5)
        painter.setPen(border_pen)
        painter.setBrush(Qt.BrushStyle.NoBrush)
        painter.drawEllipse(QRectF(-outer_r, -outer_r, outer_r * 2, outer_r * 2))
        painter.drawEllipse(QRectF(-inner_r, -inner_r, inner_r * 2, inner_r * 2))

        painter.restore()

    def _paint_slice(self, painter: QPainter, index, count, inner_r, outer_r, item: RadialItem):
        if index != self._hovered_index:
            return
        path = self._make_slice_path(index, count, inner_r, outer_r)
        # Hover highlight
        hover_grad = QRadialGradient(0, 0, outer_r)
        color = QColor(item.color)
        color.setAlpha(60)
        hover_grad.setColorAt(0.3, QColor(item.color.red(), item.color.green(), item.color.blue(), 20))
        hover_grad.setColorAt(0.7, color)
        hover_grad.setColorAt(1.0, QColor(item.color.red(), item.color.green(), item.color.blue(), 30))

        painter.save()
        painter.setPen(Qt.PenStyle.NoPen)
        painter.setBrush(QBrush(hover_grad))
        painter.drawPath(path)

        # Glow border on hovered slice
        glow_pen = QPen(QColor(item.color.red(), item.color.green(), item.color.blue(), 120), 2)
        painter.setPen(glow_pen)
        painter.setBrush(Qt.BrushStyle.NoBrush)
        painter.drawPath(path)
        painter.restore()

    def _paint_separators(self, painter: QPainter, count, inner_r, outer_r):
        if count <= 1:
            return
        painter.save()
        pen = QPen(self.theme.separator_color, 1)
        painter.setPen(pen)
        angle_step = 360.0 / count
        for i in range(count):
            angle_deg = -90 + i * angle_step
            angle_rad = math.radians(angle_deg)
            x1 = inner_r * math.cos(angle_rad)
            y1 = inner_r * math.sin(angle_rad)
            x2 = outer_r * math.cos(angle_rad)
            y2 = outer_r * math.sin(angle_rad)
            painter.drawLine(QPointF(x1, y1), QPointF(x2, y2))
        painter.restore()

    def _paint_icon_and_label(self, painter: QPainter, index, count, inner_r, outer_r, item: RadialItem):
        angle_step = 360.0 / count
        mid_angle = -90 + index * angle_step + angle_step / 2
        mid_rad = math.radians(mid_angle)
        mid_r = (inner_r + outer_r) / 2

        ix = mid_r * math.cos(mid_rad)
        iy = mid_r * math.sin(mid_rad)

        is_hovered = index == self._hovered_index
        icon_size = self.theme.icon_size + (6 if is_hovered else 0)

        # Glow behind icon if hovered
        if is_hovered:
            glow_color = QColor(item.color)
            glow_color.setAlpha(100)
            painter.save()
            painter.setPen(Qt.PenStyle.NoPen)
            grad = QRadialGradient(ix, iy, icon_size)
            grad.setColorAt(0, glow_color)
            grad.setColorAt(1, QColor(0, 0, 0, 0))
            painter.setBrush(QBrush(grad))
            painter.drawEllipse(QPointF(ix, iy), icon_size, icon_size)
            painter.restore()

        # Icon (emoji/text fallback)
        painter.save()
        icon_font = QFont(self.theme.font_family, icon_size // 2)
        painter.setFont(icon_font)
        color = QColor(255, 255, 255, 255) if is_hovered else QColor(220, 220, 240, 200)
        painter.setPen(color)
        icon_rect = QRectF(ix - icon_size / 2, iy - icon_size / 2, icon_size, icon_size)
        painter.drawText(icon_rect, Qt.AlignmentFlag.AlignCenter, item.icon_text or "?")
        painter.restore()

        # Label
        label_r = outer_r + 25
        lx = label_r * math.cos(mid_rad)
        ly = label_r * math.sin(mid_rad)

        painter.save()
        label_font = QFont(self.theme.font_family, self.theme.font_size)
        label_font.setBold(is_hovered)
        painter.setFont(label_font)

        alpha = 255 if is_hovered else 160
        painter.setPen(QColor(240, 240, 255, alpha))

        label_rect = QRectF(lx - 60, ly - 12, 120, 24)
        flags = Qt.AlignmentFlag.AlignCenter
        painter.drawText(label_rect, flags, item.label)
        painter.restore()

        # Sub-menu indicator
        if item.has_children:
            indicator_r = outer_r - 12
            ind_x = indicator_r * math.cos(mid_rad)
            ind_y = indicator_r * math.sin(mid_rad)
            painter.save()
            painter.setPen(QColor(255, 255, 255, 120))
            small_font = QFont(self.theme.font_family, 7)
            painter.setFont(small_font)
            painter.drawText(QRectF(ind_x - 6, ind_y - 6, 12, 12), Qt.AlignmentFlag.AlignCenter, "▸")
            painter.restore()

    def _paint_center(self, painter: QPainter, inner_r):
        painter.save()
        center_r = inner_r - 8

        # Center disc
        grad = QRadialGradient(0, 0, center_r)
        grad.setColorAt(0, self.theme.center_color)
        darker = QColor(self.theme.center_color)
        darker.setAlpha(min(darker.alpha() + 40, 255))
        grad.setColorAt(1, darker)

        painter.setPen(QPen(QColor(255, 255, 255, 30), 1))
        painter.setBrush(QBrush(grad))
        painter.drawEllipse(QRectF(-center_r, -center_r, center_r * 2, center_r * 2))

        # Draw back arrow or logo text
        painter.setPen(QColor(240, 240, 255, 200))
        font = QFont(self.theme.font_family, 14 if len(self._nav_stack) > 1 else 12)
        font.setBold(True)
        painter.setFont(font)
        if len(self._nav_stack) > 1:
            painter.drawText(
                QRectF(-center_r, -center_r, center_r * 2, center_r * 2),
                Qt.AlignmentFlag.AlignCenter,
                "←",
            )
        else:
            # Draw app name
            painter.setPen(QColor(self.theme.accent_color.red(), self.theme.accent_color.green(), self.theme.accent_color.blue(), 200))
            painter.drawText(
                QRectF(-center_r, -center_r, center_r * 2, center_r * 2),
                Qt.AlignmentFlag.AlignCenter,
                "WIUT",
            )
        painter.restore()

    # --- Geometry helpers ---

    def _make_ring_path(self, inner_r, outer_r) -> QPainterPath:
        path = QPainterPath()
        path.addEllipse(QRectF(-outer_r, -outer_r, outer_r * 2, outer_r * 2))
        inner = QPainterPath()
        inner.addEllipse(QRectF(-inner_r, -inner_r, inner_r * 2, inner_r * 2))
        return path.subtracted(inner)

    def _make_slice_path(self, index, count, inner_r, outer_r) -> QPainterPath:
        angle_step = 360.0 / count
        start_angle = -90 + index * angle_step
        path = QPainterPath()

        # Outer arc
        outer_rect = QRectF(-outer_r, -outer_r, outer_r * 2, outer_r * 2)
        inner_rect = QRectF(-inner_r, -inner_r, inner_r * 2, inner_r * 2)

        start_rad = math.radians(start_angle)
        end_rad = math.radians(start_angle + angle_step)

        # Start at inner arc start
        path.moveTo(inner_r * math.cos(start_rad), inner_r * math.sin(start_rad))
        # Line to outer arc start
        path.lineTo(outer_r * math.cos(start_rad), outer_r * math.sin(start_rad))
        # Qt arcTo uses 16ths of degrees and counterclockwise from 3 o'clock
        # We need to use the arcTo with proper conversion
        path.arcTo(outer_rect, -start_angle, -angle_step)
        # Line to inner arc end
        path.lineTo(inner_r * math.cos(end_rad), inner_r * math.sin(end_rad))
        # Inner arc back
        path.arcTo(inner_rect, -(start_angle + angle_step), angle_step)
        path.closeSubpath()
        return path

    # --- Hit testing ---

    def _get_hovered_index(self, pos: QPointF) -> int:
        dx = pos.x() - self._center.x()
        dy = pos.y() - self._center.y()
        dist = math.sqrt(dx * dx + dy * dy)

        scale = max(self._anim.scale, 0.01)
        effective_inner = self._inner_radius * scale
        effective_outer = self._outer_radius * scale

        if dist < effective_inner or dist > effective_outer:
            return -1

        angle = math.degrees(math.atan2(dy, dx))
        # Normalize to 0-360 starting from -90 (top)
        angle = (angle + 90) % 360

        n = len(self._current_items)
        if n == 0:
            return -1

        slice_angle = 360.0 / n
        index = int(angle / slice_angle)
        return min(index, n - 1)

    def _is_center_click(self, pos: QPointF) -> bool:
        dx = pos.x() - self._center.x()
        dy = pos.y() - self._center.y()
        dist = math.sqrt(dx * dx + dy * dy)
        scale = max(self._anim.scale, 0.01)
        return dist < (self._inner_radius - 8) * scale

    # --- Events ---

    def mouseMoveEvent(self, event):
        new_index = self._get_hovered_index(QPointF(event.position()))
        if new_index != self._hovered_index:
            self._hovered_index = new_index
            self.update()

    def mousePressEvent(self, event):
        if event.button() != Qt.MouseButton.LeftButton:
            return

        pos = QPointF(event.position())

        # Check center click (back)
        if self._is_center_click(pos):
            if len(self._nav_stack) > 1:
                self._nav_stack.pop()
                level = self._nav_stack[-1]
                self._current_items = level.items
                self._hovered_index = -1
                self._anim.animate_sub_ring_in()
                self.update()
            return

        index = self._get_hovered_index(pos)
        if index < 0:
            # Click outside - close
            self.hide_menu()
            return

        item = self._current_items[index]
        if item.has_children:
            # Navigate to sub-ring
            self._nav_stack.append(RingLevel(items=item.children, parent_label=item.label))
            self._current_items = item.children
            self._hovered_index = -1
            self._anim.animate_sub_ring_in()
            self.update()
        else:
            # Execute action
            if item.action:
                item.action()
            EventBus.instance().feature_activated.emit(item.id)
            from core.config_manager import ConfigManager
            if ConfigManager.instance().close_on_action:
                self.hide_menu()

    def keyPressEvent(self, event):
        if event.key() == Qt.Key.Key_Escape:
            if len(self._nav_stack) > 1:
                self._nav_stack.pop()
                level = self._nav_stack[-1]
                self._current_items = level.items
                self._hovered_index = -1
                self._anim.animate_sub_ring_in()
                self.update()
            else:
                self.hide_menu()

    def focusOutEvent(self, event):
        # Don't close on focus out if a child widget needs focus
        QTimer.singleShot(100, self._check_focus)

    def _check_focus(self):
        if self._is_visible and not self.isActiveWindow():
            self.hide_menu()
