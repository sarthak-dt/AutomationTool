package com.stellar.platform.processing.map;

import java.util.Objects;

/**
 * Single derived GPS track consumed by both the map renderer and Segment Analysis.
 *
 * <p>The compact {@link MapRouteModel} is the ordered, validated sequence. Diagnostics explain
 * why raw latitude/longitude samples were dropped. Raw measurement series are never mutated.
 */
public final class ValidatedRoute {

    private final MapRouteModel model;
    private final RouteBuildDiagnostics diagnostics;
    private final long[] longitudeTimestampsNanos;

    public ValidatedRoute(MapRouteModel model, RouteBuildDiagnostics diagnostics) {
        this(model, diagnostics, null);
    }

    public ValidatedRoute(MapRouteModel model, RouteBuildDiagnostics diagnostics, long[] longitudeTimestampsNanos) {
        this.model = model == null ? MapRouteModel.empty() : model;
        this.diagnostics = diagnostics == null ? RouteBuildDiagnostics.empty() : diagnostics;
        if (longitudeTimestampsNanos == null) {
            this.longitudeTimestampsNanos = new long[this.model.size()];
            for (int i = 0; i < this.model.size(); i++) {
                this.longitudeTimestampsNanos[i] = this.model.timestampNanos(i);
            }
        } else {
            if (longitudeTimestampsNanos.length != this.model.size()) {
                throw new IllegalArgumentException("longitude timestamps must match the validated route length");
            }
            this.longitudeTimestampsNanos = longitudeTimestampsNanos;
        }
    }

    public static ValidatedRoute empty() {
        return new ValidatedRoute(MapRouteModel.empty(), RouteBuildDiagnostics.empty());
    }

    public MapRouteModel model() {
        return model;
    }

    public RouteBuildDiagnostics diagnostics() {
        return diagnostics;
    }

    public boolean isEmpty() {
        return model.isEmpty();
    }

    public int size() {
        return model.size();
    }

    public RoutePoint point(int index) {
        Objects.checkIndex(index, model.size());
        return new RoutePoint(
                model.timestampNanos(index),
                model.timestampNanos(index),
                longitudeTimestampsNanos[index],
                model.latitude(index),
                model.longitude(index),
                model.altitude(index));
    }
}
