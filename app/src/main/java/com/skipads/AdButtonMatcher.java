package com.skipads;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** 判斷一段文字 / 元件 ID 是否像廣告的「關閉 (X)」或「加速」按鈕。 */
final class AdButtonMatcher {

    /** 整段文字完全等於這些字時才算（避免把一般文字裡的 x 誤判）。 */
    private static final List<String> CLOSE_EXACT = Arrays.asList(
            "x", "×", "✕", "✖", "✗", "✘", "╳", "ⓧ", "⨯", "⊗", "⊠", "❌", "❎",
            "close", "close ad", "close advertisement", "dismiss",
            "skip", "skip ad", "skip ads", "skip video",
            "關閉", "关闭", "關閉廣告", "关闭广告",
            "跳過", "跳过", "跳過廣告", "跳过广告", "略過", "略过");

    /** 文字中包含這些字就算。 */
    private static final List<String> CONTAINS = Arrays.asList("加速");

    /**
     * 加速 / 快轉符號，例如 »、››、>>、＞＞、►｜、▶▶|、⏩、⏭（去掉空白後整段比對）。
     * 單一個 >、›、►、▶ 常是一般的「下一頁 / 播放」，所以至少要兩個箭頭，或箭頭後接直線；
     * 本身就是雙箭頭的字元（»、≫、⏩、》…）一個就算。直線包含 OCR 常誤認的 I、l（比對前已轉小寫）。
     */
    private static final String ARROW = "[>＞›►▶▸▹⯈⮞〉]";
    private static final String DOUBLE_ARROW = "[»≫⏩⏭》]";
    private static final String BAR = "[|｜│┃ǀ丨il]";
    private static final Pattern SPEED_SYMBOL = Pattern.compile(
            "^(?:(?:" + DOUBLE_ARROW + "|" + ARROW + "{2,})+" + BAR + "?"
                    + "|" + ARROW + BAR + ")$");

    /** 常見廣告 SDK 關閉按鈕的 view id。 */
    private static final Pattern CLOSE_ID = Pattern.compile(
            "(^|[_:/.])(ad_?)?(close|skip|dismiss)(_?(btn|button|icon|iv|img|view|ad))?($|[_\\d])",
            Pattern.CASE_INSENSITIVE);

    private final List<String> extraKeywords = new ArrayList<>();

    AdButtonMatcher(String extraKeywordsCsv) {
        if (extraKeywordsCsv != null) {
            for (String k : extraKeywordsCsv.split("[,，、]")) {
                String t = normalize(k);
                if (!t.isEmpty()) extraKeywords.add(t);
            }
        }
    }

    static String normalize(CharSequence s) {
        if (s == null) return "";
        return s.toString().trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** 依文字（text 或 contentDescription）判斷。 */
    boolean matchesText(CharSequence raw) {
        String t = normalize(raw);
        if (t.isEmpty() || t.length() > 20) return false;
        if (CLOSE_EXACT.contains(t)) return true;
        if (isSpeedSymbol(t)) return true;
        for (String k : CONTAINS) if (t.contains(k)) return true;
        for (String k : extraKeywords) if (t.contains(k)) return true;
        return false;
    }

    /** 是否為加速 / 快轉符號（例如 »、>>、►｜）。 */
    static boolean isSpeedSymbol(CharSequence raw) {
        String t = normalize(raw).replace(" ", "");
        return !t.isEmpty() && t.length() <= 6 && SPEED_SYMBOL.matcher(t).matches();
    }

    /** 依 view id（例如 com.foo:id/ad_close_btn）判斷。 */
    static boolean matchesViewId(String viewId) {
        if (viewId == null || viewId.isEmpty()) return false;
        int slash = viewId.indexOf(":id/");
        String name = slash >= 0 ? viewId.substring(slash + 4) : viewId;
        return CLOSE_ID.matcher(name).find();
    }

    /**
     * OCR 辨識出的單一「X」字元比較容易誤判，因此另外要求它夠小、位於畫面上方或角落。
     * 加速符號只要求尺寸夠小（它可能出現在畫面任何位置）。
     */
    boolean matchesOcrText(String raw, int left, int top, int right, int bottom,
                           int screenW, int screenH) {
        String t = normalize(raw);
        if (t.isEmpty()) return false;
        int w = right - left, h = bottom - top;
        int maxSize = Math.max(screenW, screenH) / 12;
        boolean small = w <= maxSize && h <= maxSize;
        if (isSpeedSymbol(t)) return small;
        boolean singleX = t.length() == 1 && CLOSE_EXACT.contains(t);
        if (!singleX) return matchesText(t);
        if (!small) return false;
        int cx = (left + right) / 2, cy = (top + bottom) / 2;
        boolean nearTop = cy < screenH / 4;
        boolean nearBottom = cy > screenH * 3 / 4;
        boolean nearSide = cx < screenW / 5 || cx > screenW * 4 / 5;
        return nearTop || (nearBottom && nearSide);
    }
}
