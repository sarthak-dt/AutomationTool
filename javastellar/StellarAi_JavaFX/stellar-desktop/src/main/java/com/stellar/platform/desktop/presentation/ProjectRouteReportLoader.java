package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.catalog.ProjectMergedSignalStore;
import com.stellar.platform.processing.graph.GraphQueryProvider;
import com.stellar.platform.processing.graph.GraphSeriesQuery;
import com.stellar.platform.processing.map.GpsDetectionResult;
import com.stellar.platform.processing.map.GpsMappingPolicy;
import com.stellar.platform.processing.map.GpsMappingScope;
import com.stellar.platform.processing.map.GpsSemanticResolver;
import com.stellar.platform.processing.map.GpsSignalDiscoveryService;
import com.stellar.platform.processing.map.GpsSignalMapping;
import com.stellar.platform.processing.map.GpsSignalRole;
import com.stellar.platform.processing.map.GpsSignalRoleResolver;
import com.stellar.platform.processing.map.MapRoute;
import com.stellar.platform.processing.map.MapRouteBuilder;
import com.stellar.platform.processing.map.MapRouteModel;
import com.stellar.platform.processing.map.ProjectRoute;
import com.stellar.platform.processing.map.ProjectRouteAssembler;
import com.stellar.platform.processing.map.time.RecordingClockReader;
import com.stellar.platform.processing.map.time.RouteChronology;
import com.stellar.platform.analysis.source.AnalysisCancelledException;
import com.stellar.platform.parser.spi.CancellationToken;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Builds the report route from processed GPS signals. Does not open Map Route and does not
 * read measurement files.
 */
public final class ProjectRouteReportLoader {

    private static final Logger LOG = Logger.getLogger(ProjectRouteReportLoader.class.getName());

    private final GpsSignalDiscoveryService discovery = new GpsSignalDiscoveryService();

    public RouteMapReportContext load(DesktopAppService app) {
        return load(app, null);
    }

    public RouteMapReportContext load(DesktopAppService app, CancellationToken token) {
        return load(app, null, false, token);
    }

    public RouteMapReportContext load(
            DesktopAppService app,
            com.stellar.platform.analysis.investigation.EngineeringInvestigation investigation,
            boolean persistedOnly,
            CancellationToken token
    ) {
        if (token != null && token.isCancellationRequested()) {
            throw new AnalysisCancelledException();
        }
        if (app == null || app.currentProjectId() == null || app.currentProjectId().isBlank()) {
            return null;
        }
        if (!(app.signalsModel().catalogStore() instanceof GraphQueryProvider graphs)) {
            return null;
        }
        AnalysisMeasurementContext measurement = app.activeAnalysisMeasurement();
        String liveId = measurement == null ? "" : measurement.sourceId();
        String liveName = measurement == null ? "" : measurement.fileName();
        String projectId = investigation != null && investigation.projectId() != null
                && !investigation.projectId().isBlank()
                ? investigation.projectId()
                : app.currentProjectId();
        ReportMapScope.Decision decision = ReportMapScope.decide(
                investigation,
                ReportMapScope.durableScope(app.metadataStore(), projectId),
                app.isProjectWideAnalysis(), liveId, liveName, persistedOnly);
        boolean projectWide = decision.projectWide();
        List<SignalCatalogEntry> catalog = catalogFor(app, true);
        if (catalog.isEmpty()) {
            catalog = catalogFor(app, false);
        }
        if (catalog.isEmpty()) {
            return null;
        }
        if (!projectWide && decision.sourceId().isBlank()) {
            return null;
        }
        int measurementCount = projectWide ? app.workspace().measurementFiles().size() : 1;
        String scopeLabel = projectWide ? "All Measurements" : labelFor(app, decision);
        String onlyId = projectWide ? "" : decision.sourceId();
        return load(projectWide, onlyId, scopeLabel, measurementCount, catalog, graphs.graphQuery(),
                app.loadAllGpsSignalMappings(), app.loadRouteConfigDefaults(), token, measurementPaths(app));
    }

