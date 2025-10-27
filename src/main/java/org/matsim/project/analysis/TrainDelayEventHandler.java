package org.matsim.project.analysis;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.api.core.v01.events.handler.TransitDriverStartsEventHandler;
import org.matsim.core.api.experimental.events.VehicleArrivesAtFacilityEvent;
import org.matsim.core.api.experimental.events.VehicleDepartsAtFacilityEvent;
import org.matsim.core.api.experimental.events.handler.VehicleArrivesAtFacilityEventHandler;
import org.matsim.core.api.experimental.events.handler.VehicleDepartsAtFacilityEventHandler;
import org.matsim.core.events.handler.EventHandler;
import org.matsim.pt.transitSchedule.api.*;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.Vehicles;

import java.util.*;

/**
 * Event handler to capture train arrival and departure events at transit stop facilities.
 * <p>
 * It compares the actual event times with the planned times from the input transit schedule
 * to calculate delays. It also tracks the number of trains that start and finish their journey.
 */
@Log4j2
@Getter
public class TrainDelayEventHandler implements VehicleArrivesAtFacilityEventHandler, VehicleDepartsAtFacilityEventHandler, TransitDriverStartsEventHandler, EventHandler {

    private final Map<Id<TransitRoute>, TransitRoute> routes;
    private final Vehicles vehicles;

    // data collection during event processing
    private final Map<Id<Vehicle>, VehicleState> vehicleStates = new HashMap<>();
    private final Map<Id<Departure>, Map<Id<TransitStopFacility>, StopEventData>> stopEventDataMap = new LinkedHashMap<>();
    private final Set<Id<Vehicle>> departedTrains = new HashSet<>();
    private final Set<Id<Vehicle>> arrivedTrains = new HashSet<>();

    public TrainDelayEventHandler(TransitSchedule plannedSchedule, Vehicles vehicles) {
        this.vehicles = vehicles;
        this.routes = new HashMap<>();

        for (TransitLine line : plannedSchedule.getTransitLines().values()) {
            this.routes.putAll(line.getRoutes());
        }
    }

    @Override
    public void handleEvent(TransitDriverStartsEvent event) {
        Id<Vehicle> vehicleId = event.getVehicleId();
        Vehicle vehicle = vehicles.getVehicles().get(vehicleId);
        if (vehicle == null) {
            throw new IllegalStateException(
                    "Vehicle with id " + vehicleId + " from TransitDriverStartsEvent not found in vehicles file.");
        }

        TransitRoute route = this.routes.get(event.getTransitRouteId());
        if (route == null) {
            throw new IllegalStateException(
                    "Route with id " + event.getTransitRouteId() + " from TransitDriverStartsEvent not found in reference schedule.");
        }

        Departure departure = route.getDepartures().get(event.getDepartureId());
        if (departure == null) {
            throw new IllegalStateException(
                    "Departure " + event.getDepartureId() + " not found in route " + route.getId());
        }

        vehicleStates.put(vehicleId,
                new VehicleState(vehicleId, event.getDepartureId(), event.getTransitLineId(), event.getTransitRouteId(),
                        vehicle.getType().getId(), departure.getDepartureTime()));
        departedTrains.add(vehicleId);
    }

    @Override
    public void handleEvent(VehicleArrivesAtFacilityEvent event) {
        VehicleState state = vehicleStates.get(event.getVehicleId());
        if (state != null) {
            getStopData(state, event.getFacilityId()).ifPresent(data -> {
                data.actualArrival = event.getTime();

                // if this is the last stop of the route, the train has arrived
                if (data.stopSequence == data.totalStopsInRoute - 1) {
                    arrivedTrains.add(event.getVehicleId());
                }

            });
        } else {
            // this can happen for non-transit vehicles
            log.warn(
                    "Vehicle {} arrived at stop {}, but has no associated transit driver state. It might not be a transit vehicle.",
                    event.getVehicleId(), event.getFacilityId());
        }
    }

    @Override
    public void handleEvent(VehicleDepartsAtFacilityEvent event) {
        VehicleState state = vehicleStates.get(event.getVehicleId());
        if (state != null) {
            getStopData(state, event.getFacilityId()).ifPresent(data -> data.actualDeparture = event.getTime());
        } else {
            // this can happen for non-transit vehicles
            log.warn(
                    "Vehicle {} departed from stop {}, but has no associated transit driver state. It might not be a transit vehicle.",
                    event.getVehicleId(), event.getFacilityId());
        }
    }

