package com.pilipala.marcomovie;

import android.content.Intent;
import android.graphics.*;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.io.*;

/** Remote-friendly settings. Each row changes a setting consumed by the app. */
final class SettingsScreen {
    final MainActivity a;

    SettingsScreen(MainActivity activity) {
        a = activity;
    }

    void page(String title, String note) {
        a.renderPage("", "");
        a.body.addView(a.text(title, 28, MainActivity.WHITE));
        a.gap(a.body, 8);
        if (!note.isEmpty()) a.paragraph(note);
    }

    void link(String title, String detail, String key, Runnable action) {
        LinearLayout row = a.row();
        row.setPadding(a.dp(18), a.dp(12), a.dp(18), a.dp(12));
        LinearLayout copy = a.vertical();
        copy.addView(a.text(title, 17, MainActivity.WHITE));
        TextView caption = a.text(detail, 12, MainActivity.MUTED);
        caption.setPadding(0, a.dp(4), 0, 0);
        if (!detail.isEmpty()) copy.addView(caption);
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(a.text("›", 23, MainActivity.MUTED));
        row.setTag(key);
        row.setId(View.generateViewId());
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setClickable(true);
        row.setBackground(a.bg(0xff222226, 12));
        row.setOnFocusChangeListener(
                (v, f) -> {
                    FocusMotion.style(
                            a,
                            row,
                            f,
                            0xff222226,
                            0xff37373e,
                            12,
                            MainActivity.WHITE,
                            MainActivity.WHITE,
                            true,
                            1.008f);
                });
        a.pressFeedback(row);
        row.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.bottomMargin = a.dp(8);
        a.body.addView(row, size);
    }

    void open(Runnable page) {
        a.open(new MainActivity.Screen(page), true);
    }

    void show() {
        page("设置", "");
        link("首页与壁纸", "", "settings-appearance", () -> open(this::appearance));
        link("播放与字幕", "", "settings-playback", () -> open(this::playback));
        link("搜索与数据", "", "settings-data", () -> open(this::data));
        link("软件更新", "", "settings-updates", () -> open(() -> new UpdateScreen(a).show()));
    }

    void decoders() {
        page("解码组件", "组件随应用安装，无需另外下载解码包。");
        a.paragraph(DecoderSupport.description());
    }

    void accountAction(String action) {
        if (a.config != null)
            for (Cms.Site site : a.config.sites)
                if (site.api != null && site.api.contains("MyDrive")) {
                    if (!a.available(site)) {
                        a.notice("请先在“搜索与数据”中启用来源扩展。");
                        return;
                    }
                    a.sourceAction(site, action);
                    return;
                }
        a.notice("当前配置没有提供网盘账号入口，请先导入支持网盘的配置源。");
    }

    void appearance() {
        page("首页与壁纸", "界面按屏幕分辨率自动适配，海报保持 2:3 比例。");
        link(
                "界面大小",
                TvLayoutPolicy.percent(a.prefs.getInt(Prefs.UI_SCALE_PERCENT, 100))
                        + "% · 按钮、文字与图标一起调整",
                "setting-ui_scale_percent",
                () ->
                        new AppDialog.Builder(a)
                                .setTitle("界面大小")
                                .setMessage("标准会自动适配屏幕分辨率。观看距离较远时可放大；选择后界面重新载入。")
                                .setSingleChoiceItems(
                                        new String[] {"标准 · 自动适配", "放大 · 110%", "更大 · 120%"},
                                        a.prefs.getInt(Prefs.UI_SCALE_PERCENT, 100) == 120
                                                ? 2
                                                : a.prefs.getInt(Prefs.UI_SCALE_PERCENT, 100) == 110
                                                        ? 1
                                                        : 0,
                                        (d, n) -> {
                                            a.prefs
                                                    .edit()
                                                    .putInt(
                                                            Prefs.UI_SCALE_PERCENT,
                                                            new int[] {100, 110, 120}[n])
                                                    .commit();
                                            a.recreate();
                                        })
                                .setNegativeButton("返回", null)
                                .show());
        ints(
                "每行海报",
                Prefs.HOME_COLUMNS,
                new String[] {"最多 5 张 · 舒展", "最多 6 张", "最多 7 张", "最多 8 张"},
                new int[] {5, 6, 7, 8},
                5);
        link(
                "首页壁纸",
                a.prefs.getBoolean(Prefs.WALLPAPER_ENABLED, false)
                        ? "自定义壁纸"
                        : a.prefs.getBoolean(Prefs.HOME_AMBIENT, true) ? "海报配色柔光" : "纯深色",
                "settings-wallpaper",
                () -> open(this::wallpaper));
        toggle(
                "减少透明效果",
                Prefs.REDUCED_TRANSPARENCY,
                false,
                () -> com.pilipala.glass.GlassworkSurface.invalidateBackdrop(a.root));
        toggle("开屏动画", Prefs.STARTUP_ANIMATION, true, null);
        toggle("海报配色柔光", Prefs.HOME_AMBIENT, true, null);
        toggle(
                "减少动态效果",
                Prefs.REDUCED,
                false,
                () -> a.reduced = a.prefs.getBoolean(Prefs.REDUCED, false));
    }

