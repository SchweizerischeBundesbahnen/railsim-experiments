package org.matsim.project.sampling.strategy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class RandomDepartureSamplingTest {

    private RandomDepartureSampling sampler;
    private Random random;

    @BeforeEach
    void setUp() {
        sampler = new RandomDepartureSampling();
        random = new Random(42); // fixed seed for reproducibility
    }

    @Test
    void testSingleDepartureSingleHour() {
        List<Double> departures = sampler.sampleDepartures(1, 1, random);
        assertEquals(1, departures.size());
        assertTrue(departures.getFirst() >= 0 && departures.getFirst() < 3600);
    }

    @Test
    void testMultipleDeparturesSingleHour() {
        int n = 5;
        List<Double> departures = sampler.sampleDepartures(n, 1, random);
        assertEquals(n, departures.size());
        departures.forEach(d -> assertTrue(d >= 0 && d < 3600));
    }

    @Test
    void testInvalidParameters() {
        assertThrows(IllegalArgumentException.class, () -> sampler.sampleDepartures(0, 1, random));
        assertThrows(IllegalArgumentException.class, () -> sampler.sampleDepartures(1, 0, random));
        assertThrows(IllegalArgumentException.class, () -> sampler.sampleDepartures(-1, 1, random));
        assertThrows(IllegalArgumentException.class, () -> sampler.sampleDepartures(1, -5, random));
    }

    @Nested
    class MultiHourTests {

        @Test
        void testSingleDepartureMultipleHours() {
            int hours = 3;
            List<Double> departures = sampler.sampleDepartures(1, hours, random);
            assertEquals(hours, departures.size());
            for (int h = 0; h < hours; h++) {
                double dep = departures.get(h);
                assertTrue(dep >= h * 3600 && dep < (h + 1) * 3600);
            }
        }

        @Test
        void testMultipleDeparturesMultipleHours() {
            int n = 4;
            int hours = 2;
            List<Double> departures = sampler.sampleDepartures(n, hours, random);
            assertEquals(n * hours, departures.size());

            for (int h = 0; h < hours; h++) {
                for (int i = 0; i < n; i++) {
                    double dep = departures.get(h * n + i);
                    assertTrue(dep >= h * 3600 && dep < (h + 1) * 3600);
                }
            }
        }
    }
}
