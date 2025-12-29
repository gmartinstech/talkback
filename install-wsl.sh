#!/bin/bash
# TalkBack Installation Script for WSL
# Installs dependencies and configures Claude Code hooks

set -e

echo "=== TalkBack WSL Installer ==="
echo ""

# Get script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
echo "Project directory: $SCRIPT_DIR"

# Detect if we're in WSL
if grep -qi microsoft /proc/version 2>/dev/null; then
    echo "Environment: WSL detected"
else
    echo "Warning: Not running in WSL. This script is designed for WSL."
fi

# Step 1: Install Python dependencies
echo ""
echo "[1/4] Installing Python dependencies..."

# Check for pip
if command -v pip3 &> /dev/null; then
    PIP_CMD="pip3"
elif command -v pip &> /dev/null; then
    PIP_CMD="pip"
else
    echo "  Error: pip not found. Please install Python and pip first."
    exit 1
fi

$PIP_CMD install edge-tts --quiet --user 2>/dev/null || {
    echo "  Warning: Failed to install edge-tts. Will use SAPI fallback."
}
echo "  edge-tts installation attempted"

# Step 2: Create temp directory for audio files
echo ""
echo "[2/4] Creating temp directory..."
mkdir -p /mnt/c/temp
echo "  Created /mnt/c/temp for audio files"

# Step 3: Configure Claude Code hooks
echo ""
echo "[3/4] Configuring Claude Code hooks..."

CLAUDE_DIR="$HOME/.claude"
SETTINGS_PATH="$CLAUDE_DIR/settings.json"

# Create .claude directory if needed
mkdir -p "$CLAUDE_DIR"

# Convert WSL path to Windows path for the hook commands
WIN_SCRIPT_DIR=$(wslpath -w "$SCRIPT_DIR" 2>/dev/null || echo "$SCRIPT_DIR")

# Create or update settings.json
if [ -f "$SETTINGS_PATH" ]; then
    echo "  Found existing settings.json"
    # Backup existing settings
    cp "$SETTINGS_PATH" "$SETTINGS_PATH.backup"
    echo "  Backup created: settings.json.backup"
else
    echo '{}' > "$SETTINGS_PATH"
    echo "  Created new settings.json"
fi

# Use Python to update the JSON (more reliable than jq)
python3 << PYTHON_SCRIPT
import json
import os

settings_path = "$SETTINGS_PATH"
script_dir = "$SCRIPT_DIR"

# Load existing settings
try:
    with open(settings_path, 'r') as f:
        settings = json.load(f)
except:
    settings = {}

# Ensure hooks object exists
if 'hooks' not in settings:
    settings['hooks'] = {}

# Define hook configurations
# For WSL, we use the Linux Python path
on_stop_path = f"{script_dir}/hooks/on_stop.py"
on_tool_path = f"{script_dir}/hooks/on_tool_complete.py"

settings['hooks']['Stop'] = [{
    "matcher": "",
    "hooks": [{
        "type": "command",
        "command": f"python3 '{on_stop_path}'",
        "timeout": 30
    }]
}]

settings['hooks']['PostToolUse'] = [{
    "matcher": "*",
    "hooks": [{
        "type": "command",
        "command": f"python3 '{on_tool_path}'",
        "timeout": 10
    }]
}]

# Save settings
with open(settings_path, 'w') as f:
    json.dump(settings, f, indent=2)

print(f"  Updated: {settings_path}")
PYTHON_SCRIPT

# Step 4: Test TTS
echo ""
echo "[4/4] Testing TTS..."
if python3 "$SCRIPT_DIR/speak.py" "TalkBack installed successfully" 2>/dev/null; then
    echo "  TTS test passed!"
else
    echo "  TTS test failed - will try SAPI fallback"
fi

# Done
echo ""
echo "=== Installation Complete ==="
echo ""
echo "TalkBack hooks are now active!"
echo ""
echo "Configuration: $SCRIPT_DIR/config.json"
echo ""
echo "Options:"
echo "  speak_responses: true   - Speak Claude's final responses"
echo "  speak_thinking: false   - Announce what Claude is doing"
echo "  speak_tool_results: false - Speak detailed tool outputs"
echo ""
echo "To test manually: python3 '$SCRIPT_DIR/speak.py' 'Hello world'"
echo ""
