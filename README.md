# SkipAds 自動跳廣告（Android）

每秒檢查一次螢幕畫面：

1. **目標 App 在前景時**：尋找廣告上的「X / × / 關閉 / 跳過 / Skip / Close」或含「加速」的按鈕並自動點擊。
   - 先搜尋無障礙元件樹（文字、內容描述、`ad_close`、`iv_close`、`btn_skip` 這類 view id）。
   - 找不到時（Android 11 以上）會截圖並用 ML Kit 中文 OCR 辨識畫面上的文字；
     單獨的「X」字元只在畫面上方或下方角落、且尺寸夠小時才會點，以減少誤觸。
   - 可在 App 內加入額外關鍵字（逗號分隔）。
2. **其它 App 蓋在目標 App 上時**（例如點到廣告跳去 Play 商店或瀏覽器）：每秒按一次返回鍵，
   直到目標 App 回到前景。連按 5 次仍無效、或被返回到桌面時，會直接重新開啟目標 App。
   - 使用者自己按 Home 回桌面、或打開本 App 時會停止「拉回」，直到再次進入目標 App。

## 使用方式

1. 安裝後開啟 App，按「選擇目標 App」選要跳廣告的 App（例如某個遊戲）。
2. 按「開啟無障礙設定」，找到「SkipAds 自動跳廣告」並開啟
   （Android 13 以上若是側載安裝，需先到 App 資訊 → 右上角選單 → 「允許受限制的設定」）。
3. 打開「啟用自動跳廣告」開關。「最近動作」會顯示每次點擊 / 返回的紀錄。

## 建置

需要 Android SDK（compileSdk 35）與 JDK 17+：

```sh
./gradlew assembleDebug
# APK：app/build/outputs/apk/debug/app-debug.apk
```

或直接用 Android Studio 開啟此資料夾。

## 程式結構

- `AdSkipService.java`：無障礙服務，每秒輪詢、點擊按鈕、按返回、截圖 OCR。
- `AdButtonMatcher.java`：判斷文字 / view id 是否為關閉或加速按鈕。
- `MainActivity.java`：設定畫面（目標 App、開關、關鍵字、動作紀錄）。
- `Prefs.java`：共用設定與動作紀錄。
