package app.ft.server;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.os.Process;

public final class FakeContext extends ContextWrapper {
    private static FakeContext instance;

    public static synchronized FakeContext get() {
        if (instance == null) {
            instance = new FakeContext(Sys.context());
        }
        return instance;
    }

    private FakeContext(Context base) {
        super(base);
    }

    @Override
    public String getPackageName() {
        return Sys.SHELL_PACKAGE;
    }

    @Override
    public String getOpPackageName() {
        return Sys.SHELL_PACKAGE;
    }

    @Override
    public AttributionSource getAttributionSource() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new AttributionSource.Builder(Process.myUid())
                .setPackageName(Sys.SHELL_PACKAGE)
                .build();
        }
        return super.getAttributionSource();
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }
}
