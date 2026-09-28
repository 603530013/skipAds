package com.skipads;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * 每秒檢查一次畫面：
 * 1. 目標 App 在前景 → 找廣告上的「X / 關閉 / 跳過 / 加速」按鈕並點擊。
 *    先搜尋無障礙元件樹，找不到時（Android 11+）截圖並以 OCR 找文字。
 * 2. 其它 App 蓋在目標 App 上 → 按返回鍵，直到目標 App 回到前景；
 *    多次返回仍無效（或被退回桌面）時直接重新開啟目標 App。
 */
public class AdSkipService extends AccessibilityService {

    private static final long TICK_MS = 1000;
    /** 同一個位置點過後，至少隔這麼久才再點一次，避免連點。 */
    private static final long SAME_SPOT_COOLDOWN_MS = 3000;
    /** 連續按返回幾次仍回不去，就改為直接開啟目標 App。 */
    private static final int MAX_BACKS_BEFORE_RELAUNCH = 5;
    /** 重新開啟幾次仍失敗就放棄，直到使用者自己回到目標 App。 */
    private static final int MAX_RELAUNCHES = 3;
    /** 按返回後若在這段時間內跑到桌面，視為被我們退出，要重開目標 App。 */
    private static final long BACK_TO_HOME_WINDOW_MS = 3000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::onTick;

    private final Set<String> launcherPackages = new HashSet<>();
    private final Set<String> imePackages = new HashSet<>();

    /** 目標 App 曾在前景過；只有這時候才會把它「拉回來」。 */
    private boolean armed = false;
    private int backCount = 0;
    private int relaunchCount = 0;
    private long lastBackAt = 0;

    private final Rect lastClickRect = new Rect();
    private long lastClickAt = 0;

