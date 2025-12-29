#!/usr/bin/env python3
"""
TalkBack Stop Hook
Speaks Claude's final response when Claude finishes responding.

Hook Event: Stop
Input: JSON via stdin with transcript_path
Output: Exit 0 (success) or non-zero (error)
"""

import json
import sys
import os
from pathlib import Path

# Add parent directory to path for imports
sys.path.insert(0, str(Path(__file__).parent.parent))

from speak import speak, load_config, log_error, clean_text_for_speech


def read_hook_input():
    """Read JSON input from stdin"""
    try:
        return json.load(sys.stdin)
    except json.JSONDecodeError as e:
        log_error(f"Failed to parse hook input: {e}")
        return None


def parse_transcript(transcript_path):
    """
    Parse the transcript JSONL file and extract the last assistant response.
    Returns the text content of Claude's final response.
    """
    if not transcript_path or not os.path.exists(transcript_path):
        return None

    last_assistant_content = None

    try:
        with open(transcript_path, 'r', encoding='utf-8') as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue

                try:
                    event = json.loads(line)

                    # Look for assistant messages
                    # The format may vary, check common patterns
                    if event.get("type") == "assistant":
                        message = event.get("message", {})
                        content = message.get("content", [])

                        # Extract text from content blocks
                        text_parts = []
                        for block in content:
                            if isinstance(block, dict):
                                if block.get("type") == "text":
                                    text_parts.append(block.get("text", ""))
                            elif isinstance(block, str):
                                text_parts.append(block)

                        if text_parts:
                            last_assistant_content = "\n".join(text_parts)

                    # Alternative format check
                    elif event.get("role") == "assistant":
                        content = event.get("content", "")
                        if isinstance(content, str) and content:
                            last_assistant_content = content
                        elif isinstance(content, list):
                            text_parts = []
                            for block in content:
                                if isinstance(block, dict) and block.get("type") == "text":
                                    text_parts.append(block.get("text", ""))
                            if text_parts:
                                last_assistant_content = "\n".join(text_parts)

                except json.JSONDecodeError:
                    continue

    except Exception as e:
        log_error(f"Failed to parse transcript: {e}")
        return None

    return last_assistant_content


def extract_summary(text, max_sentences=3):
    """Extract first few sentences as a summary"""
    if not text:
        return ""

    # Clean the text first
    text = clean_text_for_speech(text)

    # Split into sentences
    import re
    sentences = re.split(r'(?<=[.!?])\s+', text)

    # Take first N sentences
    summary_sentences = sentences[:max_sentences]
    summary = " ".join(summary_sentences)

    return summary


def main():
    # Load configuration
    config = load_config()

    if not config.get("enabled", True):
        sys.exit(0)

    if not config.get("speak_responses", True):
        sys.exit(0)

    # Read hook input
    hook_input = read_hook_input()
    if not hook_input:
        sys.exit(0)

    # Get transcript path
    transcript_path = hook_input.get("transcript_path", "")

    # Check if stop hook is already active (avoid recursion)
    if hook_input.get("stop_hook_active", False):
        sys.exit(0)

    # Parse transcript for last response
    response_text = parse_transcript(transcript_path)

    if not response_text:
        # No response to speak
        sys.exit(0)

    # Determine if we should summarize
    max_length = config.get("max_speak_length", 500)
    if len(response_text) > max_length and config.get("summarize_long_responses", True):
        # Extract a summary (first few sentences)
        text_to_speak = extract_summary(response_text, max_sentences=3)
        if text_to_speak:
            text_to_speak = "Summary: " + text_to_speak
    else:
        text_to_speak = response_text

    # Speak the response
    if text_to_speak:
        speak(text_to_speak, config)

    sys.exit(0)


if __name__ == "__main__":
    main()
