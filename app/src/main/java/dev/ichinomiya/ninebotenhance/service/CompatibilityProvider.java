package dev.ichinomiya.ninebotenhance.service;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import dev.ichinomiya.ninebotenhance.hook.OfficialCompatibility;

/** Read-only cross-process access to the module's compatibility switch. */
public final class CompatibilityProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!OfficialCompatibility.METHOD_READ.equals(method)) throw new SecurityException("unsupported method");
        int uid = Binder.getCallingUid();
        String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
        boolean self = uid == android.os.Process.myUid();
        boolean target = false;
        if (packages != null) for (String name : packages) target |= "cn.ninebot.ninebot".equals(name);
        if (!self && !target) throw new SecurityException("caller not allowed");
        Bundle result = new Bundle();
        result.putBoolean(OfficialCompatibility.KEY_ENABLED, OfficialCompatibility.enabled(getContext()));
        return result;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { throw new SecurityException(); }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new SecurityException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new SecurityException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new SecurityException(); }
}
