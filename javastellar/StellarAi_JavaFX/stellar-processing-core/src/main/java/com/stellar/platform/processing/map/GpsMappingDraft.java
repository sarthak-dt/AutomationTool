package com.stellar.platform.processing.map;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Editable GPS role mapping for one measurement source.
 *
 * <p>All resolution, validation and status reasoning lives here so the Configuration screen only
 * has to render state and forward user gestures. The existing priority is preserved exactly:
 * user-confirmed mapping for this source, then a user-confirmed mapping from a compatible schema
 * fingerprint, then reliable automatic detection, then ask the user.
 *
 * <p>The draft never touches measurement files. It works from the already-processed signal catalog.
 */
public final class GpsMappingDraft {

    /** Roles an engineer may map explicitly. Latitude and Longitude are the only required ones. */
    public static final List<GpsSignalRole> EDITABLE_ROLES = List.of(
            GpsSignalRole.LATITUDE,
            GpsSignalRole.LONGITUDE,
            GpsSignalRole.ALTITUDE,
            GpsSignalRole.GPS_SPEED,
            GpsSignalRole.COURSE);

    /** Without both of these there is no route. Everything else is an enrichment. */
    public static final List<GpsSignalRole> REQUIRED_ROLES =
            List.of(GpsSignalRole.LATITUDE, GpsSignalRole.LONGITUDE);

    /** Hidden behind "Advanced GPS signals" — a route never depends on them. */
    public static final List<GpsSignalRole> OPTIONAL_ROLES =
            List.of(GpsSignalRole.ALTITUDE, GpsSignalRole.GPS_SPEED, GpsSignalRole.COURSE);

    /**
     * What the engineer is told about the current mapping.
     *
     * <p>The wording is deliberately about the location rather than about signal mapping: for almost
     * every recording this state is reached without anyone configuring anything, and the screen
     * should read like a confirmation rather than an unfinished form.
     */
    public enum Status {
        NO_CATALOG("No processed signals"),
        MAPPING_REQUIRED("GPS setup required"),
        AUTO_DETECTED("Location detected"),
        USER_CONFIGURED("Location configured"),
        SCHEMA_REUSED("Reused from a compatible measurement"),
        PRODUCT_DEFAULT("Using the product default"),
        MAPPING_SIGNAL_MISSING("Mapped signal no longer exists");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Why one role currently holds the signal it holds. */
    public enum RoleOrigin {
        NOT_MAPPED("Not mapped"),
        MANUAL("Manual"),
        RESTORED("Restored"),
        AUTO_DETECTED("Auto detected"),
        SUGGESTED("Suggested"),
        LOW_CONFIDENCE("Low confidence");

        private final String label;

