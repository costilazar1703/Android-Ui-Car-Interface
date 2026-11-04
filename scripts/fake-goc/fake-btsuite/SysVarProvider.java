package com.szchoiceway.btsuite;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/** Tabelul cheie → valoare al EventCenter, cu tipul modulului pe BC6, ca pe unitate. */
public class SysVarProvider extends ContentProvider {
    private SharedPreferences prefs() {
        return getContext().getSharedPreferences("sysvar", Context.MODE_PRIVATE);
    }

    static String type(Context context) {
        return context.getSharedPreferences("sysvar", Context.MODE_PRIVATE).getString("Sys_BTDeviceType", "3");
    }

    @Override public boolean onCreate() { return true; }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        MatrixCursor cursor = new MatrixCursor(new String[] {"keyvalue"});
        String key = args[0];
        String fallback = key.equals("Sys_BTDeviceType") ? "3" : null;
        String value = prefs().getString(key, fallback);
        if (value != null) cursor.addRow(new Object[] {value});
        return cursor;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        prefs().edit().putString(args[0], values.getAsString("keyvalue")).commit();
        return 1;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        prefs().edit().putString(values.getAsString("keyname"), values.getAsString("keyvalue")).commit();
        return uri;
    }

    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public String getType(Uri uri) { return null; }
}
