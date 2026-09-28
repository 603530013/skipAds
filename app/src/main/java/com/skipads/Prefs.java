package com.skipads;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 共用設定，MainActivity 寫入、AdSkipService 讀取。 */
final class Prefs {
    private static final String NAME = "skipads";
    static final String KEY_ENABLED = "enabled";
    static final String KEY_TARGET = "target_package";
    static final String KEY_OCR = "ocr_enabled";
    static final String KEY_EXTRA_KEYWORDS = "extra_keywords";
    static final String KEY_LOG = "log";

    private static final int MAX_LOG_LINES = 30;

    private Prefs() {}

    static SharedPreferences get(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    static boolean isEnabled(Context context) {
        return get(context).getBoolean(KEY_ENABLED, false);
    }

    static String targetPackage(Context context) {
        return get(context).getString(KEY_TARGET, null);
    }

    static boolean isOcrEnabled(Context context) {
        return get(context).getBoolean(KEY_OCR, true);
    }

    static String extraKeywords(Context context) {
        return get(context).getString(KEY_EXTRA_KEYWORDS, "");
    }

    /** 把動作記錄加到最前面，只保留最近幾筆。 */
    static void log(Context context, String message) {
        SharedPreferences prefs = get(context);
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        String old = prefs.getString(KEY_LOG, "");
        StringBuilder sb = new StringBuilder(time).append("  ").append(message);
        String[] lines = old.split("\n");
        for (int i = 0; i < lines.length && i < MAX_LOG_LINES - 1; i++) {
            if (!lines[i].isEmpty()) sb.append('\n').append(lines[i]);
        }
        prefs.edit().putString(KEY_LOG, sb.toString()).apply();
    }
}
