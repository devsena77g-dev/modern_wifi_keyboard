
/**
 * WiFi Keyboard - Remote Keyboard for Android.
 * Copyright (C) 2011 Ivan Volosyuk
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package com.volosyukivan;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;

import android.util.Log;

public final class KeyboardHttpConnection extends HttpConnection {

  private KeyboardHttpServer server;
  
  private static final byte[] Q_KEY = "key".getBytes();
  private static final byte[] Q_FORM = "form".getBytes();
  private static final byte[] Q_TEXT = "text".getBytes();
  private static final byte[] Q_WAIT = "wait".getBytes();
  private static final byte[] Q_DEFAULT = "".getBytes();
  private static final byte[] Q_BG_GIF = "bg.gif".getBytes();
  private static final byte[] Q_ICON_PNG = "icon.png".getBytes();
  
  private static final byte[][] patterns = {
    Q_KEY,
    Q_FORM,
    Q_TEXT,
    Q_WAIT,
    Q_DEFAULT,
    Q_BG_GIF,
    Q_ICON_PNG,
  };
  
  private static final int H_KEY = 0;
  private static final int H_FORM = 1;
  private static final int H_TEXT = 2;
  private static final int H_WAIT = 3;
  private static final int H_DEFAULT = 4;
  private static final int H_BG_GIF = 5;
  private static final int H_ICON_PNG = 6;
  
  private int requestType;
  
  private HeaderMatcher formHeaders = new HeaderMatcher(
      "Content-Type", "Content-Length"
  );
  
  public KeyboardHttpConnection(final KeyboardHttpServer server, SocketChannel ch) {
    super(ch);
    this.server = server;
  }
  
  private static final byte LETTER_SPACE = " ".getBytes()[0];
  private static final byte LETTER_SLASH = "/".getBytes()[0];
  private static final byte LETTER_QUESTION = "?".getBytes()[0];
  private int queryEnd;
  private int cmdEnd;
  
  @Override
  public HeaderMatcher lookupRequestHandler() {
    byte[] request = this.request;
    
//    Log.d("wifikeyboard", "req: " + new String(request, 0, requestLength));
    
    queryEnd = 0;
    for (int i = requestLength - 1; i >= 0; i--) {
      if (request[i] == LETTER_SPACE) {
        queryEnd = i;
        break;
      }
    }
    
    int cmdStart = 0;
    for (int i = queryEnd - 1; i >= 0; i--) {
      if (request[i] == LETTER_SLASH) {
        cmdStart = i + 1;
        break;
      }
    }
    
    cmdEnd = queryEnd;
    for (int i = cmdStart; i < queryEnd; i++) {
      if (request[i] == LETTER_QUESTION) {
        cmdEnd = i;
        break;
      }
    }
    
    requestType = H_DEFAULT;
    int nhandlers = patterns.length;
    int cmdLen = cmdEnd - cmdStart;
    outer:
    for (int i = 0; i < nhandlers; i++) {
      byte[] pattern = patterns[i];
      if (pattern.length != cmdLen) {
        continue;
      }
      
      for (int j = 0; j < cmdLen; j++) {
        if (pattern[j] != request[j + cmdStart]) continue outer; 
      }
      requestType = i;
    }
    
    switch (requestType) {
    case H_FORM: return formHeaders;
    default: return null;
    }
  }
 
  public ByteBuffer sendData(
      String content_type,
      byte[] content,
      int content_length) {
    byte[] headers = String.format("HTTP/1.1 200 OK\n" +
        "Content-Type: %s\n"+
        "Content-Length: %d\n" +
        "\n", content_type, content_length).getBytes();

    ByteBuffer out = ByteBuffer.allocate(headers.length + content_length);
    out.put(headers);
    out.put(content, 0, content_length);
    out.flip();
    return out;
  }
  
  public ByteBuffer sendImage(int resid) {
    InputStream is2 = server.getService().getResources().openRawResource(resid);
    byte[] image = new byte[10240];
    try {
      return sendData("image/gif", image, is2.read(image));
    } catch (IOException e) {
      throw new RuntimeException("failed to load resource");
    }
  }
  
  private static ThreadLocal<Map<String,ByteBuffer>> responseCache =
    new ThreadLocal<Map<String,ByteBuffer>>();

  @Override
  protected ByteBuffer requestHandler() {
    switch (requestType) {
    case H_KEY: return onKeyRequest();
    case H_TEXT: return onTextRequest();
    case H_FORM: return onFormRequest();
    case H_DEFAULT: return onDefaultRequest();
    case H_BG_GIF: return onBgGifRequest();
    case H_ICON_PNG: return onIconPngRequest();
    case H_WAIT: return onWaitRequest();
    default: return onDefaultRequest();
    }
  }

  private ByteBuffer onWaitRequest() {
    server.addWaitingConnection(KeyboardHttpConnection.this);
    return null;
  }

  private ByteBuffer onIconPngRequest() {
    return sendImage(R.raw.icon);
  }

  private ByteBuffer onBgGifRequest() {
    return sendImage(R.raw.bg);
  }

  private ByteBuffer onDefaultRequest() {
    String page = server.getPage();
    try {
      byte[] content = page.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      server.sendKey(KeyboardHttpServer.FOCUS, true);
      return sendData("text/html; charset=UTF-8", content, content.length);
    } catch (UnsupportedEncodingException e) {
      throw new RuntimeException("UTF-8 unsupported");
    }
  }

  private ByteBuffer onFormRequest() {
    String newText = "";
    try {
      newText = new String(formData, 0, formDataLength, java.nio.charset.StandardCharsets.UTF_8);
    } catch (UnsupportedEncodingException e) {
      Log.e("wifikeyboard", "UTF-8", e);
    }
    boolean success = server.replaceText(newText);
    // FIXME: use cached value
    byte[] resp = (success ? "ok" : "fail").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    return sendData("text/plain; charset=UTF-8", resp, resp.length);
  }

  private ByteBuffer onTextRequest() {
    byte[] text = null;
    try {
      if (server != null) {
        try {
          text = ((String)(server.getText())).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (NullPointerException e) {
          Log.e("wifikeyboard", "no text", e);
        }
      }
    } catch (UnsupportedEncodingException e) {
    }
    if (text == null) {
      // FIXME: error handling?
      text = new byte[0];
    }
    return sendData("text/plain; charset=UTF-8", text, text.length);
  }

  private ByteBuffer onKeyRequest() {
    String response = server.processKeyRequest(
        new String(request, cmdEnd + 1, queryEnd));
//    Log.d("wifikeyboard", "response = " + response);
    Map<String, ByteBuffer> cache = responseCache.get();
    if (cache == null) {
      cache = new TreeMap<String, ByteBuffer>();
      responseCache.set(cache);
    }
    
    ByteBuffer buffer = cache.get(response);
    if (buffer != null) {
      buffer.position(0);
      return buffer;
    }
//    Debug.d(response);
    byte[] content = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    buffer = sendData("text/plain", content, content.length);
    cache.put(response, buffer);
    return buffer;
  }
}


====================================================================================================
src/com/volosyukivan/KeyboardHttpServer.java
====================================================================================================

/**
 * WiFi Keyboard - Remote Keyboard for Android.
 * Copyright (C) 2011 Ivan Volosyuk
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package com.volosyukivan;

import static com.volosyukivan.KeycodeConvertor.convertKey;

import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;

import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;

public final class KeyboardHttpServer extends HttpServer {
  private HttpService service;
  static final int FOCUS = 1024;
  private int seqNum = 0;
  ArrayList<KeyboardHttpConnection> waitingConnections =
    new ArrayList<KeyboardHttpConnection>();
  
  public HttpConnection newConnection(SocketChannel ch) {
    return new KeyboardHttpConnection(this, ch);
  }

  KeyboardHttpServer(HttpService service, ServerSocketChannel ch) {
    super(ch);
    this.service = service;
  }
  
  public String getPage() {
    return service.htmlpage.replace("12345", Integer.toString(seqNum + 1));
  }
  
  public String processKeyRequest(String req) {
    boolean success = true;
    boolean event = false;
    String[] ev = req.split(",", -1);
    if (ev.length == 0) {
      return "problem";
    }

    final int seq;
    try {
      seq = Integer.parseInt(ev[0]);
    } catch (NumberFormatException e) {
      return "problem";
    }

    int numKeysRequired = seq - seqNum;
    if (numKeysRequired <= 0) {
      return "multi";
    }
    int numKeysAvailable = ev.length - 2;
    int numKeys = Math.min(numKeysAvailable, numKeysRequired);

    for (int i = numKeys; i >= 1; i--) {
//      Debug.d("Event: " + ev[i]);
      char mode = ev[i].charAt(0);
      int code = Integer.parseInt(ev[i].substring(1));
      if (mode == 'C') {
        // FIXME: can be a problem with extended unicode characters
        success = success && sendChar(code);
      } else {
        boolean pressed = mode == 'D';
        success = success && sendKey(code, pressed);
      }
      event = true;
    }
    seqNum = seq;

    if (!event) {
      return "multi";
    } else if (success) {
      return "ok";
    } else {
      return "problem";
    }
  }
  
  // used by network thread
  abstract class KeyboardAction extends Action {
    @Override
    public Object run() {
      try {
        RemoteKeyListener listener = service.listener;
        if (listener != null) {
          return runAction(listener);
        }
      } catch (RemoteException e) {
        Debug.e("Exception on input method side, ignore", e);
      }
      return null;
    }
    abstract Object runAction(RemoteKeyListener listener) throws RemoteException;
  };
  
  // executed by network thread
  boolean sendKey(final int code0, final boolean pressed) {
    final int code = convertKey(code0);
//    Log.d("wifikeyboard", "in: " + code0 + " out:" + code);
    
    Object success = runAction(new KeyboardAction() {
      @Override
      Object runAction(RemoteKeyListener listener) throws RemoteException {
        listener.keyEvent(code, pressed);
        return service; // not null for success
      }
    });
    return success != null;
  }
    
  // executed by network thread
  boolean sendChar(final int code) {
    Object success = runAction(new KeyboardAction() {
      @Override
      public Object runAction(RemoteKeyListener listener) throws RemoteException {
        listener.charEvent(code);
        return service; // not null
      }
    });
    return success != null;
  }
  
  public HttpService getService() {
    return service;
  }
  
  // executed by network thread
  public void addWaitingConnection(
      final KeyboardHttpConnection keyboardHttpConnection) {
    runAction(new Action() {
      @Override
      public Object run() {
        waitingConnections.add(keyboardHttpConnection);
        Log.d("wifikeyboard", "add waiting connection");
        return null;
      }
      
    });
  }
  
  public void onExit() {
    runAction(new Action() {
      @Override
      public Object run() {
        service.networkServerFinished();
        return null;
      }
    });
  }
  
  // executed by main thread
  public void notifyClient(final String text) {
    postUpdate(new Update() {
      @Override
      public void run() {
        for (KeyboardHttpConnection con : waitingConnections) {
          //            Debug.d(event);
          byte[] content = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
          ByteBuffer out = con.sendData("text/plain", content, content.length);
          setResponse(con, out);
        }
        waitingConnections.clear();
      }
    });
  }

  // Executed by network thread
  public boolean replaceText(final String string) {
    Object result = runAction(new KeyboardAction() {
      @Override
      Object runAction(RemoteKeyListener listener) throws RemoteException {
        
        return listener.setText(string) ? service : null;
      }
    });
    return result != null;
  }
  
  public Object getText() {
    return runAction(new KeyboardAction() {
      @Override
      Object runAction(RemoteKeyListener listener) throws RemoteException {
        return listener.getText();
      }
    });
  }
}


====================================================================================================
AndroidManifest.xml
====================================================================================================

<?xml version="1.0" encoding="utf-8"?>
<!--
 * WiFi Keyboard - Remote Keyboard for Android.
 * Original project copyright (C) 2011 Ivan Volosyuk.
 * Licensed under the GNU General Public License version 2.
 -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <supports-screens
        android:anyDensity="true"
        android:largeScreens="true"
        android:normalScreens="true"
        android:resizeable="true"
        android:smallScreens="true"
        android:xlargeScreens="true" />

    <application
        android:allowBackup="false"
        android:icon="@drawable/icon"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/AppTheme"
        android:usesCleartextTraffic="true">

        <activity
            android:name=".WiFiKeyboard"
            android:label="@string/app_name"
            android:exported="true"
            android:noHistory="true">

            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>

        </activity>

        <service
            android:name=".WiFiInputMethod"
            android:label="@string/app_name"
            android:permission="android.permission.BIND_INPUT_METHOD"
            android:exported="true">

            <intent-filter>
                <action android:name="android.view.InputMethod" />
            </intent-filter>

            <meta-data
                android:name="android.view.im"
                android:resource="@xml/method" />
        </service>

        <service
            android:name=".HttpService"
            android:exported="false" />
    </application>
</manifest>


====================================================================================================
res/values/styles.xml
====================================================================================================

<resources>
    <style name="WorkspaceIcon">
        <item name="android:layout_width">match_parent</item>
        <item name="android:layout_height">wrap_content</item>
        <item name="android:background">@drawable/shortcut_selector</item>
        <item name="android:paddingTop">@dimen/paddingTop</item>
        <item name="android:layout_marginLeft">@dimen/marginLeft</item>
        <item name="android:layout_marginRight">@dimen/marginRight</item>
        <item name="android:layout_marginTop">@dimen/marginTop</item>
        <item name="android:layout_marginBottom">@dimen/marginBottom</item>
    </style>

    <style name="Label">
        <item name="android:textSize">13sp</item>
        <item name="android:singleLine">true</item>
        <item name="android:ellipsize">marquee</item>
        <item name="android:shadowColor">#FF000000</item>
        <item name="android:shadowRadius">2.0</item>
        <item name="android:textColor">#FFFFFFFF</item>
        <item name="android:paddingLeft">5dp</item>
        <item name="android:paddingRight">5dp</item>
        <item name="android:gravity">center_horizontal</item>
    </style>

    <style name="AppTheme"
        parent="@android:style/Theme.Material.Light.NoActionBar">
        <item name="android:fontFamily">sans</item>
        <item name="android:colorAccent">#3F51B5</item>
        <item name="android:navigationBarColor">#000000</item>
        <item name="android:windowLightStatusBar">true</item>
    </style>
</resources>