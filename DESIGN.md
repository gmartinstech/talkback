# TalkBack Design System

## Source
Based on [gmartinstech/maritime-design-system](https://github.com/gmartinstech/maritime-design-system) — Maritime Precision: Navy #003566 + Gold #F5CC00.

## Scene
An engineer at their desk, late afternoon, ambient light from a window. The app sits quietly in the system tray until summoned. When opened, it feels like a ship's bridge instrument: authoritative, legible, focused.

## Theme
Dark. The user is a developer. The app lives in the background and is summoned for focused review work. Dark reduces eye strain and keeps the floating widget from feeling like a bright intrusion.

## Color Strategy
Committed — navy blue carries the surface identity, gold is the functional accent.

### Brand Palette
| Token | Hex | Role |
|-------|-----|------|
| navy-900 | #001D3D | Deepest backgrounds |
| navy-800 | #002952 | Header, FAB |
| navy-700 | #003566 | **Primary brand navy** |
| navy-600 | #004080 | Hover states |
| navy-500 | #0059B3 | Active states |
| navy-400 | #3385CC | Secondary accents |
| navy-300 | #66A3D9 | Subtle highlights |
| navy-200 | #99C2E6 | Borders, dividers |
| navy-100 | #CCE0F2 | Light backgrounds |
| navy-50 | #E6F0F9 | Subtle tints |

| Token | Hex | Role |
|-------|-----|------|
| gold-600 | #B8930F | Dark accent |
| gold-500 | #D4A51A | Hover |
| gold-400 | #F5CC00 | **Primary brand gold** |
| gold-300 | #FFD633 | Light accent |
| gold-200 | #FFE066 | Highlights |
| gold-100 | #FFF0A3 | Subtle tints |
| gold-50 | #FFFBE6 | Background tints |

### Neutral Palette (Slate)
| Token | Hex | Role |
|-------|-----|------|
| slate-950 | #0C1222 | Deepest background |
| slate-900 | #0F172A | Dark background |
| slate-800 | #1E293B | Card surfaces |
| slate-700 | #334155 | Borders, dividers |
| slate-600 | #475569 | Secondary text |
| slate-500 | #64748B | Muted text |
| slate-400 | #94A3B8 | Placeholder text |
| slate-300 | #CBD5E1 | Light borders |
| slate-200 | #E2E8F0 | Subtle borders |
| slate-100 | #F1F5F9 | Light backgrounds |
| slate-50 | #F8FAFC | Subtle backgrounds |

### Semantic Colors
| Token | Hex | Role |
|-------|-----|------|
| success-500 | #10B981 | Additions in diff |
| danger-500 | #EF4444 | Deletions in diff |
| warning-500 | #F59E0B | Warnings |
| info-500 | #0EA5E9 | Info |

### Dark Theme Mapping
```css
[data-theme="dark"] {
  --color-bg: var(--slate-950);
  --color-bg-alt: var(--slate-900);
  --color-surface: var(--slate-900);
  --color-surface-raised: var(--slate-800);

  --color-border: var(--slate-700);
  --color-border-subtle: var(--slate-800);
  --color-border-strong: var(--slate-600);

  --color-text-primary: var(--slate-100);
  --color-text-secondary: var(--slate-400);
  --color-text-muted: #94A3B8;
  --color-text-inverse: var(--slate-900);

  --color-brand: var(--navy-400);
  --color-brand-hover: var(--navy-300);
  --color-accent: var(--gold-400);
  --color-accent-hover: var(--gold-300);
  --color-accent-subtle: rgba(245, 204, 0, 0.1);

  --color-success: var(--success-400);
  --color-danger: var(--danger-400);
  --color-warning: var(--warning-400);
  --color-info: var(--info-400);
}
```

## Typography
- **Display / Body**: DM Sans (system fallback: Segoe UI, sans-serif)
- **Monospace**: Fira Code (system fallback: Consolas, monospace)
- **Scale**: 14px base
  - xs: 12px, sm: 14px, base: 16px, lg: 18px, xl: 20px, 2xl: 24px
- **Body line length**: max 65ch in chat bubbles
- **Weights**: 400 normal, 500 medium, 600 semibold, 700 bold

## Elevation
- No heavy shadows. Subtle 1-2px lifts.
- FAB: `box-shadow: 0 2px 8px rgba(0, 0, 0, 0.3)`
- Chat window: `box-shadow: 0 4px 24px rgba(0, 0, 0, 0.4)`
- Code popup: `box-shadow: 0 8px 32px rgba(0, 0, 0, 0.5)`

## Components

### FAB
- 56px circle
- navy-800 background, gold-400 icon
- Scale 1.05 on hover (ease-out-quart, 150ms)
- Appears bottom-right with 24px margin

### Chat Window
- 380px wide, 600px tall
- Rounded corners 12px
- No border. Shadow defines the edge.
- Header: navy-800, 48px tall, gold-400 1px bottom border

### Chat Bubbles
- Assistant: slate-900 background, slate-100 text, left-aligned
- User: navy-800 background with gold-400 tint at 10%, slate-100 text, right-aligned
- Max width 85%
- Padding 12px 16px
- Corner radius: 12px

### Code Viewer Popup
- Modal overlay at 80% viewport
- Dark background (slate-950)
- Left sidebar: file tree in slate-400
- Main area: diff with danger-400 (red) / success-400 (green) highlighting
- Uses Prism.js with custom maritime dark theme

### Input Area
- Single line, navy-900 background slightly lighter than chat
- Gold-400 focus ring (1px)
- Model selector dropdown in header right

## Motion
- Ease-out-quart for all transitions: `cubic-bezier(0.25, 1, 0.5, 1)`
- Chat open: 200ms scale + fade
- FAB hover: 150ms scale
- Message appear: 150ms slide-up + fade
- Code popup: 200ms fade + slight scale

## Layout
- Chat window floats bottom-right
- Code viewer centers on screen
- Model selector in header right (defaults to first available gemma4 model)
- Settings (gear) opens config dialog

## Absolute Bans
- No gradient text
- No glassmorphism
- No modal as first thought (code viewer is the exception)
- No side-stripe borders on cards
- No identical card grids
- No purple AI branding
