package org.matsim.project.scenario.plan;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OperationalPlanReaderTest {

    private OperationalPlanReader reader;

    @BeforeEach
    void setUp() {
        reader = new OperationalPlanReader();
    }

    private InputStream stringToStream(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Should parse the full complex Operational Plan and flatten modes")
    void testReadFullPlan() throws IOException {
        String json = """
                {
                  "volumes": { "period": 1800, "min": 4, "max": 12, "step": 1, "bidirectional": false },
                  "products": {
                    "FV": { "description": "Fernverkehr", "minHeadway": 120 },
                    "RV": { "description": "Regionalverkehr", "minHeadway": 90 },
                    "GV": { "description": "Güterverkehr", "minHeadway": 180 }
                  },
                  "flows": {
                    "LMR": { "description": "Halt bei M", "routes": { "FV": { "forward": "FV_LMR" }, "RV": { "forward": "RV_LMR" } } },
                    "LR": { "description": "Direkt", "routes": { "FV": { "forward": "FV_LR" }, "GV": { "forward": "GV_LR" } } }
                  },
                  "patterns": {
                    "STANDARD": { "shares": { "RV": { "LMR": 1.0 }, "GV": { "LR": 1.0 } } },
                    "FV_PASS": { "shares": { "FV": { "LR": 1.0 }, "RV": { "LMR": 1.0 }, "GV": { "LR": 1.0 } } },
                    "FV_STOP": { "shares": { "FV": { "LMR": 1.0 }, "RV": { "LMR": 1.0 }, "GV": { "LR": 1.0 } } }
                  },
                  "mixes": {
                    "KM": { "description": "Kernnetz", "shares": { "FV": 0.4, "RV": 0.4, "GV": 0.2 } },
                    "R":  { "description": "Restnetz", "shares": { "FV": 0.5, "GV": 0.5 } },
                    "M":  { "description": "Metro", "shares": { "RV": 1.0 } }
                  },
                  "modes": [
                    { "mix": "KM", "patterns": ["FV_PASS", "FV_STOP"] },
                    { "mix": "R",  "patterns": ["FV_STOP"] },
                    { "mix": "M",  "patterns": ["STANDARD"] }
                  ]
                }
                """;

        OperationalPlan plan = reader.read(stringToStream(json));

        // 1. Verify Volumes
        assertNotNull(plan.getTrainVolumes());
        assertFalse(plan.getTrainVolumes().isBidirectional());
        assertEquals(1800, plan.getTrainVolumes().getPeriod());

        // 2. Verify Product Parsing
        assertEquals(3, plan.getProducts().size());
        assertEquals(120, plan.getProducts().get("FV").getMinHeadway());

        // 3. Verify OperatingModes Flattening
        // KM (2 patterns) + R (1 pattern) + M (1 pattern) = 4 total scenarios
        List<OperatingMode> modes = plan.getOperatingModes();
        assertEquals(4, modes.size());

        // Verify specific generated IDs (assuming reader generates id = mix_pattern)
        assertTrue(modes.stream().anyMatch(m -> m.getId().equals("km_fv_pass")));
        assertTrue(modes.stream().anyMatch(m -> m.getId().equals("km_fv_stop")));
        assertTrue(modes.stream().anyMatch(m -> m.getId().equals("r_fv_stop")));
        assertTrue(modes.stream().anyMatch(m -> m.getId().equals("m_standard")));

        // 4. Verify Pattern Logic (Check if FV_PASS contains the LR flow for FV)
        FlowPattern fvPass = plan.getFlowPatterns().get("FV_PASS");
        Product fvProduct = plan.getProducts().get("FV");
        TrafficFlow lrFlow = plan.getTrafficFlows().get("LR");

        assertEquals(1.0, fvPass.getShares().get(fvProduct).get(lrFlow));
    }

    @Test
    @DisplayName("Should fail if shares do not sum to 1.0")
    void testInvalidShareSum() {
        String json = """
                {
                  "volumes": { "period": 1800, "bidirectional": false },
                  "products": { "FV": { "minHeadway": 60 } },
                  "mixes": {
                    "BAD_MIX": { "shares": { "FV": 0.8 } }
                  }
                }
                """;
        // 0.8 != 1.0
        Exception e = assertThrows(IllegalArgumentException.class, () -> reader.read(stringToStream(json)));
        assertTrue(e.getMessage().contains("must be 1.0"));
    }

    @Test
    @DisplayName("Should fail if reverse route is defined but bidirectional is false")
    void testInvalidRouteForUnidirectional() {
        String json = """
                {
                  "volumes": { "period": 1800, "bidirectional": false },
                  "products": { "FV": { "minHeadway": 60 } },
                  "flows": {
                    "F1": { "routes": { "FV": { "forward": "A", "reverse": "B" } } }
                  }
                }
                """;
        Exception e = assertThrows(IllegalArgumentException.class, () -> reader.read(stringToStream(json)));
        assertTrue(e.getMessage().contains("should not have reverse route"));
    }

    @Test
    @DisplayName("Should fail if pattern references unknown flow")
    void testUnknownFlowReference() {
        String json = """
                {
                  "volumes": { "period": 1800, "bidirectional": false },
                  "products": { "FV": { "minHeadway": 60 } },
                  "flows": { "REAL": { "routes": { "FV": { "forward": "A" } } } },
                  "patterns": {
                    "P1": { "shares": { "FV": { "GHOST_FLOW": 1.0 } } }
                  }
                }
                """;
        Exception e = assertThrows(IllegalArgumentException.class, () -> reader.read(stringToStream(json)));
        assertTrue(e.getMessage().contains("references unknown ID: 'GHOST_FLOW'"));
    }
}