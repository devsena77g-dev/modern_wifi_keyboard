/**
 * WiFi Keyboard - Remote Keyboard for Android.
 * Copyright (C) 2011 Ivan Volosyuk
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */
package com.volosyukivan;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.inputmethodservice.InputMethodService;
import android.os.IBinder;
import android.os.RemoteException;
import android.text.InputType;
import android.util.Log;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

import java.util.HashSet;

import com.volosyukivan.RemoteKeyListener.Stub;

public class WiFiInputMethod extends InputMethodService {
    public static final int KEY_HOME = -1000;
    public static final int KEY_END = -1001;
    public static final int KEY_CONTROL = -1002;
    public static final int KEY_DEL = -1003;

    private static final String TAG = "WiFiInputMethod";

    private final HashSet<Integer> pressedKeys =
            new HashSet<>();

    private final ExtractedTextRequest extractedTextRequest =
            new ExtractedTextRequest();

    private RemoteKeyboard remoteKeyboard;
    private ServiceConnection serviceConnection;
    private Stub keyboardListener;
    private boolean bound;

    {
        extractedTextRequest.hintMaxChars = 100000;
        extractedTextRequest.hintMaxLines = 10000;
        extractedTextRequest.flags = 0;
        extractedTextRequest.token = 1;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        serviceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(
                    ComponentName name,
                    IBinder service) {

                remoteKeyboard =
                        RemoteKeyboard.Stub.asInterface(service);

                keyboardListener =
                        new RemoteKeyListener.Stub() {
                            @Override
                            public void keyEvent(
                                    int code,
                                    boolean pressed)
                                    throws RemoteException {
                                receivedKey(code, pressed);
                            }

                            @Override
                            public void charEvent(int code)
                                    throws RemoteException {
                                receivedChar(code);
                            }

                            @Override
                            public boolean setText(String text)
                                    throws RemoteException {
                                return WiFiInputMethod.this.setText(text);
                            }

                            @Override
                            public String getText()
                                    throws RemoteException {
                                return WiFiInputMethod.this.getText();
                            }
                        };

                try {
                    remoteKeyboard.registerKeyListener(
                            keyboardListener
                    );
                    bound = true;
                } catch (RemoteException e) {
                    Log.e(
                            TAG,
                            "Failed to register keyboard listener",
                            e
                    );
                    remoteKeyboard = null;
                    keyboardListener = null;
                }
            }

            @Override
            public void onServiceDisconnected(
                    ComponentName name) {

                bound = false;
                remoteKeyboard = null;
            }
        };

