package app.ft.server;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Display;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class Power {
    private static final int OFF = 0;
    private static final int NORMAL = 2;
    private static final int SCREEN_BRIGHT = 10;
    private static final int ACQUIRE_CAUSES_WAKEUP = 0x10000000;
    private static final int SLEEP_DEVICE_ADMIN = 1;
    private static final int SLEEP_LID_SWITCH = 3;
    private static final int SLEEP_ACCESSIBILITY = 7;
    private static final int SLEEP_INATTENTIVE = 9;
    private static final int SLEEP_DEVICE_FOLD = 13;

    private static PowerManager.WakeLock awake;
    private static boolean managing;
    private static boolean autoDark;
    private static boolean dark;
    private static boolean refused;
    private static boolean guarding;
    private static long timeoutMs = 30_000L;
    private static Thread guard;
    private static Class<?> displayControl;
    private static boolean displayControlTried;

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
        if (on) {
            release();
            return true;
        }
        return manage(true);
    }

    public static synchronized boolean manage(boolean darkenByItself) {
        managing = true;
        autoDark = darkenByItself;
        refused = false;
        keepAwake(true);
        startGuard();
        if (autoDark) {
            return darken("apps run on the car");
        }
        dark = false;
        Touches.poke();
        Log.i("phone screen stays on while apps run, the power button darkens it");
        return true;
    }

    public static synchronized boolean powerKey() {
        if (!managing) {
            return false;
        }
        int reason = lastSleepReason();
        if (reason == SLEEP_DEVICE_ADMIN || reason == SLEEP_LID_SWITCH
            || reason == SLEEP_ACCESSIBILITY || reason == SLEEP_INATTENTIVE || reason == SLEEP_DEVICE_FOLD) {
            if (dark) {
                setPowerMode(false);
                Log.i("the phone went to sleep by itself (reason " + reason + "), its screen stays dark");
            } else {
                darken("the phone going to sleep by itself (reason " + reason + ")");
            }
            return dark;
        }
        if (dark) {
            light("the power button");
        } else {
            darken("the power button");
        }
        return dark;
    }

    private static int lastSleepReason() {
        try {
            PowerManager pm = (PowerManager) Sys.context().getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                return -1;
            }
            Field field = PowerManager.class.getDeclaredField("mService");
            field.setAccessible(true);
            Object service = field.get(pm);
            Method method = Class.forName("android.os.IPowerManager").getMethod("getLastSleepReason");
            method.setAccessible(true);
            return (Integer) method.invoke(service);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void release() {
        boolean was = managing;
        boolean wasDark = dark;
        managing = false;
        dark = false;
        Boolean back = wasDark ? setPowerMode(true) : Boolean.TRUE;
        if (back == null) {
            Apps.shell("input keyevent 224");
        }
        if (was) {
            Log.i("phone screen given back" + (back == null ? " (not through the display, asking the system)" : ""));
        }
    }

    private static void light(String why) {
        dark = false;
        Touches.poke();
        Boolean back = setPowerMode(true);
        if (back == null) {
            Apps.shell("input keyevent 224");
        }
        Log.i("phone screen on for " + why + (autoDark ? ", dark again after " + timeoutMs / 1000 + "s untouched" : ""));
    }

    private static boolean darken(String why) {
        Boolean off = setPowerMode(false);
        if (off == null || !off) {
            if (!refused) {
                Log.w("this phone will not let FT darken the screen, leaving it on");
            }
            refused = true;
            dark = false;
            return false;
        }
        refused = false;
        dark = true;
        Log.i("phone screen dark for " + why + ", the power button brings it back");
        return true;
    }

    private static long screenTimeout() {
        String out = Apps.shell("settings get system screen_off_timeout");
        try {
            long ms = Long.parseLong(out == null ? "" : out.trim());
            return Math.max(15_000L, Math.min(ms, 600_000L));
        } catch (NumberFormatException ignored) {
            return 30_000L;
        }
    }

    private static void startGuard() {
        if (guarding) {
            return;
        }
        guarding = true;
        guard = new Thread(() -> {
            try {
                Touches.start();
                long timeout = screenTimeout();
                synchronized (Power.class) {
                    timeoutMs = timeout;
                }
                while (true) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException stop) {
                        synchronized (Power.class) {
                            guarding = false;
                        }
                        return;
                    }
                    boolean check;
                    long limit;
                    synchronized (Power.class) {
                        if (!managing) {
                            guarding = false;
                            return;
                        }
                        check = autoDark && !dark && !refused;
                        limit = timeoutMs;
                    }
                    long idle = check ? Touches.idleMs(limit) : -1;
                    synchronized (Power.class) {
                        if (!managing) {
                            guarding = false;
                            return;
                        }
                        if (dark) {
                            setPowerMode(false);
                        } else if (autoDark && !refused && idle >= 0 && Math.min(idle, Touches.sincePoke()) > timeoutMs) {
                            darken("the phone going " + timeoutMs / 1000 + "s untouched");
                        }
                    }
                }
            } catch (RuntimeException | Error e) {
                synchronized (Power.class) {
                    guarding = false;
                }
                throw e;
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

    private static List<IBinder> tokens(Class<?> control) {
        List<IBinder> out = new ArrayList<>();
        Long only = mainPanel();
        physical(control, only, out);
        if (out.isEmpty()) {
            Class<?> moved = displayControl();
            if (moved != null) {
                physical(moved, only, out);
            }
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

    private static void physical(Class<?> owner, Long only, List<IBinder> out) {
        try {
            Method ids = owner.getDeclaredMethod("getPhysicalDisplayIds");
            ids.setAccessible(true);
            Method token = owner.getDeclaredMethod("getPhysicalDisplayToken", long.class);
            token.setAccessible(true);
            long[] list = (long[]) ids.invoke(null);
            boolean known = false;
            for (long id : list) {
                if (only != null && id == only) {
                    known = true;
                }
            }
            for (long id : list) {
                if (known && id != only) {
                    continue;
                }
                IBinder found = (IBinder) token.invoke(null, id);
                if (found != null) {
                    out.add(found);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static Long mainPanel() {
        try {
            DisplayManager manager = (DisplayManager) Sys.context().getSystemService(Context.DISPLAY_SERVICE);
            Display main = manager.getDisplay(Display.DEFAULT_DISPLAY);
            Object address = Display.class.getMethod("getAddress").invoke(main);
            return (Long) address.getClass().getMethod("getPhysicalDisplayId").invoke(address);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Class<?> displayControl() {
        if (displayControlTried || Build.VERSION.SDK_INT < 34) {
            return displayControl;
        }
        displayControlTried = true;
        String path = System.getenv("SYSTEMSERVERCLASSPATH");
        if (path == null || path.isEmpty()) {
            path = "/system/framework/services.jar";
        }
        try {
            Class<?> factory = Class.forName("com.android.internal.os.ClassLoaderFactory");
            Method create = factory.getDeclaredMethod("createClassLoader", String.class, String.class, String.class,
                ClassLoader.class, int.class, boolean.class, String.class);
            create.setAccessible(true);
            ClassLoader loader = (ClassLoader) create.invoke(null, path, null, null,
                ClassLoader.getSystemClassLoader(), 0, true, null);
            Class<?> found = loader.loadClass("com.android.server.display.DisplayControl");
            Method load = Runtime.class.getDeclaredMethod("loadLibrary0", Class.class, String.class);
            load.setAccessible(true);
            load.invoke(Runtime.getRuntime(), found, "android_servers");
            displayControl = found;
            Log.i("reaching the phone screen through the display service");
        } catch (Throwable t) {
            Log.w("could not reach the phone screen through the display service: " + t);
        }
        return displayControl;
    }
}
