# frontend/code_viewer.py
import json
from pathlib import Path

from PyQt6.QtCore import Qt, QUrl
from PyQt6.QtWidgets import (
    QDialog, QVBoxLayout, QHBoxLayout, QWidget,
    QPushButton, QLabel, QTreeWidget, QTreeWidgetItem,
    QSplitter
)
from PyQt6.QtWebEngineWidgets import QWebEngineView


PRISM_CSS = """
https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/themes/prism-tomorrow.min.css
""".strip()

PRISM_JS = """
https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/prism.min.js
https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/components/prism-python.min.js
https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/components/prism-javascript.min.js
https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/components/prism-css.min.js
https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/components/prism-json.min.js
https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/components/prism-diff.min.js
""".strip()


HTML_SHELL = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<link rel="stylesheet" href="{prism_css}">
<style>
:root {{
  --slate-950: #0C1222;
  --slate-900: #0F172A;
  --slate-800: #1E293B;
  --slate-400: #94A3B8;
  --slate-100: #F1F5F9;
  --gold-400: #F5CC00;
  --success-400: #34D399;
  --danger-400: #F87171;
}}
body {{
  font-family: 'Fira Code', 'Consolas', monospace;
  font-size: 13px;
  line-height: 1.5;
  background: var(--slate-950);
  color: var(--slate-100);
  margin: 0;
  padding: 16px;
}}
pre {{
  background: var(--slate-900) !important;
  border: 1px solid var(--slate-800);
  border-radius: 8px;
  padding: 16px;
  overflow-x: auto;
}}
.line-add { background: rgba(52, 211, 153, 0.15); display: block; }
.line-del { background: rgba(248, 113, 113, 0.15); display: block; }
.filename {{
  font-family: 'DM Sans', sans-serif;
  font-size: 14px;
  font-weight: 600;
  color: var(--gold-400);
  margin-bottom: 12px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--slate-800);
}}
</style>
</head>
<body>
<div class="filename">{filename}</div>
<pre><code class="language-diff">{code}</code></pre>
{prism_js}
<script>Prism.highlightAll();</script>
</body>
</html>
"""


class CodeViewerDialog(QDialog):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowTitle("Code Viewer")
        self.setMinimumSize(900, 700)
        self.setStyleSheet("""
            QDialog {
                background: #0C1222;
                border: 1px solid #334155;
                border-radius: 12px;
            }
        """)
        self._setup_ui()

    def _setup_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)

        # Header
        header = QWidget()
        header.setStyleSheet("background: #002952; border-bottom: 1px solid #F5CC00;")
        header.setFixedHeight(48)
        hlayout = QHBoxLayout(header)
        hlayout.setContentsMargins(16, 0, 16, 0)

        title = QLabel("PR Diff Viewer")
        title.setStyleSheet("color: #F1F5F9; font-size: 16px; font-weight: 600;")
        hlayout.addWidget(title)
        hlayout.addStretch()

        close_btn = QPushButton("✕")
        close_btn.setStyleSheet("""
            QPushButton {
                background: transparent;
                color: #94A3B8;
                border: none;
                font-size: 16px;
                padding: 4px 8px;
            }
            QPushButton:hover { color: #F5CC00; }
        """)
        close_btn.clicked.connect(self.close)
        hlayout.addWidget(close_btn)
        layout.addWidget(header)

        # Splitter: file tree + diff view
        splitter = QSplitter(Qt.Orientation.Horizontal)

        # File tree
        self._tree = QTreeWidget()
        self._tree.setHeaderHidden(True)
        self._tree.setStyleSheet("""
            QTreeWidget {
                background: #0C1222;
                color: #94A3B8;
                border: none;
                outline: none;
                padding: 8px;
            }
            QTreeWidget::item {
                padding: 6px 8px;
                border-radius: 6px;
            }
            QTreeWidget::item:selected {
                background: rgba(245, 204, 0, 0.1);
                color: #F5CC00;
            }
            QTreeWidget::item:hover {
                background: #1E293B;
            }
        """)
        self._tree.currentItemChanged.connect(self._on_file_selected)
        splitter.addWidget(self._tree)

        # Diff view
        self._web = QWebEngineView()
        self._web.setStyleSheet("background: #0C1222;")
        splitter.addWidget(self._web)
        splitter.setSizes([200, 700])

        layout.addWidget(splitter)

    def load_diff(self, diff_text: str):
        self._diff_by_file = {}
        current_file = None
        current_lines = []

        for line in diff_text.splitlines():
            if line.startswith("diff --git"):
                if current_file:
                    self._diff_by_file[current_file] = "\n".join(current_lines)
                current_lines = [line]
                parts = line.split()
                current_file = parts[2][2:] if len(parts) >= 3 else "unknown"
            elif current_file is not None:
                current_lines.append(line)

        if current_file:
            self._diff_by_file[current_file] = "\n".join(current_lines)

        self._tree.clear()
        for fname in sorted(self._diff_by_file.keys()):
            item = QTreeWidgetItem([fname])
            item.setData(0, Qt.ItemDataRole.UserRole, fname)
            self._tree.addTopLevelItem(item)

        if self._diff_by_file:
            first = self._tree.topLevelItem(0)
            self._tree.setCurrentItem(first)

    def _on_file_selected(self, current, previous):
        if not current:
            return
        fname = current.data(0, Qt.ItemDataRole.UserRole)
        diff = self._diff_by_file.get(fname, "")

        # Mark diff lines
        marked = []
        for line in diff.splitlines():
            if line.startswith("+") and not line.startswith("+++"):
                marked.append(f'<span class="line-add">{self._escape(line)}</span>')
            elif line.startswith("-") and not line.startswith("---"):
                marked.append(f'<span class="line-del">{self._escape(line)}</span>')
            else:
                marked.append(self._escape(line))

        html = HTML_SHELL.format(
            filename=fname,
            code="\n".join(marked),
            prism_css=PRISM_CSS,
            prism_js="\n".join(f'<script src="{u}"></script>' for u in PRISM_JS.splitlines()),
        )
        self._web.setHtml(html, QUrl("file:///"))

    @staticmethod
    def _escape(text: str) -> str:
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
