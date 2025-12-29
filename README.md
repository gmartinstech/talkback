# TalkBack

A Claude Code hook that speaks responses and narrates the thinking process using text-to-speech.

**Cross-platform:** Works on Windows and WSL (audio plays through Windows speakers).

## Features

- **Speak Responses**: Hear Claude's final responses read aloud
- **Thinking Narration**: Hear what Claude is doing as it works (tool announcements)
- **High-Quality TTS**: Kokoro neural voices (WSL), Edge TTS (Windows), SAPI fallback
- **Cross-Platform**: Works on Windows native and WSL distros

## TTS Engines

| Engine | Quality | Offline | Platform |
|--------|---------|---------|----------|
| **Edge TTS** | Excellent (neural) | No | Primary |
| **SAPI** | Basic | Yes | Fallback |

**Note:** WSL uses Windows Python (python.exe) to leverage the same Edge TTS installation.

## Installation

### Windows

```powershell
git clone https://github.com/gmartinstech/talkback.git
cd talkback
.\install.ps1
```

### WSL (Ubuntu, etc.)

```bash
git clone https://github.com/gmartinstech/talkback.git
cd talkback
bash install-wsl.sh
```

**Note:** Each WSL distro has its own `~/.claude/settings.json`, so install separately for each distro.

## Configuration

Edit `config.json`:

```json
{
  "enabled": true,
  "voice": "en-US-AriaNeural",

  "speak_responses": true,
  "speak_thinking": true,
  "max_speak_length": 500
}
```

### Configuration Options

| Option | Default | Description |
|--------|---------|-------------|
| `enabled` | true | Master on/off switch |
| `voice` | en-US-AriaNeural | Edge TTS voice |
| `speak_responses` | true | Speak Claude's final responses |
| `speak_thinking` | true | Announce tool usage (ON by default) |
| `max_speak_length` | 500 | Max characters to speak |

### Edge TTS Voices

- `en-US-AriaNeural` - Female, conversational (default)
- `en-US-GuyNeural` - Male, conversational
- `en-GB-SoniaNeural` - British female

## Usage

Once installed, TalkBack runs automatically:

1. **Response Speaking**: After Claude finishes, you'll hear the response
2. **Thinking Mode**: Hear what Claude is doing (tool announcements) - ON by default

### Test TTS

```bash
# Windows
python speak.py "Hello, this is a test"

# WSL (uses Windows Python)
python.exe speak.py "Hello, this is a test"
```

## Uninstall

**Windows:** `.\uninstall.ps1`
**WSL:** `bash uninstall-wsl.sh`

## Architecture

```
talkback/
├── config.json              # Configuration
├── speak.py                 # TTS engine (Kokoro, Edge, SAPI)
├── hooks/
│   ├── on_stop.py           # Speaks final responses
│   └── on_tool_complete.py  # Narrates tool usage
├── install.ps1              # Windows installation
├── install-wsl.sh           # WSL installation
├── uninstall.ps1            # Windows removal
└── uninstall-wsl.sh         # WSL removal
```

## How It Works

### Windows
1. Edge TTS generates speech via Microsoft's neural API
2. Audio plays through Windows media player

### WSL
1. Calls Windows Python (python.exe) from WSL
2. Uses the same Edge TTS installation as Windows
3. Audio saved to `/mnt/c/temp/` and plays through Windows

### Fallback Chain
Edge TTS → SAPI (Windows built-in)

## Troubleshooting

**No sound?**
1. Check `enabled: true` in config.json
2. Test: `python speak.py "test"` (Windows) or `python.exe speak.py "test"` (WSL)
3. Check `~/.claude/talkback.log`

**WSL issues?**
- Ensure Windows Python is in PATH: `python.exe --version`
- Ensure `/mnt/c/temp/` exists
- Test: `powershell.exe -Command "[console]::Beep(600,200)"`

## License

MIT
