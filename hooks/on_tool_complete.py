#!/usr/bin/env python3
"""
TalkBack PostToolUse Hook
Announces tool completions and optionally speaks results.
This serves as a "thinking process" narrator - letting you hear what Claude is doing.

Hook Event: PostToolUse
Input: JSON via stdin with tool_name, tool_input, tool_response
Output: Exit 0 (success)
"""

import json
import sys
import os
from pathlib import Path

# Add parent directory to path for imports
sys.path.insert(0, str(Path(__file__).parent.parent))

from speak import speak_announcement, speak, load_config, log_error


def read_hook_input():
    """Read JSON input from stdin"""
    try:
        return json.load(sys.stdin)
    except json.JSONDecodeError as e:
        log_error(f"Failed to parse hook input: {e}")
        return None


def generate_tool_announcement(tool_name, tool_input, tool_response, config):
    """
    Generate a natural language announcement for a tool completion.
    This narrates what Claude is doing - the "thinking process".
    """

    # Tool-specific announcements
    announcements = {
        "Read": lambda: f"Read file {get_filename(tool_input.get('file_path', ''))}",
        "Write": lambda: f"Wrote file {get_filename(tool_input.get('file_path', ''))}",
        "Edit": lambda: f"Edited file {get_filename(tool_input.get('file_path', ''))}",
        "Bash": lambda: format_bash_announcement(tool_input, tool_response),
        "Glob": lambda: f"Found {count_results(tool_response)} files",
        "Grep": lambda: f"Searched for pattern, found {count_results(tool_response)} matches",
        "WebSearch": lambda: "Completed web search",
        "WebFetch": lambda: "Fetched web content",
        "Task": lambda: "Subagent task completed",
        "TodoWrite": lambda: "Updated task list",
    }

    # Check if this tool should be announced
    tools_to_announce = config.get("tools_to_announce", ["Bash", "Write", "Edit"])
    if tools_to_announce and tool_name not in tools_to_announce:
        return None

    # Generate announcement
    if tool_name in announcements:
        try:
            return announcements[tool_name]()
        except Exception as e:
            log_error(f"Error generating announcement for {tool_name}: {e}")
            return f"{tool_name} completed"

    return f"{tool_name} completed"


def get_filename(path):
    """Extract filename from path"""
    if not path:
        return "a file"
    return os.path.basename(path)


def count_results(response):
    """Count results in a tool response"""
    if not response:
        return 0
    if isinstance(response, list):
        return len(response)
    if isinstance(response, str):
        return len(response.strip().split('\n'))
    return 0


def format_bash_announcement(tool_input, tool_response):
    """Format announcement for Bash command"""
    command = tool_input.get("command", "")

    # Extract first word of command
    first_word = command.split()[0] if command else "command"

    # Check for common commands
    common_commands = {
        "npm": "NPM command",
        "git": "Git command",
        "python": "Python script",
        "node": "Node script",
        "pip": "Pip command",
        "mkdir": "Created directory",
        "cd": "Changed directory",
        "ls": "Listed files",
        "cat": "Read file",
        "rm": "Removed file",
        "mv": "Moved file",
        "cp": "Copied file",
        "pytest": "Tests",
        "jest": "Tests",
    }

    base_name = os.path.basename(first_word)
    friendly_name = common_commands.get(base_name, f"{base_name} command")

    # Check for errors in response
    if isinstance(tool_response, str):
        if "error" in tool_response.lower() or "failed" in tool_response.lower():
            return f"{friendly_name} completed with errors"

    return f"{friendly_name} completed"


def summarize_tool_result(tool_name, tool_response, max_length=200):
    """Create a speakable summary of tool results"""
    if not tool_response:
        return None

    response_str = str(tool_response)

    # For Bash, extract key info
    if tool_name == "Bash":
        lines = response_str.strip().split('\n')
        if len(lines) <= 3:
            return response_str[:max_length]

        # Check for test results
        if "passed" in response_str.lower() or "failed" in response_str.lower():
            # Try to extract test summary
            for line in lines[-5:]:
                if "passed" in line.lower() or "failed" in line.lower():
                    return line[:max_length]

        # Return last few lines
        return " ".join(lines[-3:])[:max_length]

    # For other tools, return truncated response
    return response_str[:max_length]


def main():
    # Load configuration
    config = load_config()

    if not config.get("enabled", True):
        sys.exit(0)

    # Read hook input
    hook_input = read_hook_input()
    if not hook_input:
        sys.exit(0)

    tool_name = hook_input.get("tool_name", "")
    tool_input = hook_input.get("tool_input", {})
    tool_response = hook_input.get("tool_response", "")

    # Check if thinking/announcements are enabled
    speak_thinking = config.get("speak_thinking", False)
    speak_tool_results = config.get("speak_tool_results", False)

    if not speak_thinking and not speak_tool_results:
        sys.exit(0)

    # Filter tools if configured
    tool_filters = config.get("tool_filters", [])
    if tool_filters and tool_name not in tool_filters:
        sys.exit(0)

    # Generate and speak announcement (thinking process)
    if speak_thinking:
        announcement = generate_tool_announcement(tool_name, tool_input, tool_response, config)
        if announcement:
            speak_announcement(announcement, config)

    # Speak detailed results if enabled
    if speak_tool_results:
        result_summary = summarize_tool_result(tool_name, tool_response)
        if result_summary:
            speak(result_summary, config)

    sys.exit(0)


if __name__ == "__main__":
    main()
