package com.aresstack.argos;

import com.microsoft.playwright.options.BoundingBox;
import org.junit.jupiter.api.RepeatedTest;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RealInputTest {
    private final RealInput input = new RealInput(new SplittableRandom());

    @RepeatedTest(50) void clickPointStaysInTheInnerHalfOfTheBox() {
        BoundingBox box = new BoundingBox();
        box.x = 100;
        box.y = 40;
        box.width = 80;
        box.height = 20;
        double[] point = input.pointInside(box);
        assertTrue(point[0] >= 120 && point[0] <= 160, "x " + point[0]);
        assertTrue(point[1] >= 45 && point[1] <= 55, "y " + point[1]);
    }
}
