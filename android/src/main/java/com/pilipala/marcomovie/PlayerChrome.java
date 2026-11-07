package com.pilipala.marcomovie;

import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;

/** Three independent floating regions with one visibility/focus owner. */
final class PlayerChrome {
    final MainActivity a;
    final FrameLayout overlay;
    final LinearLayout dock, progress, heading;
    final LinearLayout loadingPill;
    final LinearLayout centerRow;
    final TextView loadingText, episodeLabel, titleView;
    final Button fsBtn;
    final StateGlyph state;
    final TextView resolution;
    boolean loading = true;

    PlayerChrome(MainActivity host, Cms.Video video, Cms.Episode episode) {
        a = host;
        overlay = new FrameLayout(a);
        overlay.setTag("player-chrome");
        overlay.setClipChildren(false);
        View topShade = new View(a);
        topShade.setBackground(
                new GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[] {0xc4000000, 0x00000000}));
        overlay.addView(topShade, new FrameLayout.LayoutParams(-1, a.dp(160), Gravity.TOP));
        View bottomShade = new View(a);
        bottomShade.setBackground(
                new GradientDrawable(
                        GradientDrawable.Orientation.BOTTOM_TOP,
                        new int[] {0xb8000000, 0x00000000}));
        overlay.addView(bottomShade, new FrameLayout.LayoutParams(-1, a.dp(210), Gravity.BOTTOM));
        heading = a.vertical();
        heading.setTag("player-heading");
        if (!a.isTvDevice()) {
            LinearLayout topActions = a.row();
            topActions.setPadding(0, 0, 0, a.dp(8));
            Button backBtn = a.navButton("‹ 返回", "player-back", a::leavePlayback);
            backBtn.setTextColor(MainActivity.WHITE);
            a.glass(backBtn, 16);
            topActions.addView(backBtn, new LinearLayout.LayoutParams(-2, a.dp(34)));
            View spacer = new View(a);
            topActions.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
            // Orientation toggle lives only on the progress row ("全屏横屏 / 退出全屏").
            heading.addView(topActions, new LinearLayout.LayoutParams(-1, -2));
        }
        LinearLayout titleRow = a.row();
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleView = a.text(video.title, a.isPortrait() ? 20 : 26, MainActivity.WHITE);
        titleView.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        titleView.getPaint().setFakeBoldText(true);
        titleView.setSingleLine();
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleView.setShadowLayer(a.dp(4), 0, a.dp(2), 0xdd000000);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-2, -2);
        titleLp.rightMargin = a.dp(10);
        titleRow.addView(titleView, titleLp);
        resolution = a.text("分辨率读取中", 11, 0xffeeeef2);
        resolution.setTag("player-resolution");
        resolution.setSingleLine();
        resolution.setEllipsize(android.text.TextUtils.TruncateAt.END);
        resolution.setPadding(a.dp(9), a.dp(3), a.dp(9), a.dp(3));
        a.glass(resolution, 10);
        titleRow.addView(resolution, new LinearLayout.LayoutParams(-2, -2));
        heading.addView(titleRow, new LinearLayout.LayoutParams(-1, -2));
        String line = a.activeLine == null ? "" : a.activeLine.name;
        episodeLabel =
                a.text(episode.name + (line.isEmpty() ? "" : "  ·  " + line), 14, 0xffdddddf);
        episodeLabel.setTag("player-episode");
        episodeLabel.setSingleLine();
        episodeLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        episodeLabel.setPadding(0, a.dp(6), 0, 0);
        heading.addView(episodeLabel);
        a.playStatus = a.text("正在加载所选剧集…", 12, MainActivity.MUTED);
        a.playStatus.setPadding(0, a.dp(6), 0, 0);
        heading.addView(a.playStatus);
        FrameLayout.LayoutParams top = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        int sideMargin = a.isPortrait() ? a.dp(20) : a.dp(44);
        top.setMargins(sideMargin, a.dp(24), sideMargin, 0);
        overlay.addView(heading, top);

        progress = a.vertical();
        progress.setTag("player-progress");
        LinearLayout clockRow = a.row();
        a.playClock = a.text("--:-- / --:--", 12, MainActivity.WHITE);
        a.playClock.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        a.playClock.setPadding(a.dp(8), 0, a.dp(12), 0);
        clockRow.addView(a.playClock, new LinearLayout.LayoutParams(0, -2, 1));
        if (!a.isTvDevice()) {
            fsBtn =
                    a.navButton(
                            a.isPortrait() ? "⛶ 全屏横屏" : "⛶ 退出全屏",
                            "player-fullscreen",
                            a::togglePlayerOrientation);
            fsBtn.setTextColor(MainActivity.WHITE);
            fsBtn.setTextSize(12);
            fsBtn.setMinHeight(a.dp(30));
            fsBtn.setMinimumHeight(a.dp(30));
            fsBtn.setPadding(a.dp(12), a.dp(4), a.dp(12), a.dp(4));
            a.glass(fsBtn, 15);
            clockRow.addView(fsBtn, new LinearLayout.LayoutParams(-2, a.dp(30)));
        } else {
            fsBtn = null;
        }
        progress.addView(clockRow, new LinearLayout.LayoutParams(-1, -2));
        int width = a.windowWidth();
        boolean mobileLandscape = !a.isTvDevice() && !a.isPortrait();
        boolean isLive = a.liveSession != null;
        FrameLayout.LayoutParams track =
                new FrameLayout.LayoutParams(
                        Math.min(
                                a.dp(760),
                                Math.max(a.dp(220), width - a.dp(mobileLandscape ? 120 : 48))),
                        -2,
                        Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        track.bottomMargin = a.dp(mobileLandscape ? 20 : 94);
        if (mobileLandscape) track.rightMargin = a.dp(36);
        if (isLive) progress.setVisibility(View.GONE);
        overlay.addView(progress, track);

        dock = a.vertical();
        dock.setTag("player-dock");
        dock.setPadding(
                a.dp(mobileLandscape ? 4 : 6), a.dp(5), a.dp(mobileLandscape ? 4 : 6), a.dp(5));
        dock.setClipChildren(false);
        a.glass(dock, 26);
        UiCompat.setElevation(dock, a.dp(10));
        FrameLayout.LayoutParams bottom =
                mobileLandscape
                        ? new FrameLayout.LayoutParams(
                                a.dp(76),
                                Math.min(
                                        a.dp(360),
                                        Math.max(a.dp(180), a.windowHeight() - a.dp(40))),
                                Gravity.RIGHT | Gravity.CENTER_VERTICAL)
                        : new FrameLayout.LayoutParams(
                                Math.min(a.dp(552), Math.max(a.dp(240), width - a.dp(32))),
                                -2,
                                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        if (mobileLandscape) bottom.rightMargin = a.dp(8);
        else bottom.bottomMargin = a.dp(16);
        overlay.addView(dock, bottom);

        state = new StateGlyph();
        state.setTag("player-state");
        state.setFocusable(false);
        state.setVisibility(View.GONE);
        state.setOnClickListener(
                v -> {
                    a.togglePlayback();
                    a.showControls(true);
                });
        if (!a.isTvDevice()) {
            centerRow = a.row();
            centerRow.setGravity(Gravity.CENTER);
            TextView rewindBtn = buildSeekCircle("↺ 15s", -15000);
            TextView forwardBtn = buildSeekCircle("15s ↻", 15000);
            LinearLayout.LayoutParams sideLp = new LinearLayout.LayoutParams(a.dp(58), a.dp(58));
            LinearLayout.LayoutParams centerLp = new LinearLayout.LayoutParams(a.dp(76), a.dp(76));
            centerLp.leftMargin = a.dp(28);
            centerLp.rightMargin = a.dp(28);
            centerRow.addView(rewindBtn, sideLp);
            centerRow.addView(state, centerLp);
            centerRow.addView(forwardBtn, sideLp);
            overlay.addView(centerRow, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        } else {
            centerRow = null;
            overlay.addView(
                    state, new FrameLayout.LayoutParams(a.dp(72), a.dp(72), Gravity.CENTER));
        }
        a.playbackLayer.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        loadingPill = a.row();
        loadingPill.setTag("player-loading");
        loadingPill.setGravity(Gravity.CENTER_VERTICAL);
        loadingPill.setPadding(a.dp(24), a.dp(16), a.dp(24), a.dp(16));
        a.glass(loadingPill, 24);
        UiCompat.setElevation(loadingPill, a.dp(10));
        loadingPill.addView(
                new PlaybackLoading.Ring(a), new LinearLayout.LayoutParams(a.dp(26), a.dp(26)));
        String epTitle =
                episode != null && episode.name != null && !episode.name.isEmpty()
                        ? " " + episode.name
                        : "";
        loadingText = a.text("正在加载" + epTitle + "…", 14, MainActivity.WHITE);
        loadingText.setPadding(a.dp(14), 0, 0, 0);
        loadingPill.addView(loadingText);
        a.playbackLayer.addView(loadingPill, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        a.playerOverlay = overlay;
        a.controlsVisible = true;
    }

    TextView buildSeekCircle(String label, int deltaMs) {
        TextView btn = a.text(label, 12, MainActivity.WHITE);
        btn.setGravity(Gravity.CENTER);
        btn.setTypeface(null, Typeface.BOLD);
        GradientDrawable bg = a.bg(0x99303034, 29);
        bg.setStroke(a.dp(1), 0x33ffffff);
        btn.setBackground(bg);
        btn.setOnClickListener(
                v -> {
                    if (a.player != null && a.prepared) {
                        int dur = Math.max(1, a.player.getDuration());
                        int cur =
                                a.playbackControls != null
                                        ? a.playbackControls.displayPosition(
                                                a.player.getCurrentPosition())
                                        : a.player.getCurrentPosition();
                        int next = Math.max(0, Math.min(dur, cur + deltaMs));
                        if (a.playbackControls != null) a.playbackControls.recordUserSeek(next);
                        a.player.seekTo(next);
                        if (a.playClock != null && dur > 1) {
                            a.playClock.setText(
                                    PlaybackController.formatTime(next)
                                            + " / "
                                            + PlaybackController.formatTime(dur));
                        }
                    }
                    a.showControls(true);
                });
        return btn;
    }

    void relayout() {
        int width = a.windowWidth();
        boolean mobileLandscape = !a.isTvDevice() && !a.isPortrait();
        if (titleView != null) {
            titleView.setTextSize(a.isPortrait() ? 20 : 26);
        }
        if (fsBtn != null) {
            fsBtn.setText(a.isPortrait() ? "⛶ 全屏横屏" : "⛶ 退出全屏");
        }
        ViewGroup.LayoutParams hlp = heading.getLayoutParams();
        if (hlp instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams fhlp = (FrameLayout.LayoutParams) hlp;
            int sideMargin = a.isPortrait() ? a.dp(20) : a.dp(44);
            fhlp.setMargins(sideMargin, a.dp(24), sideMargin, 0);
            heading.setLayoutParams(fhlp);
        }
        ViewGroup.LayoutParams p = progress.getLayoutParams();
        if (p instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams fp = (FrameLayout.LayoutParams) p;
            fp.width =
                    Math.min(
                            a.dp(760),
                            Math.max(a.dp(220), width - a.dp(mobileLandscape ? 120 : 48)));
            fp.bottomMargin = a.dp(mobileLandscape ? 20 : 94);
            fp.rightMargin = mobileLandscape ? a.dp(36) : 0;
            progress.setLayoutParams(fp);
        }
        dock.setPadding(
                a.dp(mobileLandscape ? 4 : 6), a.dp(5), a.dp(mobileLandscape ? 4 : 6), a.dp(5));
        ViewGroup.LayoutParams d = dock.getLayoutParams();
        if (d instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams fd = (FrameLayout.LayoutParams) d;
            if (mobileLandscape) {
                fd.width = a.dp(76);
                fd.height = Math.min(a.dp(360), Math.max(a.dp(180), a.windowHeight() - a.dp(40)));
                fd.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
                fd.rightMargin = a.dp(8);
                fd.bottomMargin = 0;
            } else {
                fd.width = Math.min(a.dp(552), Math.max(a.dp(240), width - a.dp(32)));
                fd.height = -2;
                fd.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                fd.rightMargin = 0;
                fd.bottomMargin = a.dp(16);
            }
            dock.setLayoutParams(fd);
        }
        if (a.playbackControls != null) {
            a.playbackControls.relayout(mobileLandscape);
        }
    }

    void resolution(int width, int height) {
        if (a.player != null && a.prepared) {
            resolution.setText(a.player.videoResolution());
        } else {
            resolution.setText(width > 0 && height > 0 ? width + " × " + height : "分辨率未知");
        }
    }

    void setLoading(boolean value) {
        loading = value;
        update();
    }

    void switchEpisode(Cms.Episode episode, Cms.Line line) {
        String lineName = line == null || line.name == null ? "" : line.name;
        String epName = episode == null || episode.name == null ? "" : episode.name;
        episodeLabel.setText(epName + (lineName.isEmpty() ? "" : "  ·  " + lineName));
        resolution.setText("分辨率读取中");
        String statusMsg = "正在加载" + (epName.isEmpty() ? "" : " " + epName) + "…";
        if (a.playStatus != null) a.playStatus.setText(statusMsg);
        loadingText.setText(statusMsg);
        loading = true;
        update();
    }

    void update() {
        if (a.prepared) loading = false;
        if (a.player != null && a.prepared) resolution.setText(a.player.videoResolution());
        boolean showSpinner = loading && !a.prepared && a.playbackLoading == null && !a.miniMode;
        loadingPill.setVisibility(showSpinner ? View.VISIBLE : View.GONE);
        state.setVisibility(showSpinner ? View.GONE : View.VISIBLE);
        if (centerRow != null) centerRow.setVisibility(showSpinner ? View.GONE : View.VISIBLE);
        state.setContentDescription(!a.prepared ? "正在准备" : a.playing ? "正在播放，确认暂停" : "已暂停，确认继续");
        state.invalidate();
    }

    final class StateGlyph extends View {
        final Paint paint = new Paint(3);

        StateGlyph() {
            super(a);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            paint.setColor(0x99303034);
            canvas.drawCircle(cx, cy, getWidth() / 2f, paint);
            paint.setColor(MainActivity.WHITE);
            if (!a.prepared) {
                paint.setStrokeWidth(a.dp(3));
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawCircle(cx, cy, a.dp(12), paint);
                paint.setStyle(Paint.Style.FILL);
                return;
            }
            if (a.playing) {
                canvas.drawRoundRect(
                        new RectF(cx - a.dp(10), cy - a.dp(13), cx - a.dp(3), cy + a.dp(13)),
                        a.dp(2),
                        a.dp(2),
                        paint);
                canvas.drawRoundRect(
                        new RectF(cx + a.dp(3), cy - a.dp(13), cx + a.dp(10), cy + a.dp(13)),
                        a.dp(2),
                        a.dp(2),
                        paint);
            } else {
                Path p = new Path();
                p.moveTo(cx - a.dp(8), cy - a.dp(14));
                p.lineTo(cx + a.dp(15), cy);
                p.lineTo(cx - a.dp(8), cy + a.dp(14));
                p.close();
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setStrokeWidth(a.dp(3));
                paint.setStyle(Paint.Style.FILL_AND_STROKE);
                canvas.drawPath(p, paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }
    }
}
