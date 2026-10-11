package app.ft.server;

import android.os.IBinder;

import java.lang.reflect.Method;
import java.util.List;

public final class Power {
    private static final int OFF = 0;
    private static final int NORMAL = 2;

    private Power() {
    }

    public static boolean screen(boolean on) {
        Boolean byPowerMode = setPowerMode(on);
        if (byPowerMode != null && byPowerMode) {
            Log.i("phone screen turned " + (on ? "on" : "off"));
            return true;
        }
        String result = Apps.shell(on ? "svc power stayon false; input keyevent 224" : "input keyevent 223");
        Log.i("phone screen " + (on ? "on" : "off") + " through the shell: " + result);
        return true;
    }

    private static Boolean setPowerMode(boolean on) {
        try {
            Class<?> control = Class.forName("android.view.SurfaceControl");
            Method setMode = null;
            for (Method method : control.getDeclaredMethods()) {
                if (method.getName().equals("setDisplayPowerMode") && method.getParameterTypes().length == 2) {
                    setMode = method;
                    break;
                }
            }
            if (setMode == null) {
                return null;
            }
            setMode.setAccessible(true);
            boolean any = false;
            for (IBinder token : tokens(control)) {
                setMode.invoke(null, token, on ? NORMAL : OFF);
                any = true;
            }
            return any;
        } catch (Throwable t) {
            Log.w("could not set the display power mode: " + t);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<IBinder> tokens(Class<?> control) throws Exception {
        java.util.ArrayList<IBinder> out = new java.util.ArrayList<>();
        try {
            Method ids = control.getDeclaredMethod("getPhysicalDisplayIds");
            ids.setAccessible(true);
            Object result = ids.invoke(null);
            Method token = control.getDeclaredMethod("getPhysicalDisplayToken", long.class);
            token.setAccessible(true);
            long[] list = (long[]) result;
            for (long id : list) {
                IBinder found = (IBinder) token.invoke(null, id);
                if (found != null) {
                    out.add(found);
                }
            }
        } catch (Throwable ignored) {
        }
        if (out.isEmpty()) {
            try {
                Method internal = control.getDeclaredMethod("getInternalDisplayToken");
                internal.setAccessible(true);
                IBinder found = (IBinder) internal.invoke(null);
                if (found != null) {
                    out.add(found);
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }
}
