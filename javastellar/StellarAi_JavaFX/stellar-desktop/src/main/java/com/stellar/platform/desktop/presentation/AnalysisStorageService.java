package com.stellar.platform.desktop.presentation;

import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.analysis.AnalysisStorageSettingsStore;
import com.stellar.platform.storage.analysis.AnalysisStorageValidator;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Application-level Analysis Storage configuration and validation.
 * Shared by Configuration UI and all New Project entry points.
 */
public final class AnalysisStorageService {

    private static final Logger LOG = Logger.getLogger(AnalysisStorageService.class.getName());

    private final AnalysisStorageSettingsStore settingsStore;
    private final AtomicReference<AnalysisStorageValidator.Result> lastResult =
            new AtomicReference<>(AnalysisStorageValidator.notConfigured());

    public AnalysisStorageService() {
        this(new AnalysisStorageSettingsStore());
    }

    public AnalysisStorageService(AnalysisStorageSettingsStore settingsStore) {
        this.settingsStore = Objects.requireNonNull(settingsStore, "settingsStore");
        reloadFromDisk();
    }

    public void reloadFromDisk() {
        Optional<Path> root = settingsStore.loadUserSelectedRoot();
        if (root.isEmpty()) {
            // Tests set stellarai.home to an isolated temp tree. Auto-provision Analysis Storage
            // there so existing DesktopAppService tests keep working without a Configuration UI.
            // Production (no stellarai.home override) remains NOT_CONFIGURED until the user Saves.
            if (bootstrapTestHomeIfNeeded()) {
                return;
            }
            AnalysisStorageRoots.clear();
            lastResult.set(AnalysisStorageValidator.notConfigured());
            return;
        }
        AnalysisStorageRoots.setUserSelectedRoot(root.get());
        AnalysisStorageValidator.Result result = AnalysisStorageValidator.lightweightCheck(root.get());
        if (!result.valid()) {
            // Prefer full probe when lightweight fails after restart (drive remount etc.).
            result = AnalysisStorageValidator.validate(root.get());
        }
        lastResult.set(result);
        AnalysisStorageValidator.Result logged = result;
        Path loggedRoot = root.get();
        LOG.info(() -> "Analysis Storage reload status=" + logged.status() + " path=" + loggedRoot);
    }

    private boolean bootstrapTestHomeIfNeeded() {
        if (Boolean.getBoolean("stellarai.analysis.storage.disable-auto")) {
            return false;
        }
        String configuredHome = System.getProperty("stellarai.home", "").trim();
        Path autoRoot;
        if (!configuredHome.isEmpty()) {
            autoRoot = Path.of(configuredHome).resolve("analysis-storage").toAbsolutePath().normalize();
        } else if (com.stellar.platform.storage.analysis.AnalysisStorageIsolation.enabled()) {
            autoRoot = com.stellar.platform.storage.StellarAiPaths.homeRoot()
                    .resolve("analysis-storage")
                    .toAbsolutePath()
                    .normalize();
        } else {
            return false;
        }
        AnalysisStorageValidator.Result probed = AnalysisStorageValidator.validate(autoRoot);
        if (!probed.valid()) {
            return false;
        }
        settingsStore.saveUserSelectedRoot(probed.userSelectedRoot());
        AnalysisStorageRoots.setUserSelectedRoot(probed.userSelectedRoot());
        AnalysisStorageRoots.ensureControlledDirectory();
        lastResult.set(probed);
        LOG.info(() -> "Analysis Storage auto-provisioned for stellarai.home: " + probed.userSelectedRoot());
        return true;
    }

    /** Forces a full probe of the current root (or NOT_CONFIGURED). */
    public AnalysisStorageValidator.Result revalidate() {
        Optional<Path> root = userSelectedRoot();
        if (root.isEmpty()) {
            AnalysisStorageValidator.Result missing = AnalysisStorageValidator.notConfigured();
            lastResult.set(missing);
            return missing;
        }
        AnalysisStorageValidator.Result result = AnalysisStorageValidator.validate(root.get());
        lastResult.set(result);
        AnalysisStorageRoots.refreshRuntimeTempBinding();
        return result;
    }

    public AnalysisStorageValidator.Result currentStatus() {
        return lastResult.get();
    }

    public boolean isValid() {
        return currentStatus().valid();
    }

    public Optional<Path> userSelectedRoot() {
        return AnalysisStorageRoots.userSelectedRoot();
    }

    public AnalysisStorageValidator.Result testAccess(Path candidateRoot) {
        AnalysisStorageValidator.Result result = AnalysisStorageValidator.validate(candidateRoot);
        LOG.info(() -> "Analysis Storage probe status=" + result.status() + " message=" + result.message());
        return result;
    }

    /**
     * Saves only after a successful full probe. Previous valid setting is left unchanged on failure.
     */
    public AnalysisStorageValidator.Result save(Path candidateRoot) {
        AnalysisStorageValidator.Result probed = AnalysisStorageValidator.validate(candidateRoot);
        if (!probed.valid()) {
            LOG.warning(() -> "Analysis Storage save rejected: " + probed.message());
            return probed;
        }
        settingsStore.saveUserSelectedRoot(probed.userSelectedRoot());
        AnalysisStorageRoots.setUserSelectedRoot(probed.userSelectedRoot());
        AnalysisStorageRoots.ensureControlledDirectory();
        lastResult.set(probed);
        LOG.info(() -> "Analysis Storage saved: " + probed.controlledRoot());
        return probed;
    }

    /** Shared New Project gate — all UI entry points must call this. */
    public void requireValidForProjectCreation() {
        AnalysisStorageValidator.Result status = currentStatus();
        if (status.status() == AnalysisStorageValidator.Status.NOT_CONFIGURED) {
            throw new AnalysisStorageRequiredException(status.message());
        }
        if (!status.valid()) {
            // Re-probe in case the drive returned.
            Path root = status.userSelectedRoot();
            if (root != null) {
                AnalysisStorageValidator.Result refreshed = AnalysisStorageValidator.validate(root);
                lastResult.set(refreshed);
                if (refreshed.valid()) {
                    AnalysisStorageRoots.setUserSelectedRoot(root);
                    return;
                }
                throw new AnalysisStorageRequiredException(refreshed.message());
            }
            throw new AnalysisStorageRequiredException(status.message());
        }
    }
}
