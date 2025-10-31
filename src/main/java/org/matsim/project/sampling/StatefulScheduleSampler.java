package org.matsim.project.sampling;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.project.sampling.strategy.DepartureSamplingStrategy;
import org.matsim.project.scenario.plan.ProductType;
import org.matsim.project.scenario.plan.SubVariant;
import org.matsim.project.scenario.plan.TrainVolume;
import org.matsim.pt.transitSchedule.api.*;
import org.matsim.vehicles.*;

import java.util.*;

@RequiredArgsConstructor
@Log4j2
public class StatefulScheduleSampler {

    private final Random random;
    private final Scenario scenario;
    private final SubVariant subVariant;

    private final Map<Id<TransitRoute>, Id<VehicleType>> routeVehicleType = new HashMap<>();

    public StatefulScheduleSampler(long seed, Scenario scenario, SubVariant subVariant) {
        this.random = new Random(seed);
        this.scenario = scenario;
        this.subVariant = subVariant;

        // assign unique vehicle type to route
        for (TransitLine transitLine : scenario.getTransitSchedule().getTransitLines().values()) {
            for (TransitRoute transitRoute : transitLine.getRoutes().values()) {
                // set of all vehicle types of all departures
                Set<Id<VehicleType>> vehicleTypeIds = new HashSet<>();
                transitRoute.getDepartures()
                        .values()
                        .forEach(departure -> vehicleTypeIds.add(scenario.getTransitVehicles()
                                .getVehicles()
                                .get(departure.getVehicleId())
                                .getType()
                                .getId()));

                if (vehicleTypeIds.size() != 1) {
                    throw new IllegalStateException(
                            String.format("Route %s has not unique or no vehicle types %s on departures ",
                                    transitRoute.getId().toString(), vehicleTypeIds));
                }

                routeVehicleType.put(transitRoute.getId(), vehicleTypeIds.iterator().next());
            }
        }
    }

    /**
     * Generates a new transit schedule with sampled departures.
     *
     * @param samplingStrategy The strategy to use for generating departure times (e.g., random, even interval).
     * @return A new, completely separate {@link TransitSchedule} instance.
     */
    public Sample sample(DepartureSamplingStrategy samplingStrategy) {
        final TransitSchedule templateSchedule = this.scenario.getTransitSchedule();

        // no need to deep copy, since no changes are made on stop facilities
        TransitSchedule newSchedule = scenario.getTransitSchedule().getFactory().createTransitSchedule();
        templateSchedule.getFacilities().values().forEach(newSchedule::addStopFacility);

        // no need to deep copy since transit vehicles types stay the same
        Vehicles newVehicles = VehicleUtils.createVehiclesContainer();
        this.scenario.getTransitVehicles().getVehicleTypes().values().forEach(newVehicles::addVehicleType);

        for (TrainVolume trainVolume : subVariant.getTrainVolumes()) {
            Optional<Match> match = findMatchingRoute(trainVolume);

            if (match.isPresent()) {
                addTransitRoute(newSchedule, newVehicles, match.get(), samplingStrategy);
            } else {
                throw new IllegalStateException(
                        "No match found for train volume " + trainVolume + " of sub-variant:" + subVariant.getId());
            }
        }

        return new Sample(newSchedule, newVehicles);
    }

    private void addTransitRoute(TransitSchedule schedule, Vehicles vehicles, Match match,
                                 DepartureSamplingStrategy samplingStrategy) {

        TransitScheduleFactory sf = schedule.getFactory();
        VehiclesFactory vf = vehicles.getFactory();

        // get or create new transit line
        TransitLine newLine = schedule.getTransitLines().get(match.transitLine.getId());
        if (newLine == null) {
            newLine = sf.createTransitLine(match.transitLine.getId());
            schedule.addTransitLine(newLine);
        }

        // ensure a route is never added twice
        if (newLine.getRoutes().containsKey(match.transitRoute.getId())) {
            throw new RuntimeException("Duplicate route id " + match.transitRoute.getId());
        }

        // create route and sample departures
        TransitRoute newRoute = sf.createTransitRoute(match.transitRoute.getId(), match.transitRoute.getRoute(),
                match.transitRoute.getStops(), match.transitRoute.getTransportMode());

        // sample departure times and add departures
        List<Double> departureTimes = samplingStrategy.sampleDepartures(match.trainVolume.getAmount(), random);
        int i = 1;
        for (double departureTime : departureTimes) {
            Id<Vehicle> vehicleId = Id.create("train_" + match.vehicleType.getId() + "_" + i, Vehicle.class);
            Vehicle vehicle = vf.createVehicle(vehicleId, match.vehicleType);
            vehicles.addVehicle(vehicle);

            Id<Departure> departureId = Id.create(match.transitRoute.getId() + "_dep_" + i, Departure.class);
            Departure departure = sf.createDeparture(departureId, departureTime);
            departure.setVehicleId(vehicleId);

            newRoute.addDeparture(departure);
            i++;
        }

        newLine.addRoute(newRoute);
    }

    /**
     * Finds a transit route in the template schedule that matches the product type and OD pair.
     */
    private Optional<Match> findMatchingRoute(TrainVolume trainVolume) {
        final TransitStopFacility fromStop = getTransitStopFacility(trainVolume.getFromStop());
        final TransitStopFacility toStop = getTransitStopFacility(trainVolume.getToStop());
        final ProductType productType = trainVolume.getProduct();

        for (TransitLine transitLine : scenario.getTransitSchedule().getTransitLines().values()) {
            for (TransitRoute transitRoute : transitLine.getRoutes().values()) {
                Id<VehicleType> vehicleTypeId = routeVehicleType.get(transitRoute.getId());

                // product not matching
                if (!productType.name().equals(vehicleTypeId.toString())) {
                    continue;
                }
                // first stop not matching
                if (!transitRoute.getStops().getFirst().getStopFacility().getId().equals(fromStop.getId())) {
                    continue;
                }
                // last stop not matching
                if (!transitRoute.getStops().getLast().getStopFacility().getId().equals(toStop.getId())) {
                    continue;
                }

                log.debug("Match {} ({} -> {}) for product type: {} == vehicle type: {}", transitRoute.getId(),
                        fromStop.getId(), toStop.getId(), productType, vehicleTypeId);
                VehicleType vehicleType = scenario.getTransitVehicles().getVehicleTypes().get(vehicleTypeId);

                return Optional.of(new Match(transitLine, transitRoute, vehicleType, trainVolume));
            }
        }

        return Optional.empty();
    }

    private TransitStopFacility getTransitStopFacility(String id) {
        TransitStopFacility transitStopFacility = scenario.getTransitSchedule()
                .getFacilities()
                .get(Id.create(id, TransitStopFacility.class));

        if (transitStopFacility == null) {
            throw new IllegalStateException("No transit stop facility found for operational plan id " + id);
        }

        return transitStopFacility;
    }

    private record Match(TransitLine transitLine, TransitRoute transitRoute, VehicleType vehicleType,
                         TrainVolume trainVolume) {
    }

    public record Sample(TransitSchedule schedule, Vehicles vehicles) {
    }

}