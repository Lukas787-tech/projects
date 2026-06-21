"""Glassmorphism visual effects - blur, frost, gradient helpers."""

from PySide6.QtCore import QRect, QRectF
from PySide6.QtGui import (
    QColor,
    QImage,
    QPainter,
    QPainterPath,
    QPixmap,
    QRadialGradient,
)

import mss
from PIL import Image, ImageFilter


def capture_screen_region(rect: QRect) -> QPixmap | None:
    """Capture a region of the screen and return as QPixmap."""
    try:
        with mss.mss() as sct:
            monitor = {
                "left": rect.x(),
                "top": rect.y(),
                "width": rect.width(),
                "height": rect.height(),
            }
            screenshot = sct.grab(monitor)
            img = Image.frombytes("RGB", screenshot.size, screenshot.bgra, "raw", "BGRX")
            img = img.filter(ImageFilter.GaussianBlur(radius=20))
            data = img.tobytes("raw", "RGBX")
            qimg = QImage(data, img.width, img.height, QImage.Format.Format_RGBX8888)
            return QPixmap.fromImage(qimg.copy())
    except Exception:
        return None


def create_frosted_background(
    center_x: float,
    center_y: float,
    outer_radius: float,
    bg_color: QColor,
) -> QRadialGradient:
    """Create a radial gradient simulating frosted glass depth."""
    gradient = QRadialGradient(center_x, center_y, outer_radius)
    inner_color = QColor(bg_color)
    inner_color.setAlpha(min(bg_color.alpha() + 30, 255))
    outer_color = QColor(bg_color)
    outer_color.setAlpha(min(bg_color.alpha() + 60, 255))

    gradient.setColorAt(0.0, inner_color)
    gradient.setColorAt(0.7, bg_color)
    gradient.setColorAt(1.0, outer_color)
    return gradient


def create_ring_clip_path(
    center_x: float,
    center_y: float,
    inner_radius: float,
    outer_radius: float,
) -> QPainterPath:
    """Create a QPainterPath that clips to the ring (donut) shape."""
    path = QPainterPath()
    path.addEllipse(
        QRectF(
            center_x - outer_radius,
            center_y - outer_radius,
            outer_radius * 2,
            outer_radius * 2,
        )
    )
    inner = QPainterPath()
    inner.addEllipse(
        QRectF(
            center_x - inner_radius,
            center_y - inner_radius,
            inner_radius * 2,
            inner_radius * 2,
        )
    )
    return path.subtracted(inner)


def create_glow_pixmap(size: int, color: QColor, blur_radius: int = 8) -> QPixmap:
    """Create a soft glow circle pixmap for icon backgrounds."""
    total = size + blur_radius * 4
    img = Image.new("RGBA", (total, total), (0, 0, 0, 0))
    from PIL import ImageDraw

    draw = ImageDraw.Draw(img)
    margin = blur_radius * 2
    draw.ellipse(
        [margin, margin, total - margin, total - margin],
        fill=(color.red(), color.green(), color.blue(), color.alpha()),
    )
    img = img.filter(ImageFilter.GaussianBlur(radius=blur_radius))
    data = img.tobytes("raw", "RGBA")
    qimg = QImage(data, img.width, img.height, QImage.Format.Format_RGBA8888)
    return QPixmap.fromImage(qimg.copy())
