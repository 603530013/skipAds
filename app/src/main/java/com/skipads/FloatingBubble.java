package com.skipads;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 浮在所有 App 之上的小白點（無障礙覆蓋視窗，不需要「顯示在其他應用程式上層」權限）。
 * 可拖曳；點一下開啟選單：設目前前景 App 為目標、開始 / 暫停、開啟設定、隱藏浮球。
 * 中心小點：綠色 = 執行中，灰色 = 暫停。
 */
final class FloatingBubble {

    interface Host {
        /** 目前前景的一般 App 套件名稱（排除桌面、本 App 等），沒有則回傳 null。 */
        String currentForegroundApp();

        /** 使用者從浮球選單把某個 App 設為目標。 */
        void onTargetChosen(String pkg);
    }

    private static final int BUBBLE_DP = 44;
    private static final int DOT_DP = 14;

    private final AccessibilityService service;
    private final Host host;
    private final WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private FrameLayout bubble;
    private View dot;
    private WindowManager.LayoutParams bubbleParams;
    private LinearLayout menu;

    FloatingBubble(AccessibilityService service, Host host) {
        this.service = service;
        this.host = host;
        this.wm = (WindowManager) service.getSystemService(AccessibilityService.WINDOW_SERVICE);
    }

    boolean isShown() {
        return bubble != null;
    }

    void show() {
        if (bubble != null) return;
        int size = dp(BUBBLE_DP);

        bubble = new FrameLayout(service);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.argb(230, 255, 255, 255));
        bg.setStroke(dp(1), Color.argb(120, 0, 0, 0));
        bubble.setBackground(bg);
        bubble.setElevation(dp(4));

        dot = new View(service);
        FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(dp(DOT_DP), dp(DOT_DP), Gravity.CENTER);
        bubble.addView(dot, dotLp);

        bubbleParams = new WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = Prefs.get(service).getInt(Prefs.KEY_BUBBLE_X, screenWidth() - size - dp(8));
        bubbleParams.y = Prefs.get(service).getInt(Prefs.KEY_BUBBLE_Y, dp(200));

