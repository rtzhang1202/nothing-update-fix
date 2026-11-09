package io.github.rtzhang.nothingupdatefix;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

/** Renders assets/changelog.json as a vertical view stack (programmatic UI). */
public final class ChangelogView {

    private ChangelogView() {}

    public static View build(Context ctx) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        try {
            JSONObject data = load(ctx);
            JSONArray versions = data.getJSONArray("versions");
            for (int v = 0; v < versions.length(); v++) {
                JSONObject ver = versions.getJSONObject(v);
                addVersion(ctx, root, ver);
            }
        } catch (Throwable t) {
            addText(ctx, root, "changelog.json 解析失败: " + t, 14, false, 0xFF888888);
        }
        return root;
    }

    private static JSONObject load(Context ctx) throws Exception {
        InputStream is = ctx.getAssets().open("changelog.json");
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
            return new JSONObject(sb.toString());
        } finally {
            is.close();
        }
    }

    private static void addVersion(Context ctx, LinearLayout root, JSONObject ver)
            throws Exception {
        String title = ver.getString("version_name") + " (" + ver.getString("build") + ")";
        addText(ctx, root, title, 17, true, 0xFFFFFFFF);
        String dateLabel = ver.optString("date_label", "");
        if (dateLabel.length() > 0) {
            addText(ctx, root, dateLabel + (ver.optBoolean("current") ? "  ·  当前版本" : ""),
                    12, false, 0xFF8A8A8A);
        }
        JSONArray sections = ver.getJSONArray("sections");
        for (int s = 0; s < sections.length(); s++) {
            JSONObject sec = sections.getJSONObject(s);
            String secTitle = sec.optString("title", "");
            if (secTitle.length() > 0) {
                addText(ctx, root, secTitle, 15, true, 0xFFE0E0E0);
            }
            JSONArray paras = sec.optJSONArray("paragraphs");
            if (paras != null) {
                for (int i = 0; i < paras.length(); i++) {
                    addText(ctx, root, paras.getString(i), 13, false, 0xFFB8B8B8);
                }
            }
            JSONArray items = sec.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    addText(ctx, root, items.getString(i), 13, false, 0xFFB8B8B8);
                }
            }
        }
        View divider = new View(ctx);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 1));
        dl.topMargin = dp(ctx, 16);
        dl.bottomMargin = dp(ctx, 16);
        divider.setLayoutParams(dl);
        divider.setBackgroundColor(0xFF222222);
        root.addView(divider);
    }

    static TextView addText(Context ctx, LinearLayout root, String text,
                            float sp, boolean bold, int color) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        if (bold) {
            tv.setTypeface(Typeface.DEFAULT_BOLD);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 4);
        lp.bottomMargin = dp(ctx, 4);
        root.addView(tv, lp);
        return tv;
    }

    static int dp(Context ctx, float dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                ctx.getResources().getDisplayMetrics());
    }
}
