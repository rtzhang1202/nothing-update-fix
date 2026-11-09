// GmsPageInjector — GMS update-page view injection (phase6, plan B).
// Landed verbatim from analysis/phase6/patch_draft.java (comment header stripped).
// L1 SystemUpdateChimeraActivity.onCreate/onResume + L2 gtah.i/gtak.a probes + L3 Activity.onResume.

package io.github.rtzhang.nothingupdatefix;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.TextUtils;
import android.util.JsonReader;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * GMS 系统更新页（GLIF）内容注入器。
 *
 * 在「版本摘要」TextView（id/description, 0x7f0b0f60）之后插入两张纯代码构建的卡片：
 *  - What's New 卡：本地 changelog.json 当前版本正文 + 分节要点 + 跳模块 ChangelogActivity；
 *  - 固件信息卡：型号 / 设备 / Android 版本 / 系统版本 / Build / 基带 / 内核 / 安全补丁 / SN。
 *
 * 与 GMS 状态机解耦：不读也不写 UpdateDescription / SystemUpdateStatus（A2 可选模式除外，
 * 且 A2 只做「临时换字段 + 重入原厂渲染器 + 立即还原」）。
 */
public final class GmsPageInjector {

    private GmsPageInjector() {
    }

    // ---- 常量 ----------------------------------------------------------------

    static final String PKG_GMS = "com.google.android.gms";

    /** 未混淆：页面宿主 Activity（classes13_dis.txt:97237 / onCreate :97723）。 */
    static final String ACT_CHIMERA =
            "com.google.android.gms.update.phone.SystemUpdateChimeraActivity";

    /** GMS 26.43.30 混淆名（形状探针校验；换版本时探针会拒绝并只丢 L2）。 */
    static final String CLS_STATE_NO_UPDATE = "gtah";   // OtaUiController: "无更新/检查中"族
    static final String CLS_RENDER = "gtak";            // 共享渲染器：标题/描述/大小
    static final String HOLDER_SIMPLE = "gtcj";         // GlifLayout holder 抽象 getter 集

    static final String TAG_CARDS = "nuf_gms_cards_v1";

    /** A2 成功标记：原生模式下 changelog HTML 里带这个串，用于判定 A 是否真的画出来了。 */
    static final String MARKER = "Nothing OS 5.0";

    // 卡片渲染上限（全量 40+ 条会把页面拉得过长；其余折到"查看全部更新日志"）
    private static final int MAX_SECTIONS = 3;
    private static final int MAX_ITEMS_PER_SECTION = 4;

    // 资源 **名称** —— id 数值随 GMS 版本变化，一律运行时 getIdentifier 解析
    private static final String[] ANCHOR_IDS = {
            "description",                   // 0x7f0b0f60 (gtcb/gtce.K())
            "descriptionLayout",             // 0x7f0b0f64
            "sud_layout_content",            // 0x7f0b1ff8
            "sud_layout_template_content",   // 0x7f0b200d
            "glif_layout",                   // 0x7f0b1496
    };

    // ---- 状态 ----------------------------------------------------------------

    private static volatile boolean sInstalled;
    private static volatile ClassLoader sCl;

    /** A2 原生模式开关（默认关；见文件头）。 */
    static volatile boolean sNativeMode = false;

    private static volatile Method sRenderMethod;   // gtak.a(Activity, gtcj, SystemUpdateStatus, Z)
    private static volatile Method sHolderK;        // gtcj.K() -> TextView

    private static final ThreadLocal<Boolean> sInNative = new ThreadLocal<Boolean>();

    // ========================================================================
    //  安装
    // ========================================================================

