package net.martinstech.talkback.service;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Text-to-speech service that bridges to the existing Python {@code speak.py} script.
 * On Windows it uses the Python Launcher ({@code py.exe}); elsewhere it falls back to {@code python3}.
 * This is a temporary bridge until a native Java TTS engine is adopted.
 */
public class TtsService {
    private final Path repoRoot;
    private final int maxLength;

    /**
     * Constructs a new {@code TtsService}.
     *
     * @param repoRoot  the path to the talkback repository root (where {@code speak.py} lives)
     * @param maxLength the maximum number of characters to speak
     */
    public TtsService(Path repoRoot, int maxLength) {
        this.repoRoot = Objects.requireNonNull(repoRoot);
        this.maxLength = maxLength;
    }

    /**
     * Speaks the given text using the Python TTS bridge.
     *
     * @param text the text to speak; will be truncated to {@code maxLength} characters
     */
    public void speak(String text) {
        String trimmed = text.length() > maxLength ? text.substring(0, maxLength) : text;

        // On Windows prefer the Python Launcher (py.exe) over python.exe,
        // because python.exe is often a Microsoft Store shim that fails silently.
        String python;
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            python = "py.exe";
        } else {
            python = "python3";
        }

        Path speakScript = repoRoot.resolve("speak.py");
        var pb = new ProcessBuilder(python, speakScript.toString(), trimmed);
        pb.inheritIO();
        Process p = null;
        try {
            p = pb.start();
            // Cap TTS duration so a missing/broken engine doesn't hang the thread forever
            // Qwen3-TTS may need several minutes on first run to download
            // the ~0.6 B model from HuggingFace. We give it a generous ceiling.
            boolean finished = p.waitFor(300, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                System.err.println("TTS timed out after 5 min");
                p.destroyForcibly();
            }
        } catch (Exception e) {
            System.err.println("TTS failed: " + e.getMessage());
        } finally {
            if (p != null) {
                p.destroyForcibly();
            }
        }
    }
}
