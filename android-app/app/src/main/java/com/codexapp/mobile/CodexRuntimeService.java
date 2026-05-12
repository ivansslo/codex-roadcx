package com.codexapp.mobile;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public final class CodexRuntimeService extends Service {
    @Override
    public void onCreate() {
        super.onCreate();
        CodexLocalServer.ensureStarted(getApplicationContext());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        CodexLocalServer.ensureStarted(getApplicationContext());
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        CodexLocalServer.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
