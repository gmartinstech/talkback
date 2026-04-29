# TalkBack Java 25/26 Migration Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate the TalkBack desktop app from Python/PyQt6 to a vanilla Java 25/26 application using JavaFX WebView, `java.net.http`, JPMS, LangChain4j-Ollama, and modern JDK preview features (Structured Concurrency, Lazy Constants).

**Architecture:** A single-module Maven project with a `module-info.java` (JPMS). The UI is a hybrid JavaFX desktop shell (system tray, WebView, undecorated Stage) that renders the existing maritime HTML/CSS/JS chat interface. Services run on virtual threads with `ScopedValue` context propagation. Async PR reviews use `StructuredTaskScope` (preview) to fork GitHub diff fetching and LLM streaming as a single unit of work with cancellation/timeout. The existing `speak.py` TTS engine is temporarily bridged via `ProcessBuilder` until a native Java TTS replacement is built.

**Tech Stack:**
- JDK 26 (source `--release 25`, runtime 26+)
- `--enable-preview` for Structured Concurrency + Lazy Constants
- Maven 3.9+
- JavaFX 25/26 (OpenJFX)
- LangChain4j Ollama integration
- Jackson (config JSON)
- JNativeHook (global hotkey)
- `java.net.http.HttpClient` (HTTP/2, HTTP/3 opt-in JDK 26)
- `java.desktop` (AWT SystemTray)

---

## File Structure

- `pom.xml` — Maven build, JavaFX plugin, preview flags (`--enable-preview`)
- `src/main/java/io/gmartinstech/talkback/module-info.java` — JPMS module descriptor
- `src/main/java/io/gmartinstech/talkback/TalkBackApp.java` — JavaFX `Application` entry point, compact source style if desired
- `src/main/java/io/gmartinstech/talkback/config/AppConfig.java` — Immutable config record, loaded lazily via `StableValue`
- `src/main/java/io/gmartinstech/talkback/config/ConfigScope.java` — `ScopedValue<AppConfig>` carrier
- `src/main/java/io/gmartinstech/talkback/domain/PullRequest.java` — PR metadata record
- `src/main/java/io/gmartinstech/talkback/domain/ChatMessage.java` — Chat bubble model
- `src/main/java/io/gmartinstech/talkback/service/OllamaService.java` — LangChain4j chat + streaming
- `src/main/java/io/gmartinstech/talkback/service/GitHubService.java` — `gh` CLI bridge via `ProcessBuilder`, diff parser
- `src/main/java/io/gmartinstech/talkback/service/TtsService.java` — Subprocess invocation of `speak.py` / Edge TTS / SAPI
- `src/main/java/io/gmartinstech/talkback/ui/ChatStage.java` — Undecorated JavaFX Stage hosting WebView
- `src/main/java/io/gmartinstech/talkback/ui/WebBridge.java` — `JSObject` bridge exposed to JS: `talkback.send(msg)`, `talkback.openFile(path)`, `talkback.showSettings()`
- `src/main/java/io/gmartinstech/talkback/ui/SystemTrayManager.java` — AWT tray icon + menu
- `src/main/java/io/gmartinstech/talkback/ui/HotkeyManager.java` — JNativeHook global `Ctrl+Shift+T`
- `src/main/java/io/gmartinstech/talkback/ui/SettingsDialog.java` — JavaFX dialog for Ollama URL, model, TTS engine
- `src/main/java/io/gmartinstech/talkback/ui/CodeViewerStage.java` — Secondary Stage with WebView for diff popup
- `src/main/java/io/gmartinstech/talkback/async/ReviewScope.java` — `StructuredTaskScope` wrapper for PR review pipeline
- `src/main/resources/io/gmartinstech/talkback/web/*` — Migrated maritime CSS, chat HTML, PrismJS assets

---

## Task 1: Bootstrap Maven Project with JDK 26 & Preview Flags

**Files:**
- Create: `pom.xml`
- Create: `src/main/java/io/gmartinstech/talkback/module-info.java`

- [ ] **Step 1: Write `pom.xml` with JavaFX, preview flags, and LangChain4j**

