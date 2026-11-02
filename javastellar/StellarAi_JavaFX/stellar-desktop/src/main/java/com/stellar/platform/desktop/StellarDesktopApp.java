package com.stellar.platform.desktop;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.ui.DesktopWindowLayout;
import com.stellar.platform.desktop.ui.MainShellController;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * StellarAI JavaFX desktop — local ASC/MF4 + DBC processing vertical slice.
 */
public final class StellarDesktopApp extends Application {

    private static final System.Logger LOG = System.getLogger(StellarDesktopApp.class.getName());
    private DesktopAppService appService;
    private MainShellController shellController;

    @Override
    public void start(Stage stage) throws Exception {
        // javafx-maven-plugin launches Application.launch(Class) and skips main().
        DesktopLogging.configureOnce();
        installSharedTypography();
        try {
            RuntimeModuleConsistency.logAndVerify();
        } catch (RuntimeException ex) {
            String message = ex.getMessage() == null || ex.getMessage().isBlank()
                    ? ProcessingCoreCompatibility.incompatibleClasspathMessage()
                    : ex.getMessage();
            LOG.log(System.Logger.Level.ERROR, message);
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("StellarAI");
            alert.setHeaderText("Incompatible desktop runtime");
            alert.setContentText(message);
            alert.showAndWait();
            throw new IllegalStateException(message, ex);
        }
        // Untitled work stays transient. Durable SQLite is created only inside a project root.
        appService = DesktopAppService.forLocalProduction(new DesktopSession());

        FXMLLoader loader = new FXMLLoader(StellarDesktopApp.class.getResource("/fxml/main-shell.fxml"));
        Parent root = loader.load();
        MainShellController controller = loader.getController();
        shellController = controller;
        controller.init(appService);

        Scene scene = new Scene(root);
        scene.getStylesheets().add(StellarDesktopApp.class.getResource("/css/stellar-desktop.css").toExternalForm());

        stage.setTitle(DesktopVersion.windowTitle());
        DesktopWindowLayout.configurePrimaryStage(stage, scene);
        stage.setOnCloseRequest(event -> {
            // Consume first so a failed confirm cannot race a half-closed stage.
            event.consume();
            if (shellController != null && !shellController.confirmExitRequest()) {
                return;
            }
            // Persist is complete (or user forced exit). Tear down FX and leave the process.
            Platform.exit();
        });
    }

    @Override
    public void stop() {
        try {
            if (shellController != null) {
                shellController.dispose();
            }
            if (appService != null) {
                appService.shutdown();
            }
        } finally {
            shellController = null;
            appService = null;
        }
    }

    /**
     * Dialogs and popups own a separate scene, so they do not inherit the main window stylesheet.
     * Attach the application sheet as each window appears.
     */
    public static void installSharedTypography() {
        String css = StellarDesktopApp.class.getResource("/css/stellar-desktop.css").toExternalForm();
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                if (!change.wasAdded()) {
                    continue;
                }
                for (Window window : change.getAddedSubList()) {
                    attachStylesheet(window, css);
                }
            }
        });
        for (Window window : Window.getWindows()) {
            attachStylesheet(window, css);
        }
    }

    private static void attachStylesheet(Window window, String css) {
        Scene scene = window.getScene();
        if (scene != null) {
            addStylesheet(scene, css);
            return;
        }
        window.sceneProperty().addListener((obs, previous, next) -> {
            if (next != null) {
                addStylesheet(next, css);
            }
        });
    }

    private static void addStylesheet(Scene scene, String css) {
        if (!scene.getStylesheets().contains(css)) {
            scene.getStylesheets().add(0, css);
        }
    }

    public static void main(String[] args) {
        DesktopLogging.configureOnce();
        RuntimeModuleConsistency.logAndVerify();
        launch(args);
    }
}