    /** 由 MainHook.hookGms(...) 调用（GMS 进程内）。 */
    public static void install(ClassLoader cl) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        sCl = cl;
        try {
            hookActivity(cl);          // L1
        } catch (Throwable t) {
            MainHook.log("gms inject: L1 install failed: " + t);
            hookAnyActivityResume(cl); // L3
        }
        try {
            hookRenderers(cl);         // L2
        } catch (Throwable t) {
            MainHook.log("gms inject: L2 unavailable: " + t);
        }
        MainHook.log("gms inject: installed (anchors=" + ACT_CHIMERA + ")");
    }

    // ---- L1：未混淆 Activity 生命周期 ---------------------------------------

    private static void hookActivity(ClassLoader cl) {
        Class<?> actCls = XposedHelpers.findClass(ACT_CHIMERA, cl);
        final XC_MethodHook onCreate = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    schedule(resolveActivity(param.thisObject), "onCreate");
                } catch (Throwable t) {
                    MainHook.log("gms inject: onCreate hook error: " + t);
                }
            }
        };
        final XC_MethodHook onResume = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    schedule(resolveActivity(param.thisObject), "onResume");
                } catch (Throwable t) {
                    MainHook.log("gms inject: onResume hook error: " + t);
                }
            }
        };
        try {
            XposedBridge.hookMethod(actCls.getDeclaredMethod("onCreate", Bundle.class), onCreate);
            MainHook.log("gms inject: L1 hooked " + ACT_CHIMERA + "#onCreate");
        } catch (Throwable t) {
            MainHook.log("gms inject: L1 onCreate unavailable: " + t);
        }
        try {
            XposedBridge.hookMethod(actCls.getDeclaredMethod("onResume"), onResume);
            MainHook.log("gms inject: L1 hooked " + ACT_CHIMERA + "#onResume");
        } catch (Throwable t) {
            MainHook.log("gms inject: L1 onResume unavailable: " + t);
        }
    }

    /** L3：L1 找不到类时，用全局 onResume + 类名过滤兜底。 */
    private static void hookAnyActivityResume(ClassLoader cl) {
        try {
            Class<?> a = XposedHelpers.findClass("android.app.Activity", cl);
            XposedBridge.hookMethod(a.getDeclaredMethod("onResume"), new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object o = param.thisObject;
                        if (!(o instanceof Activity)) {
                            return;
                        }
                        String name = o.getClass().getName();
                        if (name != null && name.contains(".update.")) {
                            schedule((Activity) o, "resume-global");
                        }
                    } catch (Throwable t) {
                        MainHook.log("gms inject: L3 error: " + t);
                    }
                }
            });
            MainHook.log("gms inject: L3 hooked Activity#onResume");
        } catch (Throwable t) {
            MainHook.log("gms inject: L3 unavailable: " + t);
        }
    }

    // ---- L1 的 Activity 解析（Chimera 委托对象 → 容器 Activity） ---------------

    private static volatile boolean sDelegateLogged;
    private static volatile boolean sProxyLogged;

    /**
     * L1 锚点 SystemUpdateChimeraActivity 是 Chimera *模块委托*类：真机上它不是
     * android.app.Activity 子类（强制转换抛 ClassCastException），页面窗口由
     * Chimera 容器 Activity（动态模块代理）持有。因此先从委托对象反射出 Activity，
     * 再退到 ActivityThread 里存活的 Activity，优先选能看到锚点视图的那个。
     */
    private static Activity resolveActivity(Object self) {
        if (self instanceof Activity) {
            return (Activity) self;
        }
        if (self != null && !sDelegateLogged) {
            sDelegateLogged = true;
            StringBuilder sb = new StringBuilder();
            for (Class<?> c = self.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                sb.append(c.getName()).append(" < ");
            }
            MainHook.log("gms inject: L1 target is not an Activity: " + sb + "Object");
        }
        Activity reflected = reflectedActivity(self);
        List<Activity> cands = new ArrayList<Activity>();
        if (reflected != null) {
            cands.add(reflected);
        }
        for (Activity a : liveActivities()) {
            if (a != null && a != reflected) {
                cands.add(a);
            }
        }
        Activity pick = null;
        for (Activity a : cands) {
            if (hasAnchor(a)) {
                pick = a;
                break;
            }
        }
        if (pick == null && !cands.isEmpty()) {
            pick = cands.get(cands.size() - 1);
        }
        if (pick != null && !sProxyLogged) {
            sProxyLogged = true;
            MainHook.log("gms inject: container Activity resolved: " + pick.getClass().getName());
        }
        return pick;
    }

    /** 委托对象上找 Activity：getActivity()/getContainerActivity() 等 0 参方法或 Activity 字段。 */
    private static Activity reflectedActivity(Object self) {
        if (self == null) {
            return null;
        }
        try {
            for (Method m : self.getClass().getMethods()) {
                if (m.getParameterTypes().length != 0
                        || !Activity.class.isAssignableFrom(m.getReturnType())) {
                    continue;
                }
                try {
                    Object v = m.invoke(self);
                    if (v instanceof Activity) {
                        return (Activity) v;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        for (Class<?> c = self.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (!Activity.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object v = f.get(self);
                    if (v instanceof Activity) {
                        return (Activity) v;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /** ActivityThread 当前存活的 Activity（Map 或 数组，随系统版本而异）。 */
    private static List<Activity> liveActivities() {
        List<Activity> out = new ArrayList<Activity>();
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method cur = at.getDeclaredMethod("currentActivityThread");
            cur.setAccessible(true);
            Object t = cur.invoke(null);
            Method acts = at.getDeclaredMethod("getActivities");
            acts.setAccessible(true);
            Object v = acts.invoke(t);
            if (v instanceof Object[]) {
                for (Object o : (Object[]) v) {
                    if (o instanceof Activity) {
                        out.add((Activity) o);
                    }
                }
            } else if (v instanceof java.util.Map) {
                for (Object o : ((java.util.Map<?, ?>) v).values()) {
                    if (o instanceof Activity) {
                        out.add((Activity) o);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 该 Activity 的视图树里能否看到注入锚点（用于挑选正确的宿主）。 */
    private static boolean hasAnchor(Activity act) {
        try {
            Resources res = act.getResources();
            for (String idName : ANCHOR_IDS) {
                int id = res.getIdentifier(idName, "id", PKG_GMS);
                if (id != 0 && act.findViewById(id) != null) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 多拍注入：GLIF 是先用 setContentView 再 replace fragment 的两段式，
     * 且状态控制器会在 attach 后异步 render，单拍容易落在空树上。
     */
    private static void schedule(final Activity act, final String why) {
        if (act == null || act.isFinishing()) {
            return;
        }
        final Handler h = new Handler(Looper.getMainLooper());
        final Runnable r = new Runnable() {
            @Override
            public void run() {
                try {
                    inject(act, why);
                } catch (Throwable t) {
                    MainHook.log("gms inject: inject error (" + why + "): " + t);
                }
            }
        };
        h.post(r);
        h.postDelayed(r, 250L);
        h.postDelayed(r, 900L);
        h.postDelayed(r, 2500L);
    }

    // ---- L2：混淆渲染器 after-hook（形状探针，全部重载） ---------------------

    private static void hookRenderers(ClassLoader cl) {
        // 1) gtah：static void xxx(gszx, gtcj) —— 已最新/检查中状态的渲染器
        try {
            Class<?> st = XposedHelpers.findClass(CLS_STATE_NO_UPDATE, cl);
            int n = 0;
            for (Method m : st.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers()) || m.getReturnType() != void.class) {
                    continue;
                }
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length != 2 || pt[0].isPrimitive()
                        || !HOLDER_SIMPLE.equals(simple(pt[1]))) {
                    continue;
                }
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Activity act = null;
                            Object holder = param.args[1];
                            // 复用 holder 的 Context（gtcj 是 Fragment 子类，requireContext 可用）
                            try {
                                act = (Activity) XposedHelpers.callMethod(holder, "requireActivity");
                            } catch (Throwable ignored) {
                            }
                            if (act == null) {
                                act = guessActivity();
                            }
                            if (act != null) {
                                MainHook.log("gms inject: L2 gtah.i fired (holder="
                                        + holder.getClass().getName() + ") -> reinject");
                                inject(act, "gtah.i");
                            }
                        } catch (Throwable t) {
                            MainHook.log("gms inject: gtah.i hook error: " + t);
                        }
                    }
                });
                n++;
                MainHook.log("gms inject: L2 hooked " + CLS_STATE_NO_UPDATE + "." + m.getName()
                        + "(..," + HOLDER_SIMPLE + ")");
            }
            if (n == 0) {
                MainHook.log("gms inject: L2 probe miss on " + CLS_STATE_NO_UPDATE);
            }
        } catch (Throwable t) {
            MainHook.log("gms inject: L2 " + CLS_STATE_NO_UPDATE + " resolve failed: " + t);
        }

        // 2) gtak：static void xxx(Activity, gtcj, SystemUpdateStatus, [Z]) —— 有更新族
        try {
            Class<?> rc = XposedHelpers.findClass(CLS_RENDER, cl);
            int n = 0;
            for (Method m : rc.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers()) || m.getReturnType() != void.class) {
                    continue;
                }
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length < 3 || !Activity.class.isAssignableFrom(pt[0])
                        || !HOLDER_SIMPLE.equals(simple(pt[1]))) {
                    continue;
                }
                m.setAccessible(true);
                if (pt.length == 4 && pt[3] == boolean.class && sRenderMethod == null) {
                    sRenderMethod = m;  // A2 重入用
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Activity act = (Activity) param.args[0];
                            if (act == null) {
                                return;
                            }
                            boolean nativeOk = nativeRenderIfEnabled(act, param.args[1], param.args[2]);
                            if (!nativeOk) {
                                inject(act, "gtak.a-fallback");
                            } else {
                                MainHook.log("gms inject: A2 native render verified, B skipped");
                            }
                        } catch (Throwable t) {
                            MainHook.log("gms inject: gtak hook error: " + t);
                        }
                    }
                });
                n++;
                MainHook.log("gms inject: L2 hooked " + CLS_RENDER + "." + m.getName()
                        + "/" + pt.length);
            }
            if (n == 0) {
                MainHook.log("gms inject: L2 probe miss on " + CLS_RENDER);
            }
        } catch (Throwable t) {
            MainHook.log("gms inject: L2 " + CLS_RENDER + " resolve failed: " + t);
        }
    }

    private static String simple(Class<?> c) {
        try {
            return c.getSimpleName();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** 从当前进程的 ActivityThread 里取前台/最近的 Activity（只在 holder 无 Context 时用）。 */
    private static Activity guessActivity() {
        List<Activity> list = liveActivities();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i) != null) {
                return list.get(i);
            }
        }
        return null;
    }

    // ========================================================================
    //  A2 —— 原生模式（默认关闭）：临时换 SystemUpdateStatus.x 并重入原厂渲染器
    // ========================================================================

    /**
     * @return true = A2 已画出可验证的原生内容（调用方应跳过 B 注入）。
     *
     * 只在 sNativeMode 打开时生效；已最新状态根本不会进到这里（gtah 不调 gtak.a），
     * 所以 A2 永远只是"有更新"状态下的加分项，B 是主路径。
     */
    private static boolean nativeRenderIfEnabled(Activity act, Object holder, Object status) {
        if (!sNativeMode || sRenderMethod == null || status == null) {
            return false;
        }
        if (Boolean.TRUE.equals(sInNative.get())) {
            return false;   // 防止重入死循环
        }
        Object backup = null;
        try {
            backup = XposedHelpers.getObjectField(status, "x");
            Class<?> ud = XposedHelpers.findClass(
                    "com.google.android.gms.update.UpdateDescription", sCl);
            String html = "<h3>" + MARKER + " (" + ChangelogData.BUILD + ")</h3>"
                    + ChangelogData.buildUpdateDescriptionHtml();
            Object fresh = ud.getConstructor(String.class, String.class, String.class, String.class)
                    .newInstance(ChangelogData.UPDATE_TITLE, html,
                            ChangelogData.BUILD, ChangelogData.CHANGELOG_URL);
            XposedHelpers.setObjectField(status, "x", fresh);
            sInNative.set(Boolean.TRUE);
            sRenderMethod.invoke(null, act, holder, status, Boolean.FALSE);
        } catch (Throwable t) {
            MainHook.log("gms inject: A2 render failed: " + t);
        } finally {
            sInNative.remove();
            if (backup != null) {
                try {
                    XposedHelpers.setObjectField(status, "x", backup);
                } catch (Throwable ignored) {
                }
            }
        }
        // 判定：holder.K() 的文本是否含 MARKER（A2 真的画上去了）
        try {
            TextView tv = holderText(holder);
            CharSequence cs = (tv == null) ? null : tv.getText();
            if (cs != null && cs.toString().contains(MARKER)
                    && tv.getVisibility() == View.VISIBLE) {
                return true;
            }
        } catch (Throwable t) {
            MainHook.log("gms inject: A2 verify failed: " + t);
        }
        MainHook.log("gms inject: A2 not visible -> fallback to B");
        return false;
    }

    private static TextView holderText(Object holder) {
        try {
            if (sHolderK == null) {
                sHolderK = holder.getClass().getMethod("K");
                sHolderK.setAccessible(true);
            }
            Object v = sHolderK.invoke(holder);
            return (v instanceof TextView) ? (TextView) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ========================================================================
    //  B —— 视图注入
    // ========================================================================

    private static void inject(Activity act, String why) {
        if (act == null || act.isFinishing()) {
            return;
        }
        View anchor = null;
        String anchorName = null;
        final Resources res = act.getResources();
        for (String idName : ANCHOR_IDS) {
            int id = res.getIdentifier(idName, "id", PKG_GMS);
            if (id == 0) {
                continue;
            }
            View v = act.findViewById(id);
            if (v != null) {
                anchor = v;
                anchorName = idName;
                break;
            }
        }
        if (anchor == null) {
            StringBuilder ids = new StringBuilder();
            for (String idName : ANCHOR_IDS) {
                int id = res.getIdentifier(idName, "id", PKG_GMS);
                if (id == 0) {
                    ids.append(idName).append("=unresolved ");
                    continue;
                }
                ids.append(idName).append("=0x").append(Integer.toHexString(id))
                        .append(act.findViewById(id) == null ? "(absent) " : "(found) ");
            }
            MainHook.log("gms inject: no anchor view in " + act.getClass().getName()
                    + " | " + ids);
            return;
        }

        ViewGroup host = resolveHost(anchor);
        if (host == null) {
            MainHook.log("gms inject: no host for anchor=" + anchorName);
            return;
        }

        // 幂等：同一个 host 上只挂一份
        View existing = host.findViewWithTag(TAG_CARDS);
        if (existing != null) {
            return;   // 多拍注入的常态，静默
        }

        final Context ctx = act;
        final Palette p = Palette.of(isNight(ctx));
        LinearLayout cards = buildCards(ctx, p);
        if (cards == null) {
            return;
        }
        cards.setTag(TAG_CARDS);

        int index = -1;
        ViewParent p1 = anchor.getParent();
        if (p1 instanceof View) {
            index = host.indexOfChild((View) p1);
        }
        if (index >= 0) {
            host.addView(cards, index + 1);
        } else {
            host.addView(cards);
        }
        MainHook.log("gms inject: added 2 cards (via " + why + ", anchor=" + anchorName
                + ", host=" + host.getClass().getSimpleName() + ")");
        MainHook.log(ctx, "gms page: What's New + firmware cards injected");
    }

    /**
     * 注入宿主：
     *   anchor=description        → 父 descriptionLayout 的父（ScrollView 内的 LinearLayout）
     *   anchor=descriptionLayout  → 父
     *   anchor=content/glif 等    → 自身（若是 ViewGroup）或父
     */
    private static ViewGroup resolveHost(View anchor) {
        try {
            ViewParent p1 = anchor.getParent();
            if (!(p1 instanceof ViewGroup)) {
                return null;
            }
            ViewGroup g1 = (ViewGroup) p1;
            ViewParent p2 = g1.getParent();
            if (p2 instanceof ViewGroup && !(p2 instanceof ScrollView)
                    && !(g1 instanceof ScrollView)) {
                return (ViewGroup) p2;
            }
            return g1;
        } catch (Throwable t) {
            MainHook.log("gms inject: resolveHost error: " + t);
            return null;
        }
    }

    private static boolean isNight(Context ctx) {
        try {
            Configuration c = ctx.getResources().getConfiguration();
            return (c.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable ignored) {
            return true;
        }
    }

    // ---- 卡片装配 ------------------------------------------------------------

    private static LinearLayout buildCards(Context ctx, Palette p) {
        Notes notes = loadNotes(ctx);
        if (notes == null) {
            notes = fallbackNotes();
        }
        if (notes == null) {
            MainHook.log("gms inject: no changelog data, skip");
            return null;
        }
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 14);
        root.setLayoutParams(lp);

        root.addView(buildChangelogCard(ctx, p, notes), matchWrap(ctx, 0));
        root.addView(buildFirmwareCard(ctx, p), matchWrap(ctx, 12));
        return root;
    }

    private static LinearLayout.LayoutParams matchWrap(Context ctx, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, topDp);
        return lp;
    }

    /** 深色圆角卡 + 左红条标题 + 可选副标题。 */
    private static LinearLayout card(Context ctx, Palette p, String title, String subtitle) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundRect(p.card, p.cardStroke, dp(ctx, 24), dp(ctx, 1)));
        int pad = dp(ctx, 18);
        card.setPadding(pad, pad, pad, pad);

        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);

        View bar = new View(ctx);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(ctx, 3), dp(ctx, 18));
        blp.rightMargin = dp(ctx, 10);
        bar.setLayoutParams(blp);
        bar.setBackground(roundRect(p.accent, p.accent, dp(ctx, 2), 0));
        head.addView(bar);

        TextView t = text(ctx, title, 18f, p.title, true);
        head.addView(t, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        card.addView(head);

        if (!TextUtils.isEmpty(subtitle)) {
            TextView s = text(ctx, subtitle, 12f, p.sub, false);
            s.setPadding(dp(ctx, 13), dp(ctx, 4), 0, 0);
            s.setLetterSpacing(0.02f);
            card.addView(s);
        }
        return card;
    }

    private static LinearLayout buildChangelogCard(Context ctx, Palette p, Notes notes) {
        String sub = notes.version + " · " + notes.build;
        LinearLayout c = card(ctx, p, "更新内容", sub);

        int shown = 0;
        int budgetSections = MAX_SECTIONS;
        for (Section s : notes.sections) {
            if (shown >= budgetSections) {
                break;
            }
            if (s.items.isEmpty() && TextUtils.isEmpty(s.paragraph)) {
                continue;
            }
            if (!TextUtils.isEmpty(s.title)) {
                TextView l = text(ctx, s.title.toUpperCase(), 12f, p.label, true);
                l.setLetterSpacing(0.10f);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(ctx, 14);
                c.addView(l, lp);
            }
            if (!TextUtils.isEmpty(s.paragraph)) {
                TextView para = text(ctx, s.paragraph, 13.5f, p.body, false);
                para.setLineSpacing(dp(ctx, 5), 1f);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(ctx, 8);
                c.addView(para, lp);
            }
            int perSection = 0;
            for (String item : s.items) {
                if (perSection >= MAX_ITEMS_PER_SECTION) {
                    break;
                }
                TextView b = text(ctx, "•  " + item, 13.5f, p.body, false);
                b.setLineSpacing(dp(ctx, 5), 1f);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(ctx, 7);
                c.addView(b, lp);
                perSection++;
            }
            shown++;
        }

        c.addView(divider(ctx, p));
        TextView more = text(ctx, "查看全部更新日志 ›", 13f, p.link, true);
        more.setPadding(0, dp(ctx, 2), 0, dp(ctx, 2));
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openChangelog(v.getContext());
            }
        });
        c.addView(more);
        return c;
    }

    private static LinearLayout buildFirmwareCard(Context ctx, Palette p) {
        LinearLayout c = card(ctx, p, "固件信息", null);
        String[][] rows = firmwareRows();
        for (int i = 0; i < rows.length; i++) {
            if (i > 0) {
                c.addView(dividerHair(ctx, p));
            }
            c.addView(kvRow(ctx, p, rows[i][0], rows[i][1], i == 3));
        }
        return c;
    }

    /** label, value 列表；顺序即为展示顺序。 */
    private static String[][] firmwareRows() {
        String model = nz(Build.MODEL, "—");
        String device = nz(Build.DEVICE, "—") + " (" + nz(Build.PRODUCT, "—") + ")";
        String rel = nz(Build.VERSION.RELEASE, "—");
        String display = nz(ChangelogData.prop("ro.build.display.id", ""), nz(Build.DISPLAY, "—"));
        String build = nz(ChangelogData.prop("ro.build.version.incremental", ""), nz(Build.ID, "—"));
        String baseband = nz(ChangelogData.prop("gsm.version.baseband", ""), "—");
        String kernel = kernelVersion();
        String patch = nz(Build.VERSION.SECURITY_PATCH, "—");
        String sn = nz(ChangelogData.prop("ro.serialno", ""),
                nz(ChangelogData.prop("ro.boot.serialno", ""), "—"));
        return new String[][]{
                {"型号", model},
                {"设备代号", device},
                {"Android", rel},
                {"系统版本", display},
                {"Build 号", build},
                {"基带", baseband},
                {"内核", kernel},
                {"安全补丁", patch},
                {"序列号", sn},
        };
    }

    private static LinearLayout kvRow(Context ctx, Palette p, String k, String v, boolean mono) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(ctx, 10), 0, dp(ctx, 10));

        TextView key = text(ctx, k, 12f, p.sub, false);
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(dp(ctx, 92),
                LinearLayout.LayoutParams.WRAP_CONTENT);
        row.addView(key, klp);

        TextView val = text(ctx, v, 13f, p.title, false);
        if (mono) {
            val.setTypeface(Typeface.MONOSPACE);
            val.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        }
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(val, vlp);
        return row;
    }

    // ---- 基础视图工具 --------------------------------------------------------

    private static TextView text(Context ctx, CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) {
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        return t;
    }

    private static View divider(Context ctx, Palette p) {
        View v = new View(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 1));
        lp.topMargin = dp(ctx, 14);
        lp.bottomMargin = dp(ctx, 10);
        v.setLayoutParams(lp);
        v.setBackgroundColor(p.divider);
        return v;
    }

    private static View dividerHair(Context ctx, Palette p) {
        View v = new View(ctx);
        v.setBackgroundColor(p.divider);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 1)));
        return v;
    }

    private static GradientDrawable roundRect(int color, int stroke, int radius, int strokeW) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(radius);
        if (strokeW > 0) {
            d.setStroke(strokeW, stroke);
        }
        return d;
    }

    private static int dp(Context ctx, float v) {
        try {
            return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
        } catch (Throwable ignored) {
            return (int) v;
        }
    }

    private static String nz(String s, String fb) {
        return (s == null || s.length() == 0) ? fb : s;
    }

    private static String kernelVersion() {
        String k = ChangelogData.prop("ro.kernel.version", "");
        if (k.length() == 0) {
            try {
                k = System.getProperty("os.version", "");
            } catch (Throwable ignored) {
            }
        }
        if (k.length() == 0) {
            k = firstLine("/proc/version");
            int at = k.indexOf(" version ");
            if (at > 0) {
                k = k.substring(0, at).trim();
            }
            String[] parts = k.split("\\s+");
            if (parts.length >= 3 && "Linux".equals(parts[0])) {
                k = parts[2];
            }
        }
        return k.length() == 0 ? "—" : k;
    }

    private static String firstLine(String path) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(path));
            String line = r.readLine();
            return line == null ? "" : line.trim();
        } catch (Throwable ignored) {
            return "";
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void openChangelog(Context ctx) {
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName(MainHook.MODULE_PKG,
                    MainHook.MODULE_PKG + ".ChangelogActivity"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) {
            MainHook.log("gms inject: open changelog failed: " + t);
        }
    }

    // ---- 调色板 --------------------------------------------------------------

    static final class Palette {
        int card, cardStroke, title, sub, label, body, divider, accent, link;

        static Palette of(boolean night) {
            Palette p = new Palette();
            if (night) {
                p.card = 0xFF141414;
                p.cardStroke = 0xFF232323;
                p.title = 0xFFF5F5F5;
                p.sub = 0xFF8A8A8E;
                p.label = 0xFFD71921;   // Nothing red
                p.body = 0xFFCFCFCF;
                p.divider = 0xFF262626;
                p.accent = 0xFFD71921;
                p.link = 0xFFFF5A5F;
            } else {
                p.card = 0xFFFFFFFF;
                p.cardStroke = 0xFFE3E3E3;
                p.title = 0xFF111111;
                p.sub = 0xFF6E6E73;
                p.label = 0xFFC4141C;
                p.body = 0xFF3C3C43;
                p.divider = 0xFFEAEAEA;
                p.accent = 0xFFD71921;
                p.link = 0xFFC4141C;
            }
            return p;
        }
    }

    // ---- 数据：assets/changelog.json（失败回退到内置 HTML） -------------------

    static final class Section {
        String title = "";
        String paragraph = "";
        final List<String> items = new ArrayList<String>();
    }

    static final class Notes {
        String version = ChangelogData.UPDATE_TITLE;
        String build = ChangelogData.BUILD;
        final List<Section> sections = new ArrayList<Section>();
    }

    /**
     * 从模块 APK 的 assets/changelog.json 读取"当前版本"那一节。
     * 跨包 assets 可读（APK 全局可读），失败则返回 null 由调用方回退。
     */
    private static Notes loadNotes(Context ctx) {
        InputStream in = null;
        JsonReader r = null;
        try {
            Context mc = ctx.createPackageContext(MainHook.MODULE_PKG,
                    Context.CONTEXT_IGNORE_SECURITY);
            in = mc.getAssets().open("changelog.json");
            r = new JsonReader(new InputStreamReader(in, "UTF-8"));
            Notes picked = null;
            Notes first = null;
            r.beginObject();
            while (r.hasNext()) {
                String name = r.nextName();
                if (!"versions".equals(name)) {
                    r.skipValue();
                    continue;
                }
                r.beginArray();
                while (r.hasNext()) {
                    Notes n = readVersion(r);
                    if (n == null) {
                        continue;
                    }
                    if (first == null) {
                        first = n;
                    }
                    if (picked == null && buildMatchesCurrent(n.build)) {
                        picked = n;
                    }
                }
                r.endArray();
            }
            r.endObject();
            if (picked == null) {
                picked = first;
            }
            if (picked != null) {
                MainHook.log("gms inject: changelog from assets (" + picked.build + ", "
                        + picked.sections.size() + " sections)");
            }
            return picked;
        } catch (Throwable t) {
            MainHook.log("gms inject: assets changelog unavailable (" + t + "), using built-in");
            return null;
        } finally {
            try {
                if (r != null) {
                    r.close();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean buildMatchesCurrent(String build) {
        if (build == null || build.length() == 0) {
            return false;
        }
        String display = ChangelogData.prop("ro.build.display.id", "");
        if (display.length() > 0 && display.contains(build)) {
            return true;
        }
        String inc = ChangelogData.prop("ro.build.version.incremental", "");
        return inc.length() > 0 && inc.contains(build);
    }

    private static Notes readVersion(JsonReader r) {
        try {
            Notes n = new Notes();
            boolean current = false;
            r.beginObject();
            while (r.hasNext()) {
                String k = r.nextName();
                if ("build".equals(k)) {
                    n.build = r.nextString();
                } else if ("version_name".equals(k)) {
                    n.version = r.nextString();
                } else if ("current".equals(k)) {
                    current = r.nextBoolean();
                } else if ("sections".equals(k)) {
                    r.beginArray();
                    while (r.hasNext()) {
                        Section s = readSection(r);
                        if (s != null) {
                            n.sections.add(s);
                        }
                    }
                    r.endArray();
                } else {
                    r.skipValue();
                }
            }
            r.endObject();
            if (current && n.sections.isEmpty()) {
                return n;   // 仍返回，由调用方决定
            }
            return n.sections.isEmpty() ? null : n;
        } catch (Throwable t) {
            MainHook.log("gms inject: version parse error: " + t);
            return null;
        }
    }

    private static Section readSection(JsonReader r) {
        try {
            Section s = new Section();
            r.beginObject();
            while (r.hasNext()) {
                String k = r.nextName();
                if ("title".equals(k)) {
                    s.title = r.nextString();
                } else if ("paragraphs".equals(k)) {
                    r.beginArray();
                    StringBuilder sb = new StringBuilder();
                    while (r.hasNext()) {
                        String p = r.nextString();
                        if (sb.length() > 0) {
                            sb.append('\n');
                        }
                        sb.append(p);
                    }
                    r.endArray();
                    s.paragraph = sb.toString();
                } else if ("items".equals(k)) {
                    r.beginArray();
                    while (r.hasNext()) {
                        s.items.add(r.nextString());
                    }
                    r.endArray();
                } else {
                    r.skipValue();
                }
            }
            r.endObject();
            return s;
        } catch (Throwable t) {
            MainHook.log("gms inject: section parse error: " + t);
            return null;
        }
    }

    /** 回退：把内置 HTML（ChangelogData）剥成纯文本，作为单节卡片。 */
    private static Notes fallbackNotes() {
        try {
            Notes n = new Notes();
            CharSequence plain;
            try {
                plain = Html.fromHtml(ChangelogData.buildUpdateDescriptionHtml(),
                        Html.FROM_HTML_MODE_LEGACY);
            } catch (Throwable t) {
                plain = Html.fromHtml(ChangelogData.buildUpdateDescriptionHtml());
            }
            Section s = new Section();
            s.title = "";
            StringBuilder body = new StringBuilder();
            for (String line : plain.toString().split("\n")) {
                String t = line.trim();
                if (t.length() == 0) {
                    continue;
                }
                if (body.length() > 0) {
                    body.append('\n');
                }
                body.append(t);
            }
            s.paragraph = body.toString();
            n.sections.add(s);
            MainHook.log("gms inject: changelog fallback to built-in HTML");
            return n;
        } catch (Throwable t) {
            MainHook.log("gms inject: fallbackNotes failed: " + t);
            return null;
        }
    }
}