package io.github.rtzhang.nothingupdatefix;

import android.app.Activity;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/**
 * Deep-link target for the '更新日志' preference injected into the Settings
 * software info page (chain A). Shows the bundled changelog viewer.
 */
public class ChangelogActivity extends Activity {

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

        ChangelogView.addText(this, root, "更新日志", 22, true, 0xFFFFFFFF);
        ChangelogView.addText(this, root,
                "Nothing Phone (3) · Nothing OS 5.0 (C5.0-260924-2115) 及历史版本", 12, false,
                0xFF9A9A9A);
        root.addView(ChangelogView.build(this));

        setContentView(scroll);
    }
}