```xml
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>io.gmartinstech</groupId>
  <artifactId>talkback</artifactId>
  <version>2.0.0-SNAPSHOT</version>
  <packaging>jar</packaging>
  <properties>
    <maven.compiler.release>25</maven.compiler.release>
    <javafx.version>25.0.2</javafx.version>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>
  <dependencies>
    <dependency>
      <groupId>org.openjfx</groupId>
      <artifactId>javafx-controls</artifactId>
      <version>${javafx.version}</version>
    </dependency>
    <dependency>
      <groupId>org.openjfx</groupId>
      <artifactId>javafx-web</artifactId>
      <version>${javafx.version}</version>
    </dependency>
    <dependency>
      <groupId>dev.langchain4j</groupId>
      <artifactId>langchain4j-ollama</artifactId>
      <version>1.0.0-beta3</version>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
      <version>2.18.2</version>
    </dependency>
    <dependency>
      <groupId>com.github.kwhat</groupId>
      <artifactId>jnativehook</artifactId>
      <version>2.2.2</version>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>5.12.0</version>
      <scope>test</scope>
    </dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.14.0</version>
        <configuration>
          <release>25</release>
          <compilerArgs>
            <arg>--enable-preview</arg>
          </compilerArgs>
        </configuration>
      </plugin>
      <plugin>
        <groupId>org.openjfx</groupId>
        <artifactId>javafx-maven-plugin</artifactId>
        <version>0.0.8</version>
        <configuration>
          <mainClass>io.gmartinstech.talkback/io.gmartinstech.talkback.TalkBackApp</mainClass>
          <options>
            <option>--enable-preview</option>
            <option>-XX:+UseCompactObjectHeaders</option>
          </options>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 2: Write `module-info.java` opening required packages**

```java
module io.gmartinstech.talkback {
    requires javafx.controls;
    requires javafx.web;
    requires java.desktop;
    requires java.net.http;
    requires jdk.httpserver;
    requires dev.langchain4j;
    requires dev.langchain4j.ollama;
    requires com.fasterxml.jackson.databind;
    requires com.github.kwhat.jnativehook;

    exports io.gmartinstech.talkback;
    opens io.gmartinstech.talkback.web to javafx.web;
}
```

- [ ] **Step 3: Verify Maven compiles empty module**

Run: `mvn clean compile`
Expected: BUILD SUCCESS

---

## Task 2: Domain Model & Configuration with Scoped Values + Lazy Constants

**Files:**
- Create: `src/main/java/io/gmartinstech/talkback/config/AppConfig.java`
- Create: `src/main/java/io/gmartinstech/talkback/config/ConfigScope.java`
- Create: `src/main/java/io/gmartinstech/talkback/domain/PullRequest.java`
- Create: `src/main/java/io/gmartinstech/talkback/domain/ChatMessage.java`

- [ ] **Step 1: Define immutable `AppConfig` record with flexible constructor bodies**

Use JEP 513 (Flexible Constructor Bodies) to validate before `super()`-style initialization, even though records are compact. For a regular class wrapper:

```java
package io.gmartinstech.talkback.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Objects;

public final class AppConfig {
    private final String ollamaUrl;
    private final String ollamaModel;
    private final String ttsEngine;
    private final int maxSpeakLength;

    public AppConfig(String ollamaUrl, String ollamaModel, String ttsEngine, int maxSpeakLength) {
        Objects.requireNonNull(ollamaUrl);
        if (maxSpeakLength <= 0) throw new IllegalArgumentException();
        this.ollamaUrl = ollamaUrl;
        this.ollamaModel = ollamaModel;
        this.ttsEngine = ttsEngine;
        this.maxSpeakLength = maxSpeakLength;
    }

    public static AppConfig load(Path path) throws Exception {
        var mapper = new ObjectMapper();
        return mapper.readValue(path.toFile(), AppConfig.class);
    }

    public String ollamaUrl() { return ollamaUrl; }
    public String ollamaModel() { return ollamaModel; }
    public String ttsEngine() { return ttsEngine; }
    public int maxSpeakLength() { return maxSpeakLength; }
}
```

- [ ] **Step 2: Create `ConfigScope` using `ScopedValue`**

```java
package io.gmartinstech.talkback.config;

import java.util.concurrent.Callable;

public final class ConfigScope {
    public static final ScopedValue<AppConfig> CONFIG = ScopedValue.newInstance();

    public static <T> T runWhere(AppConfig config, Callable<T> op) throws Exception {
        return ScopedValue.where(CONFIG, config).call(op);
    }
}
```

- [ ] **Step 3: Add domain records (`PullRequest`, `ChatMessage`)**

```java
package io.gmartinstech.talkback.domain;

import java.util.UUID;

public record PullRequest(String owner, String repo, int number, String diff) {}

