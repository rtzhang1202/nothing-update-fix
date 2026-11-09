package io.github.rtzhang.nothingupdatefix;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Nothing update fix — LSPosed/Xposed module.
 *
 * Chain A (com.android.settings): restore the missing "更新日志" / "固件信息"
 * entries on the software info page (DeviceSoftwareInfoFragment) which normally
 * come from the dead iota online config.
 *
 * Chain B (com.google.android.gms): intercept Gservices reads so the gOTA update
 * page sees update_title / update_description / update_size (normally served by
 * Google, empty for this device/channel), restoring the changelog + firmware
 * info block that is hidden when UpdateDescription.b is empty.
 *
 * Every hook body is fully try/catch wrapped; nothing may crash the host process.
 */
public class MainHook implements IXposedHookLoadPackage {

    public static final String TAG = "NothingUpdateFix";

    private static final String PKG_SETTINGS = "com.android.settings";
    private static final String PKG_GMS = "com.google.android.gms";

    /** Kept un-obfuscated by Nothing (remote config contract), stable name. */
    private static final String TARGET_FRAGMENT =
            "com.nothing.settings.deviceinfo.aboutphone.DeviceSoftwareInfoFragment";
    private static final String FWINFO_ACTIVITY =
            "com.nothing.settings.softwareinfo.SoftVersionDisplayActivity";

    public static final String MODULE_PKG = "io.github.rtzhang.nothingupdatefix";
    private static final String KEY_CHANGELOG = "nuf_changelog";
    private static final String KEY_FWINFO = "nuf_fwinfo";

    private static final String GSERVICES_AUTHORITY = "com.google.android.gsf.gservices";

    private static volatile Map<String, String> sOverrides;

