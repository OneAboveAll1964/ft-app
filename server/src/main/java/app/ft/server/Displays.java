package app.ft.server;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.view.Surface;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Displays {
    private static final int PUBLIC = 1;
    private static final int PRESENTATION = 1 << 1;
    private static final int OWN_CONTENT_ONLY = 1 << 3;
    private static final int SUPPORTS_TOUCH = 1 << 6;
    private static final int TRUSTED = 1 << 10;
    private static final int OWN_DISPLAY_GROUP = 1 << 11;
    private static final int ALWAYS_UNLOCKED = 1 << 12;
    private static final int OWN_FOCUS = 1 << 14;

    public static final class Pane {
        public final String name;
        public final VirtualDisplay display;
        public int width;
        public int height;
        public String packageName;
        public boolean fresh;

        Pane(String name, VirtualDisplay display, int width, int height) {
            this.name = name;
            this.display = display;
            this.width = width;
            this.height = height;
        }

        public int id() {
            return display.getDisplay().getDisplayId();
        }
    }

    private final Map<String, Pane> panes = new LinkedHashMap<>();

    public synchronized Pane pane(String name) {
        return panes.get(name);
    }

    public synchronized Pane open(String name, Surface surface, int width, int height, int dpi) {
        Pane existing = panes.get(name);
        if (existing != null) {
            existing.fresh = false;
            existing.display.setSurface(surface);
            if (existing.width != width || existing.height != height) {
                existing.display.resize(width, height, dpi);
                existing.width = width;
                existing.height = height;
                Log.i("pane " + name + " now " + width + "x" + height + " on display " + existing.id());
            } else {
                Log.i("pane " + name + " is back on screen, still showing " + existing.packageName);
            }
            return existing;
        }
        VirtualDisplay display = create(name, surface, width, height, dpi);
        if (display == null) {
            return null;
        }
        Pane pane = new Pane(name, display, width, height);
        pane.fresh = true;
        holdStill(pane.id());
        panes.put(name, pane);
        Log.i("pane " + name + " opened as display " + pane.id() + " at " + width + "x" + height);
        return pane;
    }

    private static void holdStill(int id) {
        String d = " -d " + id;
        String out = Apps.shell("cmd window fixed-to-user-rotation" + d + " enabled; "
            + "cmd window user-rotation" + d + " lock 0; "
            + "cmd window set-ignore-orientation-request" + d + " true");
        Log.i("display " + id + " stays the way it was made, apps cannot turn it" + (out == null || out.isEmpty() ? "" : " (" + out.replace('\n', ' ') + ")"));
    }

    private VirtualDisplay create(String name, Surface surface, int width, int height, int dpi) {
        DisplayManager manager = displayManager();
        if (manager == null) {
            Log.w("no display manager");
            return null;
        }
        int[] attempts = {
            PUBLIC | OWN_CONTENT_ONLY | PRESENTATION | SUPPORTS_TOUCH | TRUSTED | OWN_DISPLAY_GROUP | ALWAYS_UNLOCKED | OWN_FOCUS,
            PUBLIC | OWN_CONTENT_ONLY | PRESENTATION | SUPPORTS_TOUCH | TRUSTED | OWN_DISPLAY_GROUP,
            PUBLIC | OWN_CONTENT_ONLY | PRESENTATION | SUPPORTS_TOUCH | TRUSTED,
            PUBLIC | OWN_CONTENT_ONLY | PRESENTATION | SUPPORTS_TOUCH,
            PUBLIC | OWN_CONTENT_ONLY | PRESENTATION
        };
        Throwable last = null;
        for (int flags : attempts) {
            try {
                VirtualDisplay display = manager.createVirtualDisplay("ft-" + name, width, height, dpi, surface, flags);
                if (display != null) {
                    Log.i("display made with flags 0x" + Integer.toHexString(flags));
                    return display;
                }
            } catch (Throwable t) {
                last = t;
            }
        }
        Log.e("could not make a display for " + name, last == null ? new IllegalStateException("refused") : last);
        return null;
    }

    private static DisplayManager made;

    private static synchronized DisplayManager displayManager() {
        if (made != null) {
            return made;
        }
        try {
            java.lang.reflect.Constructor<DisplayManager> ctor = DisplayManager.class.getDeclaredConstructor(Context.class);
            ctor.setAccessible(true);
            made = ctor.newInstance(FakeContext.get());
            Log.i("display manager built for " + Sys.SHELL_PACKAGE);
            return made;
        } catch (Throwable t) {
            Log.w("falling back to the plain display manager: " + t);
            made = (DisplayManager) Sys.context().getSystemService(Context.DISPLAY_SERVICE);
            return made;
        }
    }

    public synchronized boolean detach(String name) {
        Pane pane = panes.get(name);
        if (pane == null) {
            return false;
        }
        try {
            pane.display.setSurface(null);
        } catch (Throwable ignored) {
        }
        Log.i("pane " + name + " is off screen, " + pane.packageName + " keeps running");
        return true;
    }

    public synchronized void close(String name) {
        Pane pane = panes.remove(name);
        if (pane == null) {
            return;
        }
        try {
            pane.display.release();
        } catch (Throwable ignored) {
        }
        Log.i("pane " + name + " closed");
    }

    public synchronized void closeAll() {
        for (Pane pane : panes.values()) {
            try {
                pane.display.release();
            } catch (Throwable ignored) {
            }
        }
        panes.clear();
    }
}
