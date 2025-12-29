#!/bin/bash
# TalkBack Uninstall Script for WSL
# Removes hooks from Claude Code settings

echo "=== TalkBack WSL Uninstaller ==="
echo ""

SETTINGS_PATH="$HOME/.claude/settings.json"

if [ ! -f "$SETTINGS_PATH" ]; then
    echo "No settings.json found. Nothing to uninstall."
    exit 0
fi

# Use Python to update the JSON
python3 << 'PYTHON_SCRIPT'
import json
import sys

settings_path = "$HOME/.claude/settings.json".replace("$HOME", __import__('os').environ['HOME'])

try:
    with open(settings_path, 'r') as f:
        settings = json.load(f)
except Exception as e:
    print(f"Error reading settings: {e}")
    sys.exit(1)

if 'hooks' in settings:
    removed = []
    if 'Stop' in settings['hooks']:
        del settings['hooks']['Stop']
        removed.append('Stop')
    if 'PostToolUse' in settings['hooks']:
        del settings['hooks']['PostToolUse']
        removed.append('PostToolUse')

    if removed:
        with open(settings_path, 'w') as f:
            json.dump(settings, f, indent=2)
        print(f"Removed hooks: {', '.join(removed)}")
    else:
        print("No TalkBack hooks found.")
else:
    print("No hooks found in settings.")

PYTHON_SCRIPT

echo ""
echo "TalkBack hooks removed successfully!"
echo ""
echo "Note: The talkback folder has not been deleted."
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
echo "To fully remove, delete: $SCRIPT_DIR"
echo ""