        try {
            bound = bindService(
                    new Intent(
                            this,
                            HttpService.class
                    ),
                    serviceConnection,
                    Context.BIND_AUTO_CREATE
            );
        } catch (RuntimeException e) {
            bound = false;
            Log.e(
                    TAG,
                    "Failed to bind HttpService",
                    e
            );
        }
    }

    @Override
    public void onStartInput(
            EditorInfo attribute,
            boolean restarting) {

        super.onStartInput(
                attribute,
                restarting
        );

        pressedKeys.clear();

        if (remoteKeyboard == null) {
            return;
        }

        try {
            remoteKeyboard.startTextEdit(
                    getText()
            );
        } catch (RemoteException e) {
            Log.w(
                    TAG,
                    "Failed communicating with HttpService",
                    e
            );
        }
    }

    @Override
    public void onFinishInput() {
        pressedKeys.clear();

        if (remoteKeyboard != null) {
            try {
                remoteKeyboard.stopTextEdit();
            } catch (RemoteException ignored) {
            }
        }

        super.onFinishInput();
    }

    @Override
    public void onDestroy() {
        pressedKeys.clear();

        if (remoteKeyboard != null
                && keyboardListener != null) {

            try {
                remoteKeyboard.unregisterKeyListener(
                        keyboardListener
                );
            } catch (RemoteException ignored) {
            }
        }

        remoteKeyboard = null;
        keyboardListener = null;

        if (bound && serviceConnection != null) {
            try {
                unbindService(serviceConnection);
            } catch (IllegalArgumentException ignored) {
            }
        }

        bound = false;
        serviceConnection = null;

        super.onDestroy();
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    void receivedChar(int code) {
        InputConnection conn =
                getCurrentInputConnection();

        if (conn == null) {
            return;
        }

        // Preserve the useful desktop shortcuts.
        if (pressedKeys.contains(KEY_CONTROL)) {
            switch (code) {
                case 'a':
                case 'A':
                    selectAll(conn);
                    return;

                case 'x':
                case 'X':
                    cut(conn);
                    return;

                case 'c':
                case 'C':
                    copy(conn);
                    return;

                case 'v':
                case 'V':
                    paste(conn);
                    return;
            }
        }

        String text;

        if (code >= 0 && code <= 0xFFFF) {
            text = new String(new char[]{(char) code});
        } else {
            try {
                text = new String(
                        Character.toChars(code)
                );
            } catch (IllegalArgumentException e) {
                Log.w(
                        TAG,
                        "Invalid Unicode code point: " + code
                );
                return;
            }
        }

        conn.commitText(
                text,
                1
        );
    }

    void receivedKey(
            int code,
            boolean pressed) {

        if (code == KeyboardHttpServer.FOCUS) {
            for (Integer key :
                    new HashSet<>(pressedKeys)) {
                sendKey(
                        key,
                        false,
                        false
                );
            }

            pressedKeys.clear();
            resetModifiers();
            return;
        }

        if (pressedKeys.contains(code) == pressed) {
            if (!pressed) {
                return;
            }

            // Ignore browser autorepeat for these keys.
            switch (code) {
                case KeyEvent.KEYCODE_ALT_LEFT:
                case KeyEvent.KEYCODE_SHIFT_LEFT:
                case KeyEvent.KEYCODE_HOME:
                case KeyEvent.KEYCODE_MENU:
                    return;
            }
        }

        if (pressed) {
            pressedKeys.add(code);
            sendKey(code, true, false);
        } else {
            pressedKeys.remove(code);
            sendKey(
                    code,
                    false,
                    pressedKeys.isEmpty()
            );
        }
    }

    void resetModifiers() {
        InputConnection conn =
                getCurrentInputConnection();

        if (conn == null) {
            return;
        }

        conn.clearMetaKeyStates(
                KeyEvent.META_ALT_ON
                        | KeyEvent.META_SHIFT_ON
                        | KeyEvent.META_SYM_ON
                        | KeyEvent.META_CTRL_ON
        );
    }

    void sendKey(
            int code,
            boolean down,
            boolean resetModifiers) {

        InputConnection conn =
                getCurrentInputConnection();

        if (conn == null) {
            return;
        }

        if (code < 0) {
            if (!down) {
                return;
            }

            switch (code) {
                case KEY_HOME:
                    keyHome(conn);
                    break;

                case KEY_END:
                    keyEnd(conn);
                    break;

                case KEY_DEL:
                    keyDel(conn);
                    break;
            }

            return;
        }

        if (pressedKeys.contains(KEY_CONTROL)) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (down) {
                        wordLeft(conn);
                    }
                    return;

                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down) {
                        wordRight(conn);
                    }
                    return;

                case KeyEvent.KEYCODE_DEL:
                    if (down) {
                        deleteWordLeft(conn);
                    }
                    return;

                case KeyEvent.KEYCODE_FORWARD_DEL:
                    if (down) {
                        deleteWordRight(conn);
                    }
                    return;

                case KeyEvent.KEYCODE_DPAD_CENTER:
                    if (down) {
                        copy(conn);
                    }
                    return;
            }
        }

        if (pressedKeys.contains(
                KeyEvent.KEYCODE_SHIFT_LEFT)) {

            if (code == KeyEvent.KEYCODE_DPAD_CENTER) {
                if (down) {
                    paste(conn);
                }
                return;
            }
        }

        if (code == KeyEvent.KEYCODE_ENTER
                && shouldSend()) {

            if (!down) {
                return;
            }

            EditorInfo editorInfo =
                    getCurrentInputEditorInfo();

            if (editorInfo != null) {
                int action =
                        editorInfo.imeOptions
                                & EditorInfo.IME_MASK_ACTION;

                if (action != EditorInfo.IME_ACTION_NONE
                        && action != EditorInfo.IME_ACTION_UNSPECIFIED) {

                    conn.performEditorAction(action);
                    return;
                }
            }

            conn.sendKeyEvent(
                    new KeyEvent(
                            android.os.SystemClock.uptimeMillis(),
                            android.os.SystemClock.uptimeMillis(),
                            KeyEvent.ACTION_DOWN,
                            KeyEvent.KEYCODE_ENTER,
                            0
                    )
            );

            return;
        }

        int metaState = 0;

        if (pressedKeys.contains(
                KeyEvent.KEYCODE_SHIFT_LEFT)) {
            metaState |=
                    KeyEvent.META_SHIFT_LEFT_ON;
        }

        if (pressedKeys.contains(KEY_CONTROL)) {
            metaState |=
                    KeyEvent.META_CTRL_ON;
        }

        if (pressedKeys.contains(
                KeyEvent.KEYCODE_ALT_LEFT)) {
            metaState |=
                    KeyEvent.META_ALT_LEFT_ON;
        }

        long now =
                android.os.SystemClock.uptimeMillis();

        conn.sendKeyEvent(
                new KeyEvent(
                        now,
                        now,
                        down
                                ? KeyEvent.ACTION_DOWN
                                : KeyEvent.ACTION_UP,
                        code,
                        0,
                        metaState
                )
        );

        if (resetModifiers) {
            conn.clearMetaKeyStates(
                    KeyEvent.META_ALT_ON
                            | KeyEvent.META_SHIFT_ON
                            | KeyEvent.META_SYM_ON
                            | KeyEvent.META_CTRL_ON
            );
        }
    }

    private boolean shouldSend() {
        if (pressedKeys.contains(KEY_CONTROL)) {
            return true;
        }

        EditorInfo editorInfo =
                getCurrentInputEditorInfo();

        if (editorInfo == null) {
            return false;
        }

        if ((editorInfo.inputType & InputType.TYPE_CLASS_TEXT) == 0) {
            return false;
        }

        if ((editorInfo.inputType
                & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0) {
            return false;
        }

        int action =
                editorInfo.imeOptions
                        & EditorInfo.IME_MASK_ACTION;

        return action != EditorInfo.IME_ACTION_NONE
                && action != EditorInfo.IME_ACTION_DONE
                && action != EditorInfo.IME_ACTION_UNSPECIFIED;
    }

    private void keyDel(InputConnection conn) {
        if (pressedKeys.contains(KEY_CONTROL)) {
            deleteWordRight(conn);
            return;
        }

        if (pressedKeys.contains(
                KeyEvent.KEYCODE_SHIFT_LEFT)) {
            cut(conn);
            return;
        }

        conn.deleteSurroundingText(
                0,
                1
        );
    }

    private void paste(InputConnection conn) {
        conn.performContextMenuAction(
                android.R.id.paste
        );
    }

    private void copy(InputConnection conn) {
        conn.performContextMenuAction(
                android.R.id.copy
        );
    }

    private void cut(InputConnection conn) {
        conn.performContextMenuAction(
                android.R.id.cut
        );
    }

    private void selectAll(InputConnection conn) {
        ExtractedText text =
                conn.getExtractedText(
                        extractedTextRequest,
                        0
                );

        if (text == null || text.text == null) {
            return;
        }

        conn.setSelection(
                0,
                text.text.length()
        );
    }

    private void deleteWordRight(
            InputConnection conn) {

        ExtractedText text =
                conn.getExtractedText(
                        extractedTextRequest,
                        0
                );

        if (text == null || text.text == null) {
            return;
        }

        int cursor = text.selectionEnd;
        String str = text.text.toString();
        int len = str.length();

        while (cursor < len
                && Character.isWhitespace(
                str.charAt(cursor))) {
            cursor++;
        }

        while (cursor < len
                && !Character.isWhitespace(
                str.charAt(cursor))) {
            cursor++;
        }

        conn.deleteSurroundingText(
                0,
                Math.max(0, cursor - text.selectionEnd)
        );
    }

    private void deleteWordLeft(
            InputConnection conn) {

        ExtractedText text =
                conn.getExtractedText(
                        extractedTextRequest,
                        0
                );

        if (text == null || text.text == null) {
            return;
        }

        int cursor = text.selectionEnd;
        String str = text.text.toString();

        while (cursor > 0
                && Character.isWhitespace(
                str.charAt(cursor - 1))) {
            cursor--;
        }

        while (cursor > 0
                && !Character.isWhitespace(
                str.charAt(cursor - 1))) {
            cursor--;
        }

        conn.deleteSurroundingText(
                Math.max(
                        0,
                        text.selectionEnd - cursor
                ),
                0
        );
    }

    private void wordRight(InputConnection conn) {
        ExtractedText text =
                conn.getExtractedText(
                        extractedTextRequest,
                        0
                );

        if (text == null || text.text == null) {
            return;
        }

        int cursor = text.selectionEnd;
        String str = text.text.toString();
        int len = str.length();

        while (cursor < len
                && Character.isWhitespace(
                str.charAt(cursor))) {
            cursor++;
        }

        while (cursor < len
                && !Character.isWhitespace(
                str.charAt(cursor))) {
            cursor++;
        }

        boolean shift =
                pressedKeys.contains(
                        KeyEvent.KEYCODE_SHIFT_LEFT
                );

        int start =
                shift
                        ? text.selectionStart
                        : cursor;

        conn.setSelection(start, cursor);
    }

    private void wordLeft(InputConnection conn) {
        ExtractedText text =
                conn.getExtractedText(
                        extractedTextRequest,
                        0
                );

        if (text == null || text.text == null) {
            return;
        }

        int cursor = text.selectionEnd;

        String str = text.text.toString();

        while (cursor > 0
                && Character.isWhitespace(
                str.charAt(cursor - 1))) {
            cursor--;
        }

        while (cursor > 0
                && !Character.isWhitespace(
                str.charAt(cursor - 1))) {
            cursor--;
        }

        boolean shift =
                pressedKeys.contains(
                        KeyEvent.KEYCODE_SHIFT_LEFT
                );

        int start =
                shift
                        ? text.selectionStart
                        : cursor;

        conn.setSelection(start, cursor);
    }

    private void keyEnd(InputConnection conn) {
        ExtractedText text =
                conn.getExtractedText(
                        extractedTextRequest,
                        0
                );

        if (text == null || text.text == null) {
            return;
        }

        boolean control =
                pressedKeys.contains(KEY_CONTROL);

        boolean shift =
                pressedKeys.contains(
                        KeyEvent.KEYCODE_SHIFT_LEFT
                );

        int end;

        if (control) {
            end = text.text.length();
        } else {
            end = text.text.toString()
                    .indexOf('\n', text.selectionEnd);

            if (end < 0) {
                end = text.text.length();
            }
        }

        conn.setSelection(
                shift ? text.selectionStart : end,
                end
        );
    }

    private void keyHome(InputConnection conn) {
        ExtractedText text =
                conn.getExtractedText(
                        extractedTextRequest,
                        0
                );

        if (text == null || text.text == null) {
            return;
        }

        boolean control =
                pressedKeys.contains(KEY_CONTROL);

        boolean shift =
                pressedKeys.contains(
                        KeyEvent.KEYCODE_SHIFT_LEFT
                );

        int end;

        if (control) {
            end = 0;
        } else {
            end = text.text.toString()
                    .lastIndexOf(
                            '\n',
                            Math.max(
                                    0,
                                    text.selectionEnd - 1
                            )
                    );

            end++;

            if (end < 0) {
                end = 0;
            }
        }

        conn.setSelection(
                shift ? text.selectionStart : end,
                end
        );
    }

    boolean setText(String text) {
        InputConnection conn =
                getCurrentInputConnection();

        if (conn == null) {
            return false;
        }

        if (text == null) {
            text = "";
        }

        ExtractedText current =
                conn.getExtractedText(
                        extractedTextRequest,
                        0
                );

        conn.beginBatchEdit();

        try {
            if (current != null
                    && current.text != null) {

                conn.setSelection(
                        0,
                        current.text.length()
                );
            }

            conn.commitText(
                    text,
                    1
            );

            return true;

        } finally {
            conn.endBatchEdit();
        }
    }

    String getText() {
        try {
            InputConnection conn =
                    getCurrentInputConnection();

            if (conn == null) {
                return "";
            }

            ExtractedText text =
                    conn.getExtractedText(
                            extractedTextRequest,
                            0
                    );

            if (text == null || text.text == null) {
                return "";
            }

            return text.text.toString();

        } catch (Throwable t) {
            Log.w(
                    TAG,
                    "Unable to read current text",
                    t
            );
            return "";
        }
    }
}
