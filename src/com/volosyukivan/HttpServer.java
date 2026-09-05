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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;

public class HttpService extends Service {
  public static final String NOTIFICATION_CHANNEL_ID = "wifi_keyboard_service";
  private static final int NOTIFICATION_ID = 1;

  // Package-private visibility so KeyboardHttpServer can access it directly
  RemoteKeyListener listener;

  private WifiLock wifiLock;
  private ServerSocketChannel serverChannel;
  private KeyboardHttpServer httpServer;
  private PortUpdateListener portUpdateListener;
  private int currentPort = 7777;

  private final RemoteKeyboard.Stub binder = new RemoteKeyboard.Stub() {
    @Override
    public void registerRemoteKeyListener(RemoteKeyListener newListener)
        throws RemoteException {
      HttpService.this.listener = newListener;
    }

    @Override
    public void unregisterRemoteKeyListener(RemoteKeyListener listener)
        throws RemoteException {
      if (HttpService.this.listener == listener) {
        HttpService.this.listener = null;
      }
    }

    @Override
    public void setPortUpdateListener(PortUpdateListener listener)
        throws RemoteException {
      HttpService.this.portUpdateListener = listener;
      if (HttpService.this.portUpdateListener != null) {
        HttpService.this.portUpdateListener.portUpdated(currentPort);
      }
    }
  };

  @Override
  public IBinder onBind(Intent intent) {
    return binder;
  }

  @Override
  public void onCreate() {
    super.onCreate();
    startForegroundServiceWithNotification();

    WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    if (wm != null) {
      wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "WiFiKeyboard");
      wifiLock.acquire();
    }

    startServer();
  }

  private void startForegroundServiceWithNotification() {
    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    if (nm == null) return;

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationChannel channel = new NotificationChannel(
          NOTIFICATION_CHANNEL_ID,
          "WiFi Keyboard Background Service",
          NotificationManager.IMPORTANCE_LOW
      );
      nm.createNotificationChannel(channel);
    }

    Intent notificationIntent = new Intent(this, WiFiKeyboard.class);
    PendingIntent pendingIntent = PendingIntent.getActivity(
        this, 0, notificationIntent,
        PendingIntent.FLAG_IMMUTABLE
    );

    Notification.Builder builder = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        ? new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        : new Notification.Builder(this);

    Notification notification = builder
        .setContentTitle("WiFi Keyboard Service")
        .setContentText("WiFi Keyboard server is running.")
        .setSmallIcon(R.drawable.icon)
        .setContentIntent(pendingIntent)
        .setOngoing(true)
        .build();

    startForeground(NOTIFICATION_ID, notification);
  }

  private void startServer() {
    try {
      serverChannel = ServerSocketChannel.open();
      serverChannel.socket().setReuseAddress(true);
      serverChannel.socket().bind(new InetSocketAddress(currentPort));
      httpServer = new KeyboardHttpServer(this, serverChannel);
      httpServer.start();
      notifyPortUpdated(currentPort);
    } catch (IOException e) {
      Debug.e("Failed to start HTTP server on port " + currentPort, e);
    }
  }

  public void networkServerFinished(KeyboardHttpServer server) {
    if (this.httpServer == server) {
      this.httpServer = null;
    }
  }

  private void notifyPortUpdated(int newPort) {
    if (portUpdateListener != null) {
      try {
        portUpdateListener.portUpdated(newPort);
      } catch (RemoteException e) {
        Debug.e("Failed to notify port update", e);
      }
    }
  }

  @Override
  public void onDestroy() {
    if (httpServer != null) {
      httpServer.finish();
      httpServer = null;
    }

    if (serverChannel != null) {
      try {
        serverChannel.close();
      } catch (IOException ignored) {
      }
      serverChannel = null;
    }

    if (wifiLock != null && wifiLock.isHeld()) {
      wifiLock.release();
      wifiLock = null;
    }

    stopForeground(true);
    super.onDestroy();
  }
}
