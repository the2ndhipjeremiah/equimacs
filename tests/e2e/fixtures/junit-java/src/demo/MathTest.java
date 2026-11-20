package demo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

public class MathTest {
    @Test
    void addsNumbers() {
        assertEquals(4, 2 + 2);
    }

    @Test
    void failsComparison() {
        assertEquals(5, 2 + 2);
    }

    @Disabled("intentional skip")
    @Test
    void skippedTest() {
        assertEquals(1, 1);
    }
}
