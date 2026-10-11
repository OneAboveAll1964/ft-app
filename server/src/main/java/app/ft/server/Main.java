package app.ft.server;

import android.os.Bundle;
import android.os.Looper;
import android.view.Surface;

import java.util.HashMap;
import java.util.Map;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> Log.e("helper crashed on " + thread.getName(), error));
        try {
            Map<String, String> options = options(args);
            String token = options.get("token");
            if (token == null || token.isEmpty()) {
                Log.w("no token given, refusing to start");
                return;
            }
            Sys.context();
            Bridge bridge = new Bridge(token);
            Commands commands = new Commands(token, bridge);
            if (!bridge.connect(commands.binder())) {
                return;
            }
            Log.i("ft-server ready");
            Looper.loop();
        } catch (Throwable t) {
            Log.e("helper could not start", t);
        }
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> out = new HashMap<>();
        for (String arg : args) {
            int split = arg.indexOf('=');
            if (split > 0) {
                out.put(arg.substring(0, split), arg.substring(split + 1));
            }
        }
        return out;
    }
}