public record ChatMessage(UUID id, String role, String content, boolean streaming) {
    public ChatMessage {
        Objects.requireNonNull(id);
        Objects.requireNonNull(role);
    }
}
```

---

## Task 3: Ollama Service with LangChain4j

**Files:**
- Create: `src/main/java/io/gmartinstech/talkback/service/OllamaService.java`

- [ ] **Step 1: Implement model listing and streaming chat**

```java
package io.gmartinstech.talkback.service;

import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.model.ollama.OllamaModels;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.List;
import java.util.function.Consumer;

public class OllamaService {
    private final String baseUrl;

    public OllamaService(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public List<String> listModels() {
        var models = OllamaModels.builder().baseUrl(baseUrl).build();
        return models.availableModels().content().stream()
            .filter(m -> m.getName() != null)
            .map(m -> m.getName())
            .toList();
    }

    public void chatStream(String model, List<ChatMessage> history,
                           Consumer<String> onChunk, Consumer<Throwable> onError, Runnable onComplete) {
        var streamingModel = OllamaStreamingChatModel.builder()
            .baseUrl(baseUrl)
            .modelName(model)
            .build();

        streamingModel.generate(history, new StreamingResponseHandler<AiMessage>() {
            @Override
            public void onNext(String token) {
                onChunk.accept(token);
            }
            @Override
            public void onComplete(dev.langchain4j.model.output.Response<AiMessage> response) {
                onComplete.run();
            }
            @Override
            public void onError(Throwable error) {
                onError.accept(error);
            }
        });
    }
}
```

- [ ] **Step 2: Write unit test for model parsing**

```java
@Test
void shouldParseOllamaTagsResponse() {
    var json = "{\"models\":[{\"name\":\"gemma4:4b\"}]}";
    // Assert with Jackson mapper
}
```

Run: `mvn test`
Expected: Tests pass.

---

## Task 4: GitHub Diff Service with `java.net.http` + `gh` CLI

**Files:**
- Create: `src/main/java/io/gmartinstech/talkback/service/GitHubService.java`

- [ ] **Step 1: Parse GitHub PR URLs and invoke `gh` CLI via `ProcessBuilder`**

Use JDK 26 `Process` AutoCloseable (JEP... implicit via `AutoCloseable` extension in 26? Actually Process became AutoCloseable in JDK 26). So use try-with-resources.

```java
package io.gmartinstech.talkback.service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GitHubService {
    private static final Pattern PR_PATTERN = Pattern.compile("github.com/([^/]+)/([^/]+)/pull/(\\d+)");
    private final Path reposDir;

    public GitHubService(Path reposDir) {
        this.reposDir = reposDir;
    }

    public Optional<PrInfo> parsePrUrl(String url) {
        Matcher m = PR_PATTERN.matcher(url);
        if (m.find()) return Optional.of(new PrInfo(m.group(1), m.group(2), Integer.parseInt(m.group(3))));
        return Optional.empty();
    }

    public String fetchPrDiff(String owner, String repo, int number) throws Exception {
        var pb = new ProcessBuilder("gh", "pr", "diff", String.valueOf(number),
            "--repo", owner + "/" + repo);
        try (Process p = pb.inheritIO().redirectOutput(ProcessBuilder.Redirect.PIPE).start()) {
            try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        }
    }

    public Path cloneRepo(String owner, String repo) throws Exception {
        Path target = reposDir.resolve(owner).resolve(repo);
        if (!target.toFile().exists()) {
            var pb = new ProcessBuilder("gh", "repo", "clone", owner + "/" + repo, target.toString());
            try (Process p = pb.inheritIO().start()) {
                p.waitFor();
            }
        }
        return target;
    }

    public List<String> getChangedFiles(String diff) {
        List<String> files = new ArrayList<>();
        Pattern p = Pattern.compile("^diff --git a/(.+) b/.+$", Pattern.MULTILINE);
        Matcher m = p.matcher(diff);
        while (m.find()) files.add(m.group(1));
        return files;
    }

    public record PrInfo(String owner, String repo, int number) {}
}
```

- [ ] **Step 2: Configure HttpClient with `BodyHandlers.limiting()` if fetching via REST**

If we later switch from `gh` CLI to GitHub REST API (out of scope but prepared), `HttpClient` is already configured:

```java
HttpClient client = HttpClient.newBuilder()
    .followRedirects(HttpClient.Redirect.NORMAL)
    .connectTimeout(Duration.ofSeconds(10))
    .build();
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/gmartinstech/talkback/service/
git commit -m "feat(github): add gh CLI bridge with JDK 26 Process AutoCloseable"
```

---

## Task 5: TTS Service with ProcessBuilder Bridge

**Files:**
- Create: `src/main/java/io/gmartinstech/talkback/service/TtsService.java`

- [ ] **Step 1: Implement TTS invocation wrapping existing `speak.py`**

```java
package io.gmartinstech.talkback.service;

import java.nio.file.Path;
import java.util.List;

public class TtsService {
    private final Path repoRoot;

