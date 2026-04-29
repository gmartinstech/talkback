package net.martinstech.talkback.ui;

import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.scene.web.WebView;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * The floating chat window. An undecorated {@link Stage} containing a {@link WebView}
 * that renders the maritime dark-theme HTML interface.
 */
public class ChatStage {
    private final Stage stage;
    private final WebView webView;

    public ChatStage() {
        stage = new Stage();
        stage.initStyle(StageStyle.UNDECORATED);
        stage.setWidth(380);
        stage.setHeight(600);
        stage.setTitle("TalkBack Reviewer");
        positionNearTray();

        webView = new WebView();
        var engine = webView.getEngine();
        var url = getClass().getResource("/net/martinstech/talkback/web/chat.html");
        if (url != null) {
            engine.load(url.toExternalForm());
        } else {
            engine.loadContent("<html><body style='background:#0C1222;color:#F1F5F9;'>TalkBack: chat UI not found</body></html>");
        }

        var scene = new Scene(webView);
        scene.setFill(Color.TRANSPARENT);
        stage.setScene(scene);
    }

    private void positionNearTray() {
        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        double padding = 16;
        stage.setX(bounds.getMaxX() - stage.getWidth() - padding);
        stage.setY(bounds.getMaxY() - stage.getHeight() - padding);
    }

    public void show() {
        positionNearTray();
        stage.show();
        stage.toFront();
    }

    public void hide() {
        stage.hide();
    }

    public boolean isVisible() {
        return stage.isShowing();
    }

    public void toggle() {
        if (stage.isShowing()) {
            stage.hide();
        } else {
            stage.show();
            stage.toFront();
        }
    }

    public WebView getWebView() {
        return webView;
    }

    public Stage getStage() {
        return stage;
    }
}
