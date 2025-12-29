#!/usr/bin/env python3
"""
TalkBack TTS Engine
Cross-platform: Windows native and WSL (plays through Windows audio)
Uses Edge TTS (primary) or Windows SAPI (fallback)
"""

import asyncio
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

# Detect environment
IS_WSL = 'microsoft' in os.uname().release.lower() if hasattr(os, 'uname') else False
IS_WINDOWS = sys.platform == 'win32'

# Configuration
CONFIG_PATH = Path(__file__).parent / "config.json"
DEFAULT_CONFIG = {
    "enabled": True,
    "voice": "en-US-AriaNeural",
    "rate": "+10%",
    "volume": "+0%",
    "max_speak_length": 500,
    "fallback_to_sapi": True,
    "log_file": "~/.claude/talkback.log"
}


def load_config():
    """Load configuration from config.json"""
    try:
        if CONFIG_PATH.exists():
            with open(CONFIG_PATH, 'r') as f:
                config = json.load(f)
                return {**DEFAULT_CONFIG, **config}
    except Exception as e:
        log_error(f"Failed to load config: {e}")
    return DEFAULT_CONFIG


def log_error(message):
    """Log errors to file"""
    config = DEFAULT_CONFIG
    log_path = Path(os.path.expanduser(config.get("log_file", "~/.claude/talkback.log")))
    try:
        log_path.parent.mkdir(parents=True, exist_ok=True)
        with open(log_path, 'a') as f:
            f.write(f"{message}\n")
    except:
        pass


def clean_text_for_speech(text):
    """Clean markdown and code from text for better speech output"""
    if not text:
        return ""

    # Remove code blocks
    text = re.sub(r'```[\s\S]*?```', ' code block omitted ', text)
    text = re.sub(r'`[^`]+`', '', text)

    # Remove markdown links but keep text
    text = re.sub(r'\[([^\]]+)\]\([^)]+\)', r'\1', text)

    # Remove markdown formatting
    text = re.sub(r'\*\*([^*]+)\*\*', r'\1', text)  # bold
    text = re.sub(r'\*([^*]+)\*', r'\1', text)      # italic
    text = re.sub(r'#{1,6}\s*', '', text)           # headers
    text = re.sub(r'[-*]\s+', '', text)             # list items

    # Remove file paths (they don't speak well)
    text = re.sub(r'[A-Za-z]:[/\\][^\s]+', 'file path', text)
    text = re.sub(r'/[^\s]+/[^\s]+', 'file path', text)

    # Clean up whitespace
    text = re.sub(r'\n+', '. ', text)
    text = re.sub(r'\s+', ' ', text)
    text = text.strip()

    return text


def truncate_text(text, max_length=500):
    """Truncate text to max length, ending at sentence boundary"""
    if len(text) <= max_length:
        return text

    # Try to end at a sentence
    truncated = text[:max_length]
    last_period = truncated.rfind('.')
    last_question = truncated.rfind('?')
    last_exclaim = truncated.rfind('!')

    cut_point = max(last_period, last_question, last_exclaim)
    if cut_point > max_length // 2:
        return truncated[:cut_point + 1]

    return truncated + "..."


def wsl_to_windows_path(linux_path):
    """Convert WSL path to Windows path"""
    try:
        result = subprocess.run(
            ['wslpath', '-w', linux_path],
            capture_output=True, text=True, timeout=5
        )
        if result.returncode == 0:
            return result.stdout.strip()
    except:
        pass
    return linux_path


def run_powershell(command, timeout=60):
    """Run PowerShell command - works from both Windows and WSL"""
    try:
        if IS_WSL:
            # Use powershell.exe from WSL
            result = subprocess.run(
                ['powershell.exe', '-NoProfile', '-Command', command],
                capture_output=True, timeout=timeout
            )
        else:
            result = subprocess.run(
                ['powershell', '-NoProfile', '-Command', command],
                capture_output=True, timeout=timeout
            )
        return result.returncode == 0
    except Exception as e:
        log_error(f"PowerShell error: {e}")
        return False


def play_audio_file(file_path):
    """Play audio file through Windows audio system"""
    # Convert path if in WSL
    if IS_WSL:
        win_path = wsl_to_windows_path(file_path)
    else:
        win_path = file_path

    # Escape backslashes for PowerShell
    win_path = win_path.replace('\\', '\\\\')

    ps_command = f'''
    Add-Type -AssemblyName presentationCore
    $player = New-Object System.Windows.Media.MediaPlayer
    $player.Open("{win_path}")
    Start-Sleep -Milliseconds 500
    $player.Play()
    $timeout = 0
    while ($player.Position -lt $player.NaturalDuration.TimeSpan -and $timeout -lt 600) {{
        Start-Sleep -Milliseconds 100
        $timeout++
    }}
    $player.Close()
    '''
    return run_powershell(ps_command, timeout=120)