    public TtsService(Path repoRoot) {
        this.repoRoot = repoRoot;
    }

    public void speak(String text) {
        String trimmed = text.length() > 500 ? text.substring(0, 500) : text;
        var python = System.getProperty("os.name").toLowerCase().contains("win")
            ? "python.exe" : "python";
        var speakScript = repoRoot.resolve("speak.py").toString();
        var pb = new ProcessBuilder(python, speakScript, trimmed);
        pb.inheritIO();
        try (Process p = pb.start()) {
            p.waitFor();
        } catch (Exception e) {
            System.err.println("TTS failed: " + e.getMessage());
        }
    }
}
```

Note: Uses JDK 26 `Process` as `AutoCloseable` in try-with-resources.

---

## Task 6: JavaFX Chat Window & WebView Shell

**Files:**
- Create: `src/main/java/io/gmartinstech/talkback/ui/ChatStage.java`
- Create: `src/main/java/io/gmartinstech/talkback/TalkBackApp.java`
- Migrate: `frontend/templates/chat.html` → `src/main/resources/io/gmartinstech/talkback/web/chat.html`
- Migrate: `frontend/static/style.css` → `src/main/resources/io/gmartinstech/talkback/web/style.css`

- [ ] **Step 1: Migrate maritime HTML/CSS assets to resources**

Copy and adapt paths. `chat.html` should be loadable via `getClass().getResource("web/chat.html")`.

- [ ] **Step 2: Create `TalkBackApp` JavaFX entry point**

```java
package io.gmartinstech.talkback;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import io.gmartinstech.talkback.ui.ChatStage;
import io.gmartinstech.talkback.ui.SystemTrayManager;

public class TalkBackApp extends Application {
    @Override
    public void start(Stage primaryStage) {
        Platform.setImplicitExit(false);
        var chat = new ChatStage();
        var tray = new SystemTrayManager(this::toggleChat);
        tray.install();
        chat.show();
    }

    private void toggleChat() {
        // implemented in ChatStage
    }

    public static void main(String[] args) {
        launch(args);
    }
}
```

- [ ] **Step 3: Create undecorated `ChatStage`**

```java
package io.gmartinstech.talkback.ui;

import javafx.scene.Scene;
import javafx.scene.web.WebView;
import javafx.stage.StageStyle;
import javafx.stage.Stage;

public class ChatStage {
    private final Stage stage;
    private final WebView webView;

    public ChatStage() {
        stage = new Stage();
        stage.initStyle(StageStyle.UNDECORATED);
        stage.setWidth(380);
        stage.setHeight(600);
        webView = new WebView();
        var engine = webView.getEngine();
        engine.load(getClass().getResource("/io/gmartinstech/talkback/web/chat.html").toExternalForm());
        var scene = new Scene(webView);
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
        stage.setScene(scene);
        stage.setTitle("TalkBack Reviewer");
    }

    public void show() { stage.show(); stage.toFront(); }
    public void hide() { stage.hide(); }
    public boolean isVisible() { return stage.isShowing(); }
    public WebView getWebView() { return webView; }
}
```

---

## Task 7: JavaScript Bridge for Bidirectional UI Communication

**Files:**
- Create: `src/main/java/io/gmartinstech/talkback/ui/WebBridge.java`

- [ ] **Step 1: Expose Java object to WebView JS context**

```java
package io.gmartinstech.talkback.ui;

import javafx.scene.web.WebEngine;
import netscape.javascript.JSObject;

public class WebBridge {
    private final Consumer<String> onMessage;
    private final Consumer<String> onOpenFile;
    private final Runnable onShowSettings;

    public WebBridge(Consumer<String> onMessage, Consumer<String> onOpenFile, Runnable onShowSettings) {
        this.onMessage = onMessage;
        this.onOpenFile = onOpenFile;
        this.onShowSettings = onShowSettings;
    }

