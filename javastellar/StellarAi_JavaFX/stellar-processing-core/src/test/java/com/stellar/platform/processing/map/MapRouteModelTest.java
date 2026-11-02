package com.stellar.platform.processing.map;

import com.stellar.platform.domain.catalog.SignalSamplePoint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapRouteModelTest {

    private static final long SECOND = 1_000_000_000L;

    private static MapRouteModel straightRoute(int count) {
        List<SignalSamplePoint> latitude = new ArrayList<>(count);
        List<SignalSamplePoint> longitude = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            latitude.add(new SignalSamplePoint(i * SECOND, 18.5, null, null));
            longitude.add(new SignalSamplePoint(i * SECOND, 73.7 + i * 0.0001, null, null));
        }
        return MapRouteBuilder.build(latitude, longitude, null);
    }

    @Test
    void nearestIndexByTimeUsesBinarySearchSemantics() {
        MapRouteModel route = straightRoute(11);

        assertEquals(0, route.nearestIndexByTime(Long.MIN_VALUE / 4));
        assertEquals(0, route.nearestIndexByTime(0));
        assertEquals(5, route.nearestIndexByTime(5 * SECOND));
        assertEquals(5, route.nearestIndexByTime(5 * SECOND + SECOND / 4));
        assertEquals(6, route.nearestIndexByTime(5 * SECOND + 3 * SECOND / 4));
        assertEquals(10, route.nearestIndexByTime(Long.MAX_VALUE / 4));
    }

    @Test
    void boundaryLookupsAreConsistent() {
        MapRouteModel route = straightRoute(5);

        assertEquals(2, route.indexAtOrAfter(2 * SECOND));
        assertEquals(3, route.indexAtOrAfter(2 * SECOND + 1));
        assertEquals(2, route.indexAtOrBefore(2 * SECOND));
        assertEquals(2, route.indexAtOrBefore(2 * SECOND + 1));
        assertEquals(-1, route.indexAtOrBefore(-SECOND));
        assertEquals(5, route.indexAtOrAfter(99 * SECOND));
    }

    @Test
    void cumulativeDistanceMakesRangeDistanceConstantTime() {
        MapRouteModel route = straightRoute(101);

        double whole = route.distanceMetersBetween(0, 100);
        double firstHalf = route.distanceMetersBetween(0, 50);
        double secondHalf = route.distanceMetersBetween(50, 100);

        assertEquals(whole, firstHalf + secondHalf, 1e-6);
        assertEquals(whole, route.totalDistanceMeters(), 1e-6);
    }

    @Test
    void distanceIsOrderIndependent() {
        MapRouteModel route = straightRoute(20);

        assertEquals(route.distanceMetersBetween(3, 17), route.distanceMetersBetween(17, 3), 1e-9);
    }

    @Test
    void emptyRouteIsSafeToQuery() {
        MapRouteModel route = MapRouteModel.empty();

        assertTrue(route.isEmpty());
        assertEquals(-1, route.nearestIndexByTime(1234));
        assertEquals(0, route.totalDistanceMeters(), 1e-9);
    }

    @Test
    void boundingBoxCoversAllSamples() {
        MapRouteModel route = straightRoute(10);

        assertTrue(route.minLongitude() <= route.longitude(0));
        assertTrue(route.maxLongitude() >= route.longitude(9));
        assertEquals(18.5, route.minLatitude(), 1e-9);
        assertEquals(18.5, route.maxLatitude(), 1e-9);
    }
}
