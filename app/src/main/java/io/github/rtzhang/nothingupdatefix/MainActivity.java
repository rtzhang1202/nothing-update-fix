package io.github.rtzhang.nothingupdatefix;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Single-screen app: status pane (hook hit log), changelog viewer,
 * links, about. Fully programmatic UI (no layout resources).
 */
public class MainActivity extends Activity {

    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setBackgroundColor(0xFF0A0A0A);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = ChangelogView.dp(this, 20);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- header ----
        ChangelogView.addText(this, root, "Nothing update fix", 24, true, 0xFFFFFFFF);
        ChangelogView.addText(this, root,
                "Nothing Phone (3) 系统更新页内容修复模块（LSPosed/Xposed）", 13, false, 0xFF9A9A9A);

        // ---- status ----
        sectionTitle(root, "状态 · 钩子命中日志");
        ChangelogView.addText(this, root,
                "启用方法：在 LSPosed 管理器中启用本模块，作用域勾选 com.android.settings 与 "
                        + "com.google.android.gms，然后重启或强制停止这两个应用。\n"
                        + "以下日志由钩子经 XposedBridge + StatusProvider 上报（仅显示最近若干条）：",
                12, false, 0xFF8A8A8A);
        statusView = ChangelogView.addText(this, root, "（暂无命中日志）", 12, false, 0xFFB0E0B0);
        statusView.setTextIsSelectable(true);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.addView(button("刷新", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshStatus();
            }
        }));
        btnRow.addView(button("清空日志", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    deleteFile("status.log");
                } catch (Throwable ignored) {
                }
                refreshStatus();
            }
        }));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = ChangelogView.dp(this, 8);
        root.addView(btnRow, bl);

        // ---- changelog ----
        sectionTitle(root, "更新日志（内置）");
        ChangelogView.addText(this, root,
                "当前版本 C5.0-260924-2115 与历史版本 changelog（来源：nothingfirmware.github.io / "
                        + "nothingarchive.tech，抓取于 2026-11-10）", 12, false, 0xFF8A8A8A);
        root.addView(ChangelogView.build(this));

        // ---- links ----
        sectionTitle(root, "链接");
        link(root, "完整 OTA 更新日志（nothingarchive.tech）",
                "https://nothingarchive.tech/docs/changelogs");
        link(root, "Nothing Phone (3) 固件版本历史（nothingfirmware.github.io）",
                "https://nothingfirmware.github.io/device/nothing-phone-3.html");
        link(root, "Nothing Community · Firmware Updates",
                "https://nothing.community/c/firmware-updates/12");

        // ---- about ----
        sectionTitle(root, "About");
        String ver = "?";
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            ver = pi.versionName;
        } catch (Throwable ignored) {
        }
        ChangelogView.addText(this, root,
                "Nothing update fix v" + ver + "\n"
                        + "Xposed API 82 · 作用域：com.android.settings / com.google.android.gms\n"
                        + "链路A：软件信息页补回「更新日志 / 固件信息」入口\n"
                        + "链路B：GMS Gservices 注入 update_title / update_description / update_size",
                12, false, 0xFF8A8A8A);
        link(root, "GitHub：github.com/rtzhang1202/nothing-update-fix",
                "https://github.com/rtzhang1202/nothing-update-fix");

        setContentView(scroll);
        refreshStatus();
    }

    private void sectionTitle(LinearLayout root, String title) {
        TextView tv = ChangelogView.addText(this, root, title, 16, true, 0xFFFFFFFF);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) tv.getLayoutParams();
        lp.topMargin = ChangelogView.dp(this, 20);
    }

    private TextView button(String label, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(13);
        tv.setTextColor(0xFF0A0A0A);
        tv.setBackgroundColor(0xFFFFFFFF);
        tv.setGravity(Gravity.CENTER);
        int pad = ChangelogView.dp(this, 12);
        tv.setPadding(pad, pad / 2, pad, pad / 2);
        tv.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = ChangelogView.dp(this, 10);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void link(LinearLayout root, String label, final String url) {
        TextView tv = ChangelogView.addText(this, root, label, 13, false, 0xFF6FB3FF);
        tv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private void refreshStatus() {
        List<String> lines = new ArrayList<>();
        File f = new File(getFilesDir(), "status.log");
        if (f.exists()) {
            BufferedReader r = null;
            try {
                r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) {
                    lines.add(line);
                }
            } catch (Throwable ignored) {
            } finally {
                try {
                    if (r != null) {
                        r.close();
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        if (lines.isEmpty()) {
            statusView.setText("（暂无命中日志）");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, lines.size() - 40); i < lines.size(); i++) {
            sb.append(lines.get(i)).append("\n");
        }
        statusView.setText(sb.toString().trim());
    }
}
