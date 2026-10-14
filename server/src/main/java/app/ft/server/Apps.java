package app.ft.server;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public final class Apps {
    private static final String OWN_WINDOW = "ALWAYS_SANDBOX_DISPLAY_APIS";
    private static final java.util.Set<String> ownWindow = new java.util.HashSet<>();
    private static final String RECORD = "/data/local/tmp/ft-car-changes.txt";
    public static volatile boolean leaveSystemAlone;
    private static volatile boolean blind;

    private Apps() {
    }

    public static boolean launch(String packageName, int displayId) {
        ownWindow(packageName);
        String component = component(packageName);
        String target = component != null ? "-n '" + component + "'" : "-p " + packageName;
        String command = "am start --display " + displayId
            + " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "
            + target + " -f 0x10000000";
        String output = shell(command);
        String lower = output == null ? "" : output.toLowerCase();
        boolean ok = output != null
            && !lower.contains("error")
            && !lower.contains("exception")
            && !lower.contains("does not exist");
        if (ok) {
            Log.i("started " + packageName + " on display " + displayId);
        } else {
            Log.w("could not start " + packageName + " on display " + displayId + ": " + output);
        }
        return ok;
    }

    public static boolean stop(String packageName) {
        shell("am force-stop " + packageName);
        return true;
    }

    public static void ownWindow(String packageName) {
        if (leaveSystemAlone || packageName == null || packageName.isEmpty() || OURSELVES.equals(packageName)) {
            return;
        }
        synchronized (ownWindow) {
            if (!ownWindow.add(packageName)) {
                return;
            }
            remember(packageName);
        }
        String out = shell("am compat enable --no-kill " + OWN_WINDOW + " " + packageName);
        if (out != null && out.toLowerCase().contains("enabled")) {
            Log.i(packageName + " now measures its own window, not the whole screen");
        } else {
            Log.w(packageName + " could not be set to measure its own window: " + (out == null ? "no answer" : out.replace('\n', ' ')));
        }
    }

    public static void wholeScreenAgain() {
        java.util.List<String> all;
        synchronized (ownWindow) {
            all = new ArrayList<>(ownWindow);
            ownWindow.clear();
        }
        for (String packageName : all) {
            shell("am compat reset --no-kill " + OWN_WINDOW + " " + packageName);
            Log.i(packageName + " measures the whole screen again");
        }
    }

    static void remember(String line) {
        try (java.io.FileWriter out = new java.io.FileWriter(RECORD, true)) {
            out.write(line + "\n");
        } catch (Throwable t) {
            Log.w("could not note a change for later: " + t);
        }
    }

    static void forget() {
        new java.io.File(RECORD).delete();
    }

    public static void undoLeftovers() {
        java.io.File record = new java.io.File(RECORD);
        if (!record.exists()) {
            return;
        }
        try (BufferedReader in = new BufferedReader(new java.io.FileReader(record))) {
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (line.equals(Displays.BOXES)) {
                    shell("cmd window reset-letterbox-style aspectRatio");
                    Log.i("portrait boxes left over from last time are back to the phone's own shape");
                } else {
                    shell("am compat reset --no-kill " + OWN_WINDOW + " " + line);
                    Log.i(line + " was left measuring its own window last time, now it measures the whole screen again");
                }
            }
        } catch (Throwable t) {
            Log.w("could not read what was left over: " + t);
        }
        record.delete();
    }

    public static List<String> packagesOn(int displayId) {
        List<String> found = new ArrayList<>();
        if (blind) {
            return found;
        }
        try {
            Object service = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
            java.lang.reflect.Method method = null;
            for (String name : new String[]{"getAllRootTaskInfosOnDisplay", "getAllStackInfosOnDisplay"}) {
                try {
                    method = service.getClass().getMethod(name, int.class);
                    break;
                } catch (NoSuchMethodException ignored) {
                }
            }
            if (method == null) {
                blind = true;
                Log.w("cannot see which apps are on the car's screens on this Android");
                return found;
            }
            Object tasks = method.invoke(service, displayId);
            if (tasks instanceof List) {
                for (Object task : (List<?>) tasks) {
                    for (String field : new String[]{"topActivity", "baseActivity"}) {
                        try {
                            Object component = task.getClass().getField(field).get(task);
                            if (component instanceof android.content.ComponentName) {
                                String name = ((android.content.ComponentName) component).getPackageName();
                                if (!found.contains(name)) {
                                    found.add(name);
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        } catch (Throwable t) {
            blind = true;
            Log.w("cannot see which apps are on the car's screens: " + t);
        }
        return found;
    }

    private static String component(String packageName) {
        try {
            PackageManager packages = Sys.context().getPackageManager();
            Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(packageName);
            List<ResolveInfo> found = packages.queryIntentActivities(intent, 0);
            if (found.isEmpty()) {
                return null;
            }
            ResolveInfo first = found.get(0);
            return first.activityInfo.packageName + "/" + first.activityInfo.name;
        } catch (Throwable t) {
            Log.w("could not find the entry point of " + packageName + ": " + t);
            return null;
        }
    }

    private static final String OURSELVES = "app.ft";

    public static Bundle list() {
        Bundle answer = new Bundle();
        ArrayList<String> packages = new ArrayList<>();
        ArrayList<String> labels = new ArrayList<>();
        try {
            PackageManager manager = Sys.context().getPackageManager();
            Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<String[]> rows = new ArrayList<>();
            for (ResolveInfo info : manager.queryIntentActivities(intent, 0)) {
                ApplicationInfo application = info.activityInfo.applicationInfo;
                if (OURSELVES.equals(application.packageName)) {
                    continue;
                }
                boolean seen = false;
                for (String[] row : rows) {
                    if (row[0].equals(application.packageName)) {
                        seen = true;
                        break;
                    }
                }
                if (seen) {
                    continue;
                }
                rows.add(new String[]{application.packageName, String.valueOf(manager.getApplicationLabel(application))});
            }
            java.util.Collections.sort(rows, (a, b) -> a[1].compareToIgnoreCase(b[1]));
            for (String[] row : rows) {
                packages.add(row[0]);
                labels.add(row[1]);
            }
        } catch (Throwable t) {
            Log.w("could not list apps: " + t);
        }
        answer.putStringArrayList("packages", packages);
        answer.putStringArrayList("labels", labels);
        return answer;
    }

    public static String shell(String command) {
        try {
            Process process = new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            process.waitFor();
            return out.toString().trim();
        } catch (Throwable t) {
            Log.w("shell failed: " + command + " -> " + t);
            return null;
        }
    }
}
