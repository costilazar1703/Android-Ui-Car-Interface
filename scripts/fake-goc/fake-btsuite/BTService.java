package com.szchoiceway.btsuite;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.FileDescriptor;
import java.nio.charset.StandardCharsets;

/**
 * Se poartă ca BTService pentru launcher: citește tipul doar la creare și, cu
 * BC6, ține /dev/goc_serial și trimite DC + DY la 5 s, deci stiva falsă le vede
 * ca FORBIDDEN. Cu tipul 6 nu atinge pty-ul.
 */
public class BTService extends Service {
    private static final String TAG = "FakeBTService";
    private volatile boolean running;
    private FileDescriptor fd;

    @Override
    public void onCreate() {
        String type = SysVarProvider.type(this);
        Log.i(TAG, "onCreate tip=" + type);
        if ("6".equals(type)) return;
        running = true;
        new Thread(this::hold).start();
    }

    private void hold() {
        try {
            fd = Os.open("/dev/goc_serial", OsConstants.O_RDWR | OsConstants.O_NOCTTY, 0);
            Log.i(TAG, "tin /dev/goc_serial");
            send("AT#DC\r\n");
            while (running) {
                send("AT#DY\r\n");
                Thread.sleep(5000);
            }
            Os.close(fd);
            Log.i(TAG, "am eliberat /dev/goc_serial");
        } catch (Exception e) {
            Log.w(TAG, "pty", e);
        }
    }

    private void send(String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        Os.write(fd, bytes, 0, bytes.length);
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy");
        running = false;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new Binder();
    }
}