    private static String labelFor(DesktopAppService app, ReportMapScope.Decision decision) {
        String label = decision.fileName();
        if (label == null || label.isBlank() || label.equals(decision.sourceId())) {
            label = app.sourceDisplayName(decision.sourceId());
        }
        return label == null || label.isBlank() ? decision.sourceId() : label;
    }

    public RouteMapReportContext load(
            boolean projectWide,
            String onlyMeasurementId,
            String scopeLabel,
            int measurementCount,
            List<SignalCatalogEntry> catalog,
            GraphSeriesQuery query,
            List<GpsSignalMapping> mappings,
            List<GpsSignalMapping> defaults
    ) {
        return load(projectWide, onlyMeasurementId, scopeLabel, measurementCount, catalog, query,
                mappings, defaults, null, Map.of());
    }

    public RouteMapReportContext load(
            boolean projectWide,
            String onlyMeasurementId,
            String scopeLabel,
            int measurementCount,
            List<SignalCatalogEntry> catalog,
            GraphSeriesQuery query,
            List<GpsSignalMapping> mappings,
            List<GpsSignalMapping> defaults,
            CancellationToken token
    ) {
        return load(projectWide, onlyMeasurementId, scopeLabel, measurementCount, catalog, query,
                mappings, defaults, token, Map.of());
    }

    public RouteMapReportContext load(
            boolean projectWide,
            String onlyMeasurementId,
            String scopeLabel,
            int measurementCount,
            List<SignalCatalogEntry> catalog,
            GraphSeriesQuery query,
            List<GpsSignalMapping> mappings,
            List<GpsSignalMapping> defaults,
            CancellationToken token,
            Map<String, Path> measurementPaths
    ) {
        if (token != null && token.isCancellationRequested()) {
            throw new AnalysisCancelledException();
        }
        if (query == null || catalog == null || catalog.isEmpty()) {
            return null;
        }
        List<MeasurementCatalogScope.Ref> refs = MeasurementCatalogScope.measurements(catalog);
        if (!projectWide) {
            refs = refs.stream()
                    .filter(ref -> same(ref.identity(), onlyMeasurementId) || same(ref.displayName(), scopeLabel))
                    .toList();
        }
        if (refs.isEmpty()) {
            return null;
        }
        List<GpsSignalMapping> projectMappings = mappings == null ? List.of() : mappings;
        List<GpsSignalMapping> fallback = defaults == null ? List.of() : defaults;
        List<ProjectRouteAssembler.PreparedMeasurement> prepared = new ArrayList<>();
        for (MeasurementCatalogScope.Ref ref : refs) {
            if (token != null && token.isCancellationRequested()) {
                throw new AnalysisCancelledException();
            }
            prepared.add(prepareOne(ref, catalog, query, projectMappings, fallback));
        }
        int advertised = projectWide ? Math.max(measurementCount, refs.size()) : 1;
        RouteChronology.Result chronology = chronology(projectWide, prepared, measurementPaths);
        ProjectRoute route = ProjectRouteAssembler.assemble(chronology.measurements(), advertised);
        if (route.isEmpty()) {
            return null;
        }
        LOG.info(() -> "Report route prepared measurements=" + route.measurementCount()
                + " gpsCapable=" + route.gpsCapableCount()
                + " segments=" + route.routeSegmentCount()
                + " samples=" + route.gpsSampleCount()
                + " projectWide=" + projectWide);
        return new RouteMapReportContext(
                projectWide, scopeLabel == null ? "" : scopeLabel, route, chronology.status());
    }

    private static RouteChronology.Result chronology(
            boolean projectWide,
            List<ProjectRouteAssembler.PreparedMeasurement> prepared,
            Map<String, Path> measurementPaths
    ) {
        if (!projectWide) {
            return new RouteChronology.Result(prepared, "", 0, 0);
        }
        List<String> ids = new ArrayList<>();
        for (ProjectRouteAssembler.PreparedMeasurement row : prepared) {
            ids.add(row.measurementId());
        }
        return RouteChronology.order(prepared, RecordingClockReader.readAll(ids, measurementPaths));
    }

