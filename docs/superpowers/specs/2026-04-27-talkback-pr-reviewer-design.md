# TalkBack PR Reviewer — Design Specification

**Date:** 2026-04-27  
**Status:** Approved  
**Register:** Product (desktop companion tool)

---

## 1. Purpose

A floating desktop chat widget for reviewing GitHub PRs with local Ollama LLMs. Sits alongside the existing TalkBack TTS system. Users paste PR links; the app clones the repo (if needed), fetches the diff via `gh` CLI, and streams a detailed walkthrough from the selected model.

---

## 2. Architecture

```
frontend/
├── app.py              # Main entry: system tray, FAB, hotkey, window mgmt
├── chat_window.py      # Floating chat widget (QWebEngineView)
├── code_viewer.py      # Popup for file/diff highlighting
├── config_dialog.py    # Ollama URL & model selector
├── services/
│   ├── github.py       # gh CLI integration: clone, PR diff, file tree
│   └── ollama.py       # List models, stream chat, default gemma4
├── templates/
│   └── chat.html       # HTML template for chat bubbles
└── static/
    └── style.css       # Maritime dark theme
```

**Tech stack:** Python 3.10+, PyQt6 (desktop shell), QWebEngineView (rich HTML chat), `gh` CLI (GitHub operations), Ollama API (local LLM).

---

## 3. Design System

**Source:** [gmartinstech/maritime-design-system](https://github.com/gmartinstech/maritime-design-system)  
**Scene:** Engineer at desk, late afternoon. App sits quietly until summoned. Feels like a ship's bridge instrument: authoritative, legible, focused.

### 3.1 Theme
Dark. Developer tool summoned for focused review work.

### 3.2 Color Strategy
Committed — navy carries surface identity, gold is functional accent.

| Token | Hex | Role |
|-------|-----|------|
| navy-800 | #002952 | Header, FAB |
| navy-700 | #003566 | Primary brand |
| gold-400 | #F5CC00 | Primary accent |
| slate-950 | #0C1222 | Deepest background |
| slate-900 | #0F172A | Card surfaces |
| slate-100 | #F1F5F9 | Primary text |
| slate-400 | #94A3B8 | Secondary text |
| success-400 | #34D399 | Diff additions |
| danger-400 | #F87171 | Diff deletions |

### 3.3 Typography
- **UI:** DM Sans (Segoe UI fallback)
- **Code:** Fira Code (Consolas fallback)
- **Scale:** 14px base; 12px, 14px, 16px, 18px, 20px, 24px

### 3.4 Motion
- Ease-out-quart: `cubic-bezier(0.25, 1, 0.5, 1)`
- Chat open: 200ms scale + fade
- FAB hover: 150ms scale 1.05
- Message appear: 150ms slide-up + fade
- Code popup: 200ms fade + slight scale

---

## 4. Components

### 4.1 FAB (Floating Action Button)
- 56px circle, navy-800 background, gold-400 icon (chat bubble or ship)
- Bottom-right, 24px margin from edges
- Hover: scale 1.05, 150ms ease-out-quart
- Click: toggles chat window

### 4.2 Chat Window
- 380px wide, 600px tall
- Rounded corners 12px, no border (shadow defines edge)
- Header: 48px tall, navy-800 background, gold-400 1px bottom border
  - Left: "TalkBack Reviewer" in slate-100
  - Right: model selector dropdown + settings gear icon
- Body: slate-950 background, scrollable message area
- Input area: single-line input, navy-900 background, gold-400 focus ring

### 4.3 Chat Bubbles
- **Assistant:** left-aligned, slate-900 background, slate-100 text, 12px radius, 85% max width
- **User:** right-aligned, navy-800 background with 10% gold tint, slate-100 text, 12px radius, 85% max width
- File references in assistant text are clickable → open code viewer

### 4.4 Code Viewer Popup
- 80% viewport, centered
- slate-950 background
- Left sidebar: file tree of changed files (slate-400 text)
- Main area: syntax-highlighted diff
  - Deletions: danger-400 `#F87171`
  - Additions: success-400 `#34D399`
- Close button top-right (X icon)
- Uses Prism.js with custom maritime dark theme

### 4.5 Settings Dialog
- Modal from gear icon
- Ollama URL input (default: `http://localhost:11434`)
- Model dropdown: fetched from `/api/tags`, defaults to first available `gemma4` model
- TTS engine selector: qwen / kokoro / edge / sapi
- Save writes to `talkback/config.json`

---

## 5. User Journey

### 5.1 Launch
1. User runs `python frontend/app.py`
2. App minimizes to system tray; FAB hidden
3. Click tray icon or press `Ctrl+Shift+T` → FAB appears
4. Click FAB → chat window opens

### 5.2 PR Review Flow
1. User pastes GitHub PR link into chat
2. Backend detects URL pattern
3. `gh repo clone` if repo not in `~/.talkback/repos/`
4. `gh pr diff` to fetch changes
5. System prompt sent to Ollama: *"You are a senior code reviewer. Walk through this PR change by change, explaining what each file does and why it matters."*
6. LLM streams response into chat
7. File references are clickable → code viewer popup

### 5.3 General Chat
- Without PR link, behaves as standard Ollama chat
- Model selectable from header dropdown

---

## 6. Data Flow

```
User Input
    → chat_window.py (UI event)
    → services/ollama.py (if PR link: services/github.py first)
    → Stream response chunks back to QWebEngineView
    → Render markdown + clickable file links
```

**PR detection:** Regex match for `github.com/[^/]+/[^/]+/pull/\d+`  
**Repo storage:** `~/.talkback/repos/<owner>/<repo>`  
**Diff fetching:** `gh pr diff <number> --repo <owner>/<repo>`  
**File tree:** Parsed from diff output

---

## 7. Error Handling

| Scenario | Behavior |
|----------|----------|
| Ollama unreachable | Show red inline error bubble; retry button |
| gh CLI not authenticated | Prompt user to run `gh auth login` |
| Repo clone fails | Show error with command output; manual retry |
| Invalid PR link | Show "Could not parse PR link" in assistant bubble |
| No gemma4 model | Default to first available model; show warning toast |

---

## 8. Configuration

Reads from and writes to existing `talkback/config.json`:

```json
{
  "ollama_url": "http://localhost:11434",
  "ollama_model": "gemma4:4b",
  "frontend": {
    "hotkey": "Ctrl+Shift+T",
    "window_width": 380,
    "window_height": 600
  }
}
```

---

## 9. Dependencies

- `PyQt6` — desktop app framework
- `PyQt6-WebEngine` — QWebEngineView for rich HTML chat
- `requests` — Ollama API calls
- `prismjs` (bundled) — syntax highlighting in code viewer

---

## 10. Out of Scope

- OAuth / web-based GitHub auth (uses `gh` CLI only)
- PR creation / editing (read-only review)
- Multi-user support
- Mobile / responsive layout
- Voice input (TTS output only, via existing TalkBack)

---

## 11. Absolute Bans

- No gradient text
- No glassmorphism
- No modal as first thought (code viewer is the exception)
- No side-stripe borders on cards
- No identical card grids
- No purple AI branding
