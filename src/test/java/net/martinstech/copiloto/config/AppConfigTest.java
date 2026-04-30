package net.martinstech.copiloto.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {

    @Test
    void shouldLoadDefaultsWhenFileMissing(@TempDir Path temp) {
        var config = AppConfig.load(temp.resolve("missing.json"));
        assertTrue(config.enabled());
        assertEquals("http://localhost:11434", config.ollamaUrl());
        assertEquals("gemma4:4b", config.ollamaModel());
        assertEquals("edge", config.ttsEngine());
    }

    @Test
    void shouldRoundTripConfig(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("config.json");
        var original = AppConfig.load(file);
        original.setOllamaModel("llama3:latest");
        original.setTtsEngine("kokoro");
        original.save(file);

        var loaded = AppConfig.load(file);
        assertEquals("llama3:latest", loaded.ollamaModel());
        assertEquals("kokoro", loaded.ttsEngine());
    }
}