    private static Map<String, Path> measurementPaths(DesktopAppService app) {
        Map<String, Path> paths = new LinkedHashMap<>();
        if (app == null || app.workspace() == null) {
            return paths;
        }
        for (var file : app.workspace().measurementFiles()) {
            if (file != null && file.id() != null && file.path() != null) {
                paths.put(file.id(), file.path());
            }
        }
        return paths;
    }

    private ProjectRouteAssembler.PreparedMeasurement prepareOne(
            MeasurementCatalogScope.Ref ref,
            List<SignalCatalogEntry> catalog,
            GraphSeriesQuery query,
            List<GpsSignalMapping> mappings,
            List<GpsSignalMapping> defaults
    ) {
        try {
            List<SignalCatalogEntry> scoped = GpsMappingScope.catalogFor(ref.identity(), catalog);
            if (scoped.isEmpty()) {
                scoped = MeasurementCatalogScope.filter(catalog, ref.identity(), ref.displayName());
            }
            Map<GpsSignalRole, SignalCatalogKey> overrides = overrides(ref, scoped, mappings, defaults);
            GpsDetectionResult detected = discovery.discover(scoped, "report|" + ref.identity(), overrides);
            Map<GpsSignalRole, SignalCatalogKey> roles = new EnumMap<>(GpsSignalRole.class);
            roles.putAll(GpsSignalDiscoveryService.toRoleMap(detected));
            roles.putAll(overrides);
            MapRoute built = MapRoute.emptyRoute();
            if (GpsSignalRoleResolver.hasRoute(roles)) {
                long[] window = window(scoped);
                List<SignalSamplePoint> latitude = read(query, roles.get(GpsSignalRole.LATITUDE), window, ref.identity());
                List<SignalSamplePoint> longitude = read(query, roles.get(GpsSignalRole.LONGITUDE), window, ref.identity());
                List<SignalSamplePoint> altitude = read(query, roles.get(GpsSignalRole.ALTITUDE), window, ref.identity());
                MapRouteModel model = MapRouteBuilder.validateAndBuild(latitude, longitude, altitude).model();
                built = new MapRoute(model);
            }
            return new ProjectRouteAssembler.PreparedMeasurement(ref.identity(), ref.displayName(), built);
        } catch (RuntimeException ex) {
            LOG.warning("Report route skipped one measurement: "
                    + (ex.getMessage() == null ? ex.toString() : ex.getMessage()));
            return new ProjectRouteAssembler.PreparedMeasurement(ref.identity(), ref.displayName(), MapRoute.emptyRoute());
        }
    }

    private static Map<GpsSignalRole, SignalCatalogKey> overrides(
            MeasurementCatalogScope.Ref ref,
            List<SignalCatalogEntry> scoped,
            List<GpsSignalMapping> mappings,
            List<GpsSignalMapping> defaults
    ) {
        GpsMappingPolicy.Resolution policy = GpsMappingPolicy.resolve(
                ref.identity(),
                GpsSemanticResolver.schemaFingerprint(scoped),
                scoped,
                GpsMappingScope.mappingsForMeasurement(ref.identity(), ref.displayName(), mappings),
                defaults);
        return policy.hasRouteOverrides() ? policy.overrides() : Map.of();
    }

    private static List<SignalSamplePoint> read(
            GraphSeriesQuery query, SignalCatalogKey key, long[] window, String sourceId) {
        if (key == null) {
            return List.of();
        }
        List<SignalSamplePoint> samples = query.queryRange(key, window[0], window[1], sourceId);
        return samples == null ? List.of() : samples;
    }

    private static long[] window(List<SignalCatalogEntry> scoped) {
        return GpsCatalogWindow.inclusive(scoped);
    }

    private static List<SignalCatalogEntry> catalogFor(DesktopAppService app, boolean projectWide) {
        if (projectWide && app.signalsModel().catalogStore() instanceof ProjectMergedSignalStore merged) {
            return merged.physicalCatalogAll();
        }
        if (projectWide && app.signalsModel().catalog() != null) {
            return app.signalsModel().catalog().entries();
        }
        return app.engineeringCatalog();
    }

    private static boolean same(String left, String right) {
        return left != null && right != null && !right.isBlank() && left.equalsIgnoreCase(right);
    }
}
