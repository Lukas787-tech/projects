"""Sub-ring rendering helpers."""

import math
from PySide6.QtCore import QRectF, QPointF
from PySide6.QtGui import QPainterPath


def compute_slices(
    count: int,
    inner_r: float,
    outer_r: float,
    start_offset: float = -90.0,
) -> list[tuple[float, float, QPainterPath]]:
    """Compute slice geometry for a ring with `count` items.

    Returns list of (start_angle_deg, span_deg, path).
    """
    if count == 0:
        return []

    span = 360.0 / count
    slices = []
    for i in range(count):
        start_angle = start_offset + i * span
        path = _build_slice_path(start_angle, span, inner_r, outer_r)
        slices.append((start_angle, span, path))
    return slices


def _build_slice_path(
    start_angle: float,
    span: float,
    inner_r: float,
    outer_r: float,
) -> QPainterPath:
    path = QPainterPath()
    outer_rect = QRectF(-outer_r, -outer_r, outer_r * 2, outer_r * 2)
    inner_rect = QRectF(-inner_r, -inner_r, inner_r * 2, inner_r * 2)

    start_rad = math.radians(start_angle)
    end_rad = math.radians(start_angle + span)

    path.moveTo(inner_r * math.cos(start_rad), inner_r * math.sin(start_rad))
    path.lineTo(outer_r * math.cos(start_rad), outer_r * math.sin(start_rad))
    path.arcTo(outer_rect, -start_angle, -span)
    path.lineTo(inner_r * math.cos(end_rad), inner_r * math.sin(end_rad))
    path.arcTo(inner_rect, -(start_angle + span), span)
    path.closeSubpath()
    return path


def slice_centroid(
    index: int,
    count: int,
    inner_r: float,
    outer_r: float,
    start_offset: float = -90.0,
) -> QPointF:
    """Compute the centroid (icon position) for a given slice."""
    span = 360.0 / count
    mid_angle = start_offset + index * span + span / 2
    mid_r = (inner_r + outer_r) / 2
    rad = math.radians(mid_angle)
    return QPointF(mid_r * math.cos(rad), mid_r * math.sin(rad))
