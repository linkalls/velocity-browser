package dev.velocity.chromium;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Diagnostic launcher intentionally runs in :diagnostic, separate from
 * Chromium's default process. SDK provider auto-init crashes in the default
 * process will not prevent the user from seeing last-exit diagnostics.
 * This is a probe, not a fix for a Cefrium crash.
 */
public final class StartupDiagnosticActivity extends Activity {
    private TextView status;
    private TextView crashStack;
    private String lastCrashText = "";

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView text(LinearLayout column, String value, int sp) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(Color.rgb(235, 239, 249));
        view.setTextSize(sp);
        view.setPadding(0, dp(9), 0, dp(9));
        column.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(Color.rgb(24, 27, 34));
        getWindow().setNavigationBarColor(Color.rgb(24, 27, 34));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(24, 27, 34));
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(20), dp(22), dp(20), dp(20));
        scroll.addView(column);
        setContentView(scroll);

        text(column, "Velocity Chromium — 起動診断", 21);
        text(column, "この画面は Chromium を初期化しない別プロセスで動きます。"
            + " 下のボタンで本体を起動し、すぐに落ちるか確かめられます。", 14);
        Button launch = new Button(this);
        launch.setText("Chromiumエンジンを起動");
        launch.setAllCaps(false);
        launch.setOnClickListener(view -> {
            try {
                startActivity(new Intent(this, ChromiumTestActivity.class));
            } catch (RuntimeException exception) {
                status.setText("起動Intent失敗: " + exception.getClass().getSimpleName());
            }
        });
        column.addView(launch, new LinearLayout.LayoutParams(-1, dp(55)));
        // The shell cannot launch ChromiumTestActivity directly: it is not
        // exported. CI starts this exported diagnostic Activity instead and
        // asks it to launch Chromium internally, exercising the real provider.
        if (getIntent() != null && getIntent().getBooleanExtra("smoke_test", false)) {
            launch.postDelayed(launch::performClick, 300);
        }
        status = text(column, "直近のプロセス終了記録: 取得中", 14);
        Button refresh = new Button(this);
        refresh.setAllCaps(false);
        refresh.setText("クラッシュ情報を更新");
        refresh.setOnClickListener(v -> { refreshCrashInfo(); refreshJavaCrash(); });
        column.addView(refresh, new LinearLayout.LayoutParams(-1, dp(55)));
        Button copy = new Button(this);
        copy.setAllCaps(false);
        copy.setText("Javaクラッシュの詳細をコピー");
        copy.setOnClickListener(v -> {
            if (lastCrashText.isEmpty()) {
                status.setText("Java例外の保存記録なし。PCのadb logcatを確認してください。");
                return;
            }
            ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Cefrium Java crash", lastCrashText));
                status.setText("スタックトレースをコピーしました。共有前に個人情報がないか確認してください。");
            }
        });
        column.addView(copy, new LinearLayout.LayoutParams(-1, dp(55)));
        crashStack = text(column, "Java例外の記録: 未取得", 12);
        crashStack.setTextIsSelectable(true);
        text(column, "Native CrashやContentProvider初期化失敗はこの画面で"
            + "直接修復できません。詳細なスタックトレースにはadb logcatが必要です。"
            + " このAPKはデバッグ専用。重要なアカウントへのログインは避けてください。", 13);
        refreshCrashInfo();
    }

    @Override protected void onResume() {
        super.onResume();
        if (status != null) { refreshCrashInfo(); refreshJavaCrash(); }
    }

    private void refreshCrashInfo() {
        if (Build.VERSION.SDK_INT < 30) {
            status.setText("終了記録: Android 11以上で利用できます");
            return;
        }
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (manager == null) {
            status.setText("終了記録: ActivityManagerを取得できません");
            return;
        }
        List<ApplicationExitInfo> infos =
            manager.getHistoricalProcessExitReasons(getPackageName(), 0, 15);
        StringBuilder result = new StringBuilder("Chromium本体の直近終了情報\n");
        int count = 0;
        for (ApplicationExitInfo exit : infos) {
            // Ignore this separate diagnostic process.
            if (!getPackageName().equals(exit.getProcessName())) continue;
            String when = new SimpleDateFormat("MM/dd HH:mm:ss", Locale.JAPAN)
                .format(new Date(exit.getTimestamp()));
            result.append(when).append("  reason=").append(exit.getReason())
                .append(exit.getReason() == ApplicationExitInfo.REASON_CRASH ? " (Java例外)" :
                    exit.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE ? " (native)" : "")
                .append("  status=").append(exit.getStatus()).append('\n');
            if (++count == 5) break;
        }
        if (count == 0) result.append("記録なし — 起動前に未記録の障害が起きた可能性もあります");
        status.setText(result.toString());
    }
    private void refreshJavaCrash() {
        File file = new File(getFilesDir(), ChromiumDiagnosticApplication.CRASH_FILE);
        if (!file.isFile()) {
            lastCrashText = "";
            if (crashStack != null) {
                crashStack.setText("Java例外の詳細記録なし。次にChromium本体を起動して再現してください。");
            }
            return;
        }
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1 && out.size() < 48000) {
                out.write(buffer, 0, Math.min(count, 48000 - out.size()));
            }
            lastCrashText = out.toString(StandardCharsets.UTF_8.name());
            if (crashStack != null) {
                crashStack.setText("Java例外スタックトレース（直近）\n" + lastCrashText);
            }
        } catch (Exception failure) {
            lastCrashText = "";
            if (crashStack != null) {
                crashStack.setText("Java例外記録の読み込み失敗: " +
                    failure.getClass().getSimpleName());
            }
        }
    }

}
