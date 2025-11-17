package org.matsim.project.analysis.headway;

import lombok.Builder;
import lombok.Value;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;

/**
 * A data class representing a single, comprehensive headway measurement between two trains.
 * This class is an immutable Data Transfer Object (DTO) created using Lombok.
 */
@Value
@Builder
public class HeadwayInfo {
    Id<Link> linkId;
    Id<Vehicle> followingVehicleId;
    Id<VehicleType> followingVehicleTypeId;
    double followingVehicleEnterTime;
    Id<Vehicle> previousVehicleId;
    Id<VehicleType> previousVehicleTypeId;
    double previousVehicleEnterTime;
    double previousVehicleLeaveTime;
    double headwayHeadToHead;
    double headwayTailToHead;
    double minimumHeadway;
    double violationHeadToHead;
    double violationTailToHead;
}