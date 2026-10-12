package app.ft.server;

import android.media.ImageReader;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

public final class Commands {
    private final String token;
    private final Bridge bridge;
    private final Displays displays = new Displays();
    private final java.util.HashMap<String, Long> gestures = new java.util.HashMap<>();

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

    public void shutdown() {
        try {
            displays.closeAll();
        } catch (Throwable ignored) {
        }
        try {
            Power.screen(true);
            Power.keepAwake(false);
        } catch (Throwable ignored) {
        }
    }

    public void watch(Bridge bridge) {
        IFtClient client = bridge.client();
        if (client == null) {
            return;
        }
        try {
            client.asBinder().linkToDeath(() -> {
                Log.w("FT went away, shutting the helper down");
                shutdown();
                System.exit(0);
            }, 0);
        } catch (Throwable t) {
            Log.w("could not watch FT: " + t);
        }
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
        try {
            return run(what, request, surface);
        } catch (Throwable t) {
            Log.e("command " + what + " failed", t);
            return null;
        }
    }

    private Bundle run(String what, Bundle request, Surface surface) {
        Bundle answer = new Bundle();
        switch (what) {
            case "ping": {
                answer.putString("pong", "ft");
                answer.putBoolean("input", Input.ready());
                return answer;
            }
            case "open": {
                String name = request.getString("name", "pane");
                int width = request.getInt("width", 1280);
                int height = request.getInt("height", 720);
                int dpi = request.getInt("dpi", 200);
                if (surface == null) {
                    Log.w("open needs a surface");
                    return null;
                }
                Displays.Pane pane = displays.open(name, surface, width, height, dpi);
                if (pane == null) {
                    return null;
                }
                String packageName = request.getString("package");
                if (packageName != null && !packageName.isEmpty()) {
                    boolean already = !pane.fresh && packageName.equals(pane.packageName);
                    pane.packageName = packageName;
                    if (!already) {
                        Apps.launch(packageName, pane.id());
                    }
                }
                answer.putInt("display", pane.id());
                return answer;
            }
            case "close": {
                String name = request.getString("name", "pane");
                displays.close(name);
                gestures.remove(name);
                return answer;
            }
            case "detach": {
                answer.putBoolean("ok", displays.detach(request.getString("name", "pane")));
                return answer;
            }
            case "swap": {
                displays.swap(request.getString("first", ""), request.getString("second", ""));
                return answer;
            }
            case "launch": {
                Displays.Pane pane = displays.pane(request.getString("name", "pane"));
                String packageName = request.getString("package");
                if (pane == null || packageName == null) {
                    return null;
                }
                pane.packageName = packageName;
                answer.putBoolean("ok", Apps.launch(packageName, pane.id()));
                return answer;
            }
            case "touch": {
                Displays.Pane pane = displays.pane(request.getString("name", "pane"));
                if (pane == null) {
                    return null;
                }
                String name = request.getString("name", "pane");
                int action = request.getInt("action", MotionEvent.ACTION_DOWN);
                float x = request.getFloat("x", 0f) / Math.max(1, request.getInt("from", pane.width)) * pane.width;
                float y = request.getFloat("y", 0f) / Math.max(1, request.getInt("fromHeight", pane.height)) * pane.height;
                long now = android.os.SystemClock.uptimeMillis();
                if (action == MotionEvent.ACTION_DOWN) {
                    gestures.put(name, now);
                }
                Long started = gestures.get(name);
                if (started == null) {
                    if (action != MotionEvent.ACTION_DOWN) {
                        return answer;
                    }
                    started = now;
                }
                boolean sent = Input.touch(pane.id(), action, x, y, started);
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    gestures.remove(name);
                }
                answer.putBoolean("ok", sent);
                return answer;
            }
            case "key": {
                Displays.Pane pane = displays.pane(request.getString("name", "pane"));
                if (pane == null) {
                    return null;
                }
                int code = request.getInt("key", 0);
                Input.key(pane.id(), KeyEvent.ACTION_DOWN, code);
                Input.key(pane.id(), KeyEvent.ACTION_UP, code);
                answer.putBoolean("ok", true);
                return answer;
            }
            case "apps":
                return Apps.list();
            case "screen": {
                boolean on = request.getBoolean("on", true);
                answer.putBoolean("ok", Power.screen(on));
                return answer;
            }
            case "awake": {
                boolean on = request.getBoolean("on", true);
                if (on && request.getBoolean("wake", false)) {
                    Power.wakeUp();
                }
                answer.putBoolean("ok", Power.keepAwake(on));
                return answer;
            }
            case "stop": {
                displays.closeAll();
                gestures.clear();
                Power.screen(true);
                Power.keepAwake(false);
                return answer;
            }
            case "selftest": {
                return selftest(request);
            }
            default:
                Log.w("unknown command " + what);
                return null;
        }
    }

    private Bundle selftest(Bundle request) {
        int width = request.getInt("width", 1280);
        int height = request.getInt("height", 720);
        String packageName = request.getString("package", "com.android.settings");
        if (request.getBoolean("wake", false)) {
            Power.wakeUp();
            Power.keepAwake(true);
        }
        ImageReader reader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2);
        Displays.Pane pane = displays.open("selftest", reader.getSurface(), width, height, 200);
        Bundle answer = new Bundle();
        if (pane == null) {
            answer.putBoolean("ok", false);
            return answer;
        }
        answer.putInt("display", pane.id());
        boolean launched = Apps.launch(packageName, pane.id());
        answer.putBoolean("launched", launched);
        answer.putBoolean("input", Input.touch(pane.id(), MotionEvent.ACTION_DOWN, width / 2f, height / 2f, 0)
            && Input.touch(pane.id(), MotionEvent.ACTION_UP, width / 2f, height / 2f, 0));
        answer.putBoolean("ok", true);
        String shot = request.getString("shot");
        if (shot != null) {
            answer.putBoolean("drawn", capture(reader, shot));
        }
        return answer;
    }

    private boolean capture(ImageReader reader, String path) {
        for (int attempt = 0; attempt < 40; attempt++) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException ignored) {
                return false;
            }
            android.media.Image image = reader.acquireLatestImage();
            if (image == null) {
                continue;
            }
            try {
                android.media.Image.Plane plane = image.getPlanes()[0];
                int stride = plane.getRowStride() / 4;
                android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                    stride, image.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
                bitmap.copyPixelsFromBuffer(plane.getBuffer());
                java.io.FileOutputStream out = new java.io.FileOutputStream(path);
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, out);
                out.close();
                Log.i("captured a frame from the hidden display to " + path);
                return true;
            } catch (Throwable t) {
                Log.w("could not capture: " + t);
                return false;
            } finally {
                image.close();
            }
        }
        Log.w("no frame arrived from the hidden display");
        return false;
    }
}