async def speak_edge_tts(text, voice="en-US-AriaNeural", rate="+10%", volume="+0%"):
    """Speak text using Edge TTS (requires edge-tts package)"""
    try:
        import edge_tts

        communicate = edge_tts.Communicate(text, voice, rate=rate, volume=volume)

        # Create temp file for audio
        # Use /tmp on WSL or Windows temp dir
        if IS_WSL:
            # Use /mnt/c/temp for WSL so Windows can access it
            temp_dir = "/mnt/c/temp"
            os.makedirs(temp_dir, exist_ok=True)
            tmp_path = os.path.join(temp_dir, f"talkback_{os.getpid()}.mp3")
        else:
            with tempfile.NamedTemporaryFile(suffix='.mp3', delete=False) as tmp:
                tmp_path = tmp.name

        await communicate.save(tmp_path)

        # Play through Windows audio
        success = play_audio_file(tmp_path)

        # Clean up
        try:
            os.unlink(tmp_path)
        except:
            pass

        return success

    except ImportError:
        log_error("edge-tts not installed. Run: pip install edge-tts")
        return False
    except Exception as e:
        log_error(f"Edge TTS error: {e}")
        return False


def speak_sapi(text, rate=0):
    """Speak text using Windows SAPI (works from both Windows and WSL)"""
    try:
        # Escape quotes and special characters
        escaped_text = text.replace('"', "'").replace('`', "'")
        # Limit length for SAPI
        if len(escaped_text) > 1000:
            escaped_text = escaped_text[:1000] + "..."

        ps_command = f'''
        Add-Type -AssemblyName System.Speech
        $synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
        $synth.Rate = {rate}
        $synth.Speak("{escaped_text}")
        '''
        return run_powershell(ps_command, timeout=120)

    except Exception as e:
        log_error(f"SAPI error: {e}")
        return False


def speak_espeak(text, rate=175):
    """Speak using espeak on Linux (for non-WSL Linux)"""
    try:
        result = subprocess.run(
            ['espeak', '-s', str(rate), text],
            capture_output=True, timeout=60
        )
        return result.returncode == 0
    except:
        return False


def speak(text, config=None):
    """Main speak function - cross-platform"""
    if config is None:
        config = load_config()

    if not config.get("enabled", True):
        return

    # Clean and prepare text
    text = clean_text_for_speech(text)
    if not text:
        return

    max_length = config.get("max_speak_length", 500)
    text = truncate_text(text, max_length)

    # Try Edge TTS first
    voice = config.get("voice", "en-US-AriaNeural")
    rate = config.get("rate", "+10%")
    volume = config.get("volume", "+0%")

    try:
        success = asyncio.run(speak_edge_tts(text, voice, rate, volume))
        if success:
            return
    except Exception as e:
        log_error(f"Edge TTS failed: {e}")

    # Fallback to SAPI (Windows/WSL)
    if config.get("fallback_to_sapi", True) and (IS_WINDOWS or IS_WSL):
        sapi_rate = 2  # slightly faster than default
        if speak_sapi(text, sapi_rate):
            return

    # Last resort: espeak on Linux
    if not IS_WINDOWS:
        speak_espeak(text)


def speak_announcement(message, config=None):
    """Speak a short announcement (tool completion, etc.)"""
    if config is None:
        config = load_config()

    if not config.get("enabled", True):
        return

    # Use SAPI for quick announcements (faster startup than Edge TTS)
    if IS_WINDOWS or IS_WSL:
        speak_sapi(message, rate=3)
    else:
        speak_espeak(message, rate=200)


def get_environment_info():
    """Return information about the runtime environment"""
    return {
        "is_windows": IS_WINDOWS,
        "is_wsl": IS_WSL,
        "platform": sys.platform,
        "python": sys.executable
    }


if __name__ == "__main__":
    # Test the TTS engine
    print(f"Environment: {'WSL' if IS_WSL else 'Windows' if IS_WINDOWS else 'Linux'}")
    print(f"Config path: {CONFIG_PATH}")

    if len(sys.argv) > 1:
        text = " ".join(sys.argv[1:])
    else:
        text = "Hello! TalkBack TTS engine is working correctly."

    print(f"Speaking: {text[:50]}...")
    speak(text)
    print("Done!")
