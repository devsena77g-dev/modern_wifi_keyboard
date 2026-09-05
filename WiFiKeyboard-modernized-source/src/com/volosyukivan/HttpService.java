/**
 * WiFi Keyboard - Remote Keyboard for Android.
 * Copyright (C) 2011 Ivan Volosyuk
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 */
package com.volosyukivan;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.channels.ServerSocketChannel;
import java.net.InetSocketAddress;

public class HttpService extends Service {
    private static final String TAG = "WiFiKeyboard.HttpService";
    private static final int DEFAULT_PORT = 7777;
    private static final int MAX_PORT = 8099;

    private RemoteKeyListener listener;
    private PortUpdateListener portUpdateListener;

    String htmlpage;
    int port;

    private KeyboardHttpServer server;
    private boolean destroying;

    private final RemoteKeyboard.Stub binder =
            new RemoteKeyboard.Stub() {
                @Override
                public void registerKeyListener(
                        RemoteKeyListener listener)
                        throws RemoteException {
                    HttpService.this.listener = listener;
                }

                @Override
                public void unregisterKeyListener(
                        RemoteKeyListener listener)
                        throws RemoteException {
                    if (HttpService.this.listener == listener) {
                        HttpService.this.listener = null;
                    }
                }

                @Override
                public void setPortUpdateListener(
                        PortUpdateListener listener)
                        throws RemoteException {
                    HttpService.this.portUpdateListener = listener;

                    if (listener != null && port != 0) {
                        listener.portUpdated(port);
                    }
                }

                @Override
                public void startTextEdit(String content)
                        throws RemoteException {
                    KeyboardHttpServer current = server;
                    if (current != null) {
                        current.notifyClient(content);
                    }
                }

                @Override
                public void stopTextEdit()
                        throws RemoteException {
                    KeyboardHttpServer current = server;
                    if (current != null) {
                        current.notifyClient(null);
                    }
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();
        destroying = false;

        Log.d(TAG, "HttpService created");

        loadHtmlPage();
        startServer();
    }

    private void loadHtmlPage() {
        try (InputStream input = getAssets().open("key.html")) {
            byte[] buffer = new byte[8192];
            StringBuilder html = new StringBuilder();

            int read;
            int total = 0;

            while ((read = input.read(buffer)) != -1) {
                total += read;

                // Prevent an accidentally huge asset from consuming memory.
                if (total > 1024 * 1024) {
                    throw new IOException("HTML page is too large");
                }

                html.append(
                        new String(
                                buffer,
                                0,
                                read,
                                StandardCharsets.UTF_8
                        )
                );
            }

            htmlpage = html.toString();

        } catch (IOException e) {
            throw new IllegalStateException(
                    "Unable to load assets/key.html",
                    e
            );
        }
    }

    private ServerSocketChannel bindSocket(int requestedPort) {
        try {
            ServerSocketChannel channel =
                    ServerSocketChannel.open();

            channel.socket().setReuseAddress(true);

            channel.socket().bind(
                    new InetSocketAddress(
                            "0.0.0.0",
                            requestedPort
                    )
            );

            return channel;

        } catch (IOException e) {
            return null;
        }
    }

    private ServerSocketChannel makeSocket() {
        android.content.SharedPreferences prefs =
                getSharedPreferences("port", MODE_PRIVATE);

        int savedPort =
                prefs.getInt("port", DEFAULT_PORT);

        ServerSocketChannel channel =
                bindSocket(savedPort);

        if (channel != null) {
            return channel;
        }

        if (savedPort != DEFAULT_PORT) {
            channel = bindSocket(DEFAULT_PORT);
            if (channel != null) {
                return channel;
            }
        }

        // Use a predictable, unprivileged fallback range.
        for (int candidate = 8000;
             candidate <= MAX_PORT;
             candidate++) {

            channel = bindSocket(candidate);

            if (channel != null) {
                return channel;
            }
        }

        throw new IllegalStateException(
                "Unable to find an available TCP port"
        );
    }

    private synchronized void startServer() {
        if (destroying || server != null) {
            return;
        }

        ServerSocketChannel socket = makeSocket();

        port = socket.socket().getLocalPort();

        getSharedPreferences("port", MODE_PRIVATE)
                .edit()
                .putInt("port", port)
                .apply();

        server = new KeyboardHttpServer(
                this,
                socket
        );

        if (portUpdateListener != null) {
            try {
                portUpdateListener.portUpdated(port);
            } catch (RemoteException e) {
                Log.w(TAG, "Port update failed", e);
            }
        }

        server.start();

        Log.d(
                TAG,
                "HTTP server listening on port " + port
        );
    }

    void networkServerFinished(
            KeyboardHttpServer finishedServer) {

        synchronized (this) {
            if (server != finishedServer) {
                return;
            }

            server = null;

            if (!destroying) {
                startServer();
            }
        }
    }

    @Override
    public void onDestroy() {
        destroying = true;

        KeyboardHttpServer current;

        synchronized (this) {
            current = server;
            server = null;
        }

        if (current != null) {
            try {
                current.finish();
            } catch (Throwable t) {
                Log.w(
                        TAG,
                        "Error stopping HTTP server",
                        t
                );
            }
        }

        listener = null;
        portUpdateListener = null;

        Log.d(TAG, "HttpService destroyed");

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    RemoteKeyListener getListener() {
        return listener;
    }
}
