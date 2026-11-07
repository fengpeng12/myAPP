package com.pilipala.marcomovie;

public final class SeekGestureChecks {
    static int checks;

    static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++;
        System.out.println("PASS " + name);
    }

    public static void main(String[] args) {
        SeekGesture s = new SeekGesture();
        s.begin(1, 120000, 3600000, 1000);
        s.advance(1100);
        check(s.target == 135000, "single right tap adds exactly 15 seconds");
        s.begin(-1, 120000, 3600000, 1000);
        s.advance(1499);
        check(
                s.target == 105000,
                "single left tap subtracts exactly 15 seconds without early repeat");
        s.begin(1, 0, 3600000, 0);
        s.advance(1000);
        check(s.target == 30000, "holding continuously advances after half-second threshold");
        s.advance(3000);
        check(s.target == 90000, "normal held speed integrates through three seconds");
        s.advance(4000);
        check(s.target == 150000, "three-second hold doubles subsequent speed");
        check(s.accelerated(4000), "accelerated state exposed for feedback");
        SeekGesture frequent = new SeekGesture();
        frequent.begin(1, 0, 3600000, 0);
        for (int t = 100; t <= 4000; t += 100) frequent.advance(t);
        check(
                frequent.target == s.target,
                "speed independent of callback and remote repeat frequency");
        s.begin(-1, 300000, 3600000, 0);
        s.advance(4000);
        check(s.target == 150000, "rewind has symmetric acceleration");
        s.begin(-1, 10000, 3600000, 0);
        s.advance(10000);
        check(s.target == 0, "rewind clamps at beginning");
        s.begin(1, 3595000, 3600000, 0);
        s.advance(10000);
        check(s.target == 3600000, "forward clamps at duration");
        s.begin(1, 0, 0, 0);
        check(!s.active, "unknown duration does not start seeking");
        s.begin(1, 120000, 3600000, 0);
        s.advance(1000);
        int position = s.target;
        s.cancel();
        s.advance(4000);
        check(!s.active && s.target == position, "cancel stops future advancement");
        s.begin(1, 100000, 3600000, 0);
        s.advance(1500);
        position = s.target;
        s.begin(-1, position, 3600000, 1500);
        check(
                s.target == position - 15000 && !s.accelerated(1500),
                "direction change resets acceleration at preview position");
        s.begin(1, Integer.MAX_VALUE - 1000, Integer.MAX_VALUE, 0);
        s.advance(10000);
        check(s.target == Integer.MAX_VALUE, "long-duration arithmetic cannot overflow");
        check(
                SeekGesture.classifyDrag(4f, 3f, 200f, 1000f, 12f) == SeekGesture.MODE_NONE,
                "micro movement below touch slop does not start drag");
        check(
                SeekGesture.classifyDrag(120f, 20f, 200f, 1000f, 12f) == SeekGesture.MODE_SEEK,
                "horizontal swipe classifies as seek");
        check(
                SeekGesture.classifyDrag(10f, -90f, 250f, 1000f, 12f)
                        == SeekGesture.MODE_BRIGHTNESS,
                "left-side vertical swipe classifies as brightness");
        check(
                SeekGesture.classifyDrag(10f, -90f, 750f, 1000f, 12f) == SeekGesture.MODE_VOLUME,
                "right-side vertical swipe classifies as volume");
        check(
                SeekGesture.dragSeekTarget(60000, 600000, 250f, 1000f) == 90000
                        && SeekGesture.dragSeekTarget(60000, 600000, -1000f, 1000f) == 0,
                "touch seek target scales by swipe fraction and clamps to [0, duration]");
        check(
                Math.abs(SeekGesture.dragVerticalLevel(0.5f, -325f, 1000f) - 1.0f) < 0.001f
                        && SeekGesture.dragVerticalLevel(0.2f, 500f, 1000f) == 0f,
                "vertical swipe level adjusts and clamps to [0, 1]");
        System.out.println("OK " + checks + " seek gesture checks");
    }
}
