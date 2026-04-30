package net.martinstech.talkback.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Immutable application configuration loaded from JSON.
 * Compatible with the legacy {@code config.json} and the new frontend settings block.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class AppConfig {
    private boolean enabled = true;
    private String voice = "en-US-AriaNeural";
    private boolean speakResponses = true;
    private boolean speakThinking = true;
    private int maxSpeakLength = 500;
    private String ollamaUrl = "http://localhost:11434";
    private String ollamaModel = "gemma4:4b";
    private String ttsEngine = "edge";
    private boolean webSearchEnabled = true;
    private String language = "pt-BR";
    private FrontendSettings frontend = new FrontendSettings();

    /** Default no-arg constructor for Jackson deserialization. */
    public AppConfig() {}

    public AppConfig(boolean enabled, String voice, boolean speakResponses,
                     boolean speakThinking, int maxSpeakLength, String ollamaUrl,
                     String ollamaModel, String ttsEngine, boolean webSearchEnabled,
                     String language, FrontendSettings frontend) {
        this.enabled = enabled;
        this.voice = Objects.requireNonNull(voice);
        this.speakResponses = speakResponses;
        this.speakThinking = speakThinking;
        this.maxSpeakLength = maxSpeakLength;
        this.ollamaUrl = Objects.requireNonNull(ollamaUrl);
        this.ollamaModel = Objects.requireNonNull(ollamaModel);
        this.ttsEngine = Objects.requireNonNull(ttsEngine);
        this.webSearchEnabled = webSearchEnabled;
        this.language = Objects.requireNonNull(language);
        this.frontend = Objects.requireNonNull(frontend);
    }

    public boolean enabled() { return enabled; }
    public String voice() { return voice; }
    public boolean speakResponses() { return speakResponses; }
    public boolean speakThinking() { return speakThinking; }
    public int maxSpeakLength() { return maxSpeakLength; }
    public String ollamaUrl() { return ollamaUrl; }
    public String ollamaModel() { return ollamaModel; }
    public String ttsEngine() { return ttsEngine; }
    public boolean webSearchEnabled() { return webSearchEnabled; }
    public String language() { return language; }
    public FrontendSettings frontend() { return frontend; }

    public void setOllamaUrl(String ollamaUrl) { this.ollamaUrl = Objects.requireNonNull(ollamaUrl); }
    public void setOllamaModel(String ollamaModel) { this.ollamaModel = Objects.requireNonNull(ollamaModel); }
    public void setTtsEngine(String ttsEngine) { this.ttsEngine = Objects.requireNonNull(ttsEngine); }
    public void setMaxSpeakLength(int maxSpeakLength) { this.maxSpeakLength = Math.max(1, maxSpeakLength); }
    public void setSpeakResponses(boolean speakResponses) { this.speakResponses = speakResponses; }
    public void setSpeakThinking(boolean speakThinking) { this.speakThinking = speakThinking; }
    public void setWebSearchEnabled(boolean webSearchEnabled) { this.webSearchEnabled = webSearchEnabled; }
    public void setLanguage(String language) { this.language = Objects.requireNonNull(language); }

    /**
     * Loads configuration from the given JSON file path.
     *
     * @param path the configuration file path
     * @return the loaded configuration, or defaults if the file is missing or invalid
     */
    public static AppConfig load(Path path) {
        var mapper = new ObjectMapper();
        if (Files.exists(path)) {
            try {
                return createMapper().readValue(path.toFile(), AppConfig.class);
            } catch (Exception e) {
                System.err.println("Failed to load config from " + path + ": " + e.getMessage());
            }
        }
        return new AppConfig();
    }

    /**
     * Persists this configuration to the given JSON file path.
     *
     * @param path the target file path
     * @throws Exception if serialization or I/O fails
     */
    public void save(Path path) throws Exception {
        createMapper().writerWithDefaultPrettyPrinter().writeValue(path.toFile(), this);
    }

    private static ObjectMapper createMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setVisibility(com.fasterxml.jackson.annotation.PropertyAccessor.FIELD,
                             com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY);
        return mapper;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FrontendSettings {
        private String hotkey = "Ctrl+Shift+T";
        private int windowWidth = 380;
        private int windowHeight = 600;

        public FrontendSettings() {}

        public String hotkey() { return hotkey; }
        public int windowWidth() { return windowWidth; }
        public int windowHeight() { return windowHeight; }

        public void setHotkey(String hotkey) { this.hotkey = hotkey; }
        public void setWindowWidth(int windowWidth) { this.windowWidth = windowWidth; }
        public void setWindowHeight(int windowHeight) { this.windowHeight = windowHeight; }
    }
}
