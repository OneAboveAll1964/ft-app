package app.ft.server;

import android.os.Bundle;
import android.view.Surface;

public final class Commands {
    private final String token;
    private final Bridge bridge;

    private final IFtServer stub = new IFtServer.Stub() {
        @Override
        public Bundle call(Bundle request) {
            return handle(request, null);
        }

        @Override
        public Bundle withSurface(Surface surface, Bundle request) {
            return handle(request, surface);
        }
    };

    public Commands(String token, Bridge bridge) {
        this.token = token;
        this.bridge = bridge;
    }

    public IFtServer binder() {
        return stub;
    }

    private Bundle handle(Bundle request, Surface surface) {
        if (request == null || !token.equals(request.getString("token"))) {
            Log.w("a call arrived with the wrong token, ignoring it");
            return null;
        }
        String what = request.getString("do");
        if (what == null) {
            return null;
        }
        Bundle answer = new Bundle();
        switch (what) {
            case "ping":
                answer.putString("pong", "ft");
                return answer;
            default:
                Log.w("unknown command " + what);
                return null;
        }
    }
}
