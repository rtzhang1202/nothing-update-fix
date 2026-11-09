package io.github.rtzhang.nothingupdatefix;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;

/**
 * Tiny exported provider used as a one-way status channel: hooks running inside
 * com.android.settings / com.google.android.gms append log lines here so the
 * module app can show "hook hits" in its status pane. Best effort only.
 */
public class StatusProvider extends ContentProvider {

    private static final String FILE_NAME = "status.log";
    private static final long MAX_BYTES = 128 * 1024;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        return handleCall(method, arg);
    }

    @Override
    public Bundle call(String authority, String method, String arg, Bundle extras) {
        return handleCall(method, arg);
    }

    private Bundle handleCall(String method, String arg) {
        try {
            if ("log".equals(method) && arg != null && getContext() != null) {
                File f = new File(getContext().getFilesDir(), FILE_NAME);
                if (f.exists() && f.length() > MAX_BYTES) {
                    trim(f);
                }
                FileOutputStream os = new FileOutputStream(f, true);
                try {
                    os.write((arg + "\n").getBytes("UTF-8"));
                } finally {
                    os.close();
                }
            }
        } catch (Throwable ignored) {
        }
        return Bundle.EMPTY;
    }

    private static void trim(File f) {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "rw");
            long len = raf.length();
            raf.seek(len / 2);
            byte[] rest = new byte[(int) (len - len / 2)];
            raf.readFully(rest);
            raf.setLength(0);
            raf.seek(0);
            raf.write(rest);
        } catch (Throwable ignored) {
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        return 0;
    }
}
