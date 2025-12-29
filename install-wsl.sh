#!/bin/bash
# TalkBack Installation Script for WSL
# Configures WSL Claude Code hooks to call Windows Python with Windows paths
# This ensures Edge TTS and Kokoro (installed on Windows) are always available

set -e

echo "=== TalkBack WSL Installer ==="
echo ""

# Detect if we're in WSL
if grep -qi microsoft /proc/version 2>/dev/null; then
    echo "Environment: WSL detected"
else
    echo "Warning: Not running in WSL. This script is designed for WSL."
fi

# Step 1: Check for Windows Python
echo ""
echo "[1/3] Checking for Windows Python..."

if command -v python.exe &> /dev/null; then
    echo "  Found Windows Python: $(python.exe --version 2>&1)"
else
    echo "  Error: Windows Python (python.exe) not found."
    echo "  Please install Python on Windows first."
    exit 1
fi

# Verify packages on Windows
echo "  Checking Windows packages..."
if python.exe -c "import edge_tts" 2>/dev/null; then
    echo "  edge-tts: installed"
else
    echo "  edge-tts: not found (run install.ps1 on Windows first)"
fi

if python.exe -c "import kokoro" 2>/dev/null; then
    echo "  kokoro: installed"
else
    echo "  kokoro: not found (optional - will use Edge TTS)"
fi

# Step 2: Create temp directory for audio files
echo ""
echo "[2/3] Creating temp directory..."
mkdir -p /mnt/c/temp
echo "  Created /mnt/c/temp for audio files"

# Step 3: Configure Claude Code hooks
echo ""
echo "[3/3] Configuring Claude Code hooks..."

CLAUDE_DIR="$HOME/.claude"
SETTINGS_PATH="$CLAUDE_DIR/settings.json"

# Create .claude directory if needed
mkdir -p "$CLAUDE_DIR"

# Windows paths to the talkback scripts (hardcoded to Windows location)
WIN_TALKBACK_DIR="C:\\Users\\gsilva\\talkback"
WIN_ON_STOP="$WIN_TALKBACK_DIR\\hooks\\on_stop.py"
WIN_ON_TOOL="$WIN_TALKBACK_DIR\\hooks\\on_tool_complete.py"

# Create or update settings.json
if [ -f "$SETTINGS_PATH" ]; then
    echo "  Found existing settings.json"
    cp "$SETTINGS_PATH" "$SETTINGS_PATH.backup"
    echo "  Backup created: settings.json.backup"
else
    echo '{}' > "$SETTINGS_PATH"
    echo "  Created new settings.json"
fi

# Use Python3 (from WSL) to update the JSON config
python3 << PYTHON_SCRIPT
import json

settings_path = "$SETTINGS_PATH"
win_on_stop = r"$WIN_ON_STOP"
win_on_tool = r"$WIN_ON_TOOL"

# Load existing settings
try:
    with open(settings_path, 'r') as f:
        settings = json.load(f)
except:
    settings = {}

# Ensure hooks object exists
if 'hooks' not in settings:
    settings['hooks'] = {}

# Configure hooks to use Windows Python with Windows paths
settings['hooks']['Stop'] = [{
    "matcher": "",
    "hooks": [{
        "type": "command",
        "command": f'python.exe "{win_on_stop}"',
        "timeout": 30
    }]
}]

settings['hooks']['PostToolUse'] = [{
    "matcher": "*",
    "hooks": [{
        "type": "command",
        "command": f'python.exe "{win_on_tool}"',
        "timeout": 10
    }]
}]

# Save settings
with open(settings_path, 'w') as f:
    json.dump(settings, f, indent=2)

print(f"  Updated: {settings_path}")
PYTHON_SCRIPT

# Test TTS using Windows Python
echo ""
echo "Testing TTS..."
WIN_SPEAK="C:\\Users\\gsilva\\talkback\\speak.py"
if python.exe "$WIN_SPEAK" "TalkBack installed successfully" 2>/dev/null; then
    echo "  TTS test passed!"
else
    echo "  TTS test may have issues"
fi

# Done
echo ""
echo "=== Installation Complete ==="
echo ""
echo "TalkBack hooks are now active for this WSL distro!"
echo ""
echo "How it works:"
echo "  - WSL Claude Code triggers hooks"
echo "  - Hooks call Windows Python (python.exe)"
echo "  - Windows Python runs talkback scripts from C:\\Users\\gsilva\\talkback"
echo "  - Edge TTS and Kokoro (on Windows) are always available"
echo ""
echo "Configuration: C:\\Users\\gsilva\\talkback\\config.json"
echo ""
echo "To test: python.exe \"C:\\Users\\gsilva\\talkback\\speak.py\" \"Hello world\""
echo ""
