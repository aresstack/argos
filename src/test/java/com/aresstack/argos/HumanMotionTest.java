package com.aresstack.argos;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HumanMotionTest {
    private static final HumanMotion.Box VIEWPORT = new HumanMotion.Box(0, 0, 1280, 720);

    private static HumanMotion motion(long seed, HumanInputSettings.Mode mode) {
        HumanInputSettings settings = new HumanInputSettings(mode, 1, 0, seed, HumanInputSettings.UnicodePolicy.STRICT);
        return new HumanMotion(new SplittableRandom(seed), settings);
    }

    @RepeatedTest(40) void pathIsAContinuousCurveOfWholePixelsEndingOnTheTarget(
            org.junit.jupiter.api.RepetitionInfo repetition) {
        HumanMotion motion = motion(repetition.getCurrentRepetition(), HumanInputSettings.Mode.NATURAL);
        HumanMotion.Point from = new HumanMotion.Point(40, 650);
        HumanMotion.Point to = new HumanMotion.Point(1100.4, 90.6);
        List<HumanMotion.Step> steps = motion.path(from, to, 30, VIEWPORT);
        HumanMotion.Step last = steps.get(steps.size() - 1);
        assertEquals(1100, last.x());
        assertEquals(91, last.y());
        assertTrue(steps.size() > 15, "a long move has many samples: " + steps.size());
        HumanMotion.Step previous = null;
        double previousX = from.x();
        double previousY = from.y();
        for (HumanMotion.Step step : steps) {
            assertEquals(Math.rint(step.x()), step.x());
            assertEquals(Math.rint(step.y()), step.y());
            assertTrue(step.x() >= 0 && step.x() < 1280 && step.y() >= 0 && step.y() < 720, step.toString());
            if (previous != null) {
                assertTrue(step.at() > previous.at(), "time advances");
                assertTrue(step.at() - previous.at() <= 200, "no long freeze inside a movement");
            }
            // No teleport: the pointer never covers more than a fifth of the distance in one sample.
            assertTrue(Math.hypot(step.x() - previousX, step.y() - previousY) < 260, "jump at " + step);
            previous = step;
            previousX = step.x();
            previousY = step.y();
        }
        long duration = last.at();
        assertTrue(duration >= 250 && duration <= 2500, "duration " + duration);
    }

    @Test void pathsBendAwayFromTheStraightLine() {
        int bent = 0;
        for (long seed = 1; seed <= 30; seed++) {
            List<HumanMotion.Step> steps = motion(seed, HumanInputSettings.Mode.NATURAL)
                    .path(new HumanMotion.Point(100, 400), new HumanMotion.Point(900, 400), 40, VIEWPORT);
            double deviation = steps.stream().mapToDouble(step -> Math.abs(step.y() - 400)).max().orElse(0);
            if (deviation > 8) bent++;
        }
        assertTrue(bent >= 25, "most paths are curved: " + bent);
    }

    @Test void fartherAndSmallerTargetsTakeLonger() {
        long near = 0;
        long far = 0;
        for (long seed = 1; seed <= 30; seed++) {
            HumanMotion.Point from = new HumanMotion.Point(100, 100);
            near += last(motion(seed, HumanInputSettings.Mode.NATURAL).path(from, new HumanMotion.Point(180, 120), 80, VIEWPORT));
            far += last(motion(seed, HumanInputSettings.Mode.NATURAL).path(from, new HumanMotion.Point(1100, 600), 12, VIEWPORT));
        }
        assertTrue(far > near * 1.8, "Fitts: near " + near + " far " + far);
    }

    @Test void sameSeedGivesTheSamePathAndOtherSeedsDiffer() {
        HumanMotion.Point from = new HumanMotion.Point(10, 10);
        HumanMotion.Point to = new HumanMotion.Point(700, 500);
        assertEquals(motion(42, HumanInputSettings.Mode.NATURAL).path(from, to, 20, VIEWPORT),
                motion(42, HumanInputSettings.Mode.NATURAL).path(from, to, 20, VIEWPORT));
        assertNotEquals(motion(42, HumanInputSettings.Mode.NATURAL).path(from, to, 20, VIEWPORT),
                motion(43, HumanInputSettings.Mode.NATURAL).path(from, to, 20, VIEWPORT));
    }

    @Test void offModeMovesStraightToTheTarget() {
        List<HumanMotion.Step> steps = motion(1, HumanInputSettings.Mode.OFF)
                .path(new HumanMotion.Point(0, 0), new HumanMotion.Point(300, 200), 20, VIEWPORT);
        assertEquals(List.of(new HumanMotion.Step(300, 200, 0)), steps);
    }

    @RepeatedTest(200) void clickPointScattersAroundTheCenterInsideTheInnerArea(
            org.junit.jupiter.api.RepetitionInfo repetition) {
        HumanMotion motion = motion(repetition.getCurrentRepetition(), HumanInputSettings.Mode.NATURAL);
        HumanMotion.Point point = motion.clickPoint(new HumanMotion.Box(100, 40, 80, 20));
        assertTrue(point.x() >= 116 && point.x() <= 164, "x " + point.x());
        assertTrue(point.y() >= 44 && point.y() <= 56, "y " + point.y());
        HumanMotion.Point tiny = motion.clickPoint(new HumanMotion.Box(10.5, 10.5, 3, 3));
        assertTrue(tiny.x() > 10.5 && tiny.x() < 13.5 && tiny.y() > 10.5 && tiny.y() < 13.5, tiny.toString());
    }

    @Test void pointerEntersAcrossTheTopEdge() {
        for (long seed = 1; seed <= 50; seed++) {
            HumanMotion.Point entry = motion(seed, HumanInputSettings.Mode.NATURAL)
                    .entryPoint(new HumanMotion.Point(1270, 500), VIEWPORT);
            assertTrue(entry.y() <= 2 && entry.x() >= 0 && entry.x() < 1280, entry.toString());
        }
    }

    @Test void wheelBurstsScrollTowardsTheTarget() {
        HumanMotion motion = motion(3, HumanInputSettings.Mode.NATURAL);
        List<long[]> down = motion.wheelBurst(950, 100);
        assertTrue(!down.isEmpty() && down.size() <= 5);
        assertTrue(down.stream().allMatch(notch -> notch[0] == 100 && notch[1] > 0));
        assertTrue(motion.wheelBurst(-120, 100).stream().allMatch(notch -> notch[0] == -100));
    }

    private static long last(List<HumanMotion.Step> steps) {
        return steps.get(steps.size() - 1).at();
    }
}