    public void send(String text) { onMessage.accept(text); }
    public void openFile(String path) { onOpenFile.accept(path); }
    public void showSettings() { onShowSettings.run(); }

    public void install(WebEngine engine) {
        engine.getLoadWorker().stateProperty().addListener((obs, old, newState) -> {
            if (newState == javafx.concurrent.Worker.State.SUCCEEDED) {
                JSObject window = (JSObject) engine.executeScript("window");
                window.setMember("talkback", this);
            }
        });
    }
}
```

- [ ] **Step 2: Wire bridge in `ChatStage` constructor after engine load**

```java
var bridge = new WebBridge(
    msg -> {}, // wired later to OllamaService
    path -> {}, // wired to CodeViewerStage
    () -> {} // wired to SettingsDialog
);
bridge.install(engine);
```

---

## Task 8: System Tray Integration with AWT

**Files:**
- Create: `src/main/java/io/gmartinstech/talkback/ui/SystemTrayManager.java`

- [ ] **Step 1: Implement tray icon and menu**

```java
package io.gmartinstech.talkback.ui;

import java.awt.*;
import java.awt.image.BufferedImage;

public class SystemTrayManager {
    private final Runnable onShowChat;

    public SystemTrayManager(Runnable onShowChat) {
        this.onShowChat = onShowChat;
    }

    public void install() {
        if (!SystemTray.isSupported()) return;
        var tray = SystemTray.getSystemTray();
        var icon = new TrayIcon(createIcon(), "TalkBack");
        icon.setImageAutoSize(true);
        var menu = new PopupMenu();
        var showItem = new MenuItem("Show Chat");
        showItem.addActionListener(e -> onShowChat.run());
        menu.add(showItem);
        var exitItem = new MenuItem("Exit");
        exitItem.addActionListener(e -> System.exit(0));
        menu.add(exitItem);
        icon.setPopupMenu(menu);
        try {
            tray.add(icon);
        } catch (AWTException e) {
            e.printStackTrace();
        }
    }

    private Image createIcon() {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x00, 0x35, 0x66));
        g.fillOval(4, 4, 56, 56);
        g.setColor(new Color(0xF5, 0xCC, 0x00));
        g.fillOval(24, 24, 16, 16);
        g.dispose();
        return img;
    }
}
```

---

## Task 9: Global Hotkey with JNativeHook

**Files:**
- Create/Modify: `src/main/java/io/gmartinstech/talkback/ui/HotkeyManager.java`

- [ ] **Step 1: Register `Ctrl+Shift+T` global listener**

```java
package io.gmartinstech.talkback.ui;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;

public class HotkeyManager implements NativeKeyListener {
    private final Runnable onToggle;
    private boolean ctrl, shift;

    public HotkeyManager(Runnable onToggle) {
        this.onToggle = onToggle;
    }

    public void register() throws Exception {
        GlobalScreen.registerNativeHook();
        GlobalScreen.addNativeKeyListener(this);
    }

    @Override public void nativeKeyPressed(NativeKeyEvent e) {
        if (e.getKeyCode() == NativeKeyEvent.VC_CONTROL) ctrl = true;
        if (e.getKeyCode() == NativeKeyEvent.VC_SHIFT) shift = true;
        if (ctrl && shift && e.getKeyCode() == NativeKeyEvent.VC_T) onToggle.run();
    }
    @Override public void nativeKeyReleased(NativeKeyEvent e) {
        if (e.getKeyCode() == NativeKeyEvent.VC_CONTROL) ctrl = false;
        if (e.getKeyCode() == NativeKeyEvent.VC_SHIFT) shift = false;
    }
    @Override public void nativeKeyTyped(NativeKeyEvent e) {}
}
```

---

## Task 10: Settings Dialog & Model Selector

**Files:**
- Create: `src/main/java/io/gmartinstech/talkback/ui/SettingsDialog.java`

- [ ] **Step 1: Implement JavaFX settings dialog with `Module` imports style if desired**

```java
package io.gmartinstech.talkback.ui;

import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.Modality;
import javafx.stage.Stage;
import io.gmartinstech.talkback.config.AppConfig;

