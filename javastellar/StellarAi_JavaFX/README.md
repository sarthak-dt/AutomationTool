# StellarAI Desktop

JavaFX desktop application for local automotive measurement analysis (ASC, MF4, BLF, and DBC). The desktop module is `stellar-desktop`. Product version is `1.0.4`, from `stellar-desktop/src/main/resources/stellar-version.properties`. Maven module versions stay `0.1.0-SNAPSHOT`.

## Requirements

Run these commands from the repository root.

| Requirement | Development run | Windows installer |
|---|---|---|
| JDK 21 | Required. `JAVA_HOME` must point at a full JDK 21. | Required. `jpackage` ships with the JDK. |
| Apache Maven 3.9+ | Required on `PATH`. | Required on `PATH`. |
| JavaFX | Maven downloads JavaFX `21.0.6`. | Bundled in the installer. |
| WiX Toolset 3.x | Not used. | Required for the Setup `.exe`: `candle.exe` and `light.exe`. The build looks in `tools\wix314\`, then a WiX 3.11 or 3.14 Program Files install. WiX 4, 5, and 7 cannot produce this installer. |

End users who receive `StellarAI-Setup-1.0.4.exe` do not need a JDK or Maven. The installer includes the runtime.

## Run the desktop application from source

Install the current reactor into the local Maven repository, then start JavaFX:

```powershell
mvn -pl stellar-desktop -am -DskipTests install
mvn -pl stellar-desktop org.openjfx:javafx-maven-plugin:run
```

The JavaFX Maven plugin starts `com.stellar.platform.desktop.StellarDesktopApp`.

Run the install command first, in the same working tree, whenever sibling modules have changed. `javafx:run` on `stellar-desktop` alone loads `0.1.0-SNAPSHOT` jars from `~/.m2`. Those jars must be the ones just installed. `mvn verify` or `mvn compile` without `install` leaves the previous SNAPSHOT jars in place, and the desktop window then reports an incompatible runtime.

A normal development session stores configuration under `%USERPROFILE%\.stellarai`.

## Build and run the Windows application

```powershell
powershell -ExecutionPolicy Bypass -File .\BUILD_WINDOWS_TESTER_INSTALLER.ps1 -SkipTests -InstallerOutputDir D:\installer
```

This compiles and installs the reactor, then builds the app image and the installer. Omit `-SkipTests` when the full test suite should run before packaging.

The packaged entry point is `com.stellar.platform.desktop.StellarDesktopLauncher`. That class does not extend `javafx.application.Application`; it delegates to `StellarDesktopApp`. `jpackage` requires that split.

Successful packaging writes:

- Installer: `D:\installer\StellarAI-Setup-1.0.4.exe` when `-InstallerOutputDir D:\installer` is used. The default directory is `release\installer`.
- App image: `release\StellarAI-1.0.4\StellarAI.exe`

Run the Setup file to install StellarAI for the current user. After installation, launch **StellarAI** from the Start Menu, the desktop shortcut, or `StellarAI.exe` in the install folder. Packaged logs and configuration are under `%LOCALAPPDATA%\StellarAI`. The installer is unsigned.

`release\` is build output. Do not put those jars on the development classpath.
