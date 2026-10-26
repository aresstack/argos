package com.aresstack.argos;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Plans mouse movements the way a hand makes them; pure arithmetic, no browser access. A movement lasts as long as
 * Fitts's law predicts for its distance and target size, follows a cubic Bézier curve beside the straight line,
 * speeds up and slows down along an (slightly front-loaded) minimum-jerk profile, carries a small 8–12 Hz tremor that
 * fades on approach, and on long distances sometimes overshoots and corrects. Points are whole CSS pixels and are
 * sampled every 10–20 ms, like a real mouse reports them.
 */
final class HumanMotion {
    record Point(double x, double y) { }

    /** A pointer position, {@code at} milliseconds after the movement started. */
    record Step(double x, double y, long at) { }

    /** A rectangle in viewport coordinates. */
    record Box(double x, double y, double width, double height) {
        Point center() {
            return new Point(x + width / 2, y + height / 2);
        }
    }

    private final RandomGenerator random;
    private final HumanInputSettings.Mode mode;
    private final double tempo;

    HumanMotion(RandomGenerator random, HumanInputSettings settings) {
        this.random = random;
        this.mode = settings.mode();
        this.tempo = settings.speed() / (mode == HumanInputSettings.Mode.CAREFUL ? 1.35 : 1.0);
    }

    boolean off() {
        return mode == HumanInputSettings.Mode.OFF;
    }

    /**
     * The steps from {@code from} to exactly {@code to}. {@code targetSize} is the smaller side of the target, and
     * {@code viewport} (may be null) keeps every point on the page.
     */
    List<Step> path(Point from, Point to, double targetSize, Box viewport) {
        List<Step> steps = new ArrayList<>();
        Point end = new Point(Math.round(to.x()), Math.round(to.y()));
        double distance = distance(from, end);
        if (off() || distance < 3) {
            steps.add(new Step(end.x(), end.y(), 0));
            return steps;
        }
        long time = 0;
        if (distance > 250 && random.nextDouble() < 0.3) {
            // Overshoot past the target along the direction of travel, then a short corrective movement.
            double ux = (end.x() - from.x()) / distance;
            double uy = (end.y() - from.y()) / distance;
            double past = Math.min(28, distance * (0.02 + random.nextDouble() * 0.04));
            double aside = random.nextGaussian() * 3;
            Point beyond = clamp(new Point(Math.round(end.x() + ux * past - uy * aside),
                    Math.round(end.y() + uy * past + ux * aside)), viewport);
            time = segment(steps, from, beyond, targetSize, 0, viewport);
            time = segment(steps, beyond, end, targetSize, time + 50 + random.nextInt(110), viewport);
        } else {
            segment(steps, from, end, targetSize, 0, viewport);
        }
        return steps;
    }

    private long segment(List<Step> steps, Point a, Point b, double targetSize, long start, Box viewport) {
        double distance = Math.max(1, distance(a, b));
        double ux = (b.x() - a.x()) / distance;
        double uy = (b.y() - a.y()) / distance;
        // Fitts's law: movement time grows with the logarithm of distance over target width.
        double fitts = 90 + 120 * log2(distance / Math.max(4, targetSize) + 1);
        long duration = Math.max(60, Math.round(fitts * Math.exp(random.nextGaussian() * 0.15) / tempo));
        // Both control points on the same side of the line: one smooth arc, not an S.
        double side = random.nextBoolean() ? 1 : -1;
        double bend = side * Math.min(180, distance * (0.04 + random.nextDouble() * 0.18));
        Point c1 = new Point(a.x() + ux * distance * (0.2 + random.nextDouble() * 0.2) - uy * bend * (0.6 + random.nextDouble() * 0.6),
                a.y() + uy * distance * (0.2 + random.nextDouble() * 0.2) + ux * bend * (0.6 + random.nextDouble() * 0.6));
        Point c2 = new Point(a.x() + ux * distance * (0.6 + random.nextDouble() * 0.2) - uy * bend * (0.6 + random.nextDouble() * 0.6),
                a.y() + uy * distance * (0.6 + random.nextDouble() * 0.2) + ux * bend * (0.6 + random.nextDouble() * 0.6));
        double skew = 0.8 + random.nextDouble() * 0.15;
        double amplitude = 0.4 + random.nextDouble() * 0.7;
        double frequency = 8 + random.nextDouble() * 4;
        double phaseX = random.nextDouble() * Math.PI * 2;
        double phaseY = random.nextDouble() * Math.PI * 2;
        Step last = steps.isEmpty() ? null : steps.get(steps.size() - 1);
        long elapsed = 0;
        while (true) {
            elapsed += 10 + random.nextInt(11);
            if (elapsed >= duration) break;
            double s = minimumJerk(Math.pow((double) elapsed / duration, skew));
            double fade = 1 - s;
            double seconds = (start + elapsed) / 1000.0;
            double x = bezier(s, a.x(), c1.x(), c2.x(), b.x())
                    + fade * (amplitude * Math.sin(2 * Math.PI * frequency * seconds + phaseX)
                    + random.nextGaussian() * 0.35);
            double y = bezier(s, a.y(), c1.y(), c2.y(), b.y())
                    + fade * (amplitude * Math.cos(2 * Math.PI * frequency * seconds + phaseY)
                    + random.nextGaussian() * 0.35);
            Point point = clamp(new Point(Math.round(x), Math.round(y)), viewport);
            // A mouse reports only actual changes.
            if (last != null && last.x() == point.x() && last.y() == point.y()) continue;
            last = new Step(point.x(), point.y(), start + elapsed);
            steps.add(last);
        }
        steps.add(new Step(b.x(), b.y(), start + duration));
        return start + duration;
    }