    void wallpaper() {
        page("首页壁纸", "壁纸只影响应用背景，不会更改电视桌面。图片以居中裁切显示，自动缩小以减少电视内存占用。");
        link("选择本地图片", "JPG、PNG、WebP", "wallpaper-file", () -> pick(44, "image/*", false));
        link("从图片地址导入", "输入你自己的 HTTP / HTTPS 图片地址", "wallpaper-url", this::wallpaperUrl);
        ints(
                "背景压暗",
                Prefs.WALLPAPER_DIM,
                new String[] {"轻 · 40%", "适中 · 60%", "深 · 80%"},
                new int[] {40, 60, 80},
                60);
        link(
                "恢复默认背景",
                "保留图片文件，随时可以重新启用",
                "wallpaper-reset",
                () -> {
                    a.prefs.edit().putBoolean(Prefs.WALLPAPER_ENABLED, false).apply();
                    a.refresh();
                });
        if (new File(a.getFilesDir(), "wallpaper.jpg").exists())
            link(
                    "启用已保存的壁纸",
                    "使用上次导入的图片",
                    "wallpaper-enable",
                    () -> {
                        a.prefs.edit().putBoolean(Prefs.WALLPAPER_ENABLED, true).apply();
                        a.refresh();
                    });
    }

    void playback() {
        page("播放与字幕", "作为之后播放的默认设置；播放界面中仍可随时调整。");
        ints(
                "画面输出",
                Prefs.VIDEO_OUTPUT,
                new String[] {"保真输出 · HDR 与流畅度优先", "兼容输出 · 支持应用内调色"},
                new int[] {0, 1},
                0);
        ints("播放器", Prefs.PLAYER_ENGINE, new String[] {"Media3", "系统播放器"}, new int[] {0, 1}, 0);
        ints(
                "解码方式",
                Prefs.DECODER_MODE,
                new String[] {"自动", "硬件解码", "软件解码优先"},
                new int[] {0, 1, 2},
                0);
        toggle("优先使用兼容音频解码", Prefs.SOFTWARE_AUDIO, false, null);
        toggle("优先选择 AAC 音轨", Prefs.PREFER_AAC, false, null);
        link("解码组件", "查看本机已加载的音频与视频扩展", "settings-decoders", () -> open(this::decoders));
        ints(
                "画面比例",
                Prefs.ASPECT,
                new String[] {"适应", "铺满", "16:9", "4:3", "裁切填充"},
                new int[] {0, 1, 2, 3, 4},
                0);
        ints(
                "网络缓冲",
                Prefs.BUFFER_SECONDS,
                new String[] {"15 秒 · 节省内存", "30 秒 · 均衡", "50 秒 · 网络波动"},
                new int[] {15, 30, 50},
                30);
        link(
                "默认速度",
                a.prefs.getFloat(Prefs.PLAY_SPEED, 1) + "×",
                "setting-play_speed",
                () ->
                        new AppDialog.Builder(a)
                                .setTitle("播放速度")
                                .setItems(
                                        new String[] {"0.5×", "0.75×", "1×", "1.25×", "1.5×", "2×"},
                                        (d, i) -> {
                                            a.prefs
                                                    .edit()
                                                    .putFloat(
                                                            Prefs.PLAY_SPEED,
                                                            new float[] {
                                                                        .5f, .75f, 1, 1.25f, 1.5f, 2
                                                                    }
                                                                    [i])
                                                    .apply();
                                            a.refresh();
                                        })
                                .show());
        toggle("自动播放下一集", Prefs.AUTO_NEXT, true, null);
        ints("循环", Prefs.REPEAT_MODE, new String[] {"关闭", "单集循环", "列表循环"}, new int[] {0, 1, 2}, 0);
        int skipIntroSec = Prefs.clampSkipSeconds(a.prefs.getInt(Prefs.SKIP_INTRO, 0));
        link(
                "跳过片头（精准拉取节点）",
                skipIntroSec <= 0
                        ? "关闭（点击可手动拉取秒数）"
                        : PlaybackController.formatTime(skipIntroSec * 1000)
                                + " · "
                                + skipIntroSec
                                + " 秒",
                "setting-" + Prefs.SKIP_INTRO,
                () -> PlaybackControls.showSkipAdjuster(a, true, 0, 0, a::refresh));
        int skipOutroSec = Prefs.clampSkipSeconds(a.prefs.getInt(Prefs.SKIP_OUTRO, 0));
        link(
                "跳过片尾（精准拉取节点）",
                skipOutroSec <= 0
                        ? "关闭（点击可手动拉取秒数）"
                        : PlaybackController.formatTime(skipOutroSec * 1000)
                                + " · "
                                + skipOutroSec
                                + " 秒",
                "setting-" + Prefs.SKIP_OUTRO,
                () -> PlaybackControls.showSkipAdjuster(a, false, 0, 0, a::refresh));
        int subSize = Prefs.clampSubtitleSize(a.prefs.getInt(Prefs.SUBTITLE_SIZE, 45));
        int subBottom = Prefs.clampSubtitleBottom(a.prefs.getInt(Prefs.SUBTITLE_BOTTOM, 24));
        int subStyle = Prefs.clampSubtitleStyle(a.prefs.getInt(Prefs.SUBTITLE_STYLE, 0));
        link(
                "字幕大小与位置（方向键直调）",
                "字号 "
                        + subSize
                        + " · 底部高度 "
                        + subBottom
                        + "dp · "
                        + SubtitleOverlayView.STYLE_LABELS[subStyle],
                "setting-" + Prefs.SUBTITLE_SIZE,
                () -> PlaybackControls.showSubtitleAdjuster(a, null, a::refresh));
        ints(
                "字幕样式",
                Prefs.SUBTITLE_STYLE,
                SubtitleOverlayView.STYLE_LABELS,
                new int[] {0, 1, 2, 3, 4},
                0);
        toggle("自动加载来源弹幕", Prefs.DANMAKU_DEFAULT, true, null);
        ints(
                "画面色彩",
                Prefs.PICTURE_EFFECT,
                new String[] {"原始", "明亮", "影院", "柔和"},
                new int[] {0, 1, 2, 3},
                0);
        ints(
                "音效",
                Prefs.AUDIO_EFFECT,
                new String[] {"原声", "影院", "人声清晰", "柔和低音"},
                new int[] {0, 1, 2, 3},
                0);
        a.paragraph(
                "兼容音频解码仅切换音频，不强制视频软解码；AAC 优先仅在媒体包含可用 AAC 音轨时生效，不会转换文件。以上两项用于 Media3。音效取决于电视提供的音频接口。系统播放器支持的字幕和网络格式少于 Media3；播放器中的字幕按钮可导入外部字幕。");
    }

