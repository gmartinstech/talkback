#!/usr/bin/env python3
"""
TalkBack TTS Engine
Cross-platform: Windows native and WSL (plays through Windows audio)
Uses Kokoro TTS (WSL primary), Edge TTS (Windows primary), or SAPI (fallback)
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
    "tts_engine": "auto",  # auto, kokoro, edge, sapi
    "voice": "en-US-AriaNeural",  # Edge TTS voice
    "kokoro_voice": "af_bella",  # Kokoro voice (af_bella, am_adam, etc.)
    "rate": "+10%",
    "volume": "+0%",
    "max_speak_length": 500,
    "fallback_to_sapi": True,
    "log_file": "~/.claude/talkback.log"
}

# Kokoro voice options
KOKORO_VOICES = {
    # American Female
    "af_bella": "American Female - Bella",
    "af_sarah": "American Female - Sarah",
    "af_nicole": "American Female - Nicole",
    "af_sky": "American Female - Sky",
    # American Male
    "am_adam": "American Male - Adam",
    "am_michael": "American Male - Michael",
    # British Female
    "bf_emma": "British Female - Emma",
    "bf_isabella": "British Female - Isabella",
    # British Male
    "bm_george": "British Male - George",
    "bm_lewis": "British Male - Lewis",
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

    # Ensure text is a string
    if not isinstance(text, str):
        text = str(text)

    # Remove ANSI escape codes (colors, bold, etc from terminal)
    text = re.sub(r'\x1b\[[0-9;]*[a-zA-Z]', '', text)
    text = re.sub(r'\x1b\].*?\x07', '', text)  # OSC sequences
    text = re.sub(r'\x1b[PX^_].*?\x1b\\', '', text)  # Other escape sequences

    # Remove other control characters except newlines and tabs
    text = re.sub(r'[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]', '', text)

    # Normalize all line breaks (Windows \r\n, old Mac \r, Unix \n, Unicode line separators)
    text = text.replace('\r\n', '\n')
    text = text.replace('\r', '\n')
    text = text.replace('\u2028', '\n')  # Unicode line separator
    text = text.replace('\u2029', '\n')  # Unicode paragraph separator

    # Remove code blocks
    text = re.sub(r'```[\s\S]*?```', ' code block omitted ', text)
    text = re.sub(r'`[^`]+`', '', text)

    # Remove markdown links but keep text
    text = re.sub(r'\[([^\]]+)\]\([^)]+\)', r'\1', text)

    # Remove markdown formatting
    text = re.sub(r'\*\*([^*]+)\*\*', r'\1', text)  # bold
    text = re.sub(r'\*([^*]+)\*', r'\1', text)      # italic
    text = re.sub(r'_{1,2}([^_]+)_{1,2}', r'\1', text)  # underscore bold/italic
    text = re.sub(r'~~([^~]+)~~', r'\1', text)      # strikethrough
    text = re.sub(r'#{1,6}\s*', '', text)           # headers
    text = re.sub(r'^[-*+]\s+', '', text, flags=re.MULTILINE)  # list items
    text = re.sub(r'^>\s*', '', text, flags=re.MULTILINE)      # blockquotes
    text = re.sub(r'^\d+\.\s+', '', text, flags=re.MULTILINE)  # numbered lists

    # Remove file paths (they don't speak well)
    text = re.sub(r'[A-Za-z]:[/\\][^\s]+', 'file path', text)
    text = re.sub(r'/[^\s]+/[^\s]+', 'file path', text)

    # Remove URLs
    text = re.sub(r'https?://[^\s]+', 'link', text)

    # Convert line breaks to periods for natural pauses
    text = re.sub(r'\n+', '. ', text)

    # Clean up whitespace and punctuation
    text = re.sub(r'\s+', ' ', text)
    text = re.sub(r'\.+', '.', text)  # Multiple periods to single
    text = re.sub(r'\.\s*\.', '.', text)  # Period space period to single
    text = re.sub(r'^\.\s*', '', text)  # Remove leading period
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


def split_into_sentences(text):
    """Split text into sentences for faster TTS processing"""
    if not text:
        return []

    # Split on sentence endings
    import re
    # Split on . ! ? followed by space or end of string
    sentences = re.split(r'(?<=[.!?])\s+', text)

    # Filter empty sentences and strip whitespace
    sentences = [s.strip() for s in sentences if s.strip()]

    return sentences


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

    # Use a more reliable playback method that waits for audio to complete
    ps_command = f'''
    Add-Type -AssemblyName presentationCore
    $player = New-Object System.Windows.Media.MediaPlayer
    $player.Open("{win_path}")

    # Wait for media to be loaded
    while (-not $player.NaturalDuration.HasTimeSpan) {{
        Start-Sleep -Milliseconds 100
    }}

    $player.Play()

    # Wait until playback completes (no arbitrary timeout)
    while ($player.Position -lt $player.NaturalDuration.TimeSpan) {{
        Start-Sleep -Milliseconds 200
    }}

    $player.Close()
    '''
    return run_powershell(ps_command, timeout=600)


def speak_kokoro(text, voice="af_bella"):
    """Speak text using Kokoro TTS (high-quality local neural TTS)"""
    try:
        import kokoro
        import soundfile as sf
        import numpy as np

        # Initialize Kokoro pipeline
        pipeline = kokoro.KPipeline(lang_code='a')  # 'a' for American English

        # Generate audio
        generator = pipeline(text, voice=voice)

        # Collect all audio samples
        audio_samples = []
        sample_rate = 24000  # Kokoro default sample rate

        for samples, sample_rate, _ in generator:
            audio_samples.append(samples)

        if not audio_samples:
            return False

        # Concatenate all samples
        full_audio = np.concatenate(audio_samples)

        # Save to temp file
        if IS_WSL:
            temp_dir = "/mnt/c/temp"
            os.makedirs(temp_dir, exist_ok=True)
            tmp_path = os.path.join(temp_dir, f"talkback_{os.getpid()}.wav")
        else:
            tmp_path = tempfile.mktemp(suffix='.wav')

        sf.write(tmp_path, full_audio, sample_rate)

        # Play through Windows audio
        success = play_audio_file(tmp_path)

        # Clean up
        try:
            os.unlink(tmp_path)
        except:
            pass

        return success

    except ImportError as e:
        log_error(f"Kokoro not installed: {e}. Run: pip install kokoro soundfile")
        return False
    except Exception as e:
        log_error(f"Kokoro TTS error: {e}")
        return False


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


def is_kokoro_available():
    """Check if Kokoro TTS is installed and available"""
    try:
        import kokoro
        import soundfile
        return True
    except ImportError:
        return False


def speak_single_batch(text, config, engine):
    """Speak a single batch of text using the specified engine"""
    success = False

    if engine == "kokoro":
        kokoro_voice = config.get("kokoro_voice", "af_bella")
        success = speak_kokoro(text, kokoro_voice)

    if not success and engine in ("edge", "auto") or (engine == "kokoro" and not success):
        voice = config.get("voice", "en-US-AriaNeural")
        rate = config.get("rate", "+10%")
        volume = config.get("volume", "+0%")
        try:
            success = asyncio.run(speak_edge_tts(text, voice, rate, volume))
        except Exception as e:
            log_error(f"Edge TTS failed: {e}")

    # Fallback to SAPI (Windows/WSL)
    if not success and config.get("fallback_to_sapi", True) and (IS_WINDOWS or IS_WSL):
        sapi_rate = 2  # slightly faster than default
        success = speak_sapi(text, sapi_rate)

    # Last resort: espeak on Linux
    if not success and not IS_WINDOWS:
        success = speak_espeak(text)

    return success


async def generate_full_audio_streaming(text, voice, rate, volume, output_path):
    """Generate audio file using Edge TTS streaming - handles unlimited text length"""
    try:
        import edge_tts

        communicate = edge_tts.Communicate(text, voice, rate=rate, volume=volume)

        # Use streaming to write audio data as it's received
        with open(output_path, "wb") as audio_file:
            async for chunk in communicate.stream():
                if chunk["type"] == "audio":
                    audio_file.write(chunk["data"])

        return True
    except Exception as e:
        log_error(f"Edge TTS streaming failed: {e}")
        return False


async def stream_to_mpv(text, voice, rate, volume):
    """Stream audio directly to mpv player for instant playback"""
    try:
        import edge_tts

        communicate = edge_tts.Communicate(text, voice, rate=rate, volume=volume)

        # Start mpv process reading from stdin
        mpv_process = subprocess.Popen(
            ['mpv', '--no-cache', '--no-terminal', '--no-video', '--', 'fd://0'],
            stdin=subprocess.PIPE,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL
        )

        # Stream audio chunks directly to mpv
        async for chunk in communicate.stream():
            if chunk["type"] == "audio":
                try:
                    mpv_process.stdin.write(chunk["data"])
                    mpv_process.stdin.flush()
                except BrokenPipeError:
                    break

        # Close stdin and wait for mpv to finish
        mpv_process.stdin.close()
        mpv_process.wait()

        return True
    except FileNotFoundError:
        log_error("mpv not found - falling back to file-based playback")
        return False
    except Exception as e:
        log_error(f"mpv streaming failed: {e}")
        return False


def is_mpv_available():
    """Check if mpv player is installed"""
    try:
        result = subprocess.run(
            ['mpv', '--version'],
            capture_output=True,
            timeout=5
        )
        return result.returncode == 0
    except:
        return False


def speak(text, config=None):
    """Main speak function - cross-platform with streaming TTS for smooth playback"""
    if config is None:
        config = load_config()

    if not config.get("enabled", True):
        return

    # Clean and prepare text
    text = clean_text_for_speech(text)
    if not text:
        return

    # Determine TTS engine
    engine = config.get("tts_engine", "auto")

    # Auto-select engine based on environment
    if engine == "auto":
        if IS_WSL and is_kokoro_available():
            engine = "kokoro"
        elif IS_WINDOWS or IS_WSL:
            engine = "edge"
        else:
            engine = "espeak"

    # For Edge TTS, try streaming to mpv first (instant playback), fallback to file-based
    if engine == "edge":
        voice = config.get("voice", "en-US-AriaNeural")
        rate = config.get("rate", "+10%")
        volume = config.get("volume", "+0%")

        success = False

        # Try mpv streaming first (instant playback - audio starts immediately)
        if config.get("use_mpv_streaming", True) and is_mpv_available():
            try:
                success = asyncio.run(stream_to_mpv(text, voice, rate, volume))
                if success:
                    return
            except Exception as e:
                log_error(f"mpv streaming failed: {e}")

        # Fallback to file-based playback (wait for full generation, then play)
        if IS_WSL:
            temp_dir = "/mnt/c/temp"
            os.makedirs(temp_dir, exist_ok=True)
            audio_path = os.path.join(temp_dir, f"talkback_{os.getpid()}.mp3")
        else:
            audio_path = os.path.join(tempfile.gettempdir(), f"talkback_{os.getpid()}.mp3")

        try:
            # Generate complete audio using streaming (handles any text length)
            success = asyncio.run(generate_full_audio_streaming(text, voice, rate, volume, audio_path))

            if success and os.path.exists(audio_path):
                # Play the complete audio file - smooth continuous playback
                play_audio_file(audio_path)
                # Clean up
                try:
                    os.unlink(audio_path)
                except:
                    pass
            else:
                # Fallback to SAPI
                if config.get("fallback_to_sapi", True):
                    speak_sapi(text, rate=2)
        except Exception as e:
            log_error(f"Edge TTS failed: {e}")
            if config.get("fallback_to_sapi", True):
                speak_sapi(text, rate=2)
    else:
        # For other engines, speak the whole text
        speak_single_batch(text, config, engine)


def speak_announcement(message, config=None):
    """Speak a short announcement (tool completion, etc.)"""
    if config is None:
        config = load_config()

    if not config.get("enabled", True):
        return

    # Use the same speak function for consistency
    speak(message, config)


def get_environment_info():
    """Return information about the runtime environment"""
    return {
        "is_windows": IS_WINDOWS,
        "is_wsl": IS_WSL,
        "platform": sys.platform,
        "python": sys.executable,
        "kokoro_available": is_kokoro_available()
    }


def list_kokoro_voices():
    """Print available Kokoro voices"""
    print("\nAvailable Kokoro Voices:")
    print("-" * 40)
    for voice_id, description in KOKORO_VOICES.items():
        print(f"  {voice_id:15} - {description}")
    print()


if __name__ == "__main__":
    # Test the TTS engine
    print(f"Environment: {'WSL' if IS_WSL else 'Windows' if IS_WINDOWS else 'Linux'}")
    print(f"Config path: {CONFIG_PATH}")
    print(f"Kokoro available: {is_kokoro_available()}")

    if len(sys.argv) > 1:
        if sys.argv[1] == "--voices":
            list_kokoro_voices()
            sys.exit(0)
        elif sys.argv[1] in ("--file", "-f"):
            # Read text from file
            if len(sys.argv) < 3:
                print("Error: --file requires a file path argument")
                sys.exit(1)
            file_path = sys.argv[2]
            try:
                with open(file_path, 'r', encoding='utf-8') as f:
                    text = f.read()
                print(f"Reading from file: {file_path}")
            except FileNotFoundError:
                print(f"Error: File not found: {file_path}")
                sys.exit(1)
            except Exception as e:
                print(f"Error reading file: {e}")
                sys.exit(1)
        elif sys.argv[1] == "--help":
            print("Usage: speak.py [OPTIONS] [TEXT]")
            print("\nOptions:")
            print("  --file, -f FILE   Read text from a file")
            print("  --voices          List available Kokoro voices")
            print("  --help            Show this help message")
            print("\nExamples:")
            print("  speak.py Hello world")
            print("  speak.py --file document.txt")
            print("  speak.py -f notes.md")
            sys.exit(0)
        else:
            text = " ".join(sys.argv[1:])
    else:
        text = "Hello! TalkBack TTS engine is working correctly."

    print(f"Speaking: {text[:50]}...")
    speak(text)
    print("Done!")
