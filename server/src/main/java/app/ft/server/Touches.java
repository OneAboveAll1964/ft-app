package app.ft.server;

import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

public final class Touches {
    private static volatile long last = SystemClock.uptimeMillis();
    private static volatile int watching;
    private static boolean started;
    private static long askedAt;
    private static long systemIdle = -1;
    private static boolean systemWorks = true;

    private Touches() {
    }

    public static synchronized long idleMs() {
        long ours = SystemClock.uptimeMillis() - last;
        if (watching > 0) {
            return ours;
        }
        if (!systemWorks) {
            return -1;
        }
        long now = SystemClock.uptimeMillis();
        if (now - askedAt >= 5000) {
            askedAt = now;
            long seen = systemIdle();
            if (seen < 0) {
                systemWorks = false;
                Log.w("cannot tell when the phone was last used, it stays lit until the power button");
                return -1;
            }
            systemIdle = seen - (SystemClock.uptimeMillis() - now);
        }
        return Math.min(ours, systemIdle + (now - askedAt));
    }

    private static long systemIdle() {
        String out = Apps.shell("dumpsys power");
        if (out == null) {
            return -1;
        }
        int group = out.indexOf("groupId: 0");
        int at = out.indexOf("lastUserActivityTime=", Math.max(group, 0));
        if (at < 0) {
            return -1;
        }
        int open = out.indexOf('(', at);
        int end = out.indexOf(" ms ago)", at);
        int line = out.indexOf('\n', at);
        if (open < 0 || end < 0 || (line >= 0 && end > line)) {
            return -1;
        }
        try {
            return Long.parseLong(out.substring(open + 1, end).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static long sincePoke() {
        return SystemClock.uptimeMillis() - last;
    }

    public static void poke() {
        last = SystemClock.uptimeMillis();
    }

    public static synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        List<String> screens = screens();
        if (screens.isEmpty()) {
            Log.i("found no touch screen to watch, asking the system when the phone was last used instead");
            return;
        }
        for (String path : screens) {
            Thread reader = new Thread(() -> watch(path));
            reader.setDaemon(true);
            reader.setName("ft-touches");
            reader.start();
        }
    }

    private static void watch(String path) {
        byte[] buffer = new byte[4096];
        try (FileInputStream in = new FileInputStream(path)) {
            synchronized (Touches.class) {
                watching++;
            }
            Log.i("watching " + path + " for the phone being used");
            while (in.read(buffer) > 0) {
                last = SystemClock.uptimeMillis();
            }
        } catch (Throwable t) {
            Log.i("cannot watch " + path + " (" + t.getMessage() + "), asking the system when the phone was last used instead");
        } finally {
            synchronized (Touches.class) {
                if (watching > 0) {
                    watching--;
                }
            }
        }
    }

    private static List<String> screens() {
        List<String> out = new ArrayList<>();
        String listing = Apps.shell("getevent -lp");
        if (listing != null) {
            String device = null;
            for (String line : listing.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("add device")) {
                    int at = trimmed.indexOf("/dev/input/");
                    device = at >= 0 ? trimmed.substring(at).trim() : null;
                } else if (device != null && trimmed.startsWith("ABS_MT_POSITION_X")) {
                    out.add(device);
                    device = null;
                }
            }
        }
        if (!out.isEmpty()) {
            return out;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/bus/input/devices"))) {
            String handler = null;
            boolean touch = false;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    if (handler != null && touch) {
                        out.add("/dev/input/" + handler);
                    }
                    handler = null;
                    touch = false;
                } else if (line.startsWith("H: Handlers=")) {
                    for (String part : line.substring(12).trim().split("\\s+")) {
                        if (part.startsWith("event")) {
                            handler = part;
                        }
                    }
                } else if (line.startsWith("B: PROP=")) {
                    try {
                        touch = (Long.parseLong(line.substring(8).trim().split("\\s+")[0], 16) & 0x2) != 0;
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (handler != null && touch) {
                out.add("/dev/input/" + handler);
            }
        } catch (Throwable t) {
            Log.w("cannot list input devices: " + t);
        }
        return out;
    }
}
