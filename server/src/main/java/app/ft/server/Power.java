package app.ft.server;

import android.content.Context;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.List;

public final class Power {
    private static final int OFF = 0;
    private static final int NORMAL = 2;
    private static final int SCREEN_BRIGHT = 10;
    private static final int ACQUIRE_CAUSES_WAKEUP = 0x10000000;

    private static PowerManager.WakeLock awake;
    private static boolean wantDark;
    private static Thread guard;

    private Power() {
    }

    public static synchronized boolean keepAwake(boolean on) {
        try {
            if (on) {
                if (awake == null) {
                    PowerManager pm = (PowerManager) Sys.context().getSystemService(Context.POWER_SERVICE);
                    if (pm == null) {
                        return false;
                    }
                    awake = pm.newWakeLock(SCREEN_BRIGHT | ACQUIRE_CAUSES_WAKEUP, "FT:panes");
                    awake.setReferenceCounted(false);
                }
                if (!awake.isHeld()) {
                    awake.acquire();
                    Log.i("holding the phone awake while apps run on the car");
                }
                return true;
            }
            if (awake != null && awake.isHeld()) {
                awake.release();
                Log.i("letting the phone sleep again");
            }
            return true;
        } catch (Throwable t) {
            Log.w("could not hold the phone awake: " + t);
            return false;
        }
    }

    public static boolean wakeUp() {
        try {
            PowerManager pm = (PowerManager) Sys.context().getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                return false;
            }
            for (Method method : PowerManager.class.getMethods()) {
                if (!method.getName().equals("wakeUp")) {
                    continue;
                }
                Class<?>[] types = method.getParameterTypes();
                method.setAccessible(true);
                long now = SystemClock.uptimeMillis();
                if (types.length == 1) {
                    method.invoke(pm, now);
                    return true;
                }
                if (types.length == 3 && types[1] == int.class) {
                    method.invoke(pm, now, 0, "FT");
                    return true;
                }
                if (types.length == 2 && types[1] == String.class) {
                    method.invoke(pm, now, "FT");
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.w("could not wake the phone: " + t);
        }
        return false;
    }

    public static synchronized boolean screen(boolean on) {
        wantDark = !on;
        if (on) {
            Boolean back = setPowerMode(true);
            Log.i("phone screen turned on" + (back == null ? " (not through the display, asking the system)" : ""));
            if (back == null) {
                Apps.shell("input keyevent 224");
            }
            return true;
        }
        keepAwake(true);
        Boolean dark = setPowerMode(false);
        if (dark == null || !dark) {
            Log.w("this phone will not let FT darken the screen, leaving it on");
            wantDark = false;
            return false;
        }
        Log.i("phone screen turned off while apps run on the car");
        startGuard();
        return true;
    }

    private static void startGuard() {
        if (guard != null && guard.isAlive()) {
            return;
        }
        guard = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException stop) {
                    return;
                }
                synchronized (Power.class) {
                    if (!wantDark) {
                        return;
                    }
                    setPowerMode(false);
                }
            }
        });
        guard.setDaemon(true);
        guard.setName("ft-screen-guard");
        guard.start();
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