    private TextRecognizer recognizer;
    private boolean ocrBusy = false;
    private long lastOcrAt = 0;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        refreshSystemPackages();
        Prefs.log(this, "無障礙服務已啟動");
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, TICK_MS);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 以固定每秒輪詢為主，事件不需處理。
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        if (recognizer != null) recognizer.close();
        super.onDestroy();
    }

    private void onTick() {
        try {
            if (Prefs.isEnabled(this)) check();
        } catch (RuntimeException e) {
            Prefs.log(this, "錯誤：" + e);
        } finally {
            handler.postDelayed(tick, TICK_MS);
        }
    }

    private void check() {
        String target = Prefs.targetPackage(this);
        if (target == null) return;

        AccessibilityNodeInfo root = findForegroundAppRoot();
        if (root == null) return;
        CharSequence pkgCs = root.getPackageName();
        String fg = pkgCs == null ? null : pkgCs.toString();
        if (fg == null) return;

        if (fg.equals(target)) {
            armed = true;
            backCount = 0;
            relaunchCount = 0;
            skipAds(root);
            return;
        }

        if (fg.equals(getPackageName())) {
            // 使用者打開了本 App 的設定畫面，不要干擾。
            armed = false;
            return;
        }
        if (!armed || imePackages.contains(fg) || fg.equals("com.android.systemui")) return;

        if (launcherPackages.contains(fg)) {
            if (SystemClock.uptimeMillis() - lastBackAt < BACK_TO_HOME_WINDOW_MS) {
                // 是我們按返回鍵把目標 App 退掉的 → 重新開啟。
                relaunchTarget(target);
            } else {
                // 使用者自己按了 Home，停止追蹤。
                armed = false;
            }
            return;
        }

        // 其它 App 蓋在目標 App 上面。
        if (backCount < MAX_BACKS_BEFORE_RELAUNCH) {
            backCount++;
            lastBackAt = SystemClock.uptimeMillis();
            performGlobalAction(GLOBAL_ACTION_BACK);
            Prefs.log(this, "前景是 " + fg + "，按返回 (" + backCount + ")");
        } else {
            relaunchTarget(target);
        }
    }

    private void relaunchTarget(String target) {
        backCount = 0;
        lastBackAt = 0;
        if (relaunchCount >= MAX_RELAUNCHES) {
            armed = false;
            Prefs.log(this, "無法回到目標 App，暫停追蹤");
            return;
        }
        relaunchCount++;
        Intent intent = getPackageManager().getLaunchIntentForPackage(target);
        if (intent == null) {
            armed = false;
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(intent);
            Prefs.log(this, "重新開啟目標 App");
        } catch (RuntimeException e) {
            Prefs.log(this, "無法開啟目標 App：" + e.getMessage());
        }
    }

    /** 取得最上層「應用程式」視窗的根節點（略過輸入法、狀態列等系統視窗）。 */
    private AccessibilityNodeInfo findForegroundAppRoot() {
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows != null) {
            // getWindows() 依 Z 軸由上而下排序
            for (AccessibilityWindowInfo w : windows) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null && r.getPackageName() != null) return r;
            }
        }
        return getRootInActiveWindow();
    }

    // ---------------------------------------------------------------- 找廣告按鈕

    private void skipAds(AccessibilityNodeInfo targetRoot) {
        AdButtonMatcher matcher = new AdButtonMatcher(Prefs.extraKeywords(this));

        // 廣告可能在目標 App 的任一應用程式視窗（包含對話框）裡。
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows != null) {
            for (AccessibilityWindowInfo w : windows) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null || r.getPackageName() == null
                        || !targetRoot.getPackageName().toString().equals(r.getPackageName().toString())) {
                    continue;
                }
                if (clickFirstMatch(r, matcher)) return;
            }
        } else if (clickFirstMatch(targetRoot, matcher)) {
            return;
        }

        if (Prefs.isOcrEnabled(this)) screenshotAndOcr(matcher);
    }

    private boolean clickFirstMatch(AccessibilityNodeInfo root, AdButtonMatcher matcher) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited < 3000) {
            AccessibilityNodeInfo n = queue.poll();
            visited++;
            if (n == null) continue;
            if (n.isVisibleToUser() && isAdButton(n, matcher)) {
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                if (!r.isEmpty() && click(n, r, describe(n))) return true;
            }
            for (int i = 0; i < n.getChildCount(); i++) queue.add(n.getChild(i));
        }
        return false;
    }

    private static boolean isAdButton(AccessibilityNodeInfo n, AdButtonMatcher matcher) {
        return matcher.matchesText(n.getText())
                || matcher.matchesText(n.getContentDescription())
                || AdButtonMatcher.matchesViewId(n.getViewIdResourceName());
    }

    private static String describe(AccessibilityNodeInfo n) {
        if (n.getText() != null && n.getText().length() > 0) return "「" + n.getText() + "」";
        if (n.getContentDescription() != null && n.getContentDescription().length() > 0) {
            return "「" + n.getContentDescription() + "」";
        }
        return String.valueOf(n.getViewIdResourceName());
    }

    /** 先嘗試元件本身或可點擊的父元件，失敗則在按鈕中心模擬觸控。 */
    private boolean click(AccessibilityNodeInfo node, Rect bounds, String what) {
        long now = SystemClock.uptimeMillis();
        if (bounds.equals(lastClickRect) && now - lastClickAt < SAME_SPOT_COOLDOWN_MS) return false;

        boolean ok = false;
        if (node != null) {
            AccessibilityNodeInfo n = node;
            for (int depth = 0; n != null && depth < 3; depth++) {
                if (n.isClickable()) {
                    ok = n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    break;
                }
                n = n.getParent();
            }
        }
        if (!ok) ok = tap(bounds.centerX(), bounds.centerY());

        if (ok) {
            lastClickRect.set(bounds);
            lastClickAt = now;
            Prefs.log(this, "點擊 " + what);
        }
        return ok;
    }

    private boolean tap(int x, int y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 50))
                .build();
        return dispatchGesture(gesture, null, null);
    }

    // ---------------------------------------------------------------- 截圖 + OCR

    private void screenshotAndOcr(AdButtonMatcher matcher) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        long now = SystemClock.uptimeMillis();
        if (ocrBusy || now - lastOcrAt < TICK_MS - 100) return;
        ocrBusy = true;
        lastOcrAt = now;

        Executor mainExecutor = handler::post;
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult result) {
                Bitmap bitmap = null;
                try (HardwareBuffer buffer = result.getHardwareBuffer()) {
                    Bitmap hw = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                    if (hw != null) {
                        bitmap = hw.copy(Bitmap.Config.ARGB_8888, false);
                        hw.recycle();
                    }
                }
                if (bitmap == null) {
                    ocrBusy = false;
                    return;
                }
                runOcr(bitmap, matcher);
            }

            @Override
            public void onFailure(int errorCode) {
                ocrBusy = false;
            }
        });
    }

    private void runOcr(Bitmap bitmap, AdButtonMatcher matcher) {
        if (recognizer == null) {
            recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        }
        final int w = bitmap.getWidth(), h = bitmap.getHeight();
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener(text -> {
                    handleOcrResult(text, matcher, w, h);
                })
                .addOnCompleteListener(task -> {
                    bitmap.recycle();
                    ocrBusy = false;
                });
    }

    private void handleOcrResult(Text text, AdButtonMatcher matcher, int w, int h) {
        // 只在目標 App 仍在前景時才點
        AccessibilityNodeInfo root = findForegroundAppRoot();
        String target = Prefs.targetPackage(this);
        if (root == null || target == null || root.getPackageName() == null
                || !target.equals(root.getPackageName().toString())) {
            return;
        }
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                if (tryOcrClick(line.getText(), line.getBoundingBox(), matcher, w, h)) return;
                for (Text.Element el : line.getElements()) {
                    if (tryOcrClick(el.getText(), el.getBoundingBox(), matcher, w, h)) return;
                }
            }
        }
    }

    private boolean tryOcrClick(String s, Rect box, AdButtonMatcher matcher, int w, int h) {
        if (box == null) return false;
        if (!matcher.matchesOcrText(s, box.left, box.top, box.right, box.bottom, w, h)) return false;
        return click(null, box, "（截圖辨識）「" + s + "」");
    }

    // ---------------------------------------------------------------- 系統 App 名單

    private void refreshSystemPackages() {
        launcherPackages.clear();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)) {
            launcherPackages.add(ri.activityInfo.packageName);
        }
        imePackages.clear();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            for (InputMethodInfo info : imm.getEnabledInputMethodList()) {
                imePackages.add(info.getPackageName());
            }
        }
        String defaultIme = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (defaultIme != null) {
            ComponentName cn = ComponentName.unflattenFromString(defaultIme);
            if (cn != null) imePackages.add(cn.getPackageName());
        }
    }
}
