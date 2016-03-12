package com.conveyal.gtfs.model;

import com.conveyal.gtfs.GTFSFeed;
import com.google.common.base.Joiner;
import com.vividsolutions.jts.geom.LineString;

import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by landon on 2/5/16.
 */
public class Pattern {
    public String pattern_id;
    public int[] segmentIndex;
    public double[] segmentFraction;
    public List<String> orderedStops;
    public List<String> associatedTrips;
    public Set<Route> associatedRoutes;
    public LineString geometry;
    public String name;
    public Joiner joiner = Joiner.on("-").skipNulls();
    public String feed_id;

    // TODO: Should a Pattern be generated for a single trip or a set of trips that share the same ordered stop list?
    public Pattern (GTFSFeed feed, Map.Entry<List<String>, List<String>> tripsForStopPattern){


        this.feed_id = feed.feedId;

        this.pattern_id = UUID.randomUUID().toString();

        // Assign ordered stops to key of tripsForStopPattern
        this.orderedStops = tripsForStopPattern.getKey();

        // Assign associated trips to value of tripsForStopPattern
        this.associatedTrips = tripsForStopPattern.getValue();
        this.associatedRoutes = new HashSet<>();
        this.associatedTrips.forEach((id) -> {
            this.associatedRoutes.add(feed.trips.get(id).route);
        });
//        for (String tripId : this.associatedTrips){
//            this.associatedRoutes.add(feed.trips.get(tripId).route);
//        }
        // Get geometry for first trip in list of associated trips
        String trip_id = associatedTrips.get(0);
        Trip trip;

        trip = feed.trips.get(trip_id);
        this.geometry = feed.getTripGeometry(trip.trip_id);

        if (trip.trip_headsign != null){
            name = trip.trip_headsign;
        }
        else if (trip.trip_short_name != null) {
            name = trip.trip_short_name;
        }
        else if (trip.direction_id >= 0){
            name = String.valueOf(trip.direction_id);
        }
        else{
            name = joiner.join(orderedStops);
        }

        // TODO: Implement segmentIndex using JTS to segment out LineString by stops.

        // TODO: Implement segmentFraction using JTS to segment out LineString by stops.

    }
}
