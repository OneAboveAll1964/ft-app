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
    private Apps() {
    }

    public static boolean launch(String packageName, int displayId) {
        String component = component(packageName);
        String target = component != null ? "-n " + component : "-p " + packageName;
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

    public static Bundle list() {
        Bundle answer = new Bundle();
        ArrayList<String> packages = new ArrayList<>();
        ArrayList<String> labels = new ArrayList<>();
        try {
            PackageManager manager = Sys.context().getPackageManager();
            Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo info : manager.queryIntentActivities(intent, 0)) {
                ApplicationInfo application = info.activityInfo.applicationInfo;
                if (packages.contains(application.packageName)) {
                    continue;
                }
                packages.add(application.packageName);
                labels.add(String.valueOf(manager.getApplicationLabel(application)));
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