    /**
     * Returns the collected data as sorted list of immutable records.
     */
    public List<TrainDelayAnalysis.DetailedStopInfo> getStopEvents() {
        List<TrainDelayAnalysis.DetailedStopInfo> result = new ArrayList<>();

        // iterate through all collected departures and their associated stop events
        stopEventDataMap.forEach((departureId, stopMap) -> {
            // find the corresponding vehicle state that contains metadata for this departure
            vehicleStates.values().stream().filter(s -> s.departureId.equals(departureId)).findFirst()
                    // if found, process all stop events for that departure
                    .ifPresent(state -> stopMap.values().stream()
                            // sort stops by their sequence number (0, 1, 2, ...)
                            .sorted(Comparator.comparingInt(d -> d.stopSequence))
                            // convert to immutable record
                            .map(data -> data.toDetailedStopInfo(state)).forEach(result::add));
        });

        return result;
    }

    /**
     * Retrieves or creates a data container for a specific stop event.
     *
     * @param state      The current state of the vehicle (containing route info).
     * @param facilityId The ID of the stop facility where the event occurred.
     * @return An Optional containing the mutable StopEventData for this event.
     */
    private Optional<StopEventData> getStopData(VehicleState state, Id<TransitStopFacility> facilityId) {
        TransitRoute route = this.routes.get(state.routeId());

        if (route == null) {
            // vehicle is on a route not in the reference schedule
            return Optional.empty();
        }

        // iterate through the planned stops of the vehicle's route to find a match
        for (int i = 0; i < route.getStops().size(); i++) {
            TransitRouteStop stop = route.getStops().get(i);

            // check if the event's facility ID matches the planned stop's facility ID
            if (stop.getStopFacility().getId().equals(facilityId)) {
                // get or create the map of stops for this specific departure
                Map<Id<TransitStopFacility>, StopEventData> stopDataMapForDeparture = stopEventDataMap.computeIfAbsent(
                        state.departureId(), k -> new LinkedHashMap<>());

                // get or create the data object for this specific stop event
                int stopIndex = i;
                StopEventData stopEventData = stopDataMapForDeparture.computeIfAbsent(facilityId,
                        k -> new StopEventData(stopIndex, route.getStops().size(), stop, state.departureTime()));

                return Optional.of(stopEventData);
            }
        }

        // the facility is not part of this vehicle's planned route
        return Optional.empty();
    }

    @Override
    public void reset(int iteration) {
        vehicleStates.clear();
        stopEventDataMap.clear();
        departedTrains.clear();
        arrivedTrains.clear();
    }

    private record VehicleState(Id<Vehicle> vehicleId, Id<Departure> departureId, Id<TransitLine> lineId,
                                Id<TransitRoute> routeId, Id<VehicleType> vehicleTypeId, double departureTime) {
    }

    private static class StopEventData {
        final int stopSequence;
        final int totalStopsInRoute;
        final Id<TransitStopFacility> stopId;
        final double plannedArrival;
        final double plannedDeparture;
        double actualArrival = Double.NaN;
        double actualDeparture = Double.NaN;

        StopEventData(int sequence, int totalStops, TransitRouteStop stop, double departureTime) {
            this.stopSequence = sequence;
            this.totalStopsInRoute = totalStops;
            this.stopId = stop.getStopFacility().getId();

            // calculate absolute planned times by adding the departure time to the offset
            this.plannedArrival = departureTime + stop.getArrivalOffset().seconds();
            this.plannedDeparture = departureTime + stop.getDepartureOffset().seconds();
        }

        TrainDelayAnalysis.DetailedStopInfo toDetailedStopInfo(VehicleState state) {
            double pArr = plannedArrival;
            double aArr = actualArrival;
            double pDep = plannedDeparture;
            double aDep = actualDeparture;

            // first stop has no planned or actual arrival event, but its departure time is its planned arrival
            if (stopSequence == 0) {
                pArr = aArr = state.departureTime();
            }

            // last stop has no planned or actual departure event
            if (stopSequence == totalStopsInRoute - 1) {
                pDep = aDep = Double.NaN;
            }

            double arrivalDelay = (!Double.isNaN(aArr) && !Double.isNaN(pArr)) ? Math.max(0, aArr - pArr) : 0.0;
            double departureDelay = (!Double.isNaN(aDep) && !Double.isNaN(pDep)) ? Math.max(0, aDep - pDep) : 0.0;

            // use the actual vehicle ID stored in the state, no reconstruction needed
            return new TrainDelayAnalysis.DetailedStopInfo(state.vehicleId(), state.departureId(), state.routeId(),
                    state.vehicleTypeId(), stopSequence, stopId, pArr, aArr, arrivalDelay, pDep, aDep, departureDelay);
        }
    }
}