package org.matsim.project.sampling;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.matsim.project.scenario.plan.*;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class TrainVolumeDiscretizerTest {

    private Product fv, rv;
    private TrafficFlow flowA, flowB;

    @BeforeEach
    void setUp() {
        fv = Product.builder().id("FV").build();
        rv = Product.builder().id("RV").build();

        RouteMapping mapA = RouteMapping.builder().forwardRouteId("A_fwd").reverseRouteId("A_rev").build();
        RouteMapping mapB = RouteMapping.builder().forwardRouteId("B_fwd").build(); // reverse is null

        flowA = TrafficFlow.builder().id("FlowA").routes(Map.of(fv, mapA, rv, mapA)).build();
        flowB = TrafficFlow.builder().id("FlowB").routes(Map.of(fv, mapB, rv, mapB)).build();
    }

    @Test
    @DisplayName("Conservation of Mass: Sum of results must always equal input total")
    void testSumEquality() {
        ProductMix mix = ProductMix.builder().id("Mix_KM").shares(Map.of(fv, 0.4, rv, 0.6)).build();

        FlowPattern pattern = FlowPattern.builder()
                .id("Pat_Standard")
                .shares(Map.of(fv, Map.of(flowA, 0.7, flowB, 0.3), rv, Map.of(flowA, 1.0)))
                .build();

        OperatingMode mode = OperatingMode.builder().productMix(mix).flowPattern(pattern).build();

        TrainVolumeDiscretizer distributor = new TrainVolumeDiscretizer(new Random(123));

        for (int total = 1; total <= 50; total++) {
            List<TrainVolumeDiscretizer.TrainVolume> result = distributor.discretize(total, mode);
            int sum = result.stream().mapToInt(TrainVolumeDiscretizer.TrainVolume::amount).sum();
            assertEquals(total, sum, "Sum mismatch at total: " + total);
        }
    }

    @Test
    @DisplayName("Probability Weighting: High remainder should win more often than low remainder")
    void testProbabilityWeighting() {
        ProductMix mix = ProductMix.builder().id("Mix_FV_Only").shares(Map.of(fv, 1.0)).build();

        // Flow A gets 0.9 remainder, Flow B gets 0.1 remainder
        FlowPattern pattern =
                FlowPattern.builder().id("Pat_Split").shares(Map.of(fv, Map.of(flowA, 0.9, flowB, 0.1))).build();

        OperatingMode mode = OperatingMode.builder().productMix(mix).flowPattern(pattern).build();

        int aWins = 0;
        int iterations = 1000;
        for (int i = 0; i < iterations; i++) {
            TrainVolumeDiscretizer distributor = new TrainVolumeDiscretizer(new Random(i));
            List<TrainVolumeDiscretizer.TrainVolume> res = distributor.discretize(1, mode);
            if (res.getFirst().routeMapping().getForwardRouteId().equals("A_fwd")) {
                aWins++;
            }
        }

        // Check statistical significance (90% target)
        assertTrue(aWins > 850, "Flow A (90% weight) won too few times: " + aWins);
    }

    @Test
    @DisplayName("Robustness: Exception thrown when Pattern is missing Mix Product")
    void testMissingProductInPattern() {
        ProductMix mix = ProductMix.builder().id("Mix_Both").shares(Map.of(fv, 0.5, rv, 0.5)).build();

        // Pattern only defines FV, results in error when trying to distribute RV
        FlowPattern pattern = FlowPattern.builder().id("Pat_FV_Only").shares(Map.of(fv, Map.of(flowA, 1.0))).build();

        OperatingMode mode = OperatingMode.builder().productMix(mix).flowPattern(pattern).build();

        TrainVolumeDiscretizer distributor = new TrainVolumeDiscretizer(new Random(42));
        assertThrows(IllegalStateException.class, () -> distributor.discretize(10, mode));
    }

    @Test
    @DisplayName("Determinism: Same seed must produce identical distributions")
    void testDeterminism() {
        ProductMix mix = ProductMix.builder().id("Mix_FV").shares(Map.of(fv, 1.0)).build();

        FlowPattern pattern =
                FlowPattern.builder().id("Pat_5050").shares(Map.of(fv, Map.of(flowA, 0.5, flowB, 0.5))).build();

        OperatingMode mode = OperatingMode.builder().productMix(mix).flowPattern(pattern).build();

        long seed = 12345;
        List<TrainVolumeDiscretizer.TrainVolume> res1 =
                new TrainVolumeDiscretizer(new Random(seed)).discretize(1, mode);
        List<TrainVolumeDiscretizer.TrainVolume> res2 =
                new TrainVolumeDiscretizer(new Random(seed)).discretize(1, mode);

        assertEquals(res1.getFirst().routeMapping().getForwardRouteId(),
                res2.getFirst().routeMapping().getForwardRouteId());
    }
}