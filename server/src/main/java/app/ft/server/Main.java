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
            if (options.containsKey("selftest")) {
                Sys.context();
                Commands check = new Commands("self", null);
                Bundle request = new Bundle();
                request.putString("token", "self");
                request.putString("do", "selftest");
                request.putString("package", options.getOrDefault("package", "com.android.settings"));
                if (options.containsKey("shot")) request.putString("shot", options.get("shot"));
                if (options.containsKey("wake")) request.putBoolean("wake", true);
                Bundle answer = check.binder().call(request);
                Log.i("selftest display=" + (answer == null ? "none" : answer.getInt("display", -1))
                    + " launched=" + (answer != null && answer.getBoolean("launched"))
                    + " input=" + (answer != null && answer.getBoolean("input"))
                    + " drawn=" + (answer != null && answer.getBoolean("drawn")));
                Thread.sleep(Long.parseLong(options.getOrDefault("hold", "3000")));
                Log.i("selftest done");
                return;
            }
            String token = options.get("token");
            if (token == null || token.isEmpty()) {
                Log.w("no token given, refusing to start");
                return;
            }
            Sys.context();
            Bridge bridge = new Bridge(token);
            Commands commands = new Commands(token, bridge);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                Log.i("helper going away, giving the phone screen back");
                commands.shutdown();
            }));
            if (!bridge.connect(commands.binder())) {
                return;
            }
            commands.watch(bridge);
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
