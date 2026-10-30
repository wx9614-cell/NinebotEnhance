package dev.ichinomiya.ninebotenhance.hook;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

/** Conservative switch shared by the module UI and the injected host process. */
public final class OfficialCompatibility {
    public static final String AUTHORITY = "dev.ichinomiya.ninebotenhance.compatibility";
    public static final String METHOD_READ = "read";
    public static final String KEY_ENABLED = "enabled";
    private static final String PREFS = "official_compatibility";
    private OfficialCompatibility() {}

    public static boolean enabled() {
        Context context = currentApplication();
        if (context == null) return true;
        if ("dev.ichinomiya.ninebotenhance".equals(context.getPackageName())) return enabled(context);
        try {
            Bundle result = context.getContentResolver().call(Uri.parse("content://" + AUTHORITY), METHOD_READ, null, null);
            return result == null || result.getBoolean(KEY_ENABLED, true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static boolean enabled(Context context) {
        try {
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static boolean setEnabled(Context context, boolean value) {
        try {
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_ENABLED, value).commit();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Context currentApplication() {
        try {
            Class<?> type = Class.forName("android.app.ActivityThread");
            Object thread = type.getMethod("currentActivityThread").invoke(null);
            if (thread == null) return null;
            Object app = type.getMethod("getApplication").invoke(thread);
            return app instanceof Context ? ((Context) app).getApplicationContext() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