    void data() {
        page("搜索与数据", "搜索默认识别首拼和全拼，并检索配置中全部可搜索来源。");
        ints(
                "同时搜索的来源数",
                Prefs.SEARCH_THREADS,
                new String[] {"2 个 · 轻量", "4 个 · 均衡", "6 个 · 快速"},
                new int[] {2, 4, 6},
                6);
        ints(
                "单个来源等待时间",
                Prefs.SEARCH_TIMEOUT,
                new String[] {"10 秒", "20 秒", "30 秒"},
                new int[] {10, 20, 30},
                20);
        link(
                "导入或更新配置源",
                a.isTvDevice()
                        ? "手机扫码、遥控输入或本地包 (.json/.js/.py/.zip)"
                        : "剪贴板粘贴、网址输入或本地包 (.json/.js/.py/.zip)",
                "settings-import",
                a::importDialog);
        link(
                "导入或管理电视直播源",
                "支持 M3U、TXT (#genre#)、JSON 及本地直播文件",
                "settings-live-import",
                () -> SourceController.showLiveImportDialog(a));
        toggle("启用来源扩展", Prefs.EXPERIMENTAL_CSP, true, a::closeEngines);
        link(
                "网盘与账号",
                "网盘扫码登录、凭据与网盘顺序（由配置提供）",
                "settings-accounts",
                () -> AccountPanel.show(a, null));
        link(
                "备份配置与偏好",
                "导出配置与偏好，请妥善保管；不含插件单独保存的登录状态",
                "settings-backup",
                () -> pick(45, "application/json", true));
        link(
                "还原配置与偏好",
                "从 Marco Movie 备份文件还原",
                "settings-restore",
                () -> pick(46, "application/json", false));
        link("观看历史", "查看、继续播放或删除本机观看记录", "settings-history", () -> open(a::historyPage));
        link(
                "清除搜索记录",
                "清除最近搜索，保留网盘登录",
                "settings-clear-history",
                () ->
                        confirm(
                                "清除搜索记录？",
                                () -> {
                                    a.prefs.edit().remove(Prefs.GLOBAL_SEARCH_HISTORY).apply();
                                    a.refresh();
                                }));
        link(
                "重新加载首页图片与片单",
                "清除内存中的首页缓存",
                "settings-clear-images",
                () -> {
                    a.images.evictAll();
                    a.homeData.clear();
                    a.toast("已清除首页缓存");
                });
        link("运行日志", "查看本机最近异常与诊断记录", "settings-logs", this::diagnostics);
        link(
                "恢复播放与外观默认设置",
                "保留来源配置和网盘登录",
                "settings-reset",
                () ->
                        confirm(
                                "恢复默认设置？",
                                () -> {
                                    android.content.SharedPreferences.Editor edit = a.prefs.edit();
                                    for (String key : KEYS) edit.remove(key);
                                    edit.commit();
                                    a.reduced = false;
                                    a.recreate();
                                }));
        a.paragraph(
                "Marco Movie 0.4.26 · 作者 Pilipala · github.com/Pilipala9919/MarcoMovie。本产品仅为工具，不含任何影视资源；内容与配置请自行合法获取。播放能力取决于服务授权和设备支持。");
    }

