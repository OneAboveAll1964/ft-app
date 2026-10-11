package app.ft.server;

import android.content.Context;
import android.os.Looper;

import java.lang.reflect.Method;

public final class Sys {
    private static Context context;

    private Sys() {
    }

    public static synchronized Context context() {
        if (context != null) {
            return context;
        }
        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper();
        }
        context = viaSystemMain();
        if (context == null) {
            context = viaCurrentThread();
        }
        if (context == null) {
            throw new IllegalStateException("no system context available to the helper");
        }
        beShell(context);
        return context;
    }

    public static final String SHELL_PACKAGE = "com.android.shell";

    private static void beShell(Context target) {
        if (android.os.Build.VERSION.SDK_INT >= 31 && setAttributionSource(target)) {
            return;
        }
        setString(target, "mOpPackageName");
        setString(target, "mBasePackageName");
    }

    private static boolean setAttributionSource(Context target) {
        try {
            android.content.AttributionSource source = new android.content.AttributionSource.Builder(android.os.Process.myUid())
                .setPackageName(SHELL_PACKAGE)
                .build();
            java.lang.reflect.Field field = field(target.getClass(), "mAttributionSource");
            if (field == null) {
                return false;
            }
            field.setAccessible(true);
            field.set(target, source);
            Log.i("helper identifies as " + SHELL_PACKAGE);
            return true;
        } catch (Throwable t) {
            Log.w("could not set the helper identity: " + t);
            return false;
        }
    }

    private static void setString(Context target, String name) {
        try {
            java.lang.reflect.Field field = field(target.getClass(), name);
            if (field == null) {
                return;
            }
            field.setAccessible(true);
            field.set(target, SHELL_PACKAGE);
        } catch (Throwable t) {
            Log.w("could not set " + name + ": " + t);
        }
    }

    private static java.lang.reflect.Field field(Class<?> type, String name) {
        Class<?> at = type;
        while (at != null) {
            try {
                return at.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                at = at.getSuperclass();
            }
        }
        return null;
    }

    private static Context viaSystemMain() {
        try {
            Class<?> thread = Class.forName("android.app.ActivityThread");
            Method systemMain = thread.getDeclaredMethod("systemMain");
            systemMain.setAccessible(true);
            Object instance = systemMain.invoke(null);
            Method systemContext = thread.getDeclaredMethod("getSystemContext");
            systemContext.setAccessible(true);
            Context found = (Context) systemContext.invoke(instance);
            if (found != null) {
                Log.i("system context from ActivityThread.systemMain");
            }
            return found;
        } catch (Throwable t) {
            Log.w("ActivityThread.systemMain did not work: " + t);
            return null;
        }
    }

    private static Context viaCurrentThread() {
        try {
            Class<?> thread = Class.forName("android.app.ActivityThread");
            Method current = thread.getDeclaredMethod("currentActivityThread");
            current.setAccessible(true);
            Object instance = current.invoke(null);
            if (instance == null) {
                Method attach = thread.getDeclaredMethod("attach", boolean.class, long.class);
                attach.setAccessible(true);
                instance = thread.getDeclaredConstructor().newInstance();
                attach.invoke(instance, true, 0L);
            }
            Method systemContext = thread.getDeclaredMethod("getSystemContext");
            systemContext.setAccessible(true);
            Context found = (Context) systemContext.invoke(instance);
            if (found != null) {
                Log.i("system context from ActivityThread.currentActivityThread");
            }
            return found;
        } catch (Throwable t) {
            Log.w("ActivityThread.currentActivityThread did not work: " + t);
            return null;
        }
    }
}
