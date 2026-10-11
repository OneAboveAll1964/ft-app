package app.ft.server;

import android.content.Context;
import android.os.SystemClock;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;

public final class Input {
    private static final int ASYNC = 0;

    private static Object manager;
    private static Method inject;
    private static Method setDisplay;
    private static boolean looked;

    private Input() {
    }

    public static synchronized boolean ready() {
        if (looked) {
            return inject != null && manager != null;
        }
        looked = true;
        try {
            setDisplay = InputEvent.class.getMethod("setDisplayId", int.class);
            setDisplay.setAccessible(true);
        } catch (Throwable t) {
            Log.w("no setDisplayId on this Android: " + t);
        }
        manager = global();
        if (manager == null) {
            manager = Sys.context().getSystemService(Context.INPUT_SERVICE);
        }
        if (manager == null) {
            Log.w("no input manager");
            return false;
        }
        for (Method method : manager.getClass().getMethods()) {
            if (method.getName().equals("injectInputEvent") && method.getParameterTypes().length == 2) {
                method.setAccessible(true);
                inject = method;
                break;
            }
        }
        if (inject == null) {
            Log.w("no injectInputEvent on " + manager.getClass().getName());
            return false;
        }
        Log.i("input ready through " + manager.getClass().getSimpleName());
        return true;
    }

    private static Object global() {
        try {
            Class<?> type = Class.forName("android.hardware.input.InputManagerGlobal");
            Method getInstance = type.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            return getInstance.invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean touch(int displayId, int action, float x, float y, long downTime) {
        if (!ready()) {
            return false;
        }
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(downTime <= 0 ? now : downTime, now, action, x, y, 0);
        event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        boolean sent = send(event, displayId);
        event.recycle();
        return sent;
    }

    public static boolean key(int displayId, int action, int keyCode) {
        if (!ready()) {
            return false;
        }
        long now = SystemClock.uptimeMillis();
        KeyEvent event = new KeyEvent(now, now, action, keyCode, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, android.view.InputDevice.SOURCE_KEYBOARD);
        return send(event, displayId);
    }

    private static boolean send(InputEvent event, int displayId) {
        try {
            if (setDisplay != null && displayId >= 0) {
                setDisplay.invoke(event, displayId);
            }
            Object result = inject.invoke(manager, event, ASYNC);
            return !(result instanceof Boolean) || (Boolean) result;
        } catch (Throwable t) {
            Log.w("could not inject: " + t);
            return false;
        }
    }
}
