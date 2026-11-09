package io.github.rtzhang.nothingupdatefix;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Method;

/**
 * Bundled changelog data + runtime HTML builder for the GMS update_description flag.
 * Changelog text source: nothingfirmware.github.io / nothingarchive.tech (see assets/changelog.json).
 */
public final class ChangelogData {

    public static final String BUILD = "C5.0-260924-2115";
    public static final String UPDATE_TITLE = "Nothing OS 5.0";
    public static final String CHANGELOG_URL = "https://nothingarchive.tech/docs/changelogs";
    public static final String DEVICE = "Nothing Phone (3) (Metroid)";

    private ChangelogData() {}

    // ---- current build changelog (C5.0-260924-2115) ----
    private static final String[] SECTION_TITLES = {
        "",
        "New Features",
        "Visual Enhancements",
        "Fresh Widgets",
        "Camera Enhancements",
        "General Improvements and Bug Fixes",
        "Notes",
    };

    // sections flattened: per section, first the title index, then item/paragraph lines prefixed with '*' or ' '.
    private static final String[] SECTION_LINES = {
        // [0] 
        " You’re now upgrading to the official release of Nothing OS 5.0, powered by Android 17. Please back up your data, including photos, call logs, and contacts, before upgrading. Here's what's new:",
        // [1] New Features
        "*🔮 Introduced the Glyph app, designed specifically for the Glyph Interface, offering quicker access and a refreshed interactive experience. Find it in the app drawer.",
        "*🗄️ Quick Settings and the notification panel now support split, giving you a cleaner layout and easier access. Enable it in Settings > Notifications > General > Notifications and Quick Settings.",
        "*✅ Arranging home screen icons in bulk is now available. Try it out via long-pressing the home screen and tapping on \"Arrange.\"",
        "*🚥 Introduced a new three-colour status bar indicator for scenes of sensitive access, location, AI processing, letting you know device activity instantly.",
        // [2] Visual Enhancements
        "*🫧 Introduced the new Translucent theme for Launcher, featuring an elegant frosted-glass style and subtle accent colours inspired by the wallpaper. Set it up in Customisation > Theme & Style > Translucent.",
        "*🎨 Lock screen customisation now supports setting up a colour for your clock face.",
        "*🕰️ Added two new lock screen clock faces.",
        "*🎴 Updated the wallpaper gallery with a new style wallpaper.",
        "*🛜 Refined the status bar icons with a clearer and more consistent style and subtle animations.",
        "*🖋️ Refined the system design with the newly introduced Geist typeface and refreshed the Settings UI for clearer readability and a more cohesive look.",
        "*🌌 Improved interactions and transition animations across various scenarios for a more fluid experience.",
        // [3] Fresh Widgets
        "*🪆 Introduced Collector widgets, a playful new way to turn your photos into different-style decorations on your home screen.",
        "*⏱️ Away Time widgets are now available, effortlessly tracking your time away from the screen to help you gain better insights into your daily habits.",
        "*⏳ Glyph Timer is now supported in the widget drawer, offering you a handy interaction on your home screen.",
        // [4] Camera Enhancements
        "*🎛️ Introduced Tuning with seven adjustable effects and a reset option.",
        "*🎞️ Upgraded Presets with easier editing, richer cards, Playground integration, and a new 2×2 widget.",
        "*🎥 Enhanced video creation with support for Filters and Tuning, added Dual-view Video, and H.265 encoding.",
        "*✨ Improved recording, zoom, close-up shooting, QR scanning, Google Lens, and Motion Photo playback.",
        "*📷 Refreshed the Camera UI, controls, feedback, and animations.",
        // [5] General Improvements and Bug Fixes
        "*🌑 Expanded Dark theme is now available, automatically applying the Dark theme to most apps that don't support it natively. Adjust it anytime in Settings > Display > Dark theme > Use Expanded Dark theme.",
        "*🔆 Enhanced HDR brightness is now available, allowing you to customize HDR intensity for a comfortable viewing experience. Set it up in Settings > Display > Enhanced HDR brightness.",
        "*👆 Depth (Beta) wallpapers now support pinching to shrink, giving more flexibility for your operation.",
        "*💊 Refined Live Updates with an enhanced status bar experience for ongoing activities.",
        "*👻 Introduced Noto 3D Emoji in Gboard for a more vivid look and expression.",
        "*🎙️ Essential Voice transcriptions now appear faster with reduced latency.",
        "*🏠 Improved interaction with the lock screen shortcuts for a more natural experience.",
        "*🎧 The Bluetooth in Quick Settings now supports instant connection or disconnection with a single tap of the device icon.",
        // [6] Notes
        " The device may temporarily heat up and increase power consumption during the update and will quickly return to normal after the process is complete.",
    };

