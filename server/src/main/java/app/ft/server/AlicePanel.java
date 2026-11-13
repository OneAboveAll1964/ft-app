package app.ft.server;

import android.content.ComponentName;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Owns a temporary assistant display; the watcher only reparents newly created Yandex tasks. */
public final class AlicePanel implements AutoCloseable {
    private static final String[] PACKAGES = {"com.yandex.searchapp", "ru.yandex.searchplugin"};
    private final int displayId;
    private final Set<Integer> previous = new HashSet<>();
    private volatile boolean closed;
    private volatile String state = "starting";
    private Thread watcher;
    private Object service;
    private Method getTasks;

    public AlicePanel(int displayId) {
        this.displayId = displayId;
    }

    public String state() { return state; }

    public boolean start() {
        discoverTasks();
        for (Object task : tasks()) previous.add(number(task, "taskId"));
        for (String pkg : PACKAGES) {
            Apps.ownWindow(pkg);
            String out = Apps.shell("am start --display " + displayId
                + " -a android.intent.action.ASSIST -p " + pkg + " -f 0x18000000");
            String lower = out == null ? "error" : out.toLowerCase(java.util.Locale.ROOT);
            if (!lower.contains("error") && !lower.contains("exception") && !lower.contains("unable to resolve")) {
                Log.i("Alice requested on display " + displayId + ", package=" + pkg);
                watcher = new Thread(this::watch, "ft-alice-" + displayId);
                watcher.setDaemon(true);
                watcher.start();
                return true;
            }
            Log.w("Alice launch refused for " + pkg + ": " + out);
        }
        state = "unavailable";
        return false;
    }

    private void discoverTasks() {
        try {
            service = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
            for (Method method : service.getClass().getMethods()) {
                Class<?>[] p = method.getParameterTypes();
                if (method.getName().equals("getTasks") && p.length >= 1 && p.length <= 4 && p[0] == int.class) {
                    boolean supported = true;
                    for (int i = 1; i < p.length; i++) supported &= p[i] == int.class || p[i] == boolean.class;
                    if (supported && (getTasks == null || p.length > getTasks.getParameterCount())) getTasks = method;
                }
            }
            if (getTasks == null) Log.w("Alice task tracking unavailable on this Android");
        } catch (Throwable t) {
            Log.w("Alice task tracking unavailable: " + t);
        }
    }

    private List<?> tasks() {
        if (getTasks == null) return java.util.Collections.emptyList();
        try {
            Class<?>[] p = getTasks.getParameterTypes();
            Object[] args = new Object[p.length];
            args[0] = 100;
            for (int i = 1; i < p.length; i++) args[i] = p[i] == boolean.class ? (Object) false : -1;
            Object result = getTasks.invoke(service, args);
            if (result instanceof List) return (List<?>) result;
        } catch (Throwable t) {
            Log.w("Alice task query failed: " + t);
            getTasks = null;
        }
        return java.util.Collections.emptyList();
    }

    private static int number(Object task, String field) {
        try { return task.getClass().getField(field).getInt(task); }
        catch (Throwable ignored) { return -1; }
    }

    private static boolean yandex(Object task) {
        for (String field : new String[]{"topActivity", "baseActivity"}) {
            try {
                Object value = task.getClass().getField(field).get(task);
                if (value instanceof ComponentName) {
                    String pkg = ((ComponentName) value).getPackageName();
                    for (String allowed : PACKAGES) if (allowed.equals(pkg)) return true;
                }
            } catch (Throwable ignored) { }
        }
        return false;
    }

    private void watch() {
        long deadline = android.os.SystemClock.elapsedRealtime() + 6000;
        Set<Integer> moved = new HashSet<>();
        while (!closed) {
            boolean visible = false;
            for (Object task : tasks()) {
                int id = number(task, "taskId");
                int display = number(task, "displayId");
                if (display == displayId && yandex(task)) visible = true;
                // Some Yandex versions create a second activity on display 0. Reparent only
                // fresh tasks; a Yandex app already open on the phone is left alone.
                if (display == 0 && id >= 0 && !previous.contains(id) && yandex(task)
                    && android.os.SystemClock.elapsedRealtime() < deadline && moved.add(id)) {
                    synchronized (this) {
                        if (closed) return;
                        try {
                            service.getClass().getMethod("moveRootTaskToDisplay", int.class, int.class).invoke(service, id, displayId);
                            Log.i("Alice task " + id + " moved to display " + displayId);
                        } catch (Throwable t) { Log.w("Alice task move failed: " + t); }
                    }
                }
            }
            if (visible) state = "visible";
            else if (android.os.SystemClock.elapsedRealtime() >= deadline && getTasks != null) {
                state = "not_on_display";
            }
            try { Thread.sleep(250); }
            catch (InterruptedException ignored) { return; }
        }
    }

    @Override public synchronized void close() {
        closed = true;
        if (watcher != null) watcher.interrupt();
        // The display also has DESTROY_CONTENT_ON_REMOVAL: releasing it must not
        // move its assistant activities onto the phone screen.
        for (Object task : tasks()) {
            if (number(task, "displayId") != displayId) continue;
            int id = number(task, "taskId");
            if (id < 0) continue;
            try { service.getClass().getMethod("removeTask", int.class).invoke(service, id); }
            catch (Throwable t) { Log.w("Alice task cleanup failed: " + t); }
        }
        state = "closed";
        Log.i("Alice panel closed on display " + displayId);
    }
}