        RoleOrigin(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final String projectId;
    private final String sourceId;
    private final List<SignalCatalogEntry> catalog;
    private final Map<SignalCatalogKey, SignalCatalogEntry> byKey;
    private final String schemaFingerprint;
    private final GpsSemanticResolver resolver = new GpsSemanticResolver();

    private final EnumMap<GpsSignalRole, SignalCatalogKey> assigned = new EnumMap<>(GpsSignalRole.class);
    private final EnumMap<GpsSignalRole, RoleOrigin> roleOrigins = new EnumMap<>(GpsSignalRole.class);
    /**
     * Signal names the engineer typed instead of picking. Kept separately from {@link #assigned}
     * because a typed name may name a signal this measurement does not contain — which is exactly the
     * case a product configuration has to be able to express.
     */
    private final EnumMap<GpsSignalRole, String> manualNames = new EnumMap<>(GpsSignalRole.class);
    /**
     * Roles the engineer owns, as opposed to roles merely pre-filled by detection. Only these are
     * written back, so opening the screen can never turn a low-confidence automatic guess into
     * project data the user never agreed to.
     */
    private final EnumSet<GpsSignalRole> userOwned = EnumSet.noneOf(GpsSignalRole.class);
    private GpsMappingPolicy.Origin origin = GpsMappingPolicy.Origin.UNRESOLVED;
    private List<GpsSignalMapping> productDefaults = List.of();
    private boolean persistedMissing;
    private String persistedMissingDetail = "";
    private String droppedRolesDetail = "";
    /** Measurement that owns the mapping when this source only borrows it via schema reuse. */
    private String reusedFromSourceId = "";
    private boolean userEdited;
    /**
     * True while the draft intentionally represents pure auto detection. Saving then removes any
     * stored override instead of freezing detection into project data.
     */
    private boolean autoOnly;

    private GpsMappingDraft(String projectId, String sourceId, Collection<SignalCatalogEntry> catalog) {
        this.projectId = projectId == null ? "" : projectId;
        this.sourceId = sourceId == null ? "" : sourceId;
        List<SignalCatalogEntry> entries = new ArrayList<>();
        if (catalog != null) {
            for (SignalCatalogEntry entry : catalog) {
                if (entry != null) {
                    entries.add(entry);
                }
            }
        }
        this.catalog = List.copyOf(entries);
        Map<SignalCatalogKey, SignalCatalogEntry> index = new LinkedHashMap<>();
        for (SignalCatalogEntry entry : this.catalog) {
            index.putIfAbsent(entry.key(), entry);
        }
        this.byKey = Map.copyOf(index);
        this.schemaFingerprint = GpsSemanticResolver.schemaFingerprint(this.catalog);
    }

    /**
     * Builds the draft the user should see when GPS &amp; Map Configuration opens.
     *
     * @param persisted every stored mapping for the project, not only this source
     */
    public static GpsMappingDraft resolve(
            String projectId,
            String sourceId,
            Collection<SignalCatalogEntry> catalog,
            Collection<GpsSignalMapping> persisted
    ) {
        return resolve(projectId, sourceId, catalog, persisted, List.of());
    }

    /**
     * @param defaults product-wide configuration, inherited when the project configured nothing
     */
    public static GpsMappingDraft resolve(
            String projectId,
            String sourceId,
            Collection<SignalCatalogEntry> catalog,
            Collection<GpsSignalMapping> persisted,
            Collection<GpsSignalMapping> defaults
    ) {
        GpsMappingDraft draft = new GpsMappingDraft(projectId, sourceId, catalog);
        draft.productDefaults = defaults == null ? List.of() : List.copyOf(defaults);
        draft.applyResolution(persisted);
        return draft;
    }

    private void applyResolution(Collection<GpsSignalMapping> persisted) {
        assigned.clear();
        roleOrigins.clear();
        userOwned.clear();
        manualNames.clear();
        userEdited = false;
        droppedRolesDetail = "";
        reusedFromSourceId = "";
        GpsMappingPolicy.Resolution policy = GpsMappingPolicy.resolve(
                sourceId, schemaFingerprint, catalog, persisted, productDefaults);
        persistedMissing = policy.persistedMissing();
        persistedMissingDetail = policy.statusMessage();
        origin = policy.origin();
        if (origin == GpsMappingPolicy.Origin.SCHEMA_REUSED) {
            reusedFromSourceId = owningSource(persisted);
        }
        if (policy.hasRouteOverrides()) {
            for (GpsSignalRole role : EDITABLE_ROLES) {
                SignalCatalogKey key = policy.overrides().get(role);
                if (key != null) {
                    assigned.put(role, key);
                    roleOrigins.put(role, RoleOrigin.RESTORED);
                    userOwned.add(role);
                }
            }
            restoreManualNames(policy.appliedMappings());
            if (policy.optionalRolesDropped()) {
                droppedRolesDetail = policy.statusMessage();
            }
            // Roles the stored mapping does not name stay under automatic control, so pinning the
            // coordinates by hand never withdraws altitude, speed or course from the route.
            fillUnassignedFromDetection();
            if (origin == GpsMappingPolicy.Origin.PRODUCT_DEFAULT) {
                // Inheriting the product default is not a project save. Opening Configuration must
                // not freeze it into this project's database.
                userOwned.clear();
                autoOnly = true;
            } else {
                autoOnly = false;
            }
            return;
        }
        autoOnly = true;
        fillFromDetection();
        // A stored mapping that names an absent signal still has to be shown and correctable, so the
        // typed names survive even when nothing could be resolved against this catalog.
        restoreManualNames(policy.appliedMappings());
    }

    /** Keeps typed names visible in the editor, including names this measurement cannot resolve. */
    private void restoreManualNames(List<GpsSignalMapping> applied) {
        for (GpsSignalMapping mapping : applied) {
            if (mapping == null || !mapping.manual() || !EDITABLE_ROLES.contains(mapping.role())) {
                continue;
            }
            manualNames.put(mapping.role(), mapping.manualSignalName());
            userOwned.add(mapping.role());
            roleOrigins.put(mapping.role(), RoleOrigin.MANUAL);
        }
    }

    /**
     * Re-runs semantic detection and pre-populates every editable role it can resolve, discarding
     * unsaved manual edits. Unlike {@link #resetToAuto()} this keeps the result saveable, so an
     * engineer can deliberately freeze the current detection into the project.
     */
    public void autoDetect() {
        assigned.clear();
        roleOrigins.clear();
        userOwned.clear();
        manualNames.clear();
        persistedMissing = false;
        persistedMissingDetail = "";
        droppedRolesDetail = "";
        userEdited = false;
        autoOnly = false;
        fillFromDetection();
        // Pressing Auto Detect is an explicit decision, so the result is the engineer's to save.
        userOwned.addAll(assigned.keySet());
    }

    /**
     * Discards any stored override for this source and returns to detection. Saving afterwards
     * removes the persisted rows rather than writing detection back as project data.
     */
    public void resetToAuto() {
        autoDetect();
        origin = GpsMappingPolicy.Origin.AUTO;
        autoOnly = true;
    }

    private void fillFromDetection() {
        GpsDetectionResult detected = resolver.detect(catalog, detectionGeneration());
        Map<GpsSignalRole, SignalCatalogKey> roles = GpsSignalDiscoveryService.toRoleMap(detected);
        for (GpsSignalRole role : EDITABLE_ROLES) {
            SignalCatalogKey key = roles.get(role);
            if (key == null) {
                continue;
            }
            assigned.put(role, key);
            roleOrigins.put(role, confidenceOrigin(role, key));
        }
        origin = roles.isEmpty() ? GpsMappingPolicy.Origin.UNRESOLVED : GpsMappingPolicy.Origin.AUTO;
    }

    /**
     * Pre-fills roles the stored mapping does not cover, using the same override-aware detection
     * Map Route runs, so both screens agree on which signal each role actually resolves to.
     */
    private void fillUnassignedFromDetection() {
        GpsDetectionResult detected = resolver.detect(catalog, detectionGeneration(), new EnumMap<>(assigned));
        Map<GpsSignalRole, SignalCatalogKey> roles = GpsSignalDiscoveryService.toRoleMap(detected);
        for (GpsSignalRole role : EDITABLE_ROLES) {
            SignalCatalogKey key = roles.get(role);
            if (key == null || assigned.containsKey(role) || byKey.get(key) == null) {
                continue;
            }
            assigned.put(role, key);
            roleOrigins.put(role, confidenceOrigin(role, key));
        }
    }

    private String owningSource(Collection<GpsSignalMapping> persisted) {
        if (persisted == null) {
            return "";
        }
        return persisted.stream()
                .filter(m -> m.userConfirmed()
                        && schemaFingerprint.equals(m.schemaFingerprint())
                        && !sourceId.equals(m.sourceId()))
                .map(GpsSignalMapping::sourceId)
                .filter(s -> s != null && !s.isBlank())
                .findFirst()
                .orElse("");
    }

    private String detectionGeneration() {
        return sourceId + '|' + schemaFingerprint;
    }

    private RoleOrigin confidenceOrigin(GpsSignalRole role, SignalCatalogKey key) {
        SignalCatalogEntry entry = byKey.get(key);
        if (entry == null) {
            return RoleOrigin.NOT_MAPPED;
        }
        return switch (resolver.confidenceOf(role, entry)) {
            case HIGH -> RoleOrigin.AUTO_DETECTED;
            case MEDIUM -> RoleOrigin.SUGGESTED;
            case LOW -> RoleOrigin.LOW_CONFIDENCE;
            case NONE -> RoleOrigin.NOT_MAPPED;
        };
    }

    /**
     * Assigns a signal to a role.
     *
     * <p>Validation is advisory: a suspicious range is reported but still applied so unusual yet
     * legitimate engineering data is never silently rejected. Only a physically impossible
     * selection (non-numeric, or no signal at all) is refused.
     *
     * @return the advisory validation result for the caller to surface
     */
    public GpsSignalValidator.Result assign(GpsSignalRole role, SignalCatalogKey key) {
        Objects.requireNonNull(role, "role");
        if (!EDITABLE_ROLES.contains(role)) {
            throw new IllegalArgumentException("Role is not user-mappable: " + role);
        }
        if (key == null) {
            clear(role);
            return new GpsSignalValidator.Result(GpsSignalValidator.Severity.OK, "");
        }
        SignalCatalogEntry entry = byKey.get(key);
        GpsSignalValidator.Result result = GpsSignalValidator.validate(role, entry);
        if (result.blocking()) {
            return result;
        }
        assigned.put(role, key);
        manualNames.remove(role);
        roleOrigins.put(role, RoleOrigin.MANUAL);
        userOwned.add(role);
        markEdited();
        return result;
    }

    /**
     * Records a signal name the engineer typed.
     *
     * <p>The name is bound to a catalog signal when this measurement carries one, so the route updates
     * straight away. When it does not, the configuration is still kept: the same product may be
     * measured later in a file that does use that name. That case is reported, never guessed at.
     */
    public GpsSignalValidator.Result assignManual(GpsSignalRole role, String signalName) {
        Objects.requireNonNull(role, "role");
        if (!EDITABLE_ROLES.contains(role)) {
            throw new IllegalArgumentException("Role is not user-mappable: " + role);
        }
        String name = signalName == null ? "" : signalName.trim();
        if (name.isEmpty()) {
            clear(role);
            return new GpsSignalValidator.Result(GpsSignalValidator.Severity.OK, "");
        }
        manualNames.put(role, name);
        SignalCatalogKey bound = findByName(name);
        if (bound == null) {
            assigned.remove(role);
        } else {
            assigned.put(role, bound);
        }
        roleOrigins.put(role, RoleOrigin.MANUAL);
        userOwned.add(role);
        markEdited();
        if (bound == null) {
            return new GpsSignalValidator.Result(GpsSignalValidator.Severity.SUSPICIOUS,
                    "No signal named " + name + " in this measurement. The mapping is stored for "
                            + "measurements that contain it.");
        }
        return GpsSignalValidator.validate(role, byKey.get(bound));
    }

    private SignalCatalogKey findByName(String name) {
        for (SignalCatalogEntry entry : catalog) {
            if (entry.key().signalName().equals(name)) {
                return entry.key();
            }
        }
        for (SignalCatalogEntry entry : catalog) {
            if (entry.key().signalName().equalsIgnoreCase(name)) {
                return entry.key();
            }
        }
        return null;
    }

    /**
     * Removes the engineer's choice for a role.
     *
     * <p>An optional role stays visibly empty for the rest of the editing session so the gesture has
     * an effect the user can see. It is not persisted as a suppression: once saved, a role the
     * mapping does not name is automatic again.
     */
    public void clear(GpsSignalRole role) {
        Objects.requireNonNull(role, "role");
        userOwned.remove(role);
        boolean hadManual = manualNames.remove(role) != null;
        if (assigned.remove(role) != null || hadManual) {
            roleOrigins.remove(role);
            markEdited();
        }
    }

    private void markEdited() {
        userEdited = true;
        autoOnly = false;
        persistedMissing = false;
        persistedMissingDetail = "";
        droppedRolesDetail = "";
    }

    public String projectId() {
        return projectId;
    }

    public String sourceId() {
        return sourceId;
    }

    public String schemaFingerprint() {
        return schemaFingerprint;
    }

    public List<SignalCatalogEntry> catalog() {
        return catalog;
    }

    public SignalCatalogEntry entry(SignalCatalogKey key) {
        return key == null ? null : byKey.get(key);
    }

    /** Suggested candidates for a role, highest score first; catalog order is untouched. */
    public List<GpsSignalCandidate> suggestions(GpsSignalRole role) {
        return resolver.suggest(role, catalog);
    }

    public SignalCatalogKey assigned(GpsSignalRole role) {
        return assigned.get(role);
    }

    /** The name typed for this role, or empty when the role holds a picked signal. */
    public String manualName(GpsSignalRole role) {
        String name = manualNames.get(role);
        return name == null ? "" : name;
    }

    public RouteMappingMode mode(GpsSignalRole role) {
        return manualNames.containsKey(role)
                ? RouteMappingMode.MANUAL_TEXT
                : RouteMappingMode.SIGNAL_REFERENCE;
    }

    /** True when a typed name does not match any signal in this measurement. */
    public boolean manualNameUnresolved(GpsSignalRole role) {
        return manualNames.containsKey(role) && assigned.get(role) == null;
    }

    /** Which configuration level the shown mapping came from, or empty when it is automatic. */
    public RouteMappingScope resolvedScope() {
        return switch (origin) {
            case USER_CONFIRMED, SCHEMA_REUSED -> RouteMappingScope.PROJECT;
            case PRODUCT_DEFAULT -> RouteMappingScope.DEFAULT;
            case AUTO, UNRESOLVED -> null;
        };
    }

    /** True when this project shows the product default because it configured nothing itself. */
    public boolean inheritedFromDefault() {
        return !userEdited && origin == GpsMappingPolicy.Origin.PRODUCT_DEFAULT;
    }

    /** True when a product default exists to fall back on. */
    public boolean hasProductDefault() {
        return !productDefaults.isEmpty();
    }

    public Map<GpsSignalRole, SignalCatalogKey> roles() {
        return new EnumMap<>(assigned);
    }

    public RoleOrigin roleOrigin(GpsSignalRole role) {
        RoleOrigin resolved = roleOrigins.get(role);
        return resolved == null ? RoleOrigin.NOT_MAPPED : resolved;
    }

    /** Advisory validation for the currently assigned signal, for an inline warning. */
    public GpsSignalValidator.Result advisory(GpsSignalRole role) {
        SignalCatalogKey key = assigned.get(role);
        if (key == null) {
            return new GpsSignalValidator.Result(GpsSignalValidator.Severity.OK, "");
        }
        return GpsSignalValidator.validate(role, byKey.get(key));
    }

    /**
     * Plausibility of the coordinate pair as a whole, which is the only level at which a radian
     * recording can be told apart from a genuine one near 0°/0°.
     */
    public GpsSignalValidator.Result coordinateAdvisory() {
        return GpsSignalValidator.coordinatePairAdvisory(
                entry(assigned.get(GpsSignalRole.LATITUDE)),
                entry(assigned.get(GpsSignalRole.LONGITUDE)));
    }

    /** True when the role holds a signal the engineer chose, rather than a detected suggestion. */
    public boolean userOwned(GpsSignalRole role) {
        return userOwned.contains(role);
    }

    /**
     * Measurement that owns the mapping this source borrows through schema reuse, or empty when the
     * mapping is this source's own. Deleting this source's rows cannot remove a borrowed mapping, so
     * the screen has to say so instead of reporting a success that reverts on reopen.
     */
    public String reusedFromSourceId() {
        return reusedFromSourceId;
    }

    public boolean hasRoute() {
        return GpsSignalRoleResolver.hasRoute(assigned);
    }

    public boolean userEdited() {
        return userEdited;
    }

    public Status status() {
        if (catalog.isEmpty()) {
            return Status.NO_CATALOG;
        }
        if (persistedMissing) {
            return Status.MAPPING_SIGNAL_MISSING;
        }
        if (!hasRoute()) {
            return Status.MAPPING_REQUIRED;
        }
        if (userEdited) {
            return Status.USER_CONFIGURED;
        }
        return switch (origin) {
            case USER_CONFIRMED -> Status.USER_CONFIGURED;
            case SCHEMA_REUSED -> Status.SCHEMA_REUSED;
            case PRODUCT_DEFAULT -> Status.PRODUCT_DEFAULT;
            case AUTO, UNRESOLVED -> Status.AUTO_DETECTED;
        };
    }

    /** Extra explanation for the status, such as which mapped signal disappeared. */
    public String statusDetail() {
        String unresolvedManual = unresolvedManualDetail();
        if (!unresolvedManual.isEmpty()) {
            return unresolvedManual;
        }
        if (persistedMissing) {
            return persistedMissingDetail;
        }
        if (!hasRoute()) {
            return "Latitude and Longitude are required to generate the route.";
        }
        GpsSignalValidator.Result coordinates = coordinateAdvisory();
        if (coordinates.severity() != GpsSignalValidator.Severity.OK) {
            return droppedRolesDetail.isEmpty()
                    ? coordinates.message()
                    : droppedRolesDetail + " " + coordinates.message();
        }
        return droppedRolesDetail;
    }

    /** Optional roles whose stored signal disappeared; the coordinates were kept. */
    public String droppedRolesDetail() {
        return droppedRolesDetail;
    }

    /** Typed names that this measurement cannot bind, listed for the editor. */
    private String unresolvedManualDetail() {
        List<String> unresolved = new ArrayList<>();
        for (GpsSignalRole role : EDITABLE_ROLES) {
            if (manualNameUnresolved(role)) {
                unresolved.add(role.displayName() + " → " + manualNames.get(role));
            }
        }
        if (unresolved.isEmpty()) {
            return "";
        }
        return "Stored, but not present in this measurement: " + String.join(", ", unresolved) + ".";
    }

    /**
     * True when both coordinate roles name a signal, whether picked or typed. A typed name that this
     * measurement cannot bind still counts: the configuration is legitimate, it just cannot draw a
     * route until a measurement containing that signal is opened.
     */
    public boolean requiredRolesConfigured() {
        for (GpsSignalRole role : REQUIRED_ROLES) {
            if (assigned.get(role) == null && manualName(role).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** True when Save Mapping has something meaningful to write or delete. */
    public boolean saveable() {
        return !catalog.isEmpty() && (requiredRolesConfigured() || autoOnly);
    }

    /**
     * Rows to persist for this source. Empty means "no stored override" — the caller deletes the
     * source's rows, which is exactly what Use Automatic Detection has to achieve.
     *
     * <p>Coordinates are always written, because pinning them is the purpose of saving. An optional
     * role is written only when the engineer owns it; a role merely pre-filled by detection stays
     * automatic, so simply opening the screen cannot freeze a guess into project data.
     */
    public List<GpsSignalMapping> toMappings(Instant now) {
        return toMappings(now, RouteMappingScope.PROJECT);
    }

    /**
     * @param scope PROJECT for this project's own configuration, DEFAULT to publish the same
     *              selection as the product-wide fallback
     */
    public List<GpsSignalMapping> toMappings(Instant now, RouteMappingScope scope) {
        if (autoOnly || !requiredRolesConfigured()) {
            return List.of();
        }
        RouteMappingScope target = scope == null ? RouteMappingScope.PROJECT : scope;
        Instant stamp = now == null ? Instant.now() : now;
        List<GpsSignalMapping> out = new ArrayList<>(assigned.size());
        for (GpsSignalRole role : EDITABLE_ROLES) {
            boolean own = REQUIRED_ROLES.contains(role) || userOwned.contains(role);
            if (!own) {
                continue;
            }
            String manual = manualName(role);
            if (!manual.isEmpty()) {
                out.add(GpsSignalMapping.manual(
                        projectId, sourceId, schemaFingerprint, role, manual, target, stamp));
                continue;
            }
            SignalCatalogKey key = assigned.get(role);
            if (key == null) {
                continue;
            }
            out.add(new GpsSignalMapping(projectId, sourceId, schemaFingerprint, role,
                    RouteMappingMode.SIGNAL_REFERENCE, key, "", target, true, stamp));
        }
        return List.copyOf(out);
    }
}
