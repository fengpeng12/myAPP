package com.pilipala.marcomovie;

import android.content.Context;
import android.media.AudioManager;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Touch gesture controller for phone and tablet playback: single-tap controls toggle, double-tap
 * play/pause, long-press 2.0x speed boost, horizontal swipe seek, left-side brightness swipe, and
 * right-side volume swipe.
 */
final class TouchPlayerGesture implements View.OnTouchListener {
    private final MainActivity a;
    private final GestureDetector detector;
    private final TextView hud;
    private final float touchSlop;
    private float downX, downY;
    private int dragMode = SeekGesture.MODE_NONE;
    private int startPositionMs, durationMs, targetSeekMs;
    private float startBrightness = 0.5f;
    private float startVolume = 0.5f;
    private float savedSpeed = 1.0f;
    private boolean speedBoosting;

    private TouchPlayerGesture(MainActivity activity) {
        this.a = activity;
        this.touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        this.hud = activity.text("", 14, MainActivity.WHITE);
        this.hud.setTag("player-touch-hud");
        this.hud.setGravity(Gravity.CENTER);
        this.hud.setPadding(activity.dp(18), activity.dp(10), activity.dp(18), activity.dp(10));
        UiCompat.setBackground(this.hud, activity.bg(0xd8202026, 20));
        this.hud.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp =
                new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = activity.dp(36);
        activity.playbackLayer.addView(this.hud, lp);

        this.detector =
                new GestureDetector(
                        activity,
                        new GestureDetector.SimpleOnGestureListener() {
                            @Override
                            public boolean onDown(MotionEvent e) {
                                return true;
                            }

                            @Override
                            public boolean onSingleTapConfirmed(MotionEvent e) {
                                if (a.miniMode) {
                                    a.expandPlayback();
                                    return true;
                                }
                                a.showControls(!a.controlsVisible);
                                return true;
                            }

                            @Override
                            public boolean onDoubleTap(MotionEvent e) {
                                if (a.miniMode) return false;
                                a.togglePlayback();
                                a.showControls(true);
                                return true;
                            }

                            @Override
                            public void onLongPress(MotionEvent e) {
                                if (a.miniMode
                                        || a.isLive()
                                        || dragMode != SeekGesture.MODE_NONE
                                        || a.player == null
                                        || !a.prepared
                                        || !a.playing) return;
                                try {
                                    savedSpeed = a.player.getSpeed();
                                    a.player.setSpeed(2.0f);
                                    speedBoosting = true;
                                    showHud("2.0× 倍速播放中 · 松手恢复");
                                } catch (Exception ignored) {
                                }
                            }
                        });
    }

    static void attach(MainActivity activity, View surfaceView) {
        TouchPlayerGesture listener = new TouchPlayerGesture(activity);
        activity.root.setOnTouchListener(listener);
    }

    private void showHud(String text) {
        hud.setText(text);
        hud.setVisibility(View.VISIBLE);
    }

    private void hideHud() {
        hud.setVisibility(View.GONE);
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (a.miniMode) {
            return detector.onTouchEvent(event);
        }
        detector.onTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            downX = event.getRawX();
            downY = event.getRawY();
            dragMode = SeekGesture.MODE_NONE;
            if (a.playbackControls != null) a.playbackControls.touchSeeking = false;
            if (a.player != null && a.prepared) {
                startPositionMs = a.player.getCurrentPosition();
                durationMs = a.player.getDuration();
                targetSeekMs = startPositionMs;
            }
            WindowManager.LayoutParams attrs = a.getWindow().getAttributes();
            startBrightness =
                    attrs.screenBrightness >= 0f && attrs.screenBrightness <= 1f
                            ? attrs.screenBrightness
                            : 0.5f;
            AudioManager audio = (AudioManager) a.getSystemService(Context.AUDIO_SERVICE);
            if (audio != null) {
                int max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                int cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                startVolume = cur / (float) max;
            }
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (speedBoosting) return true;
            float dx = event.getRawX() - downX;
            float dy = event.getRawY() - downY;
            int width = Math.max(1, a.windowWidth());
            int height = Math.max(1, a.windowHeight());
            if (dragMode == SeekGesture.MODE_NONE) {
                dragMode = SeekGesture.classifyDrag(dx, dy, downX, width, touchSlop);
                if (dragMode == SeekGesture.MODE_SEEK && (a.isLive() || durationMs <= 0)) {
                    dragMode = SeekGesture.MODE_NONE;
                }
                if (dragMode != SeekGesture.MODE_NONE && v.getParent() != null) {
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                }
            }
            if (dragMode == SeekGesture.MODE_SEEK && a.player != null && a.prepared) {
                targetSeekMs = SeekGesture.dragSeekTarget(startPositionMs, durationMs, dx, width);
                if (a.playbackControls != null) {
                    a.playbackControls.touchSeeking = true;
                    a.playbackControls.timeline.setProgress(targetSeekMs);
                }
                if (a.playClock != null) {
                    a.playClock.setText(
                            PlaybackController.formatTime(targetSeekMs)
                                    + " / "
                                    + PlaybackController.formatTime(durationMs));
                }
                showHud(
                        (dx >= 0 ? "快进至 " : "快退至 ")
                                + PlaybackController.formatTime(targetSeekMs)
                                + " / "
                                + PlaybackController.formatTime(durationMs));
            } else if (dragMode == SeekGesture.MODE_BRIGHTNESS) {
                float level = SeekGesture.dragVerticalLevel(startBrightness, dy, height);
                WindowManager.LayoutParams attrs = a.getWindow().getAttributes();
                attrs.screenBrightness = Math.max(0.02f, level);
                a.getWindow().setAttributes(attrs);
                showHud("屏幕亮度 " + Math.round(level * 100) + "%");
            } else if (dragMode == SeekGesture.MODE_VOLUME) {
                float level = SeekGesture.dragVerticalLevel(startVolume, dy, height);
                AudioManager audio = (AudioManager) a.getSystemService(Context.AUDIO_SERVICE);
                if (audio != null) {
                    int max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                    int vol = Math.round(level * max);
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0);
                }
                showHud("媒体音量 " + Math.round(level * 100) + "%");
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (a.playbackControls != null) a.playbackControls.touchSeeking = false;
            if (speedBoosting) {
                speedBoosting = false;
                if (a.player != null && a.prepared) {
                    try {
                        a.player.setSpeed(savedSpeed);
                    } catch (Exception ignored) {
                    }
                }
                hideHud();
                return true;
            }
            if (dragMode == SeekGesture.MODE_SEEK && a.player != null && a.prepared) {
                if (a.playbackControls != null) {
                    a.playbackControls.recordUserSeek(targetSeekMs);
                }
                a.player.seekTo(targetSeekMs);
                if (a.playClock != null && durationMs > 0) {
                    a.playClock.setText(
                            PlaybackController.formatTime(targetSeekMs)
                                    + " / "
                                    + PlaybackController.formatTime(durationMs));
                }
                a.scheduleHide();
            }
            dragMode = SeekGesture.MODE_NONE;
            hideHud();
            return true;
        }
        return true;
    }
}
