package io.gmartinstech.talkback.ui;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;

/**
 * Global hotkey manager using JNativeHook.
 * Listens for {@code Ctrl+Shift+T} to toggle the chat window.
 */
public class HotkeyManager implements NativeKeyListener {
    private final Runnable onToggle;
    private boolean ctrlDown;
    private boolean shiftDown;

    public HotkeyManager(Runnable onToggle) {
        this.onToggle = onToggle;
    }

    /**
     * Registers the native hook and attaches this listener.
     *
     * @throws Exception if registration fails
     */
    public void register() throws Exception {
        GlobalScreen.registerNativeHook();
        GlobalScreen.addNativeKeyListener(this);
    }

    @Override
    public void nativeKeyPressed(NativeKeyEvent e) {
        if (e.getKeyCode() == NativeKeyEvent.VC_CONTROL) ctrlDown = true;
        if (e.getKeyCode() == NativeKeyEvent.VC_SHIFT) shiftDown = true;
        if (ctrlDown && shiftDown && e.getKeyCode() == NativeKeyEvent.VC_T) {
            onToggle.run();
        }
    }

    @Override
    public void nativeKeyReleased(NativeKeyEvent e) {
        if (e.getKeyCode() == NativeKeyEvent.VC_CONTROL) ctrlDown = false;
        if (e.getKeyCode() == NativeKeyEvent.VC_SHIFT) shiftDown = false;
    }

    @Override
    public void nativeKeyTyped(NativeKeyEvent e) { /* unused */ }
}
