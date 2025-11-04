package org.matsim.project.sampling.strategy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class HeadwayDepartureSamplingTest {

    private HeadwayDepartureSampling strategy;
    private Random random;

    @BeforeEach
    void setUp() {
        strategy = new HeadwayDepartureSampling();
        random = new Random(42);
    }

    @Nested
    class InvalidParametersTests {

        @Test
        void testZeroDeparturesThrows() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> strategy.sampleDepartures(0, 1, random));
            assertTrue(ex.getMessage().contains("n=0"));
        }

        @Test
        void testZeroHoursThrows() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> strategy.sampleDepartures(1, 0, random));
            assertTrue(ex.getMessage().contains("hours=0"));
        }

        @Test
        void testNegativeParametersThrow() {
            assertThrows(IllegalArgumentException.class, () -> strategy.sampleDepartures(-1, 1, random));
            assertThrows(IllegalArgumentException.class, () -> strategy.sampleDepartures(1, -2, random));
        }
    }

    @Nested
    class SingleHourTests {

        @Test
        void testSingleDeparture() {
            List<Double> departures = strategy.sampleDepartures(1, 1, random);
            assertEquals(1, departures.size());
            assertEquals(0.0, departures.getFirst());
        }

        @Test
        void testMultipleDepartures() {
            int n = 4;
            List<Double> departures = strategy.sampleDepartures(n, 1, random);
            assertEquals(n, departures.size());

            // Check that headway is constant within the hour
            double headway = departures.get(1) - departures.get(0);
            for (int i = 1; i < departures.size(); i++) {
                assertEquals(headway, departures.get(i) - departures.get(i - 1), 1e-6);
            }

            // Check that all departures fit within the hour
            departures.forEach(d -> assertTrue(d >= 0 && d < 3600));
        }
    }

    @Nested
    class MultiHourTests {

        @Test
        void testMultipleHoursNumberOfDepartures() {
            int n = 3;
            int hours = 5;
            List<Double> departures = strategy.sampleDepartures(n, hours, random);

            // Total departures
            assertEquals(n * hours, departures.size());

            // Check number of departures per hour
            for (int h = 0; h < hours; h++) {
                int startIndex = h * n;
                int endIndex = startIndex + n;
                List<Double> hourDepartures = departures.subList(startIndex, endIndex);

                // Headway constant within hour
                double headway = hourDepartures.get(1) - hourDepartures.get(0);
                for (int i = 1; i < hourDepartures.size(); i++) {
                    assertEquals(headway, hourDepartures.get(i) - hourDepartures.get(i - 1), 1e-6);
                }

                // Departures relative to hour
                for (Double d : hourDepartures) {
                    double relative = d - h * 3600;
                    assertTrue(relative >= 0 && relative < 3600);
                }
            }
        }

        @Test
        void testHeadwayConsistentAcrossHours() {
            int n = 4;
            int hours = 3;
            List<Double> departures = strategy.sampleDepartures(n, hours, random);

            // Headway should be the same for every hour
            for (int h = 0; h < hours; h++) {
                int startIndex = h * n;
                int endIndex = startIndex + n;
                List<Double> hourDepartures = departures.subList(startIndex, endIndex);

                double headway = hourDepartures.get(1) - hourDepartures.get(0);
                for (int i = 1; i < hourDepartures.size(); i++) {
                    assertEquals(headway, hourDepartures.get(i) - hourDepartures.get(i - 1), 1e-6);
                }
            }
        }

        @Test
        void testTransitionBetweenHours() {
            int n = 2;
            int hours = 2;
            List<Double> departures = strategy.sampleDepartures(n, hours, random);

            // Check that the last departure of the first hour < first departure of second hour
            double lastFirstHour = departures.get(n - 1);
            double firstSecondHour = departures.get(n);
            assertTrue(lastFirstHour < firstSecondHour);

            // Check that second hour departures are exactly 3600s ahead of first hour offsets
            double firstHourOffset0 = departures.getFirst();
            double secondHourOffset0 = departures.get(n);
            assertEquals(firstHourOffset0 + 3600, secondHourOffset0, 1e-6);
        }
    }
}
