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

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;

public class WiFiKeyboard extends Activity {
    private static final int DEFAULT_PORT = 7777;

    private int port = DEFAULT_PORT;
    private ServiceConnection serviceConnection;
    private boolean bound;

    public static ArrayList<String> getNetworkAddresses() {
        Set<String> addresses = new HashSet<>();

        try {
            Enumeration<NetworkInterface> interfaces =
                    NetworkInterface.getNetworkInterfaces();

            if (interfaces == null) {
                return new ArrayList<>();
            }

            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();

                try {
                    if (!iface.isUp() || iface.isLoopback() || iface.isVirtual()) {
                        continue;
                    }
                } catch (SocketException ignored) {
                    continue;
                }

                Enumeration<InetAddress> inetAddresses =
                        iface.getInetAddresses();

                while (inetAddresses.hasMoreElements()) {
                    InetAddress address = inetAddresses.nextElement();

                    if (!(address instanceof Inet4Address)
                            || address.isLoopbackAddress()
                            || address.isLinkLocalAddress()) {
                        continue;
                    }

                    String host = address.getHostAddress();
                    if (!TextUtils.isEmpty(host)) {
                        addresses.add(host);
                    }
                }
            }
        } catch (SocketException e) {
            Debug.e("Failed to get network interfaces", e);
        }

        ArrayList<String> result = new ArrayList<>(addresses);
        Collections.sort(result);
        return result;
    }

    private View createView() {
        ArrayList<String> addresses = getNetworkAddresses();

        ScrollView parent = new ScrollView(this);
        parent.setFillViewport(true);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20);
        layout.setPadding(padding, padding, padding, padding);
        parent.addView(layout);

        TextView title = text(layout, "WiFi Keyboard", 28, true);
        title.setGravity(Gravity.CENTER_HORIZONTAL);

        text(layout, "Use your computer's browser to type into Android over Wi-Fi.",
                18, false);
        text(layout, "", 8, false);

        text(layout, "1. Enable WiFi Keyboard in Android keyboard settings.",
                16, false);
        text(layout, "2. Select WiFi Keyboard as your current keyboard.",
                16, false);
        text(layout, "3. Keep this app running and open one of the addresses below on your PC.",
                16, false);

        text(layout, "", 12, false);

        if (addresses.isEmpty()) {
            TextView warning = text(
                    layout,
                    "No usable local IPv4 address was found.\n\n"
                            + "Connect the phone to Wi-Fi and reopen this screen.",
                    18,
                    true
            );
            warning.setGravity(Gravity.CENTER);
        } else if (addresses.size() == 1) {
            TextView url = text(
                    layout,
                    "http://" + addresses.get(0) + ":" + port,
                    22,
                    true
            );
            url.setTextIsSelectable(true);
        } else {
            text(layout, "Open any of these addresses:", 17, true);

            for (String address : addresses) {
                TextView url = text(
                        layout,
                        "http://" + address + ":" + port,
                        20,
                        true
                );
                url.setTextIsSelectable(true);
                url.setPadding(0, dp(5), 0, dp(5));
            }
        }

        text(layout, "", 12, false);
        text(layout, "Port: " + port, 14, false);
        text(layout,
                "The computer and phone must be reachable on the same local network.",
                14, false);

        return parent;
    }

    private TextView text(
            LinearLayout parent,
            String message,
            int fontSize,
            boolean bold) {

        TextView view = new TextView(this);
        view.setText(message);
        view.setTextSize(fontSize);
        view.setTextIsSelectable(true);

        if (bold) {
            view.setTypeface(
                    view.getTypeface(),
                    android.graphics.Typeface.BOLD
            );
        }

        parent.addView(
                view,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                )
        );

        return view;
    }

    private int dp(int value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density
        );
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.app_name);
        bindToHttpService();
        setContentView(createView());
    }

    private void bindToHttpService() {
        if (bound) {
            return;
        }

        serviceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(
                    ComponentName name,
                    IBinder service) {

                bound = true;
                Debug.d("WiFiKeyboard connected to HttpService.");

                try {
                    PortUpdateListener listener = new PortUpdateListener.Stub() {
                        @Override
                        public void portUpdated(int newPort)
                                throws RemoteException {

                            runOnUiThread(() -> {
                                if (newPort != port) {
                                    port = newPort;
                                    setContentView(createView());
                                }
                            });
                        }
                    };

                    RemoteKeyboard remote =
                            RemoteKeyboard.Stub.asInterface(service);

                    remote.setPortUpdateListener(listener);

                } catch (RemoteException e) {
                    Debug.e(
                            "Failed to receive HttpService port",
                            e
                    );
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                bound = false;
                Debug.d("WiFiKeyboard disconnected from HttpService.");
            }
        };

        try {
            bound = bindService(
                    new Intent(this, HttpService.class),
                    serviceConnection,
                    Context.BIND_AUTO_CREATE
            );
        } catch (RuntimeException e) {
            bound = false;
            throw e;
        }

        if (!bound) {
            throw new IllegalStateException(
                    "Failed to bind HttpService"
            );
        }
    }

    @Override
    protected void onDestroy() {
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
}
