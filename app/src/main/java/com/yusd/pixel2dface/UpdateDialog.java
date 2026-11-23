package com.yusd.pixel2dface;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.text.format.Formatter;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import org.json.JSONException;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Owns the manual update request and its dialogs for one settings activity. */
final class UpdateDialog {
    private final Activity activity;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private AlertDialog dialog;
    private Future<?> request;

    UpdateDialog(Activity activity) {
        this.activity = activity;
    }

    void check() {
        if (dialog != null || activity.isFinishing()) return;
        LinearProgressIndicator progressBar = new LinearProgressIndicator(activity);
        progressBar.setIndeterminate(true);
        LinearLayout content = Ui.column(activity);
        int inset = Ui.dp(activity, 24);
        content.setPadding(inset, 0, inset, Ui.dp(activity, 12));
        content.addView(progressBar, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog progress = new MaterialAlertDialogBuilder(activity)
                .setTitle("检查更新")
                .setMessage("正在从 GitHub 查询最新正式版…")
                .setView(content)
                .setNegativeButton("取消", null)
                .create();
        show(progress);
        request = worker.submit(() -> {
            try {
                GitHubUpdates.Release release = GitHubUpdates.check(BuildConfig.VERSION_NAME);
                activity.runOnUiThread(() -> complete(progress, release, null));
            } catch (GitHubUpdates.RateLimitedException e) {
                activity.runOnUiThread(() -> complete(progress, null, "GitHub 暂时限制了查询次数，请稍后再试。"));
            } catch (IOException | JSONException e) {
                activity.runOnUiThread(() -> complete(progress, null, "暂时无法获取更新信息。请检查网络后重试。"));
            }
        });
    }

    private void complete(AlertDialog progress, GitHubUpdates.Release release, String error) {
        if (dialog != progress || !progress.isShowing() || activity.isFinishing() || activity.isDestroyed()) return;
        request = null;
        progress.dismiss();
        MaterialAlertDialogBuilder result = new MaterialAlertDialogBuilder(activity);
        if (release == null) {
            result.setTitle("检查更新失败")
                    .setMessage(error)
                    .setNeutralButton("GitHub 发布页", (d, which) -> open(GitHubUpdates.RELEASES_URL))
                    .setNegativeButton("关闭", null)
                    .setPositiveButton("重试", (d, which) -> {
                        // A button callback runs before the framework dismisses the current dialog.
                        dialog = null;
                        check();
                    });
        } else {
            String versions = "当前版本 " + BuildConfig.VERSION_NAME + "\n最新正式版 " + release.version;
            result.setNeutralButton("GitHub 发布页", (d, which) -> open(release.pageUrl));
            if (release.newer) {
                String details = versions;
                if (release.downloadUrl != null)
                    details += "\n安装包 " + Formatter.formatShortFileSize(activity, release.bytes);
                if (!release.notes.isEmpty()) details += "\n\n" + release.notes;
                result.setTitle("发现新版本")
                        .setMessage(details)
                        .setNegativeButton("稍后", null);
                if (release.downloadUrl != null) {
                    result.setPositiveButton("下载 APK", (d, which) -> open(release.downloadUrl));
                } else {
                    result.setPositiveButton("查看发布详情", (d, which) -> open(release.pageUrl))
                            .setNeutralButton(null, null);
                }
            } else {
                result.setTitle("暂无可用更新").setMessage(versions).setPositiveButton("完成", null);
            }
        }
        show(result.create());
    }

    private void show(AlertDialog next) {
        dialog = next;
        next.setOnDismissListener(d -> {
            if (dialog != next) return;
            dialog = null;
            if (request != null) {
                request.cancel(true);
                request = null;
            }
        });
        next.show();
    }

    private void open(String url) {
        Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            activity.startActivity(browser);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, "请安装浏览器后再打开 GitHub。", Toast.LENGTH_LONG).show();
        }
    }

    void close() {
        worker.shutdownNow();
        if (dialog != null) dialog.dismiss();
        dialog = null;
    }
}