        bubble.setOnTouchListener(new DragListener());
        wm.addView(bubble, bubbleParams);
        refresh();
    }

    void hide() {
        dismissMenu();
        if (bubble != null) {
            wm.removeView(bubble);
            bubble = null;
        }
    }

    /** 依目前設定更新中心小點顏色。 */
    void refresh() {
        if (dot == null) return;
        boolean running = Prefs.isEnabled(service) && Prefs.targetPackage(service) != null;
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(running ? Color.rgb(46, 175, 80) : Color.rgb(160, 160, 160));
        dot.setBackground(d);
    }

    /**
     * 模擬觸控的位置剛好在浮球上時，暫時讓浮球不接收觸控，讓點擊穿透到底下的廣告按鈕。
     */
    void letTouchPassAt(int x, int y) {
        if (bubble == null) return;
        int[] loc = new int[2];
        bubble.getLocationOnScreen(loc);
        Rect r = new Rect(loc[0], loc[1], loc[0] + bubble.getWidth(), loc[1] + bubble.getHeight());
        if (!r.contains(x, y)) return;
        setTouchable(false);
        handler.postDelayed(() -> setTouchable(true), 500);
    }

    private void setTouchable(boolean touchable) {
        if (bubble == null) return;
        if (touchable) {
            bubbleParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            bubbleParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        wm.updateViewLayout(bubble, bubbleParams);
    }

    // ---------------------------------------------------------------- 選單

    private void toggleMenu() {
        if (menu != null) {
            dismissMenu();
        } else {
            showMenu();
        }
    }

    private void showMenu() {
        menu = new LinearLayout(service);
        menu.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), Color.argb(60, 0, 0, 0));
        menu.setBackground(bg);
        menu.setElevation(dp(6));
        menu.setPadding(0, dp(4), 0, dp(4));

        String fg = host.currentForegroundApp();
        String target = Prefs.targetPackage(service);
        if (fg != null && !fg.equals(target)) {
            addItem("設為目標：" + labelOf(fg), () -> {
                host.onTargetChosen(fg);
                if (!Prefs.isEnabled(service)) {
                    Prefs.get(service).edit().putBoolean(Prefs.KEY_ENABLED, true).apply();
                }
                toast("目標 App：" + labelOf(fg) + "，已開始");
            });
        } else if (target != null) {
            addInfo("目標：" + labelOf(target));
        } else {
            addInfo("請先開啟要跳廣告的 App，再點浮球設為目標");
        }

        boolean enabled = Prefs.isEnabled(service);
        addItem(enabled ? "⏸ 暫停" : "▶ 開始", () ->
                Prefs.get(service).edit().putBoolean(Prefs.KEY_ENABLED, !enabled).apply());
        addItem("⚙ 開啟設定", () -> {
            Intent i = new Intent(service, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            service.startActivity(i);
        });
        addItem("✕ 隱藏浮球（可在設定中再開啟）", () ->
                Prefs.get(service).edit().putBoolean(Prefs.KEY_BUBBLE, false).apply());

        // 點選單外面就關閉
        menu.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_OUTSIDE) {
                dismissMenu();
                return true;
            }
            return false;
        });

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;

        menu.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int menuW = menu.getMeasuredWidth();
        int menuH = menu.getMeasuredHeight();
        int size = dp(BUBBLE_DP);
        lp.x = clamp(bubbleParams.x + size / 2 - menuW / 2, 0, screenWidth() - menuW);
        int below = bubbleParams.y + size + dp(4);
        lp.y = below + menuH <= screenHeight() ? below : Math.max(0, bubbleParams.y - menuH - dp(4));

        wm.addView(menu, lp);
    }

    private void dismissMenu() {
        if (menu != null) {
            wm.removeView(menu);
            menu = null;
        }
    }

    private void addItem(String text, Runnable action) {
        TextView tv = makeRow(text);
        tv.setTextColor(Color.rgb(30, 30, 30));
        tv.setOnClickListener(v -> {
            dismissMenu();
            action.run();
        });
        menu.addView(tv);
    }

    private void addInfo(String text) {
        TextView tv = makeRow(text);
        tv.setTextColor(Color.rgb(110, 110, 110));
        menu.addView(tv);
    }

    private TextView makeRow(String text) {
        TextView tv = new TextView(service);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setMaxWidth(screenWidth() * 3 / 4);
        tv.setPadding(dp(16), dp(10), dp(16), dp(10));
        return tv;
    }

    // ---------------------------------------------------------------- 拖曳

    private final class DragListener implements View.OnTouchListener {
        private final int slop = ViewConfiguration.get(service).getScaledTouchSlop();
        private float downRawX, downRawY;
        private int startX, startY;
        private boolean dragging;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = e.getRawX();
                    downRawY = e.getRawY();
                    startX = bubbleParams.x;
                    startY = bubbleParams.y;
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downRawX, dy = e.getRawY() - downRawY;
                    if (!dragging && Math.hypot(dx, dy) > slop) {
                        dragging = true;
                        dismissMenu();
                    }
                    if (dragging) {
                        int size = dp(BUBBLE_DP);
                        bubbleParams.x = clamp(startX + (int) dx, 0, screenWidth() - size);
                        bubbleParams.y = clamp(startY + (int) dy, 0, screenHeight() - size);
                        wm.updateViewLayout(bubble, bubbleParams);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (dragging) {
                        snapToEdge();
                    } else {
                        toggleMenu();
                    }
                    return true;
                default:
                    return false;
            }
        }
    }

    /** 放開後貼齊左右邊緣，並記住位置。 */
    private void snapToEdge() {
        int size = dp(BUBBLE_DP);
        int margin = dp(4);
        bubbleParams.x = bubbleParams.x + size / 2 < screenWidth() / 2 ? margin : screenWidth() - size - margin;
        wm.updateViewLayout(bubble, bubbleParams);
        Prefs.get(service).edit()
                .putInt(Prefs.KEY_BUBBLE_X, bubbleParams.x)
                .putInt(Prefs.KEY_BUBBLE_Y, bubbleParams.y)
                .apply();
    }

    // ---------------------------------------------------------------- 工具

    private String labelOf(String pkg) {
        try {
            return service.getPackageManager()
                    .getApplicationLabel(service.getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private void toast(String s) {
        Toast.makeText(service, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * service.getResources().getDisplayMetrics().density);
    }

    private int screenWidth() {
        return service.getResources().getDisplayMetrics().widthPixels;
    }

    private int screenHeight() {
        return service.getResources().getDisplayMetrics().heightPixels;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(v, Math.max(min, max)));
    }
}
