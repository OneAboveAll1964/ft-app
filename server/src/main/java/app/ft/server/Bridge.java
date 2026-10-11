package app.ft.server;

import android.os.Bundle;
import android.os.IBinder;

public final class Bridge {
    private static final String AUTHORITY = "app.ft.supercast";
    private static final String GET_CLIENT = "getClient";

    private final String token;
    private IFtClient client;

    public Bridge(String token) {
        this.token = token;
    }

    public IFtClient client() {
        return client;
    }

    public boolean connect(IFtServer server) {
        try {
            Bundle extras = new Bundle();
            extras.putString("token", token);
            Bundle answer = Providers.call(AUTHORITY, GET_CLIENT, extras);
            if (answer == null) {
                Log.w("FT refused the bridge (wrong token, or FT is not running)");
                return false;
            }
            IBinder binder = answer.getBinder("client");
            if (binder == null) {
                Log.w("FT answered without a bridge");
                return false;
            }
            client = IFtClient.Stub.asInterface(binder);
            client.onServer(server, token);
            Log.i("connected to FT");
            return true;
        } catch (Throwable t) {
            Throwable cause = t;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            Log.e("could not reach FT", cause);
            return false;
        }
    }

    public void event(String name, Bundle extra) {
        IFtClient target = client;
        if (target == null) {
            return;
        }
        Bundle event = extra == null ? new Bundle() : extra;
        event.putString("event", name);
        try {
            target.onEvent(event);
        } catch (Throwable t) {
            Log.w("could not tell FT about " + name + ": " + t);
        }
    }
}