    /**
     * Where to click inside {@code box}: people aim at the middle and scatter around it, so a normal distribution
     * around the center, kept inside the inner 60 % so borders and rounded corners are never hit. Whole pixels.
     */
    Point clickPoint(Box box) {
        Point center = box.center();
        if (off()) return new Point(Math.round(center.x()), Math.round(center.y()));
        double x = center.x() + random.nextGaussian() * box.width() / 7;
        double y = center.y() + random.nextGaussian() * box.height() / 7;
        x = Math.round(Math.min(box.x() + box.width() * 0.8, Math.max(box.x() + box.width() * 0.2, x)));
        y = Math.round(Math.min(box.y() + box.height() * 0.8, Math.max(box.y() + box.height() * 0.2, y)));
        if (x <= box.x() || x >= box.x() + box.width()) x = center.x();
        if (y <= box.y() || y >= box.y() + box.height()) y = center.y();
        return new Point(x, y);
    }

    /**
     * Where the pointer enters the page before its first movement in a tab: on the top edge, as a hand coming from
     * the address bar or the tab strip, roughly above the target.
     */
    Point entryPoint(Point target, Box viewport) {
        double x = target.x() + random.nextGaussian() * 180;
        if (viewport != null) x = Math.min(viewport.width() - 1, x);
        return new Point(Math.max(0, Math.round(x)), random.nextInt(3));
    }

    /** From the click into a field to the first key: 120–700 ms, mostly around 260 ms. */
    long reactionPause() {
        return off() ? 20 : scaled(logNormal(260, 0.3, 120, 700));
    }

    /** Resting on the target before pressing: 60–400 ms, mostly around 120 ms. */
    long hoverBeforePress() {
        return off() ? 30 : scaled(logNormal(120, 0.4, 60, 400));
    }

    /** How long the button stays down: 45–180 ms, mostly around 85 ms. */
    long buttonHold() {
        return off() ? 40 : scaled(logNormal(85, 0.3, 45, 180));
    }

    /**
     * One flick of the wheel: 1–5 notches of {@code notch} pixels with short gaps; the sign gives the direction.
     * Returned as alternating delta and pause-after values.
     */
    List<long[]> wheelBurst(double remaining, int notch) {
        List<long[]> burst = new ArrayList<>();
        int needed = (int) Math.max(1, Math.ceil(Math.abs(remaining) / notch));
        int count = off() ? needed : Math.min(needed, 1 + random.nextInt(5));
        for (int i = 0; i < count; i++) {
            burst.add(new long[] {(long) Math.signum(remaining) * notch, off() ? 15 : scaled(25 + random.nextInt(50))});
        }
        return burst;
    }

    /** Reading pause after a flick of the wheel, before the next one. */
    long wheelPause() {
        return off() ? 120 : scaled(180 + random.nextInt(320));
    }

    /** Where the target should end up after scrolling: between 30 % and 60 % of the viewport height. */
    double scrollAnchor(double viewportHeight) {
        return viewportHeight * (off() ? 0.4 : 0.3 + random.nextDouble() * 0.3);
    }

    private long scaled(double millis) {
        return Math.round(millis / tempo);
    }

    double logNormal(double median, double sigma, double min, double max) {
        return Math.min(max, Math.max(min, median * Math.exp(random.nextGaussian() * sigma)));
    }

    private static Point clamp(Point point, Box viewport) {
        double x = Math.max(0, point.x());
        double y = Math.max(0, point.y());
        if (viewport != null) {
            x = Math.min(viewport.width() - 1, x);
            y = Math.min(viewport.height() - 1, y);
        }
        return new Point(x, y);
    }

    private static double minimumJerk(double u) {
        return u * u * u * (10 - 15 * u + 6 * u * u);
    }

    private static double bezier(double t, double p0, double p1, double p2, double p3) {
        double r = 1 - t;
        return r * r * r * p0 + 3 * r * r * t * p1 + 3 * r * t * t * p2 + t * t * t * p3;
    }

    private static double distance(Point a, Point b) {
        return Math.hypot(b.x() - a.x(), b.y() - a.y());
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2);
    }
}
