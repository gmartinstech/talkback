package net.martinstech.talkback;

import net.martinstech.talkback.async.ReviewScope;
import net.martinstech.talkback.config.AppConfig;
import net.martinstech.talkback.config.ConfigScope;
import net.martinstech.talkback.service.GitHubService;
import net.martinstech.talkback.service.OllamaService;
import net.martinstech.talkback.service.TtsService;
import net.martinstech.talkback.ui.ChatStage;
import net.martinstech.talkback.ui.CodeViewerStage;
import net.martinstech.talkback.ui.HotkeyManager;
import net.martinstech.talkback.ui.SettingsDialog;
import net.martinstech.talkback.ui.SystemTrayManager;
import net.martinstech.talkback.ui.WebBridge;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.web.WebEngine;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main entry point for the TalkBack desktop application.
 *
 * <p>Bootstraps the JavaFX runtime, loads configuration, wires services
 * and installs the system tray icon, global hotkey and floating chat window.
 * Uses virtual threads for all background I/O so the UI stays responsive.
 */
public class TalkBackApp extends Application {
    private static final Path CONFIG_PATH = Path.of("config.json");
    private static final Path REPOS_DIR = Path.of(System.getProperty("user.home"), ".talkback", "repos");

    private AppConfig config;
    private OllamaService ollama;
    private GitHubService github;
    private TtsService tts;
    private ChatStage chat;
    private CodeViewerStage codeViewer;
    private final List<ChatMessage> history = new ArrayList<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public void start(Stage ignored) {
        Platform.setImplicitExit(false);

        config = AppConfig.load(CONFIG_PATH);
        ollama = new OllamaService(config.ollamaUrl());
        github = new GitHubService(REPOS_DIR);
        tts = new TtsService(Path.of(".").toAbsolutePath().getParent(), config.maxSpeakLength());

        chat = new ChatStage();
        codeViewer = new CodeViewerStage();

        var bridge = new WebBridge(
            this::onMessage,
            this::onOpenFile,
            this::openSettings,
            this::onModelChanged
        );
        bridge.install(chat.getWebView().getEngine());

        var tray = new SystemTrayManager(() -> Platform.runLater(chat::toggle));
        tray.install();

        try {
            var hotkey = new HotkeyManager(() -> Platform.runLater(chat::toggle));
            hotkey.register();
        } catch (Exception e) {
            System.err.println("Global hotkey unavailable: " + e.getMessage());
        }

        executor.submit(this::loadModels);
    }

    private void loadModels() {
        try {
            List<String> models = ollama.listModels();
            Platform.runLater(() -> injectScript("window.setModels(" + toJsArray(models) + ")"));
        } catch (Exception e) {
            System.err.println("Could not load models: " + e.getMessage());
        }
    }

    private void onMessage(String text) {
        if (text == null || text.isBlank()) return;

        injectScript("window.appendMessage('user', `" + escapeJs(text) + "`);");
        injectScript("window.setTyping(true);");

        var prInfo = github.parsePrUrl(text);
        if (prInfo.isPresent()) {
            executor.submit(() -> handlePrReview(text));
        } else {
            executor.submit(() -> handleChat(text));
        }
    }

    private void handlePrReview(String url) {
        try {
            var pr = ConfigScope.runWhere(config,
                () -> ReviewScope.runReview(url, github));

            String systemPrompt =
                "You are a senior code reviewer. Walk through this PR change by change, " +
                "explaining what each file does and why it matters. " +
                "Reference files by name so they can be clicked.";

            String prompt = systemPrompt + "\n\nPR: " + url
                + "\n\nChanged files: " + String.join(", ", pr.files())
                + "\n\nDiff:\n" + pr.diff();

            streamAssistant(prompt);
        } catch (Exception e) {
            Platform.runLater(() -> {
                injectScript("window.setTyping(false);");
                injectScript("window.appendMessage('assistant', `**Error:** " + escapeJs(e.getMessage()) + "`);");
            });
        }
    }

    private void handleChat(String text) {
        history.add(new UserMessage(text));
        streamAssistant(text);
    }

    private void streamAssistant(String prompt) {
        List<ChatMessage> messages = new ArrayList<>(history);
        if (history.isEmpty() || !(history.get(history.size() - 1) instanceof UserMessage)) {
            messages.add(new UserMessage(prompt));
        }
        // Ensure we have at least the current prompt in messages
        if (messages.stream().noneMatch(m -> m instanceof UserMessage && m.text().equals(prompt))) {
            messages.add(new UserMessage(prompt));
        }

        var sb = new StringBuilder();

        ollama.chatStream(config.ollamaModel(), messages,
            token -> Platform.runLater(() -> {
                sb.append(token);
                injectScript("window.appendAssistantChunk(`" + escapeJs(token) + "`);");
            }),
            error -> Platform.runLater(() -> {
                injectScript("window.setTyping(false);");
                injectScript("window.appendMessage('assistant', `**Error:** " + escapeJs(error.getMessage()) + "`);");
            }),
            () -> Platform.runLater(() -> {
                injectScript("window.setTyping(false);");
                injectScript("window.finalizeAssistantMessage();");
                history.add(new AiMessage(sb.toString()));
                if (history.size() > 20) {
                    history.removeFirst();
                }
            })
        );
    }

    private void onOpenFile(String filePath) {
        Platform.runLater(() -> {
            try {
                var prInfo = github.parsePrUrl(filePath);
                if (prInfo.isPresent()) {
                    var diff = github.fetchPrDiff(prInfo.get().owner(), prInfo.get().repo(), prInfo.get().number());
                    codeViewer.showDiff(diff);
                } else {
                    codeViewer.showDiff("File: " + filePath + "\nNo diff available.");
                }
            } catch (Exception e) {
                codeViewer.showDiff("Error loading diff: " + e.getMessage());
            }
        });
    }

    private void openSettings() {
        Platform.runLater(() -> {
            List<String> models;
            try {
                models = ollama.listModels();
            } catch (Exception e) {
                models = List.of(config.ollamaModel());
            }
            var dialog = new SettingsDialog(config, models);
            var result = dialog.showAndWait();
            result.ifPresent(newConfig -> {
                config = newConfig;
                try {
                    config.save(CONFIG_PATH);
                } catch (Exception e) {
                    System.err.println("Failed to save config: " + e.getMessage());
                }
                ollama = new OllamaService(config.ollamaUrl());
            });
        });
    }

    private void onModelChanged(String model) {
        config.setOllamaModel(model);
    }

    private void injectScript(String script) {
        WebEngine engine = chat.getWebView().getEngine();
        if (engine.getLoadWorker().getState() == javafx.concurrent.Worker.State.SUCCEEDED) {
            engine.executeScript(script);
        }
    }

    private static String escapeJs(String text) {
        return text.replace("\\", "\\\\")
                   .replace("`", "\\`")
                   .replace("$", "\\$")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r");
    }

    private static String toJsArray(List<String> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            sb.append("\"").append(list.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
            if (i < list.size() - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
