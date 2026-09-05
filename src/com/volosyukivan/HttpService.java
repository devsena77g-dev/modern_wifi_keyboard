package com.volosyukivan;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public abstract class HttpService extends Service {
    protected PortUpdateListener listener;

    public abstract void networkServerFinished(HttpServer server);

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
