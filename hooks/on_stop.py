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
        log_error("Hook disabled in config")
        sys.exit(0)

    if not config.get("speak_responses", True):
        log_error("speak_responses disabled")
        sys.exit(0)

    # Read hook input
    hook_input = read_hook_input()
    if not hook_input:
        log_error("No hook input received")
        sys.exit(0)

    # Get transcript path
    transcript_path = hook_input.get("transcript_path", "")
    log_error(f"Transcript path: {transcript_path}")

    # Check if stop hook is already active (avoid recursion)
    if hook_input.get("stop_hook_active", False):
        log_error("Stop hook already active, skipping")
        sys.exit(0)

    # Parse transcript for last response
    response_text = parse_transcript(transcript_path)

    if not response_text:
        log_error("No response text found in transcript")
        sys.exit(0)

    log_error(f"Response text length: {len(response_text)}")

    # Speak the full response (batching is handled by speak function)
    speak(response_text, config)

    sys.exit(0)


if __name__ == "__main__":
    main()
