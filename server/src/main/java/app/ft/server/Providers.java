package app.ft.server;

import android.content.AttributionSource;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class Providers {
    private Providers() {
    }

    public static Bundle call(String authority, String method, Bundle extras) throws Exception {
        Object manager = activityManager();
        IBinder token = new Binder();
        Object holder = external(manager, authority, token);
        boolean viaExternal = holder != null;
        if (holder == null) {
            holder = plain(manager, authority);
        }
        if (holder == null) {
            throw new IllegalStateException("the system would not hand over the FT provider");
        }
        Object provider = read(holder, "provider");
        if (provider == null) {
            throw new IllegalStateException("the FT provider is not running");
        }
        try {
            return invokeCall(provider, authority, method, extras);
        } finally {
            if (viaExternal) {
                release(manager, authority, token);
            }
        }
    }

    private static Object activityManager() throws Exception {
        try {
            Class<?> type = Class.forName("android.app.ActivityManager");
            Method getService = type.getDeclaredMethod("getService");
            getService.setAccessible(true);
            return getService.invoke(null);
        } catch (Throwable ignored) {
            Class<?> type = Class.forName("android.app.ActivityManagerNative");
            Method getDefault = type.getDeclaredMethod("getDefault");
            getDefault.setAccessible(true);
            return getDefault.invoke(null);
        }
    }

    private static Object external(Object manager, String authority, IBinder token) {
        for (Method method : manager.getClass().getMethods()) {
            if (!method.getName().equals("getContentProviderExternal")) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            try {
                method.setAccessible(true);
                if (types.length == 4) {
                    return method.invoke(manager, authority, userId(), token, "ft");
                }
                if (types.length == 3) {
                    return method.invoke(manager, authority, userId(), token);
                }
            } catch (Throwable t) {
                Log.w("getContentProviderExternal did not work: " + t);
            }
        }
        return null;
    }

    private static Object plain(Object manager, String authority) {
        for (Method method : manager.getClass().getMethods()) {
            if (!method.getName().equals("getContentProvider")) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            try {
                method.setAccessible(true);
                if (types.length == 5) {
                    return method.invoke(manager, null, Sys.SHELL_PACKAGE, authority, userId(), true);
                }
                if (types.length == 4) {
                    return method.invoke(manager, null, authority, userId(), true);
                }
            } catch (Throwable t) {
                Log.w("getContentProvider did not work: " + t);
            }
        }
        return null;
    }

    private static void release(Object manager, String authority, IBinder token) {
        for (Method method : manager.getClass().getMethods()) {
            if (!method.getName().equals("removeContentProviderExternal")) {
                continue;
            }
            try {
                method.setAccessible(true);
                method.invoke(manager, authority, token);
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    private static Bundle invokeCall(Object provider, String authority, String method, Bundle extras) throws Exception {
        for (Method candidate : candidates(provider)) {
            Class<?>[] types = candidate.getParameterTypes();
            candidate.setAccessible(true);
            if (Build.VERSION.SDK_INT >= 31 && types.length == 5 && types[0] == AttributionSource.class) {
                AttributionSource source = new AttributionSource.Builder(Process.myUid())
                    .setPackageName(Sys.SHELL_PACKAGE)
                    .build();
                return (Bundle) candidate.invoke(provider, source, authority, method, null, extras);
            }
            if (types.length == 6 && types[0] == String.class) {
                return (Bundle) candidate.invoke(provider, Sys.SHELL_PACKAGE, null, authority, method, null, extras);
            }
            if (types.length == 5 && types[0] == String.class) {
                return (Bundle) candidate.invoke(provider, Sys.SHELL_PACKAGE, authority, method, null, extras);
            }
        }
        throw new IllegalStateException("no usable provider call method on this Android version");
    }

    private static java.util.List<Method> candidates(Object provider) {
        java.util.List<Method> found = new java.util.ArrayList<>();
        for (Class<?> face : provider.getClass().getInterfaces()) {
            for (Method method : face.getDeclaredMethods()) {
                if (method.getName().equals("call")) {
                    found.add(method);
                }
            }
        }
        for (Method method : provider.getClass().getMethods()) {
            if (method.getName().equals("call")) {
                found.add(method);
            }
        }
        found.sort((a, b) -> Integer.compare(b.getParameterTypes().length, a.getParameterTypes().length));
        return found;
    }

    private static int userId() {
        try {
            Method myUserId = Class.forName("android.os.UserHandle").getDeclaredMethod("myUserId");
            myUserId.setAccessible(true);
            return (int) myUserId.invoke(null);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static Object read(Object holder, String name) throws Exception {
        Field field = holder.getClass().getField(name);
        field.setAccessible(true);
        return field.get(holder);
    }
}
