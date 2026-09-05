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

  public boolean replaceText(String text) {
    RemoteKeyListener listener = service.listener;
    if (listener == null) {
      return false;
    }
    try {
      listener.replaceText(text);
      return true;
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
      InputStream is = service.getResources().openRawResource(R.raw.key);
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
