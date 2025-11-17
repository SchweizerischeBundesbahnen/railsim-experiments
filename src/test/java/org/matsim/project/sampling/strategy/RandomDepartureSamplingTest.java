package org.matsim.project.sampling.strategy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class RandomDepartureSamplingTest {

    public static final int SEED = 42;
    private RandomDepartureSampling strategy;

    @BeforeEach
    void setUp() {
        strategy = new RandomDepartureSampling();
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
            List<Double> departures = strategy.sampleDepartures(7, 1800, 5000, new Random(SEED));
            assertFalse(departures.isEmpty());
            for (double dep : departures) {
                assertEquals(0.0, dep - Math.floor(dep), "Departure time should be a whole number.");
            }
        }

        @Test
        void testRepeatingPatternBehavior() {
            int n = 3;
            int period = 1000;
            int time = 2500; // 2.5 periods

            // This test is the core behavioral contract of this strategy.
            List<Double> departures = strategy.sampleDepartures(n, period, time, new Random(SEED));

            // Get the rounded offsets from the first full period
            List<Double> firstPeriodOffsets = departures.stream().filter(d -> d < period).toList();

            // Get the rounded offsets from the second full period
            List<Double> secondPeriodOffsets = departures.stream()
                    .filter(d -> d >= period && d < 2 * period)
                    .map(d -> d - period)
                    .toList();

            assertEquals(n, firstPeriodOffsets.size());
            assertEquals(n, secondPeriodOffsets.size());

            // The key assertion: the pattern of relative times must be identical.
            for (int i = 0; i < n; i++) {
                assertEquals(firstPeriodOffsets.get(i), secondPeriodOffsets.get(i),
                        "The relative offsets in each full period must be identical.");
            }
        }
    }

    @Nested
    class EdgeCaseTests {

        @Test
        void testTimeIsShorterThanPeriod() {
            int n = 5;
            int period = 3600;
            int time = 1000;

            List<Double> departures = strategy.sampleDepartures(n, period, time, new Random(SEED));

            // We don't assert the exact number, but we check the properties.
            assertAll("Properties for short simulation time", () -> assertTrue(departures.size() <= n),
                    () -> assertTrue(departures.stream().allMatch(d -> d < time)));
        }

        @Test
        void testTimeIsNotMultipleOfPeriod() {
            int n = 3;
            int period = 1000;
            int time = 2500; // 2.5 periods

            List<Double> departures = strategy.sampleDepartures(n, period, time, new Random(SEED));

            long fullPeriods = time / period;
            long expectedInFullPeriods = n * fullPeriods;
            long departuresInPartialPeriod = departures.stream().filter(d -> d >= fullPeriods * period).count();

            assertAll("Properties for non-multiple time", () -> assertTrue(departures.size() >= expectedInFullPeriods),
                    () -> assertTrue(departures.size() <= expectedInFullPeriods + n),
                    () -> assertTrue(departuresInPartialPeriod <= n),
                    () -> assertTrue(departures.stream().allMatch(d -> d < time)));
        }

        @Test
        void testNoDeparturesGeneratedWhenTimeIsTooShort() {
            // With a given seed, the first random departure might be late in the period.
            // If time is shorter than that first departure, the list should be empty.
            // This is probabilistic, but with a fixed seed it's a valid test.
            List<Double> departures = strategy.sampleDepartures(5, 3600, 80, new Random(SEED));
            assertTrue(departures.isEmpty());
        }
    }
}