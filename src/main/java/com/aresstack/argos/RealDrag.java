package com.aresstack.argos;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

import java.util.random.RandomGenerator;

/**
 * Drags with the real mouse: the pointer travels to the source on a human path ({@link RealInput}), the left button
 * goes down, the pointer moves a few pixels so the browser starts its own drag session, travels to the target, lingers
 * there with small movements and releases. Every step is a WebDriver BiDi {@code input.performActions} command, so
 * the browser itself fires {@code dragstart}, {@code dragenter}, {@code dragover} and {@code drop} (or plain
 * mousemove/mouseup for script-made sliders and sortable lists). Neither {@code locator.dragTo} (marker events,
 * playwright4j #38) nor script-made DragEvents are used.
 */
final class RealDrag {
    /** Firefox starts a drag session after the pointer moved more than a few pixels with the button held. */
    private static final double DRAG_THRESHOLD = 9;

    private final RealInput input;
    private final RandomGenerator random;

    RealDrag(RealInput input, RandomGenerator random) {
        this.input = input;
        this.random = random;
    }

    /** One end of a drag: an element or a viewport point in CSS pixels. */
    record End(Locator element, Double x, Double y) {
        static End of(Locator element) {
            return new End(element, null, null);
        }

        static End at(double x, double y) {
            return new End(null, x, y);
        }
    }

    /** Drags from {@code source} to {@code target}; returns the press and release points. */
    HumanMotion.Point[] drag(Page page, End source, End target) {
        return drag(page, InputDevice.of(page), source, target);
    }

    HumanMotion.Point[] drag(Page page, InputDevice device, End source, End target) {
        HumanMotion.Point from = reach(page, source);
        device.sleep(between(120, 260));
        device.down(MouseButton.LEFT);
        boolean released = false;
        try {
            device.sleep(between(90, 180));
            // Leave the press point a little first: the drag session starts here, still above the source.
            input.humanMove(page, from.x() + (random.nextBoolean() ? DRAG_THRESHOLD : -DRAG_THRESHOLD),
                    from.y() + DRAG_THRESHOLD / 2);
            device.sleep(between(40, 90));
            HumanMotion.Point to = reach(page, target);
            // Rest on the target with tiny movements so dragover reaches it more than once before the drop.
            for (int i = 0; i < 2; i++) {
                device.sleep(between(60, 120));
                device.move(to.x() + (i == 0 ? 1 : 0), to.y() + (i == 0 ? 1 : 0));
            }
            device.sleep(between(80, 160));
            device.up(MouseButton.LEFT);
            released = true;
            return new HumanMotion.Point[] {from, to};
        } finally {
            // Never leave the button held: the next click in this tab would otherwise continue the drag.
            if (!released) device.up(MouseButton.LEFT);
        }
    }

    private HumanMotion.Point reach(Page page, End end) {
        return end.element() != null ? input.humanHover(page, end.element()) : input.humanMove(page, end.x(), end.y());
    }

    private long between(int min, int max) {
        return min + random.nextInt(max - min + 1);
    }
}
