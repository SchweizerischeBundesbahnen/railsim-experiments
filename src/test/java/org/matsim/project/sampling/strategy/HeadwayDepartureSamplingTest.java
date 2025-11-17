package org.matsim.project.sampling.strategy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class HeadwayDepartureSamplingTest {

    public static final int SEED = 42;
    private HeadwayDepartureSampling strategy;

    @BeforeEach
    void setUp() {
        strategy = new HeadwayDepartureSampling();
    }

    @Nested
    class InvalidParametersTests {

        @Test
        void testNonPositiveParametersThrow() {
            assertThrows(IllegalArgumentException.class, () -> strategy.sampleDepartures(0, 3600, 7200, new Random()));
            assertThrows(IllegalArgumentException.class, () -> strategy.sampleDepartures(1, 0, 7200, new Random()));
            assertThrows(IllegalArgumentException.class, () -> strategy.sampleDepartures(1, 3600, 0, new Random()));
            assertThrows(IllegalArgumentException.class, () -> strategy.sampleDepartures(-1, 3600, 7200, new Random()));
        }
    }

    @Nested
    class CoreFunctionalityTests {

        @Test
        void testAllDeparturesAreSortedAndWithinTime() {
            int time = 7200;
            List<Double> departures = strategy.sampleDepartures(10, 1800, time, new Random(SEED));
            assertFalse(departures.isEmpty());
            for (int i = 0; i < departures.size() - 1; i++) {
                assertTrue(departures.get(i) <= departures.get(i + 1), "Departures should be sorted.");
            }
            assertTrue(departures.getLast() < time, "All departures must be before the end time.");
        }

        @Test
        void testAllDeparturesAreWholeSeconds() {
            // Use parameters that guarantee fractional headways
            List<Double> departures = strategy.sampleDepartures(7, 1800, 5000, new Random(SEED));
            assertFalse(departures.isEmpty());
            for (double dep : departures) {
                assertEquals(0.0, dep - Math.floor(dep), "Departure time should be a whole number.");
            }
        }

        @Test
        void testHeadwayIsApproximatelyConstant() {
            int n = 7;
            int period = 1000;
            double expectedHeadway = (double) period / n;

            List<Double> departures = strategy.sampleDepartures(n, period, 3600, new Random(SEED));

            // Due to rounding, the final integer headway can have a +/- 1s jitter.
            for (int i = 0; i < departures.size() - 1; i++) {
                double actualHeadway = departures.get(i + 1) - departures.get(i);
                assertTrue(Math.abs(expectedHeadway - actualHeadway) <= 1.0,
                        "Actual headway should fluctuate by at most 1s around the true headway.");
            }
        }
    }

    @Nested
    class EdgeCaseTests {

        @Test
        void testTimeIsShorterThanPeriod() {
            int n = 10; // Headway of 180s
            int period = 1800;
            int time = 500;
            List<Double> departures = strategy.sampleDepartures(n, period, time, new Random(SEED));

            // We don't assert the exact number, as it depends on the random offset.
            // Instead, we test the properties that must hold true.
            assertAll("Properties for short simulation time", () -> assertTrue(departures.size() <= n),
                    () -> assertTrue(departures.stream().allMatch(d -> d < time)));
        }

        @Test
        void testTimeIsNotMultipleOfPeriod() {
            int n = 2; // Headway of 900s
            int period = 1800;
            int time = 4000; // 4.44 periods
            // With a fixed seed (42), we know the offset is ~658s.
            // Departures are expected at ~658, ~1558, ~2458, ~3358. The next at ~4258 is cut off.
            List<Double> departures = strategy.sampleDepartures(n, period, time, new Random(SEED));
            assertEquals(4, departures.size());
            assertTrue(departures.stream().allMatch(d -> d < time));
        }

        @Test
        void testNoDeparturesGeneratedWhenTimeIsShorterThanFirstPossibleDeparture() {
            // Headway is 180s. The first departure must be in [0, 180).
            // If time is very short, it's possible no departures are generated.
            List<Double> departures = strategy.sampleDepartures(10, 1800, 10, new Random(SEED));
            // We can't guarantee it's empty, but we can check its properties if it's not.
            if (!departures.isEmpty()) {
                assertEquals(1, departures.size());
                assertTrue(departures.getFirst() < 10);
            }
        }
    }
}