# TalkBack

A Claude Code hook that speaks responses and narrates the thinking process using text-to-speech.

**Cross-platform:** Works on Windows and WSL (audio plays through Windows speakers).

## Features

- **Speak Responses**: Hear Claude's final responses read aloud
- **Thinking Narration**: Hear what Claude is doing as it works (tool announcements)
- **High-Quality TTS**: Uses Microsoft Edge neural voices (with SAPI fallback)
- **Cross-Platform**: Works on Windows native and WSL distros

## Installation

### Windows

```powershell
cd C:\Users\gsilva\talkback
.\install.ps1
```

### WSL (Ubuntu, Wiley, etc.)

From within your WSL distro:

```bash
cd /mnt/c/Users/gsilva/talkback
bash install-wsl.sh
```

**Note:** Each WSL distro has its own `~/.claude/settings.json`, so install separately for each distro you use with Claude Code.

## Configuration

Edit `config.json` to customize behavior:

```json
{
  "enabled": true,
  "voice": "en-US-AriaNeural",
  "rate": "+10%",

  "speak_responses": true,      // Speak final responses
  "speak_thinking": false,      // Narrate tool usage
  "speak_tool_results": false,  // Speak detailed results

  "max_speak_length": 500       // Truncate long responses
}
```

### Configuration Options

| Option | Default | Description |
|--------|---------|-------------|
| `enabled` | true | Master on/off switch |
| `voice` | en-US-AriaNeural | Edge TTS voice |
| `rate` | +10% | Speech speed adjustment |
| `speak_responses` | true | Speak Claude's final responses |
| `speak_thinking` | false | Announce tool usage (thinking process) |
| `speak_tool_results` | false | Speak tool output details |
| `tools_to_announce` | ["Bash","Write","Edit"] | Tools to narrate |
| `max_speak_length` | 500 | Max characters to speak |
| `fallback_to_sapi` | true | Use Windows SAPI if Edge fails |

### Available Voices

Best neural voices for English:
- `en-US-AriaNeural` - Female, conversational (default)
- `en-US-GuyNeural` - Male, conversational
- `en-US-JennyNeural` - Female, newscast style
- `en-GB-SoniaNeural` - British female
- `en-AU-NatashaNeural` - Australian female

## Usage

Once installed, TalkBack runs automatically:

1. **Response Speaking**: After Claude finishes responding, you'll hear the response
2. **Thinking Mode**: Enable `speak_thinking: true` to hear tool announcements

### Test TTS

**Windows:**
```powershell
python C:\Users\gsilva\talkback\speak.py "Hello, this is a test"
```

**WSL:**
```bash
python3 /mnt/c/Users/gsilva/talkback/speak.py "Hello, this is a test"
```

## Uninstall

**Windows:**
```powershell
cd C:\Users\gsilva\talkback
.\uninstall.ps1
```

**WSL:**
```bash
cd /mnt/c/Users/gsilva/talkback
bash uninstall-wsl.sh
```

## Architecture

```
talkback/
├── config.json              # Configuration
├── speak.py                 # TTS engine (cross-platform)
├── hooks/
│   ├── on_stop.py           # Speaks final responses
│   └── on_tool_complete.py  # Narrates tool usage
├── install.ps1              # Windows installation
├── install-wsl.sh           # WSL installation
├── uninstall.ps1            # Windows removal
├── uninstall-wsl.sh         # WSL removal
├── PLAN.md                  # Implementation plan
└── README.md                # This file
```

## How It Works

### Windows
- Uses Edge TTS to generate high-quality speech
- Falls back to Windows SAPI if Edge TTS fails
- Audio plays through default Windows audio device

### WSL
- Uses Edge TTS within WSL
- Audio files saved to `/mnt/c/temp/`
- Plays through Windows via `powershell.exe` interop
- Falls back to Windows SAPI via PowerShell

## Limitations

- **Extended Thinking**: Claude's internal reasoning is not accessible for privacy reasons. The `speak_thinking` option narrates tool usage instead.
- **Internet Required**: Edge TTS requires internet. Falls back to Windows SAPI offline.
- **Windows Audio**: Both Windows and WSL play audio through Windows speakers.

## Troubleshooting

**No sound?**
1. Check `enabled: true` in config.json
2. Test TTS directly: `python speak.py "test"`
3. Check `~/.claude/talkback.log` for errors
4. Ensure Windows audio is not muted

**Edge TTS errors?**
- Ensure internet connectivity
- Reinstall: `pip install edge-tts --upgrade`
- Will automatically fall back to SAPI

**WSL not playing audio?**
- Ensure `powershell.exe` is accessible from WSL
- Check `/mnt/c/temp/` exists and is writable
- Try: `powershell.exe -Command "[console]::Beep(600,200)"` to test

**Hooks not running?**
- Check `~/.claude/settings.json` for hook configuration
- Restart Claude Code session
- Run `/hooks` in Claude Code to verify configuration
