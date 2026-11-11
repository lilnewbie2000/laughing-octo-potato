package dev.kiprian.kipautomate;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class LogBook {
    private static final Object LOCK = new Object();
    private static final int MAX_BYTES = 250_000;
    private LogBook() {}

    public static void write(Context context, String message) {
        synchronized (LOCK) {
            try {
                File file = new File(context.getFilesDir(), "automation.log");
                if (file.length() > MAX_BYTES) {
                    byte[] data = readBytes(file);
                    int keep = Math.min(data.length, MAX_BYTES / 2);
                    try (FileOutputStream out = new FileOutputStream(file, false)) {
                        out.write(data, data.length - keep, keep);
                    }
                }
                String when = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date());
                try (FileOutputStream out = new FileOutputStream(file, true)) {
                    out.write((when + "  " + message + "\n").getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) { }
        }
    }

    public static String read(Context context) {
        synchronized (LOCK) {
            try { return new String(readBytes(new File(context.getFilesDir(), "automation.log")), StandardCharsets.UTF_8); }
            catch (Exception ignored) { return "Журнал пока пуст."; }
        }
    }

    public static void clear(Context context) {
        synchronized (LOCK) { new File(context.getFilesDir(), "automation.log").delete(); }
    }

    private static byte[] readBytes(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }
}
