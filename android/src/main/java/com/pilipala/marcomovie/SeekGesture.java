package com.pilipala.marcomovie;

/**
 * Remote-key timeline preview and touch-drag gesture math for seek, brightness, and volume
 * controls.
 */
final class SeekGesture {
    static final int TAP_MS = 15000, HOLD_DELAY_MS = 500, ACCELERATE_MS = 3000;
    static final int MODE_NONE = 0, MODE_SEEK = 1, MODE_BRIGHTNESS = 2, MODE_VOLUME = 3;
    boolean active;
    int direction, target, duration;
    long started, updated;

    static int classifyDrag(float dx, float dy, float startX, float width, float touchSlop) {
        if (Math.abs(dx) < touchSlop && Math.abs(dy) < touchSlop) return MODE_NONE;
        if (Math.abs(dx) >= Math.abs(dy) * 1.15f) return MODE_SEEK;
        if (Math.abs(dy) > Math.abs(dx)) {
            return startX < width * 0.5f ? MODE_BRIGHTNESS : MODE_VOLUME;
        }
        return MODE_NONE;
    }

    static int dragSeekTarget(int startPositionMs, int durationMs, float dx, float width) {
        if (durationMs <= 0 || width <= 0) return 0;
        int span = Math.max(60000, Math.min(300000, durationMs / 5));
        long delta = Math.round((dx / width) * span);
        return (int) Math.max(0L, Math.min(durationMs, (long) startPositionMs + delta));
    }

    static float dragVerticalLevel(float startLevel, float dy, float height) {
        float clampedStart = Math.max(0f, Math.min(1f, startLevel));
        if (height <= 0) return clampedStart;
        return Math.max(0f, Math.min(1f, clampedStart - dy / (height * 0.65f)));
    }

    void begin(int direction, int position, int duration, long now) {
        this.direction = direction < 0 ? -1 : 1;
        this.duration = Math.max(0, duration);
        target = bound((long) position + this.direction * TAP_MS);
        started = updated = now;
        active = duration > 0;
    }

    void advance(long now) {
        if (!active || now <= updated) return;
        long from = Math.max(updated, started + HOLD_DELAY_MS),
                to = Math.max(from, now),
                boundary = started + ACCELERATE_MS;
        long normal = Math.max(0, Math.min(to, boundary) - Math.min(from, boundary));
        long fast = Math.max(0, to - Math.max(from, boundary));
        target = bound((long) target + direction * (normal * 30 + fast * 60));
        updated = now;
    }

    boolean accelerated(long now) {
        return active && now - started >= ACCELERATE_MS;
    }

    int bound(long value) {
        return (int) Math.max(0, Math.min(duration, value));
    }

    void cancel() {
        active = false;
    }
}
