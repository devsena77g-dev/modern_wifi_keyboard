package com.volosyukivan;

import java.io.InputStream;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;

import android.os.RemoteException;
import android.util.Log;

public class KeyboardHttpServer extends HttpServer {
  private HttpService service;
  private ArrayList<KeyboardHttpConnection> waitingConnections = new ArrayList<KeyboardHttpConnection>();

  public static final int FOCUS = -1;

  public KeyboardHttpServer(HttpService service, ServerSocketChannel ch) {
    super(ch);
    this.service = service;
  }

  public HttpService getService() {
    return service;
  }

  @Override
  public HttpConnection newConnection(SocketChannel ch) {
    return new KeyboardHttpConnection(this, ch);
  }

  public String processKeyRequest(String request) {
    int key = 0;
    try {
      key = Integer.parseInt(request);
    } catch (NumberFormatException e) {
      return "err";
    }

    boolean isPressed = true;
    if (key < 0) {
      key = -key;
      isPressed = false;
    }

    return sendKey(key, isPressed);
  }

  public String sendKey(int key, boolean isPressed) {
    RemoteKeyListener listener = service.listener;
    if (listener == null) {
      return "err";
    }
    try {
      listener.keyEvent(key, isPressed);
    } catch (RemoteException e) {
      Log.e("wifikeyboard", "RemoteException during key dispatch", e);
      return "err";
    }
    return "ok";
  }

  public boolean commitText(String text) {
    RemoteKeyListener listener = service.listener;
    if (listener == null) {
      return false;
    }
    try {
      return listener.commitText(text);
    } catch (RemoteException e) {
      Log.e("wifikeyboard", "RemoteException during text commit", e);
      return false;
    }
  }

  public boolean replaceText(String text) {
    RemoteKeyListener listener = service.listener;
    if (listener == null) {
      return false;
    }
    try {
      return listener.setText(text);
    } catch (RemoteException e) {
      Log.e("wifikeyboard", "RemoteException during text replace", e);
      return false;
    }
  }

  public String getText() {
    RemoteKeyListener listener = service.listener;
    if (listener == null) {
      return "";
    }
    try {
      return listener.getText();
    } catch (RemoteException e) {
      Log.e("wifikeyboard", "RemoteException during getText", e);
      return "";
    }
  }

  public String getPage() {
    try {
      int resId = service.getResources().getIdentifier("key", "raw", service.getPackageName());
      if (resId == 0) {
        return "Error loading page: raw/key not found";
      }
      InputStream is = service.getResources().openRawResource(resId);
      byte[] buffer = new byte[is.available()];
      is.read(buffer);
      is.close();
      return new String(buffer, java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception e) {
      Log.e("wifikeyboard", "Failed to load HTML page", e);
      return "Error loading page";
    }
  }

  public void addWaitingConnection(KeyboardHttpConnection connection) {
    waitingConnections.add(connection);
  }

  @Override
  protected void onExit() {
    service.networkServerFinished(this);
  }
}
