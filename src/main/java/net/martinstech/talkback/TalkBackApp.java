package net.martinstech.talkback;

import net.martinstech.talkback.async.ReviewScope;
import net.martinstech.talkback.config.AppConfig;
import net.martinstech.talkback.config.ConfigScope;
import net.martinstech.talkback.service.GitHubService;
import net.martinstech.talkback.service.OllamaService;
import net.martinstech.talkback.service.SkillsService;
import net.martinstech.talkback.service.TtsService;
import net.martinstech.talkback.service.WebSearchService;
import net.martinstech.talkback.ui.ChatStage;
import net.martinstech.talkback.ui.CodeViewerStage;
import net.martinstech.talkback.ui.HotkeyManager;
import net.martinstech.talkback.ui.SettingsDialog;
import net.martinstech.talkback.ui.SystemTrayManager;
import net.martinstech.talkback.ui.WebBridge;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import com.fasterxml.jackson.databind.ObjectMapper;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.web.WebEngine;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private WebSearchService webSearch;
    private SkillsService skills;
    private ChatStage chat;
    private CodeViewerStage codeViewer;
    private final List<String> pendingScripts = new ArrayList<>();
    private volatile boolean pageLoaded = false;
    private final List<ChatMessage> history = new ArrayList<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ObjectMapper jsonMapper = new ObjectMapper();

    @Override
    public void start(Stage ignored) {
        Platform.setImplicitExit(false);

        config = AppConfig.load(CONFIG_PATH);
        ollama = new OllamaService(config.ollamaUrl());
        github = new GitHubService(REPOS_DIR);
        tts = new TtsService(Path.of(".").toAbsolutePath().getParent(), config.maxSpeakLength());
        webSearch = new WebSearchService();
        skills = new SkillsService();

        chat = new ChatStage();
        codeViewer = new CodeViewerStage();

        var engine = chat.getWebView().getEngine();
        engine.getLoadWorker().stateProperty().addListener((obs, old, newState) -> {
            if (newState == javafx.concurrent.Worker.State.SUCCEEDED) {
                pageLoaded = true;
                flushPendingScripts();
            }
        });

        var bridge = new WebBridge(
            this::onMessage,
            this::onSpeakText,
            this::onOpenFile,
            this::openSettings,
            this::onModelChanged,
            () -> chat.getStage().setIconified(true),
            () -> Platform.exit(),
            this::onSkillsSearch,
            this::onSkillsList,
            this::onSkillsAdd,
            this::onSkillsRemove,
            this::onSkillsUpdate,
            this::onSkillsDetails
        );
        bridge.install(engine);

        var tray = new SystemTrayManager(() -> Platform.runLater(chat::toggle));
        tray.install();

        try {
            var hotkey = new HotkeyManager(() -> Platform.runLater(chat::toggle));
            hotkey.register();
        } catch (Exception e) {
            System.err.println("Global hotkey unavailable: " + e.getMessage());
        }

        chat.show();
    }

    private void onMessage(String text) {
        if (text == null || text.isBlank()) return;

        injectScript("window.appendMessage('user', `" + escapeJs(text) + "`);");
        injectScript("window.setTyping(true);");

        if (handleHelpCommand(text)) {
            return;
        }

        if (handleSkillsCommand(text)) {
            return;
        }

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
                injectScript("window.appendMessage('assistant', `**Erro:** " + escapeJs(e.getMessage()) + "`);");
            });
        }
    }

    private void handleChat(String text) {
        history.add(new UserMessage(text));
        if (config.webSearchEnabled()) {
            streamAssistantWithTools(text);
        } else {
            streamAssistant(text);
        }
    }

    private void streamAssistant(String prompt) {
        List<ChatMessage> messages = new ArrayList<>(history);
        if (history.isEmpty() || !(history.get(history.size() - 1) instanceof UserMessage)) {
            messages.add(new UserMessage(prompt));
        }
        // Ensure we have at least the current prompt in messages
        if (messages.stream().noneMatch(m -> m instanceof UserMessage um && um.singleText().equals(prompt))) {
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
                injectScript("window.appendMessage('assistant', `**Erro:** " + escapeJs(error.getMessage()) + "`);");
            }),
            () -> Platform.runLater(() -> {
                injectScript("window.setTyping(false);");
                injectScript("window.finalizeAssistantMessage();");
                history.add(new AiMessage(sb.toString()));
                if (history.size() > 20) {
                    history.removeFirst();
                }
                if (config.speakResponses()) {
                    executor.submit(() -> tts.speak(sb.toString()));
                }
            })
        );
    }

    private void streamAssistantWithTools(String prompt) {
        var ollamaMessages = toOllamaMessages(history);
        ollamaMessages.add(new OllamaService.OllamaMessage("user", prompt, null, null));

        var webSearchTool = new OllamaService.ToolDefinition(
            "web_search",
            "Search the web for current information, news, facts, or documentation. " +
            "Use when the user asks about recent events, current data, or information " +
            "that may not be in your training data.",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "query", Map.of(
                        "type", "string",
                        "description", "The search query string"
                    )
                ),
                "required", List.of("query")
            )
        );

        var sb = new StringBuilder();

        ollama.chatStreamWithTools(
            config.ollamaModel(),
            ollamaMessages,
            List.of(webSearchTool),
            tc -> {
                String query = (String) tc.arguments().get("query");
                System.out.println("[web_search] query=" + query);
                var results = webSearch.search(query);
                return webSearch.formatForLlm(results);
            },
            token -> Platform.runLater(() -> {
                sb.append(token);
                injectScript("window.appendAssistantChunk(`" + escapeJs(token) + "`);");
            }),
            error -> Platform.runLater(() -> {
                injectScript("window.setTyping(false);");
                injectScript("window.appendMessage('assistant', `**Erro:** " + escapeJs(error.getMessage()) + "`);");
            }),
            () -> Platform.runLater(() -> {
                injectScript("window.setTyping(false);");
                injectScript("window.finalizeAssistantMessage();");
                history.add(new AiMessage(sb.toString()));
                if (history.size() > 20) {
                    history.removeFirst();
                }
                if (config.speakResponses()) {
                    executor.submit(() -> tts.speak(sb.toString()));
                }
            })
        );
    }

    private List<OllamaService.OllamaMessage> toOllamaMessages(List<ChatMessage> messages) {
        var result = new ArrayList<OllamaService.OllamaMessage>();
        for (ChatMessage msg : messages) {
            if (msg instanceof UserMessage um) {
                result.add(new OllamaService.OllamaMessage("user", um.singleText(), null, null));
            } else if (msg instanceof AiMessage am) {
                result.add(new OllamaService.OllamaMessage("assistant", am.text(), null, null));
            }
        }
        return result;
    }

    private void onOpenFile(String filePath) {
        executor.submit(() -> {
            try {
                var prInfo = github.parsePrUrl(filePath);
                String diff;
                if (prInfo.isPresent()) {
                    diff = github.fetchPrDiff(prInfo.get().owner(), prInfo.get().repo(), prInfo.get().number());
                } else {
                    diff = "File: " + filePath + "\nNo diff available.";
                }
                String finalDiff = diff;
                Platform.runLater(() -> codeViewer.showDiff(finalDiff));
            } catch (Exception e) {
                Platform.runLater(() -> codeViewer.showDiff("Error loading diff: " + e.getMessage()));
            }
        });
    }

    private void openSettings() {
        executor.submit(() -> {
            List<String> models;
            try {
                models = ollama.listModels();
            } catch (Exception e) {
                models = List.of(config.ollamaModel());
            }
            final List<String> finalModels = models;
            Platform.runLater(() -> {
                var dialog = new SettingsDialog(config, finalModels);
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
        });
    }

    private void onModelChanged(String model) {
        config.setOllamaModel(model);
    }

    private void onSpeakText(String text) {
        executor.submit(() -> {
            try {
                tts.speak(text);
            } catch (Exception e) {
                System.err.println("TTS failed: " + e.getMessage());
            }
        });
    }

    private boolean handleHelpCommand(String text) {
        String trimmed = text.trim();
        if (!trimmed.equals("/ajuda") && !trimmed.equals("/help")) {
            return false;
        }
        String help = "**Comandos disponíveis:**\n"
            + "`/ajuda` — mostrar esta mensagem\n"
            + "`/skills` — gerenciar habilidades\n"
            + "  `/skills listar` — listar instaladas\n"
            + "  `/skills buscar <termo>` — buscar habilidades\n"
            + "  `/skills instalar <pacote>` — instalar uma habilidade\n"
            + "  `/skills remover <habilidade>` — remover uma habilidade\n"
            + "  `/skills atualizar` — atualizar todas\n"
            + "  `/skills info <habilidade>` — detalhes da habilidade\n\n"
            + "Também pode colar um link de PR do GitHub para revisão automática.";
        injectSystemMessage(help);
        injectScript("window.setTyping(false);");
        return true;
    }

    private boolean handleSkillsCommand(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("/skills")) {
            return false;
        }

        String rest = trimmed.substring("/skills".length()).trim();
        if (rest.isEmpty()) {
            showSkillsHelp();
            injectScript("window.setTyping(false);");
            return true;
        }

        executor.submit(() -> {
            String[] parts = rest.split("\\s+", 2);
            String subcommand = parts[0].toLowerCase();
            String argument = parts.length > 1 ? parts[1].trim() : "";

            switch (subcommand) {
                case "list", "listar" -> handleSkillsList();
                case "search", "buscar" -> handleSkillsSearch(argument);
                case "add", "instalar" -> handleSkillsAdd(argument);
                case "remove", "remover" -> handleSkillsRemove(argument);
                case "update", "atualizar" -> handleSkillsUpdate();
                case "info", "detalhes" -> handleSkillsInfo(argument);
                default -> Platform.runLater(() -> {
                    injectSystemMessage("**Erro:** Comando desconhecido: `" + subcommand + "`. Digite `/skills` para ajuda.");
                    injectScript("window.setTyping(false);");
                });
            }
        });

        return true;
    }

    private void showSkillsHelp() {
        String help = "**Comandos de Habilidades:**\n"
            + "/skills listar — listar habilidades instaladas\n"
            + "/skills buscar <termo> — buscar habilidades\n"
            + "/skills instalar <pacote> — instalar uma habilidade\n"
            + "/skills remover <habilidade> — remover uma habilidade\n"
            + "/skills atualizar — atualizar todas as habilidades\n"
            + "/skills info <habilidade> — mostrar detalhes da habilidade";
        injectSystemMessage(help);
    }

    private void handleSkillsList() {
        try {
            var installed = skills.listInstalledSkills();
            Platform.runLater(() -> {
                if (installed.isEmpty()) {
                    injectSystemMessage("**Habilidades Instaladas:**\n_Nenhuma habilidade instalada._");
                } else {
                    StringBuilder sb = new StringBuilder("**Habilidades Instaladas:**\n");
                    for (int i = 0; i < installed.size(); i++) {
                        var s = installed.get(i);
                        sb.append(i + 1).append(". ").append(s.name());
                        if (!s.description().isBlank()) {
                            sb.append(" — ").append(s.description());
                        }
                        sb.append("\n");
                    }
                    injectSystemMessage(sb.toString().trim());
                }
                injectScript("window.setTyping(false);");
            });
        } catch (Exception e) {
            Platform.runLater(() -> {
                injectSystemMessage("**Erro:** Falha ao listar habilidades — " + e.getMessage());
                injectScript("window.setTyping(false);");
            });
        }
    }

    private void handleSkillsSearch(String query) {
        if (query.isBlank()) {
            Platform.runLater(() -> {
                injectSystemMessage("**Erro:** Forneça um termo de busca. Uso: `/skills buscar <termo>`");
                injectScript("window.setTyping(false);");
            });
            return;
        }
        try {
            var results = skills.findSkills(query);
            Platform.runLater(() -> {
                if (results.isEmpty()) {
                    injectSystemMessage("**Resultados da Busca:**\n_Nenhuma habilidade encontrada para \"" + query + "\"._");
                } else {
                    StringBuilder sb = new StringBuilder("**Resultados da busca para \"" + query + "\":**\n");
                    for (int i = 0; i < results.size(); i++) {
                        var s = results.get(i);
                        sb.append(i + 1).append(". **").append(s.name()).append("**");
                        if (s.installs() > 0) {
                            sb.append(" (").append(String.format("%,d", s.installs())).append(" instalações)");
                        }
                        if (s.installed()) {
                            sb.append(" ✓ instalada");
                        }
                        if (!s.source().isBlank()) {
                            sb.append(" — `").append(s.source()).append("`");
                        }
                        sb.append("\n");
                    }
                    injectSystemMessage(sb.toString().trim());
                }
                injectScript("window.setTyping(false);");
            });
        } catch (Exception e) {
            Platform.runLater(() -> {
                injectSystemMessage("**Erro:** Falha na busca — " + e.getMessage());
                injectScript("window.setTyping(false);");
            });
        }
    }

    private void handleSkillsAdd(String packageSpec) {
        if (packageSpec.isBlank()) {
            Platform.runLater(() -> {
                injectSystemMessage("**Erro:** Forneça um pacote. Uso: `/skills instalar <pacote>`");
                injectScript("window.setTyping(false);");
            });
            return;
        }
        var result = skills.addSkill(packageSpec);
        Platform.runLater(() -> {
            if (result.success()) {
                injectSystemMessage("**Sucesso:** Habilidade `" + packageSpec + "` instalada.\n\n**Saída:**\n```\n" + result.output() + "\n```");
            } else {
                injectSystemMessage("**Erro:** Falha ao instalar habilidade `" + packageSpec + "`.\n\n**Saída:**\n```\n" + result.output() + "\n```");
            }
            injectScript("window.setTyping(false);");
        });
    }

    private void handleSkillsRemove(String skillName) {
        if (skillName.isBlank()) {
            Platform.runLater(() -> {
                injectSystemMessage("**Erro:** Forneça o nome da habilidade. Uso: `/skills remover <habilidade>`");
                injectScript("window.setTyping(false);");
            });
            return;
        }
        var result = skills.removeSkill(skillName);
        Platform.runLater(() -> {
            if (result.success()) {
                injectSystemMessage("**Sucesso:** Habilidade `" + skillName + "` removida.\n\n**Saída:**\n```\n" + result.output() + "\n```");
            } else {
                injectSystemMessage("**Erro:** Falha ao remover habilidade `" + skillName + "`.\n\n**Saída:**\n```\n" + result.output() + "\n```");
            }
            injectScript("window.setTyping(false);");
        });
    }

    private void handleSkillsUpdate() {
        try {
            var result = skills.updateSkills().join();
            Platform.runLater(() -> {
                if (result.success()) {
                    injectSystemMessage("**Sucesso:** Todas as habilidades atualizadas.\n\n**Saída:**\n```\n" + result.output() + "\n```");
                } else {
                    injectSystemMessage("**Erro:** Falha ao atualizar habilidades.\n\n**Saída:**\n```\n" + result.output() + "\n```");
                }
                injectScript("window.setTyping(false);");
            });
        } catch (Exception e) {
            Platform.runLater(() -> {
                injectSystemMessage("**Erro:** Falha na atualização — " + e.getMessage());
                injectScript("window.setTyping(false);");
            });
        }
    }

    private void handleSkillsInfo(String skillPath) {
        if (skillPath.isBlank()) {
            Platform.runLater(() -> {
                injectSystemMessage("**Erro:** Forneça o nome da habilidade. Uso: `/skills info <habilidade>`");
                injectScript("window.setTyping(false);");
            });
            return;
        }
        try {
            var info = skills.getSkillDetails(skillPath);
            Platform.runLater(() -> {
                if (info == null) {
                    injectSystemMessage("**Erro:** Habilidade `" + skillPath + "` não encontrada.");
                } else {
                    StringBuilder sb = new StringBuilder();
                    sb.append("**").append(info.name()).append("**\n\n");
                    if (!info.description().isBlank()) {
                        sb.append(info.description()).append("\n\n");
                    }
                    if (!info.id().isBlank() && !info.id().equals(info.name())) {
                        sb.append("**ID:** `").append(info.id()).append("`\n");
                    }
                    if (!info.source().isBlank()) {
                        sb.append("**Fonte:** `").append(info.source()).append("`\n");
                    }
                    if (!info.content().isBlank()) {
                        String[] lines = info.content().split("\n", 21);
                        StringBuilder preview = new StringBuilder();
                        int maxLines = Math.min(lines.length, 20);
                        for (int i = 0; i < maxLines; i++) {
                            if (i > 0) preview.append("\n");
                            preview.append(lines[i]);
                        }
                        if (lines.length > 20) {
                            preview.append("\n...");
                        }
                        String previewStr = preview.toString();
                        if (previewStr.length() > 800) {
                            previewStr = previewStr.substring(0, 800) + "...";
                        }
                        sb.append("\n**Pré-visualização:**\n```markdown\n").append(previewStr).append("\n```");
                    }
                    injectSystemMessage(sb.toString());
                }
                injectScript("window.setTyping(false);");
            });
        } catch (Exception e) {
            Platform.runLater(() -> {
                injectSystemMessage("**Erro:** Falha ao obter informações da habilidade — " + e.getMessage());
                injectScript("window.setTyping(false);");
            });
        }
    }

    private void injectSystemMessage(String text) {
        injectScript("window.appendMessage('system', `" + escapeJs(text) + "`);");
    }

    private String onSkillsSearch(String query) {
        try {
            var results = skills.findSkills(query);
            return jsonMapper.writeValueAsString(results);
        } catch (Exception e) {
            System.err.println("Skills search failed: " + e.getMessage());
            return "[]";
        }
    }

    private String onSkillsList() {
        try {
            var results = skills.listInstalledSkills();
            return jsonMapper.writeValueAsString(results);
        } catch (Exception e) {
            System.err.println("Skills list failed: " + e.getMessage());
            return "[]";
        }
    }

    private SkillsService.SkillResult onSkillsAdd(String packageSpec) {
        return skills.addSkill(packageSpec);
    }

    private SkillsService.SkillResult onSkillsRemove(String skillName) {
        return skills.removeSkill(skillName);
    }

    private SkillsService.SkillResult onSkillsUpdate() {
        try {
            return skills.updateSkills().join();
        } catch (Exception e) {
            System.err.println("Skills update failed: " + e.getMessage());
            return new SkillsService.SkillResult(false, "Falha na atualização: " + e.getMessage());
        }
    }

    private String onSkillsDetails(String skillPath) {
        try {
            var details = skills.getSkillDetails(skillPath);
            if (details == null) {
                return "{}";
            }
            return jsonMapper.writeValueAsString(details);
        } catch (Exception e) {
            System.err.println("Skills details failed: " + e.getMessage());
            return "{}";
        }
    }

    private void injectScript(String script) {
        WebEngine engine = chat.getWebView().getEngine();
        if (pageLoaded && engine.getLoadWorker().getState() == javafx.concurrent.Worker.State.SUCCEEDED) {
            try {
                engine.executeScript(script);
            } catch (RuntimeException e) {
                System.err.println("JS injection failed: " + e.getMessage());
            }
        } else {
            synchronized (pendingScripts) {
                pendingScripts.add(script);
            }
        }
    }

    private void flushPendingScripts() {
        WebEngine engine = chat.getWebView().getEngine();
        List<String> scripts;
        synchronized (pendingScripts) {
            scripts = List.copyOf(pendingScripts);
            pendingScripts.clear();
        }
        for (String script : scripts) {
            try {
                engine.executeScript(script);
            } catch (RuntimeException e) {
                System.err.println("JS injection failed: " + e.getMessage());
            }
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
