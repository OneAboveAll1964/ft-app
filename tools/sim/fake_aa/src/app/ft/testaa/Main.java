package app.ft.testaa;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toolbar;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

public class Main extends Activity {
    static final String START = "Start head unit server";
    static final String STOP = "Stop head unit server";
    static volatile ServerSocket server;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        SharedPreferences prefs = getSharedPreferences("aa", MODE_PRIVATE);
        Intent intent = getIntent();
        if (intent.hasExtra("dev")) prefs.edit().putBoolean("dev", intent.getBooleanExtra("dev", true)).commit();
        if (intent.getBooleanExtra("stop", false)) stopServer();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        Toolbar bar = new Toolbar(this);
        bar.setTitle("Settings");
        root.addView(bar);
        TextView version = new TextView(this);
        version.setText("Version 1.0");
        version.setPadding(48, 48, 48, 48);
        root.addView(version);
        setContentView(root);
        setActionBar(bar);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add("Help and feedback");
        if (getSharedPreferences("aa", MODE_PRIVATE).getBoolean("dev", true)) {
            menu.add("Developer settings");
            menu.add(server == null ? START : STOP);
            menu.add("Quit developer mode");
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        String title = String.valueOf(item.getTitle());
        if (START.equals(title)) startServer();
        if (STOP.equals(title)) stopServer();
        invalidateOptionsMenu();
        return true;
    }

    static synchronized void startServer() {
        if (server != null) return;
        try {
            ServerSocket s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 5277));
            server = s;
            Log.i("TestAA", "head unit server started");
            new Thread(() -> {
                try {
                    while (true) {
                        Socket c = s.accept();
                        c.close();
                    }
                } catch (Exception ignored) {
                }
            }).start();
        } catch (Exception e) {
            Log.w("TestAA", "could not start: " + e);
        }
    }

    static synchronized void stopServer() {
        ServerSocket s = server;
        server = null;
        if (s == null) return;
        try {
            s.close();
        } catch (Exception ignored) {
        }
        Log.i("TestAA", "head unit server stopped");
    }
}
