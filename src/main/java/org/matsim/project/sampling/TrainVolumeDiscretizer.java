package org.matsim.project.sampling;

import lombok.RequiredArgsConstructor;
import org.matsim.project.scenario.plan.OperatingMode;
import org.matsim.project.scenario.plan.Product;
import org.matsim.project.scenario.plan.RouteMapping;
import org.matsim.project.scenario.plan.TrafficFlow;

import java.util.*;

/**
 * Scalable logic to convert fractional train shares into discrete train counts.
 * <p>
 * This class ensures that the sum of distributed trains always equals the requested
 * total, even when shares result in complex floating-point remainders.
 * <p>
 * The distribution follows a two-stage process:
 * 1. Integer Allocation: Every route receives the 'floor' of its fractional target (e.g., a target of 2.8 trains
 * guarantees 2 trains).
 * 2. Stochastic Remainder Distribution: The remaining trains (the difference between the total and the sum of floors)
 * are distributed via a weighted lottery.
 * A candidate's probability of winning an extra train is proportional to its fractional remainder (e.g., 0.8 has an
 * 80% chance).
 */
@RequiredArgsConstructor
public class TrainVolumeDiscretizer {

    private final Random random;

    /**
     * Distributes a total volume of trains across the products and flows of an Operating Mode.
     *
     * @param totalTrains The discrete number of trains to be distributed.
     * @param mode        The Operating Mode providing the Product Mix and Flow Pattern.
     * @return A list of discrete train volumes per route mapping.
     * @throws IllegalStateException if the Pattern is missing products defined in the Mix.
     */
    public List<TrainVolume> discretize(int totalTrains, OperatingMode mode) {
        if (totalTrains <= 0) {
            return Collections.emptyList();
        }

        List<Candidate> candidates = new ArrayList<>();
        Map<Product, Double> mixShares = mode.getProductMix().getShares();
        Map<Product, Map<TrafficFlow, Double>> patternShares = mode.getFlowPattern().getShares();

        // sort Products by ID to ensure deterministic iteration order
        List<Product> sortedProducts = new ArrayList<>(mixShares.keySet());
        sortedProducts.sort(Comparator.comparing(Product::getId));

        // calculate fractional targets
        for (Product product : sortedProducts) {
            double share = mixShares.get(product);
            double productVolumeBudget = totalTrains * share;

            Map<TrafficFlow, Double> flows = patternShares.get(product);

            if (flows == null || flows.isEmpty()) {
                throw new IllegalStateException(
                        String.format("Inconsistency: Product '%s' is in Mix '%s' but has no flows in Pattern '%s'",
                                product.getId(), mode.getProductMix().getId(), mode.getFlowPattern().getId()));
            }

            // sort flows by ID to ensure deterministic iteration order
            List<TrafficFlow> sortedFlows = new ArrayList<>(flows.keySet());
            sortedFlows.sort(Comparator.comparing(TrafficFlow::getId));

            for (TrafficFlow flow : sortedFlows) {
                double flowShare = flows.get(flow);
                RouteMapping mapping = flow.getRoutes().get(product);

                if (mapping == null) {
                    throw new IllegalStateException(
                            String.format("Inconsistency: Flow '%s' does not define routes for product '%s'",
                                    flow.getId(), product.getId()));
                }

                double fractionalTarget = productVolumeBudget * flowShare;
                candidates.add(new Candidate(product, mapping, fractionalTarget));
            }
        }

        // assign floors (guaranteed integer part)
        int allocatedCount = 0;
        for (Candidate c : candidates) {
            c.allocated = (int) Math.floor(c.target);
            allocatedCount += c.allocated;
        }

        // lottery for remainders
        int trainsToDistribute = totalTrains - allocatedCount;

        // candidates who have a fractional part > 0
        List<Candidate> lotteryPool = new ArrayList<>();
        for (Candidate c : candidates) {
            if (c.target - c.allocated > 0) {
                lotteryPool.add(c);
            }
        }

        while (trainsToDistribute > 0 && !lotteryPool.isEmpty()) {
            Candidate winner = pickWinner(lotteryPool);
            winner.allocated++;
            trainsToDistribute--;
            // remove winner to ensure we spread remainders across as many candidates as possible
            lotteryPool.remove(winner);
        }

        // the candidates list was built in a sorted order, so the output list is also sorted
        return candidates.stream()
                .filter(c -> c.allocated > 0)
                .map(c -> new TrainVolume(c.product, c.routeMapping, c.allocated))
                .toList();
    }

    /**
     * Performs a weighted random selection.
     * <p>
     * The probability of a candidate being picked is (target - allocated) / sum(remainders).
     * Example:
     * Candidate A (remainder 0.8), Candidate B (remainder 0.2).
     * Total Weight = 1.0.
     * Random value 0.0 to 0.8 -> A wins.
     * Random value 0.8 to 1.0 -> B wins.
     */
    private Candidate pickWinner(List<Candidate> pool) {
        double totalWeight = pool.stream().mapToDouble(c -> c.target - c.allocated).sum();

        // safety for precision errors
        if (totalWeight <= 0) {
            return pool.get(random.nextInt(pool.size()));
        }

        double r = random.nextDouble() * totalWeight;
        double cumulative = 0;

        for (Candidate c : pool) {
            cumulative += (c.target - c.allocated);
            if (r <= cumulative) {
                return c;
            }
        }
        return pool.getLast();
    }

    private static class Candidate {
        final Product product;
        final RouteMapping routeMapping;
        final double target;
        int allocated;

        Candidate(Product product, RouteMapping routeMapping, double target) {
            this.product = product;
            this.routeMapping = routeMapping;
            this.target = target;
        }
    }

    public record TrainVolume(Product product, RouteMapping routeMapping, int amount) {
    }
}