    // ------------------------------------------------------------------ entry

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            if (PKG_SETTINGS.equals(lpparam.packageName)) {
                hookSettings(lpparam);
            } else if (PKG_GMS.equals(lpparam.packageName)) {
                hookGms(lpparam);
            }
        } catch (Throwable t) {
            log("handleLoadPackage error: " + t);
        }
    }

    // ---------------------------------------------------------------- logging

    public static void log(String msg) {
        try {
            XposedBridge.log(TAG + ": " + msg);
        } catch (Throwable ignored) {
        }
    }

    /** Also report to the module app's status pane (best effort, never throws). */
    public static void log(Context ctx, String msg) {
        log(msg);
        try {
            if (ctx != null) {
                ctx.getContentResolver().call(
                        Uri.parse("content://" + MODULE_PKG + ".status/log"), "log",
                        TAG + ": " + msg, null);
            }
        } catch (Throwable ignored) {
        }
    }

    // ================================================================ chain A

    private void hookSettings(final XC_LoadPackage.LoadPackageParam lpparam) {
        // Route 1: the stable androidx signature. PreferenceFragmentCompat.
        // onCreatePreferences(Bundle, String) may be abstract in the bundled
        // androidx build; if hooking it fails we rely on route 2.
        try {
            Class<?> pfc = XposedHelpers.findClass(
                    "androidx.preference.PreferenceFragmentCompat", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(pfc, "onCreatePreferences",
                    Bundle.class, String.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                injectPreferences(param, lpparam.classLoader, "route1");
                            } catch (Throwable t) {
                                log("route1 inject error: " + t);
                            }
                        }
                    });
            log("chainA route1 hooked: androidx.preference.PreferenceFragmentCompat#onCreatePreferences");
        } catch (Throwable t) {
            log("chainA route1 unavailable (" + t + "), falling back to direct fragment hook");
        }

        // Route 2: hook the concrete onCreatePreferences(Bundle, String) on the
        // target fragment itself (most-derived declaration in its hierarchy).
        try {
            Class<?> frag = XposedHelpers.findClass(TARGET_FRAGMENT, lpparam.classLoader);
            Method target = findConcreteMethod(frag, "onCreatePreferences",
                    new Class<?>[]{Bundle.class, String.class});
            if (target != null) {
                XposedBridge.hookMethod(target, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            injectPreferences(param, lpparam.classLoader, "route2");
                        } catch (Throwable t) {
                            log("route2 inject error: " + t);
                        }
                    }
                });
                log("chainA route2 hooked: " + target.getDeclaringClass().getName()
                        + "#onCreatePreferences");
            } else {
                log("chainA route2: no concrete onCreatePreferences(Bundle,String) on "
                        + TARGET_FRAGMENT);
            }
        } catch (Throwable t) {
            log("chainA route2 error: " + t);
        }
    }

    /** Walk the class hierarchy, return the most-derived non-abstract declaration. */
    private static Method findConcreteMethod(Class<?> clazz, String name, Class<?>[] params) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, params);
                if (!Modifier.isAbstract(m.getModifiers())) {
                    return m;
                }
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    /** Add the two preferences to the software info page, idempotently. */
    private static void injectPreferences(XC_MethodHook.MethodHookParam param,
                                          ClassLoader classLoader, String route) {
        Object self = param.thisObject;
        if (self == null) {
            return;
        }
        if (!TARGET_FRAGMENT.equals(self.getClass().getName())) {
            return;
        }
        Object screen = XposedHelpers.callMethod(self, "getPreferenceScreen");
        if (screen == null) {
            log("chainA " + route + ": PreferenceScreen is null, skip");
            return;
        }
        Context ctx = null;
        try {
            ctx = (Context) XposedHelpers.callMethod(self, "getActivity");
        } catch (Throwable ignored) {
        }
        if (ctx == null) {
            try {
                ctx = (Context) XposedHelpers.callMethod(self, "requireContext");
            } catch (Throwable ignored) {
            }
        }
        if (ctx == null) {
            log("chainA " + route + ": no context, skip");
            return;
        }

        Class<?> prefCls = XposedHelpers.findClass("androidx.preference.Preference", classLoader);

        if (findPreferenceCompat(screen, prefCls, KEY_CHANGELOG) == null) {
            Intent i = new Intent();
            i.setComponent(new ComponentName(MODULE_PKG, MODULE_PKG + ".ChangelogActivity"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            addPreference(prefCls, screen, ctx, KEY_CHANGELOG, "更新日志", i, 100);
            log(ctx, "chainA " + route + ": added '更新日志' preference");
        }
        if (findPreferenceCompat(screen, prefCls, KEY_FWINFO) == null) {
            Intent i = new Intent();
            i.setComponent(new ComponentName(PKG_SETTINGS, FWINFO_ACTIVITY));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            addPreference(prefCls, screen, ctx, KEY_FWINFO, "固件信息", i, 101);
            log(ctx, "chainA " + route + ": added '固件信息' preference");
        }
    }

    /**
     * Dedupe probe: PreferenceScreen#findPreference(CharSequence) via exact-signature
     * reflection (the Vector legacy bridge's callMethod matching rejects String→
     * CharSequence), with a manual getPreferenceCount()/getPreference(i) scan fallback.
     */
    private static Object findPreferenceCompat(Object screen, Class<?> prefCls, String key) {
        try {
            Method m = screen.getClass().getMethod("findPreference", CharSequence.class);
            return m.invoke(screen, key);
        } catch (Throwable ignored) {
        }
        try {
            Method cnt = screen.getClass().getMethod("getPreferenceCount");
            Method get = screen.getClass().getMethod("getPreference", int.class);
            Method getKey = prefCls.getMethod("getKey");
            int n = (Integer) cnt.invoke(screen);
            for (int i = 0; i < n; i++) {
                Object p = get.invoke(screen, i);
                if (p != null && key.equals(getKey.invoke(p))) {
                    return p;
                }
            }
        } catch (Throwable t) {
            log("findPreferenceCompat fallback error: " + t);
        }
        return null;
    }

    /** Build + attach a Preference using exact-signature reflection only. */
    private static void addPreference(Class<?> prefCls, Object screen, Context ctx,
                                      String key, CharSequence title, Intent intent, int order) {
        try {
            Object p = prefCls.getConstructor(Context.class).newInstance(ctx);
            prefCls.getMethod("setKey", String.class).invoke(p, key);
            prefCls.getMethod("setTitle", CharSequence.class).invoke(p, title);
            prefCls.getMethod("setIntent", Intent.class).invoke(p, intent);
            try {
                prefCls.getMethod("setOrder", int.class).invoke(p, order);
            } catch (Throwable ignored) {
            }
            screen.getClass().getMethod("addPreference", prefCls).invoke(screen, p);
            log("addPreference ok: " + key);
        } catch (Throwable t) {
            log("addPreference error (" + key + "): " + t);
        }
    }

    // ================================================================ chain B

    private void hookGms(final XC_LoadPackage.LoadPackageParam lpparam) {
        final XC_MethodHook queryHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (!(param.args[0] instanceof Uri)) {
                        return;
                    }
                    Uri uri = (Uri) param.args[0];
                    if (!GSERVICES_AUTHORITY.equals(uri.getAuthority())) {
                        return;
                    }
                    String[] selectionArgs = extractSelectionArgs(param.args);
                    Cursor orig = (param.getResult() instanceof Cursor)
                            ? (Cursor) param.getResult() : null;
                    Cursor repl = rewriteGservicesCursor(orig, uri, selectionArgs);
                    if (repl != null) {
                        param.setResult(repl);
                        log("chainB: intercepted Gservices query " + uri);
                    }
                } catch (Throwable t) {
                    log("chainB query error: " + t);
                }
            }
        };

        // ContentResolver.query — 4 public overloads.
        hookQueryOverloads("android.content.ContentResolver", lpparam.classLoader, queryHook);
        // ContentProviderClient.query — equivalent overloads (direct provider path).
        hookQueryOverloads("android.content.ContentProviderClient", lpparam.classLoader, queryHook);

        // Bonus: non-obfuscated UpdateDescription data class (4 String fields a/b/c/d).
        try {
            Class<?> ud = XposedHelpers.findClass(
                    "com.google.android.gms.update.UpdateDescription", lpparam.classLoader);
            XposedHelpers.findAndHookConstructor(ud,
                    String.class, String.class, String.class, String.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object o = param.thisObject;
                                setIfEmpty(o, "a", ChangelogData.UPDATE_TITLE);
                                setIfEmpty(o, "b", ChangelogData.buildUpdateDescriptionHtml());
                                setIfEmpty(o, "c", ChangelogData.BUILD);
                                setIfEmpty(o, "d", ChangelogData.CHANGELOG_URL);
                                log("chainB bonus: UpdateDescription ctor patched");
                            } catch (Throwable t) {
                                log("chainB bonus error: " + t);
                            }
                        }
                    });
            log("chainB bonus hooked: com.google.android.gms.update.UpdateDescription.<init>");
        } catch (Throwable t) {
            log("chainB bonus unavailable (" + t + ")");
        }
    }

    private static void hookQueryOverloads(String className, ClassLoader cl, XC_MethodHook hook) {
        Object[][] overloads = {
                {android.net.Uri.class, String[].class, String.class, String[].class, String.class},
                {android.net.Uri.class, String[].class, String.class, String[].class, String.class,
                        android.os.CancellationSignal.class},
                {android.net.Uri.class, String[].class, Bundle.class,
                        android.os.CancellationSignal.class},
                {android.net.Uri.class, String[].class, Bundle.class},
        };
        for (Object[] sig : overloads) {
            try {
                Class<?>[] params = new Class<?>[sig.length];
                for (int i = 0; i < sig.length; i++) {
                    params[i] = (Class<?>) sig[i];
                }
                XposedHelpers.findAndHookMethod(className, cl, "query", concat(params, hook));
                log("chainB hooked " + className + ".query(" + params.length + " args)");
            } catch (Throwable t) {
                log("chainB hook skipped " + className + ".query/" + sig.length + ": " + t);
            }
        }
    }

    private static Object[] concat(Class<?>[] params, XC_MethodHook hook) {
        Object[] out = new Object[params.length + 1];
        System.arraycopy(params, 0, out, 0, params.length);
        out[params.length] = hook;
        return out;
    }

    private static String[] extractSelectionArgs(Object[] args) {
        try {
            // legacy form: (Uri, String[], String selection, String[] selectionArgs, ...)
            if (args.length >= 4 && args[2] instanceof String && args[3] instanceof String[]) {
                return (String[]) args[3];
            }
            // bundle form: (Uri, String[], Bundle queryArgs, ...)
            for (Object a : args) {
                if (a instanceof Bundle) {
                    Object v = ((Bundle) a).get("android:query-arg-sql-selection-args");
                    if (v instanceof String[]) {
                        return (String[]) v;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Rebuild the Gservices cursor with our update_* values merged in. */
    private static Cursor rewriteGservicesCursor(Cursor orig, Uri uri, String[] selectionArgs) {
        String prefix = null;
        try {
            String path = uri.getPath();
            if (path != null && path.startsWith("/prefix/")) {
                prefix = path.substring("/prefix/".length());
            }
        } catch (Throwable ignored) {
        }

        String[] cols = (orig != null) ? orig.getColumnNames() : null;
        if (cols == null || cols.length == 0) {
            cols = new String[]{"name", "value"};
        }
        int nameIdx = indexOf(cols, "name");
        if (nameIdx < 0) {
            nameIdx = 0;
        }
        int valIdx = indexOf(cols, "value");
        if (valIdx < 0) {
            valIdx = (cols.length > 1) ? 1 : 0;
        }

        Map<String, String> overrides = overrides();
        Set<String> pending = new HashSet<>(overrides.keySet());
        MatrixCursor mc = new MatrixCursor(cols);
        if (orig != null) {
            int n = cols.length;
            while (orig.moveToNext()) {
                Object[] row = new Object[n];
                for (int i = 0; i < n; i++) {
                    row[i] = safeGetString(orig, i);
                }
                String name = safeGetString(orig, nameIdx);
                if (name != null && overrides.containsKey(name)) {
                    row[valIdx] = overrides.get(name);
                    pending.remove(name);
                }
                mc.addRow(row);
            }
        }
        for (String k : new ArrayList<>(pending)) {
            boolean wanted;
            if (selectionArgs != null && selectionArgs.length > 0) {
                wanted = false;
                for (String s : selectionArgs) {
                    if (k.equals(s)) {
                        wanted = true;
                        break;
                    }
                }
            } else if (prefix != null) {
                wanted = k.startsWith(prefix);
            } else {
                wanted = true; // full-table query
            }
            if (wanted) {
                Object[] row = new Object[cols.length];
                row[nameIdx] = k;
                row[valIdx] = overrides.get(k);
                mc.addRow(row);
            }
        }
        return mc;
    }

    private static Map<String, String> overrides() {
        Map<String, String> m = sOverrides;
        if (m == null) {
            m = new LinkedHashMap<>();
            m.put("update_title", ChangelogData.UPDATE_TITLE);
            m.put("update_description", ChangelogData.buildUpdateDescriptionHtml());
            m.put("update_size", ChangelogData.BUILD);
            sOverrides = m;
        }
        return m;
    }

    private static void setIfEmpty(Object obj, String field, String value) {
        try {
            Object cur = XposedHelpers.getObjectField(obj, field);
            if (cur instanceof String && ((String) cur).length() > 0) {
                return;
            }
            XposedHelpers.setObjectField(obj, field, value);
        } catch (Throwable t) {
            log("setIfEmpty " + field + ": " + t);
        }
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) {
            if (v.equals(arr[i])) {
                return i;
            }
        }
        return -1;
    }

    private static String safeGetString(Cursor c, int idx) {
        try {
            return c.getString(idx);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