    private static final int[] SECTION_START = {
        0, 1, 5, 12, 15, 20, 28,
    };

    private static final int SECTION_COUNT = SECTION_TITLES.length;
    private static final int TOTAL_LINES = SECTION_LINES.length;

    // ---- runtime device values (best effort, never throw) ----

    public static String prop(String key, String fallback) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Method m = c.getMethod("get", String.class, String.class);
            Object v = m.invoke(null, key, "");
            if (v instanceof String && ((String) v).length() > 0) {
                return (String) v;
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private static String kernelVersion() {
        String k = prop("ro.kernel.version", "");
        if (k.length() == 0) {
            try {
                k = System.getProperty("os.version", "");
            } catch (Throwable ignored) {
            }
        }
        if (k.length() == 0) {
            k = readFirstLine("/proc/version");
        }
        return k.length() > 0 ? k : "未知";
    }

    private static String readFirstLine(String path) {
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

    private static String htmlEsc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Simple HTML for GMS update_description (rendered via Html.fromHtml):
     * h3 title + changelog sections + h3 固件信息 + table + link.
     */
    public static String buildUpdateDescriptionHtml() {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<h3>Nothing OS 5.0 (").append(BUILD).append(")</h3>");
        for (int s = 0; s < SECTION_COUNT; s++) {
            int start = SECTION_START[s];
            int end = (s + 1 < SECTION_COUNT) ? SECTION_START[s + 1] : TOTAL_LINES;
            String title = SECTION_TITLES[s];
            if (title.length() > 0) {
                sb.append("<h4>").append(htmlEsc(title)).append("</h4>");
            }
            boolean inList = false;
            for (int i = start; i < end; i++) {
                String line = SECTION_LINES[i];
                if (line.startsWith("*")) {
                    if (!inList) {
                        sb.append("<ul>");
                        inList = true;
                    }
                    sb.append("<li>").append(htmlEsc(line.substring(1))).append("</li>");
                } else {
                    if (inList) {
                        sb.append("</ul>");
                        inList = false;
                    }
                    sb.append("<p>").append(htmlEsc(line.substring(1))).append("</p>");
                }
            }
            if (inList) sb.append("</ul>");
        }
        sb.append("<h3>固件信息</h3><table>");
        sb.append("<tr><td>Android 版本：</td><td>")
          .append(htmlEsc(prop("ro.build.version.release", "17"))).append("</td></tr><br/>");
        sb.append("<tr><td>安全更新：</td><td>")
          .append(htmlEsc(prop("ro.build.version.security_patch", "2026-09-01"))).append("</td></tr><br/>");
        sb.append("<tr><td>基带：</td><td>")
          .append(htmlEsc(prop("gsm.version.baseband", "未知"))).append("</td></tr><br/>");
        sb.append("<tr><td>内核：</td><td>")
          .append(htmlEsc(kernelVersion())).append("</td></tr><br/>");
        sb.append("<tr><td>Build 号：</td><td>")
          .append(htmlEsc(prop("ro.build.display.id", prop("ro.build.version.incremental", BUILD))))
          .append("</td></tr><br/>");
        sb.append("</table>");
        sb.append("<p><a href=\"").append(CHANGELOG_URL)
          .append("\">完整更新日志与历史版本（nothingarchive.tech）</a></p>");
        return sb.toString();
    }
}
