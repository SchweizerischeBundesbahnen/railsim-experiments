package org.matsim.project.analysis;

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
 * An event handler to capture train arrival and departure events at transit facilities.
 * It compares the actual event times with the planned times from a reference transit schedule
 * to calculate delays. This handler is designed to be used in a post-simulation analysis step.
 */
public class TrainDelayEventHandler implements VehicleArrivesAtFacilityEventHandler, VehicleDepartsAtFacilityEventHandler, TransitDriverStartsEventHandler, EventHandler {

    private final Map<Id<TransitRoute>, TransitRoute> routes;
    private final Vehicles vehicles;

    // Data collected during event processing
    private final Map<Id<Vehicle>, VehicleState> vehicleStates = new HashMap<>();
    private final Map<Id<Departure>, Map<Id<TransitStopFacility>, StopEventData>> stopEventDataMap = new LinkedHashMap<>();

    public TrainDelayEventHandler(TransitSchedule plannedSchedule, Vehicles vehicles) {
        this.vehicles = vehicles;

        // Pre-process the schedule to create a fast lookup map for routes.
        this.routes = new HashMap<>();
        for (TransitLine line : plannedSchedule.getTransitLines().values()) {
            this.routes.putAll(line.getRoutes());
        }
    }

    @Override
    public void handleEvent(TransitDriverStartsEvent event) {
        // This event reliably links a vehicle to its full transit context.
        Id<Vehicle> vehicleId = event.getVehicleId();
        Vehicle vehicle = vehicles.getVehicles().get(vehicleId);
        if (vehicle != null) {
            vehicleStates.put(vehicleId,
                    new VehicleState(event.getDepartureId(), event.getTransitLineId(), event.getTransitRouteId(),
                            vehicle.getType().getId()));
        }
    }

    @Override
    public void handleEvent(VehicleArrivesAtFacilityEvent event) {
        VehicleState state = vehicleStates.get(event.getVehicleId());
        if (state != null) {
            getStopData(state, event.getFacilityId()).ifPresent(data -> data.actualArrival = event.getTime());
        }
    }

    @Override
    public void handleEvent(VehicleDepartsAtFacilityEvent event) {
        VehicleState state = vehicleStates.get(event.getVehicleId());
        if (state != null) {
            getStopData(state, event.getFacilityId()).ifPresent(data -> data.actualDeparture = event.getTime());
        }
    }

    /**
     * Finalizes the collected data into a clean, sorted list of immutable records.
     * This should be called after all events have been processed.
     *
     * @return A list of {@link TrainDelayAnalysis.StopDelayInfo} records.
     */
    public List<TrainDelayAnalysis.StopDelayInfo> getStopEvents() {
        List<TrainDelayAnalysis.StopDelayInfo> result = new ArrayList<>();
        stopEventDataMap.forEach((departureId, stopMap) -> {
            // Find the state associated with this departure to get its metadata
            Optional<VehicleState> stateOpt = vehicleStates.values()
                    .stream()
                    .filter(s -> s.departureId().equals(departureId))
                    .findFirst();

            // Sort the stops by their sequence in the route and convert to final record format
            stateOpt.ifPresent(state -> stopMap.values()
                    .stream()
                    .sorted(Comparator.comparingInt(d -> d.stopSequence))
                    .map(data -> data.toStopDelayInfo(state))
                    .forEach(result::add));
        });
        return result;
    }

    private Optional<StopEventData> getStopData(VehicleState state, Id<TransitStopFacility> facilityId) {
        TransitRoute route = this.routes.get(state.routeId());
        if (route == null) return Optional.empty();

        // Find the specific stop in the vehicle's planned route that corresponds to the event's facility.
        for (int i = 0; i < route.getStops().size(); i++) {
            TransitRouteStop stop = route.getStops().get(i);
            if (stop.getStopFacility().getId().equals(facilityId)) {
                Map<Id<TransitStopFacility>, StopEventData> stopData = stopEventDataMap.computeIfAbsent(
                        state.departureId(), k -> new LinkedHashMap<>());
                final int stopIndex = i;
                return Optional.of(stopData.computeIfAbsent(facilityId, k -> new StopEventData(stopIndex, stop)));
            }
        }
        return Optional.empty(); // Stop not part of this vehicle's planned route
    }

    @Override
    public void reset(int iteration) {
        vehicleStates.clear();
        stopEventDataMap.clear();
    }

    /**
     * Represents the planned route information for a vehicle currently in the simulation.
     */
    private record VehicleState(Id<Departure> departureId, Id<TransitLine> lineId, Id<TransitRoute> routeId,
                                Id<VehicleType> vehicleTypeId) {
    }

    /**
     * Helper class to store mutable event data during event processing.
     */
    private static class StopEventData {
        final int stopSequence;
        final Id<TransitStopFacility> stopId;
        final double plannedArrival;
        final double plannedDeparture;
        double actualArrival = Double.NaN;
        double actualDeparture = Double.NaN;

        StopEventData(int sequence, TransitRouteStop stop) {
            this.stopSequence = sequence;
            this.stopId = stop.getStopFacility().getId();
            this.plannedArrival = stop.getArrivalOffset().seconds();
            this.plannedDeparture = stop.getDepartureOffset().seconds();
        }

        TrainDelayAnalysis.StopDelayInfo toStopDelayInfo(VehicleState state) {
            // Heuristic to extract subvariant ID from the departure ID string, e.g., "uc1_bb2_km1.1_sample_0_dep_1"
            String[] departureParts = state.departureId().toString().split("_");
            String subVariant = (departureParts.length > 2) ? departureParts[2] : "unknown";

            String train = state.lineId().toString();

            double arrivalDelay = (Double.isNaN(actualArrival) || Double.isNaN(plannedArrival)) ? 0 : Math.max(0,
                    actualArrival - plannedArrival);
            double departureDelay = (Double.isNaN(actualDeparture) || Double.isNaN(plannedDeparture)) ? 0 : Math.max(0,
                    actualDeparture - plannedDeparture);

            // Handle the first stop, where arrival is often undefined (NaN).
            if (stopSequence == 0) {
                actualArrival = actualDeparture; // First stop's "arrival" is its departure
                arrivalDelay = 0;
            }
            // Handle the last stop, where departure is often undefined (NaN).
            if (Double.isNaN(actualDeparture)) {
                actualDeparture = actualArrival;
                departureDelay = 0;
            }

            return new TrainDelayAnalysis.StopDelayInfo(subVariant, train, state.routeId().toString(),
                    state.vehicleTypeId().toString(), state.departureId().toString(), stopSequence, stopId.toString(),
                    plannedArrival, actualArrival, arrivalDelay, plannedDeparture, actualDeparture, departureDelay);
        }
    }
}