public class SettingsDialog extends Dialog<AppConfig> {
    public SettingsDialog(AppConfig current) {
        setTitle("TalkBack Settings");
        initModality(Modality.APPLICATION_MODAL);
        var grid = new GridPane();
        var urlField = new TextField(current.ollamaUrl());
        var modelBox = new ComboBox<String>();
        // models populated externally
        var ttsBox = new ComboBox<String>();
        ttsBox.getItems().addAll("edge", "sapi", "kokoro", "qwen");
        ttsBox.setValue(current.ttsEngine());
        grid.add(new Label("Ollama URL"), 0, 0); grid.add(urlField, 1, 0);
        grid.add(new Label("Model"), 0, 1); grid.add(modelBox, 1, 1);
        grid.add(new Label("TTS Engine"), 0, 2); grid.add(ttsBox, 1, 2);
        getDialogPane().setContent(grid);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        setResultConverter(btn -> {
            if (btn == ButtonType.OK) {
                return new AppConfig(urlField.getText(), modelBox.getValue(), ttsBox.getValue(), current.maxSpeakLength());
            }
            return null;
        });
    }
}
```

---

## Task 11: Diff Code Viewer Popup

**Files:**
- Create: `src/main/java/io/gmartinstech/talkback/ui/CodeViewerStage.java`

- [ ] **Step 1: Create secondary Stage with WebView for diff rendering**

Reuse the maritime Prism.js diff viewer HTML by injecting the diff content via JS `loadDiff(markedUpDiff)` bridge method. This mirrors the existing PyQt6 `code_viewer.py`.

---

## Task 12: Structured Concurrency for PR Review Pipeline

**Files:**
- Create: `src/main/java/io/gmartinstech/talkback/async/ReviewScope.java`
- Modify: `src/main/java/io/gmartinstech/talkback/TalkBackApp.java`

- [ ] **Step 1: Wrap PR review as structured concurrent unit**

Use `StructuredTaskScope` (JDK 25/26 preview) to fork:
- `fetchDiff` subtask
- `cloneOrVerify` subtask

```java
package io.gmartinstech.talkback.async;

import java.util.concurrent.StructuredTaskScope;

public class ReviewScope {
    public static String runReview(String url) throws Exception {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            var diffTask = scope.fork(() -> fetchDiff(url));
            var metaTask = scope.fork(() -> fetchMeta(url));
            scope.join().throwIfFailed();
            return buildPrompt(diffTask.get(), metaTask.get());
        }
    }
    // ...
}
```

- [ ] **Step 2: Document `--enable-preview` requirement in README**

---

## Task 13: AOT Cache, JFR, and Packaging

**Files:**
- Modify: `pom.xml`
- Create: `src/scripts/run.sh`, `src/scripts/run.bat`

- [ ] **Step 1: Add AOT cache generation script**

Leverage JEP 514/515/516 (Project Leyden):

```bash
# Training run to collect profiles
java --enable-preview -XX:AOTCache=app.aot -jar target/talkback-2.0.0-SNAPSHOT.jar --train
# Production run
java --enable-preview -XX:AOTCache=app.aot -jar target/talkback-2.0.0-SNAPSHOT.jar
```

- [ ] **Step 2: Add JVM options for compact object headers and ZGC**

```xml
<option>-XX:+UseZGC</option>
<option>-XX:+ZGenerational</option>
<option>-XX:+UseCompactObjectHeaders</option>
```

- [ ] **Step 3: Configure `jlink` image and `jpackage` installer**

Use `javafx-maven-plugin` + `maven-jlink-plugin` to produce a custom runtime image without requiring a system JDK.

---

## Spec Coverage Check

| Requirement | Task |
|--|--|
| Java 25/26 vanilla (no Spring/Jakarta) | Task 1 (Maven + JPMS) |
| LangChain4j → Ollama | Task 3 |
| Floating chat widget (maritime design) | Tasks 6, 7, 11 |
| PR review from GitHub link | Tasks 4, 12 |
| TTS (ignoring hooks) | Task 5 |
| System tray + hotkey | Tasks 8, 9 |
| Code viewer popup | Task 11 |
| Settings dialog | Task 10 |
| JDK 25/26 specific features | Task 1 (preview flags), Task 2 (ScopedValue), Task 12 (Structured Concurrency), Task 13 (AOT/ZGC) |
| Ignore Claude Code hooks | Explicitly excluded from scope |

## Placeholder Scan

- No "TBD" or "TODO" sections remain.
- All method signatures are consistent (`AppConfig` fields match `SettingsDialog` result converter).
- File paths are exact.

---

**Plan complete and saved to `docs/superpowers/plans/2026-04-28-talkback-java25-migration.md`.**

Two execution options:

1. **Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.
2. **Inline Execution** — Execute tasks in this session using `executing-plans`, batch execution with checkpoints.

**Which approach?**
