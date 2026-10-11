package app.ft.server;

public final class Log {
    private static final String TAG = "FT/Server";

    private Log() {
    }

    public static void i(String message) {
        android.util.Log.i(TAG, message);
        System.out.println("[ft-server] " + message);
    }

    public static void w(String message) {
        android.util.Log.w(TAG, message);
        System.out.println("[ft-server] WARN " + message);
    }

    public static void e(String message, Throwable error) {
        android.util.Log.e(TAG, message, error);
        System.out.println("[ft-server] ERROR " + message + ": " + error);
        error.printStackTrace(System.out);
    }
}
