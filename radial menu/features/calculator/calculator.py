"""Calculator feature - basic calculator popup."""

import ast
import operator
import math

from features.base_feature import BaseFeature
from ui.radial_item import RadialItem
from PySide6.QtGui import QColor, QFont
from PySide6.QtWidgets import (
    QWidget, QVBoxLayout, QLineEdit, QLabel, QGridLayout, QPushButton,
)
from PySide6.QtCore import Qt


SAFE_OPERATORS = {
    ast.Add: operator.add,
    ast.Sub: operator.sub,
    ast.Mult: operator.mul,
    ast.Div: operator.truediv,
    ast.Pow: operator.pow,
    ast.USub: operator.neg,
    ast.UAdd: operator.pos,
    ast.Mod: operator.mod,
    ast.FloorDiv: operator.floordiv,
}


def safe_eval(expr: str) -> float:
    """Safely evaluate a mathematical expression."""
    expr = expr.replace("^", "**")
    tree = ast.parse(expr, mode="eval")

    def _eval(node):
        if isinstance(node, ast.Expression):
            return _eval(node.body)
        elif isinstance(node, ast.Constant):
            if isinstance(node.value, (int, float)):
                return node.value
            raise ValueError("Unsupported type")
        elif isinstance(node, ast.BinOp):
            op_type = type(node.op)
            if op_type not in SAFE_OPERATORS:
                raise ValueError(f"Unsupported operator: {op_type}")
            left = _eval(node.left)
            right = _eval(node.right)
            return SAFE_OPERATORS[op_type](left, right)
        elif isinstance(node, ast.UnaryOp):
            op_type = type(node.op)
            if op_type not in SAFE_OPERATORS:
                raise ValueError(f"Unsupported operator: {op_type}")
            return SAFE_OPERATORS[op_type](_eval(node.operand))
        elif isinstance(node, ast.Call):
            if isinstance(node.func, ast.Name):
                safe_funcs = {"sqrt": math.sqrt, "abs": abs, "round": round,
                              "sin": math.sin, "cos": math.cos, "tan": math.tan,
                              "log": math.log, "log10": math.log10}
                if node.func.id in safe_funcs:
                    args = [_eval(a) for a in node.args]
                    return safe_funcs[node.func.id](*args)
            raise ValueError("Unsupported function")
        else:
            raise ValueError(f"Unsupported expression: {type(node)}")

    return _eval(tree)


class CalculatorPopup(QWidget):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("Calculator")
        self.setWindowFlags(
            Qt.WindowType.WindowStaysOnTopHint | Qt.WindowType.Tool
        )
        self.setFixedSize(320, 420)
        self.setStyleSheet("""
            QWidget { background-color: #1a1a2e; color: #e0e0f0; }
            QLineEdit {
                background-color: #16213e; color: #e0e0f0;
                border: 1px solid #333366; border-radius: 8px;
                padding: 12px; font-size: 20px; font-family: Consolas;
            }
            QLabel {
                color: #888; font-size: 12px; padding: 4px;
            }
            QPushButton {
                background-color: #16213e; color: #e0e0f0;
                border: 1px solid #333366; border-radius: 8px;
                padding: 12px; font-size: 16px; font-weight: bold;
            }
            QPushButton:hover { background-color: #6C63FF; border-color: #6C63FF; }
            QPushButton#op { background-color: #2a2a4e; }
            QPushButton#op:hover { background-color: #6C63FF; }
            QPushButton#equals { background-color: #6C63FF; }
            QPushButton#equals:hover { background-color: #5A52E0; }
            QPushButton#clear { background-color: #F38181; }
            QPushButton#clear:hover { background-color: #E06060; }
        """)

        layout = QVBoxLayout(self)
        layout.setSpacing(6)

        self._history = QLabel("")
        layout.addWidget(self._history)

        self._display = QLineEdit("0")
        self._display.setAlignment(Qt.AlignmentFlag.AlignRight)
        self._display.returnPressed.connect(self._evaluate)
        layout.addWidget(self._display)

        grid = QGridLayout()
        grid.setSpacing(4)
        buttons = [
            ("C", 0, 0, "clear"), ("(", 0, 1, "op"), (")", 0, 2, "op"), ("/", 0, 3, "op"),
            ("7", 1, 0, ""), ("8", 1, 1, ""), ("9", 1, 2, ""), ("*", 1, 3, "op"),
            ("4", 2, 0, ""), ("5", 2, 1, ""), ("6", 2, 2, ""), ("-", 2, 3, "op"),
            ("1", 3, 0, ""), ("2", 3, 1, ""), ("3", 3, 2, ""), ("+", 3, 3, "op"),
            ("0", 4, 0, ""), (".", 4, 1, ""), ("^", 4, 2, "op"), ("=", 4, 3, "equals"),
        ]

        for text, row, col, obj_name in buttons:
            btn = QPushButton(text)
            if obj_name:
                btn.setObjectName(obj_name)
            btn.clicked.connect(lambda checked, t=text: self._on_button(t))
            grid.addWidget(btn, row, col)

        layout.addLayout(grid)

    def _on_button(self, text: str):
        if text == "C":
            self._display.setText("0")
            self._history.setText("")
        elif text == "=":
            self._evaluate()
        else:
            current = self._display.text()
            if current == "0" and text not in ".()*+-/^":
                self._display.setText(text)
            else:
                self._display.setText(current + text)

    def _evaluate(self):
        expr = self._display.text()
        try:
            result = safe_eval(expr)
            if isinstance(result, float) and result == int(result):
                result = int(result)
            self._history.setText(f"{expr} =")
            self._display.setText(str(result))
        except Exception:
            self._history.setText(f"{expr} = Error")
            self._display.setText("0")


class CalculatorFeature(BaseFeature):
    id = "calculator"
    label = "Calculator"
    icon = "\U0001F5A9"  # Calculator emoji
    color = "#6BCB77"

    def __init__(self):
        self._popup = None

    def _open_calc(self):
        self._popup = CalculatorPopup()
        self._popup.show()

    def _open_windows_calc(self):
        import subprocess
        try:
            subprocess.Popen("calc.exe")
        except Exception:
            pass

    def get_items(self) -> list[RadialItem]:
        return [
            RadialItem(id="calc_builtin", label="Quick Calc", icon_text="\U0001F5A9",
                       color=QColor("#6BCB77"), action=self._open_calc,
                       feature_id=self.id, action_id="builtin"),
            RadialItem(id="calc_windows", label="Windows Calc", icon_text="\U0001F4BB",
                       color=QColor("#45B7D1"), action=self._open_windows_calc,
                       feature_id=self.id, action_id="windows"),
        ]
