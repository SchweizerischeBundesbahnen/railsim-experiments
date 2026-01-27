package org.matsim.project.scenario.plan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class OperationalPlanReader {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public OperationalPlan read(Path filePath) throws IOException {
        Objects.requireNonNull(filePath, "Path cannot be null.");
        return convert(MAPPER.readValue(filePath.toFile(), OperationalPlanDto.class));
    }

    public OperationalPlan read(InputStream inputStream) throws IOException {
        Objects.requireNonNull(inputStream, "Input stream cannot be null.");
        return convert(MAPPER.readValue(inputStream, OperationalPlanDto.class));
    }

    private OperationalPlan convert(OperationalPlanDto dto) {
        if (dto.volumes() == null) {
            throw new IllegalArgumentException("Train volumes configuration is missing.");
        }

        TrainVolumes volumes = convertTrainVolumes(dto.volumes());
        Map<String, Product> products = convertProducts(dto.products());
        Map<String, TrafficFlow> flows = convertTrafficFlows(dto.flows(), products, volumes);
        Map<String, FlowPattern> patterns = convertFlowPatterns(dto.patterns(), products, flows);
        Map<String, ProductMix> mixes = convertProductMixes(dto.mixes(), products);
        List<OperatingMode> modes = convertOperatingModes(dto.modes(), mixes, patterns);

        return OperationalPlan.builder()
                .trainVolumes(volumes)
                .products(Map.copyOf(products))
                .trafficFlows(Map.copyOf(flows))
                .flowPatterns(Map.copyOf(patterns))
                .productMixes(Map.copyOf(mixes))
                .operatingModes(List.copyOf(modes))
                .build();
    }

    private Map<String, Product> convertProducts(Map<String, ProductDto> productDtos) {
        if (productDtos == null) {
            return Map.of();
        }

        return productDtos.entrySet()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> Product.builder()
                        .id(e.getKey())
                        .description(e.getValue().description())
                        .minHeadway(e.getValue().minHeadway())
                        .build()));
    }

    private Map<String, TrafficFlow> convertTrafficFlows(Map<String, FlowDto> flowDtos, Map<String, Product> products,
                                                         TrainVolumes volumes) {
        if (flowDtos == null) {
            return Map.of();
        }

        Map<String, TrafficFlow> flows = new HashMap<>();

        flowDtos.forEach((flowId, flowDto) -> {
            Map<Product, RouteMapping> routeMap = new HashMap<>();
            if (flowDto.routes() != null) {
                flowDto.routes().forEach((productId, routeDto) -> {
                    Product product = resolve(products, productId, "Flow " + flowId);
                    validateRouteMapping(volumes.isBidirectional(), flowId, productId, routeDto);
                    routeMap.put(product, new RouteMapping(routeDto.forward(), routeDto.reverse()));
                });
            }
            flows.put(flowId, TrafficFlow.builder()
                    .id(flowId)
                    .description(flowDto.description())
                    .routes(Map.copyOf(routeMap))
                    .build());
        });

        return flows;
    }

    private Map<String, FlowPattern> convertFlowPatterns(Map<String, PatternDto> patternDtos,
                                                         Map<String, Product> products,
                                                         Map<String, TrafficFlow> flows) {
        if (patternDtos == null) {
            return Map.of();
        }

        Map<String, FlowPattern> patterns = new HashMap<>();

        patternDtos.forEach((patternId, patternDto) -> {
            Map<Product, Map<TrafficFlow, Double>> patternShares = new HashMap<>();
            if (patternDto.shares() != null) {
                patternDto.shares().forEach((productId, flowDist) -> {
                    Product product = resolve(products, productId, "Pattern " + patternId);
                    validateShareSum("Pattern " + patternId, productId, flowDist);

                    Map<TrafficFlow, Double> flowMap = flowDist.entrySet()
                            .stream()
                            .collect(Collectors.toMap(
                                    e -> resolve(flows, e.getKey(), "Pattern " + patternId + " product " + productId),
                                    Map.Entry::getValue));
                    patternShares.put(product, Map.copyOf(flowMap));
                });
            }
            patterns.put(patternId, FlowPattern.builder()
                    .id(patternId)
                    .description(patternDto.description())
                    .shares(Map.copyOf(patternShares))
                    .build());
        });

        return patterns;
    }

    private Map<String, ProductMix> convertProductMixes(Map<String, MixDto> mixDtos, Map<String, Product> products) {
        if (mixDtos == null) {
            return Map.of();
        }

        Map<String, ProductMix> mixes = new HashMap<>();

        mixDtos.forEach((mixId, mixDto) -> {
            Map<Product, Double> productShares = new HashMap<>();
            if (mixDto.shares() != null) {
                validateShareSum("Mix " + mixId, "Products", mixDto.shares());
                mixDto.shares().forEach((productId, share) -> {
                    Product product = resolve(products, productId, "Mix " + mixId);
                    productShares.put(product, share);
                });
            }
            mixes.put(mixId, ProductMix.builder()
                    .id(mixId)
                    .description(mixDto.description())
                    .shares(Map.copyOf(productShares))
                    .build());
        });

        return mixes;
    }

    private List<OperatingMode> convertOperatingModes(List<ModeDto> modeDtos, Map<String, ProductMix> mixes,
                                                      Map<String, FlowPattern> patterns) {
        if (modeDtos == null) {
            return List.of();
        }

        List<OperatingMode> results = new ArrayList<>();

        for (int i = 0; i < modeDtos.size(); i++) {
            ModeDto modeDto = modeDtos.get(i);
            ProductMix mix = resolve(mixes, modeDto.mix(), "Modes entry [" + i + "]");

            if (modeDto.patterns() != null) {
                for (String patternId : modeDto.patterns()) {
                    FlowPattern pattern = resolve(patterns, patternId, "Mode " + modeDto.mix());

                    // validate compatibility between mix and pattern
                    for (Product product : mix.getShares().keySet()) {
                        if (!pattern.getShares().containsKey(product)) {
                            throw new IllegalArgumentException(String.format(
                                    "Incompatibility in OperatingMode: Mix '%s' requires product '%s', " + "but Pattern '%s' does not define a flow distribution for it.",
                                    mix.getId(), product.getId(), pattern.getId()));
                        }
                    }

                    results.add(new OperatingMode(mix, pattern));
                }
            }
        }

        return results;
    }

    private TrainVolumes convertTrainVolumes(TrainVolumesDto dto) {
        return TrainVolumes.builder()
                .period(dto.period())
                .min(dto.min())
                .max(dto.max())
                .step(dto.step())
                .bidirectional(dto.bidirectional())
                .build();
    }

    private <T> T resolve(Map<String, T> registry, String id, String context) {
        T item = registry.get(id);
        if (item == null) {
            throw new IllegalArgumentException(String.format("%s references unknown ID: '%s'", context, id));
        }

        return item;
    }

    private void validateRouteMapping(boolean isBidirectional, String flowId, String productId, RoutesDto route) {
        boolean hasReverse = route.reverse() != null && !route.reverse().isBlank();
        if (isBidirectional && !hasReverse) {
            throw new IllegalArgumentException(
                    "Bidirectional flow '" + flowId + "' (" + productId + ") missing reverse route.");
        } else if (!isBidirectional && hasReverse) {
            throw new IllegalArgumentException(
                    "Unidirectional flow '" + flowId + "' (" + productId + ") should not have reverse route.");
        }
    }

    private void validateShareSum(String context, String subContext, Map<String, Double> shares) {
        double sum = shares.values().stream().mapToDouble(Double::doubleValue).sum();
        if (Math.abs(sum - 1.0) > 1e-5) {
            throw new IllegalArgumentException(
                    String.format("Shares in %s for %s sum to %.4f (must be 1.0)", context, subContext, sum));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OperationalPlanDto(TrainVolumesDto volumes, Map<String, ProductDto> products,
                                      Map<String, FlowDto> flows, Map<String, PatternDto> patterns,
                                      Map<String, MixDto> mixes, List<ModeDto> modes) {
    }

    private record TrainVolumesDto(int period, int min, int max, int step, boolean bidirectional) {
    }

    private record ProductDto(String description, int minHeadway) {
    }

    private record FlowDto(String description, Map<String, RoutesDto> routes) {
    }

    private record RoutesDto(String forward, String reverse) {
    }

    private record PatternDto(String description, Map<String, Map<String, Double>> shares) {
    }

    private record MixDto(String description, Map<String, Double> shares) {
    }

    private record ModeDto(String mix, List<String> patterns) {
    }
}