    void confirm(String title, Runnable action) {
        new AppDialog.Builder(a)
                .setTitle(title)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, i) -> action.run())
                .show();
    }

    void diagnostics() {
        String text = AppLog.dump();
        new AppDialog.Builder(a)
                .setTitle("运行日志")
                .setMessage(text.isEmpty() ? "当前运行正常，暂无异常或告警记录。" : text)
                .setNeutralButton(
                        "清空记录",
                        (d, i) -> {
                            AppLog.clear();
                            a.toast("已清空运行日志");
                        })
                .setPositiveButton("关闭", null)
                .show();
    }

    void toggle(String title, String key, boolean defaultValue, Runnable after) {
        link(
                title,
                a.prefs.getBoolean(key, defaultValue) ? "开启" : "关闭",
                "setting-" + key,
                () -> {
                    Runnable change =
                            () -> {
                                a.prefs
                                        .edit()
                                        .putBoolean(key, !a.prefs.getBoolean(key, defaultValue))
                                        .apply();
                                if (after != null) after.run();
                                a.refresh();
                            };
                    if (key.equals(Prefs.EXPERIMENTAL_CSP) && !a.prefs.getBoolean(key, false))
                        confirm("运行配置源提供的扩展？请仅使用你信任的配置。", change);
                    else change.run();
                });
    }

    void ints(String title, String key, String[] labels, int[] values, int fallback) {
        int current = a.prefs.getInt(key, fallback), selected = 0;
        for (int i = 0; i < values.length; i++) if (current == values[i]) selected = i;
        final int choice = selected;
        link(
                title,
                labels[selected],
                "setting-" + key,
                () ->
                        new AppDialog.Builder(a)
                                .setTitle(title)
                                .setSingleChoiceItems(
                                        labels,
                                        choice,
                                        (dialog, index) -> {
                                            a.prefs.edit().putInt(key, values[index]).apply();
                                            dialog.dismiss();
                                            a.refresh();
                                        })
                                .setNegativeButton("取消", null)
                                .show());
    }

    void pick(int request, String mime, boolean create) {
        try {
            Intent intent =
                    new Intent(
                            create ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(mime);
            if (create) intent.putExtra(Intent.EXTRA_TITLE, "MarcoMovie-backup.json");
            a.startActivityForResult(intent, request);
        } catch (Exception error) {
            a.notice("电视没有文件选择器。壁纸也可以通过图片地址导入；文件导入导出需要安装文件管理器。");
        }
    }

    void wallpaperUrl() {
        AddressInput input = new AddressInput(a, "https://…/wallpaper.jpg", "");
        new AppDialog.Builder(a)
                .setTitle("壁纸图片地址")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton(
                        "导入",
                        (d, i) -> {
                            String address = input.value();
                            if (!Cms.isHttp(address)) {
                                a.notice("请输入 HTTP 或 HTTPS 图片地址。");
                                return;
                            }
                            a.worker.execute(
                                    () -> {
                                        try {
                                            java.net.HttpURLConnection connection =
                                                    (java.net.HttpURLConnection)
                                                            new java.net.URL(
                                                                            Cms.normalizeHttp(
                                                                                    address))
                                                                    .openConnection();
                                            connection.setConnectTimeout(15000);
                                            connection.setReadTimeout(15000);
                                            try (InputStream stream = connection.getInputStream()) {
                                                saveWallpaper(a, stream);
                                            } finally {
                                                connection.disconnect();
                                            }
                                            a.ui.post(
                                                    () -> {
                                                        a.refresh();
                                                        a.toast("壁纸已更新");
                                                    });
                                        } catch (Exception error) {
                                            a.ui.post(() -> a.notice("图片无法读取，请检查图片地址和格式。"));
                                        }
                                    });
                        })
                .show();
    }

    static void saveWallpaper(MainActivity a, InputStream stream) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int length;
        while ((length = stream.read(chunk)) != -1) {
            if (bytes.size() + length > 16 * 1024 * 1024) throw new IOException("Image too large");
            bytes.write(chunk, 0, length);
        }
        byte[] data = bytes.toByteArray();
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("Invalid image");
        options.inSampleSize = 1;
        while (options.outWidth / options.inSampleSize > 1920
                || options.outHeight / options.inSampleSize > 1080) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        Bitmap image = BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (image == null) throw new IOException("Invalid image");
        File temporary = new File(a.getFilesDir(), "wallpaper.tmp");
        try {
            try (OutputStream output = new FileOutputStream(temporary)) {
                if (!image.compress(Bitmap.CompressFormat.JPEG, 90, output))
                    throw new IOException("Encoding failed");
            }
            if (!temporary.renameTo(new File(a.getFilesDir(), "wallpaper.jpg")))
                throw new IOException("Unable to save wallpaper");
        } finally {
            image.recycle();
            temporary.delete();
        }
        a.prefs.edit().putBoolean(Prefs.WALLPAPER_ENABLED, true).apply();
    }

    static final String[] KEYS = Prefs.KEYS;

    static void result(MainActivity a, int request, Uri uri) {
        a.worker.execute(
                () -> {
                    try {
                        if (request == 44) {
                            try (InputStream input = a.getContentResolver().openInputStream(uri)) {
                                saveWallpaper(a, input);
                            }
                        } else if (request == 45) {
                            org.json.JSONObject backup =
                                    new org.json.JSONObject()
                                            .put("format", "MarcoMovie-1")
                                            .put("schema", "marco-movie-backup")
                                            .put("version", 1)
                                            .put("origin", a.configOrigin)
                                            .put(
                                                    "config",
                                                    a.config == null
                                                            ? org.json.JSONObject.NULL
                                                            : new org.json.JSONObject(
                                                                    a.config.raw));
                            org.json.JSONObject settings = new org.json.JSONObject();
                            for (String key : KEYS)
                                if (a.prefs.contains(key))
                                    settings.put(key, a.prefs.getAll().get(key));
                            for (String dataKey :
                                    new String[] {
                                        Prefs.SELECTED_SITE,
                                        Prefs.WATCH_HISTORY,
                                        Prefs.FAVORITES,
                                        Prefs.GLOBAL_SEARCH_HISTORY
                                    }) {
                                if (a.prefs.contains(dataKey)) {
                                    settings.put(dataKey, a.prefs.getString(dataKey, ""));
                                }
                            }
                            backup.put("settings", settings);
                            try (OutputStream output =
                                    a.getContentResolver().openOutputStream(uri, "wt")) {
                                output.write(backup.toString(2).getBytes("UTF-8"));
                            }
                        } else if (request == 46) {
                            org.json.JSONObject backup;
                            try (InputStream input = a.getContentResolver().openInputStream(uri)) {
                                backup = new org.json.JSONObject(Cms.read(input, Cms.MAX_JSON));
                            }
                            if (!Prefs.isBackupObject(
                                    backup.optString("format", null),
                                    backup.optString("schema", null),
                                    backup.optInt("version", 0)))
                                throw new IOException("Invalid backup");
                            final String origin = backup.optString("origin", "");
                            final String configJson;
                            if (!backup.has("config") || backup.isNull("config")) {
                                configJson = null;
                            } else {
                                configJson = Prefs.extractBackupConfigJson(backup.opt("config"));
                                if (configJson == null)
                                    throw new IOException("Invalid backup config");
                            }
                            final Cms.Config config =
                                    configJson == null ? null : Cms.parseConfig(configJson, origin);
                            final org.json.JSONObject settings = backup.optJSONObject("settings");
                            if (settings != null) {
                                for (String key :
                                        new String[] {
                                            Prefs.WATCH_HISTORY,
                                            Prefs.FAVORITES,
                                            Prefs.GLOBAL_SEARCH_HISTORY
                                        }) {
                                    if (!settings.has(key)) continue;
                                    org.json.JSONArray rows =
                                            new org.json.JSONArray(
                                                    String.valueOf(settings.get(key)));
                                    for (int i = 0; i < rows.length(); i++) {
                                        if (Prefs.GLOBAL_SEARCH_HISTORY.equals(key)) {
                                            if (!(rows.get(i) instanceof String))
                                                throw new IOException("Invalid search history");
                                        } else {
                                            org.json.JSONObject item = rows.getJSONObject(i);
                                            if (item.optString("id").isEmpty()
                                                    || item.optString("site").isEmpty())
                                                throw new IOException("Invalid bookmark");
                                        }
                                    }
                                }
                            }
                            a.ui.post(
                                    () ->
                                            new AppDialog.Builder(a)
                                                    .setTitle("还原备份？")
                                                    .setMessage("将替换本机来源配置与外观、播放偏好。网盘账号保持不变。")
                                                    .setNegativeButton("取消", null)
                                                    .setPositiveButton(
                                                            "还原",
                                                            (d, i) -> {
                                                                try {
                                                                    int oldScale =
                                                                            a.prefs.getInt(
                                                                                    Prefs
                                                                                            .UI_SCALE_PERCENT,
                                                                                    100);
                                                                    restoreSettings(a, settings);
                                                                    if (config != null)
                                                                        a.saveConfig(
                                                                                config, origin);
                                                                    else a.refresh();
                                                                    if (oldScale
                                                                            != a.prefs.getInt(
                                                                                    Prefs
                                                                                            .UI_SCALE_PERCENT,
                                                                                    100))
                                                                        a.recreate();
                                                                } catch (Exception error) {
                                                                    AppLog.w(
                                                                            "Settings",
                                                                            "restoreSettings failed",
                                                                            error);
                                                                    a.notice("备份无法还原。");
                                                                }
                                                            })
                                                    .show());
                            return;
                        }
                        a.ui.post(
                                () -> {
                                    a.refresh();
                                    a.toast(request == 45 ? "备份已保存" : "壁纸已更新");
                                });
                    } catch (Exception error) {
                        AppLog.w("Settings", "file result " + request + " failed", error);
                        a.ui.post(() -> a.notice("文件无法处理。请检查文件格式与可用存储空间。"));
                    }
                });
    }

    static void restoreSettings(MainActivity a, org.json.JSONObject settings) {
        if (settings == null) return;
        android.content.SharedPreferences.Editor edit = a.prefs.edit();
        for (String key : KEYS) {
            Object clean = Prefs.sanitizeSetting(key, settings.opt(key));
            if (clean instanceof Boolean) edit.putBoolean(key, (Boolean) clean);
            else if (clean instanceof Float) edit.putFloat(key, (Float) clean);
            else if (clean instanceof Integer) edit.putInt(key, (Integer) clean);
        }
        for (String dataKey :
                new String[] {
                    Prefs.SELECTED_SITE,
                    Prefs.WATCH_HISTORY,
                    Prefs.FAVORITES,
                    Prefs.GLOBAL_SEARCH_HISTORY
                }) {
            if (settings.has(dataKey)) {
                String cleanData = Prefs.sanitizeUserData(dataKey, settings.opt(dataKey));
                if (cleanData != null) edit.putString(dataKey, cleanData);
            }
        }
        edit.apply();
        a.reduced = a.prefs.getBoolean(Prefs.REDUCED, false);
    }
}
