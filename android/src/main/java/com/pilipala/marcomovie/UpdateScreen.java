// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.marcomovie;

import android.content.*;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.TextView;

/** Uses the app's TV controls; only installation and its permission belong to Android. */
final class UpdateScreen {
    static final int INSTALL = 1401;
    final MainActivity a;
    final UpdateManager updates;
    TextView status;

    UpdateScreen(MainActivity host) {
        a = host;
        updates = UpdateManager.get(a);
    }

    void show() {
        SettingsScreen settings = new SettingsScreen(a);
        settings.page("软件更新", "镜像优先，连接失败时尝试 GitHub。安装前会核对版本、文件完整性与签名。");
        a.updateScreen = this;
        status = a.text("", 16, MainActivity.MUTED);
        status.setTag("update-status");
        status.setLineSpacing(a.dp(4), 1);
        a.body.addView(status);
        a.gap(a.body, 18);
        settings.link("检查更新", "当前使用正式版更新渠道", "update-check", () -> updates.check(true));
        settings.link(
                "下载新版",
                "下载后仍需你确认安装",
                "update-download",
                () -> {
                    if (updates.available == null) a.toast("请先检查更新");
                    else updates.download();
                });
        settings.link("安装已下载版本", "覆盖升级保留配置、收藏和观看记录", "update-install", this::install);
        settings.link(
                "更新内容",
                "查看新版说明",
                "update-notes",
                () ->
                        a.notice(
                                updates.available == null
                                        ? "尚未发现更高版本。"
                                        : updates.available.version
                                                + "\n\n"
                                                + updates.available.notes));
        settings.link("更新方式", "自动检查提醒、自动下载或仅手动", "update-mode", this::mode);
        settings.link(
                "取消下载或检查",
                "取消不会影响已安装版本",
                "update-cancel",
                () -> {
                    if (updates.busy) updates.cancel();
                    else a.toast("当前没有进行中的任务");
                });
        refresh();
    }

    void refresh() {
        if (status != null && status.isAttachedToWindow())
            status.setText(
                    "当前版本 "
                            + UpdateManager.installedVersion(a)
                            + " · 正式版\n"
                            + updates.message
                            + (updates.percent >= 0 ? " · " + updates.percent + "%" : "")
                            + "\n"
                            + modeName(updates.mode()));
    }

    static String modeName(String mode) {
        return "off".equals(mode) ? "仅手动检查" : "download".equals(mode) ? "自动下载后提醒" : "自动检查并提醒";
    }

    void mode() {
        String[] modes = {"notify", "download", "off"};
        new AppDialog.Builder(a)
                .setTitle("更新方式")
                .setMessage("自动检查至少间隔六小时，仅在应用首页运行时发起。播放过程中不弹出更新窗口；安装始终由你确认。")
                .setSingleChoiceItems(
                        new String[] {"自动检查并提醒", "自动下载后提醒", "仅手动检查"},
                        java.util.Arrays.asList(modes).indexOf(updates.mode()),
                        (d, n) -> {
                            updates.setMode(modes[n]);
                            refresh();
                        })
                .setNegativeButton("返回", null)
                .show();
    }

    void install() {
        if (updates.busy) {
            a.toast("请等待当前任务完成");
            return;
        }
        if (!updates.ready()) {
            a.toast("请先下载并校验新版");
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            new AppDialog.Builder(a)
                    .setTitle("允许安装更新")
                    .setMessage("电视需要你允许 Marco Movie 安装应用。打开系统授权后，返回本页再次选择安装。")
                    .setPositiveButton(
                            "打开授权页面",
                            (d, n) -> {
                                try {
                                    a.startActivity(
                                            new Intent(
                                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                                    Uri.parse("package:" + a.getPackageName())));
                                } catch (RuntimeException e) {
                                    a.notice("这台电视未提供授权页面，请使用电脑或 U 盘安装。");
                                }
                            })
                    .setNegativeButton("稍后", null)
                    .show();
            return;
        }
        new AppDialog.Builder(a)
                .setTitle("安装 " + updates.available.version + "？")
                .setMessage("将打开电视系统的安装确认页面。覆盖升级保留配置、收藏和观看记录。")
                .setPositiveButton("继续安装", (d, n) -> prepareInstall())
                .setNegativeButton("稍后", null)
                .show();
    }

    static Intent installIntent() {
        Intent intent =
                new Intent(Intent.ACTION_INSTALL_PACKAGE)
                        .setDataAndType(
                                UpdateApkProvider.URI, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .putExtra(Intent.EXTRA_RETURN_RESULT, true);
        intent.setClipData(ClipData.newRawUri("Marco Movie update", UpdateApkProvider.URI));
        return intent;
    }

    void prepareInstall() {
        final UpdateManager.Release release = updates.available;
        if (release == null || updates.busy) return;
        final int epoch = a.generation;
        updates.busy = true;
        updates.resetCancellation();
        updates.message = "正在核对安装包…";
        refresh();
        updates.worker.execute(
                () -> {
                    try {
                        UpdateManager.verifyApk(a, updates.apk(), release);
                        a.ui.post(
                                () -> {
                                    updates.busy = false;
                                    if (!a.foreground
                                            || a.isFinishing()
                                            || a.isDestroyed()
                                            || epoch != a.generation
                                            || updates.cancelled()
                                            || status == null
                                            || !status.isAttachedToWindow()) {
                                        updates.message = "校验完成，回到软件更新页后可继续安装";
                                        return;
                                    }
                                    try {
                                        updates.prefs
                                                .edit()
                                                .putInt("pending_code", release.code)
                                                .commit();
                                        a.startActivityForResult(installIntent(), INSTALL);
                                        updates.message = "请在系统安装页面确认或返回";
                                    } catch (RuntimeException error) {
                                        updates.prefs.edit().remove("pending_code").apply();
                                        updates.message = "无法打开安装器，请使用电脑或 U 盘安装";
                                    }
                                    refresh();
                                });
                    } catch (Exception error) {
                        a.ui.post(
                                () -> {
                                    updates.busy = false;
                                    updates.message = "安装包校验失败，请重新下载";
                                    refresh();
                                });
                    }
                });
    }

    static void result(MainActivity a) {
        UpdateManager updates = UpdateManager.get(a);
        updates.prefs.edit().remove("pending_code").apply();
        updates.message = "已返回应用；若取消安装，原版本继续保留";
        if (a.updateScreen != null) a.updateScreen.refresh();
    }
}
