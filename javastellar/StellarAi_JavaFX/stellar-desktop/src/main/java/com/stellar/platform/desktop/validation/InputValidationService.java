package com.stellar.platform.desktop.validation;

import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.parse.DbcParseException;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.FileValidationStatus;
import com.stellar.platform.desktop.project.MeasurementSourceCapability;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.parser.mf4.Mf4Inspector;
import com.stellar.platform.parser.mf4.analysis.MdfConsistencyAnalyser;
import com.stellar.platform.parser.mf4.analysis.MdfFinalizationStatus;
import com.stellar.platform.parser.mf4.dynamicschema.MdfDynamicSchemaBuilder;
import com.stellar.platform.parser.mf4.dynamicschema.MdfDynamicSchemaResult;
import com.stellar.platform.parser.mf4.dynamicschema.MdfRecordSchema;
import com.stellar.platform.parser.mf4.dynamicschema.MdfSchemaStatus;
import com.stellar.platform.parser.mf4.graph.MdfBlockGraphDiscovery;
import com.stellar.platform.parser.mf4.id.MdfIdentificationReader;
import com.stellar.platform.parser.mf4.io.MdfFileAccess;
import com.stellar.platform.parser.mf4.report.MdfInspectionReport;
import com.stellar.platform.parser.mf4.schema.MdfSchemaExtractor;
import com.stellar.platform.parser.spi.CancellationToken;
import com.stellar.platform.parser.spi.ParserException;
import com.stellar.platform.processing.ParserRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Evidence-based selection validation. Unfinalized / recovery-candidate MF4 files remain processable.
 */
public final class InputValidationService {

    private final ParserRegistry parserRegistry;
    private final Mf4Inspector mf4Inspector;
    private final MdfDynamicSchemaBuilder schemaBuilder;

    public InputValidationService(ParserRegistry parserRegistry) {
        this(parserRegistry, new Mf4Inspector(), new MdfDynamicSchemaBuilder());
    }

    public InputValidationService(
            ParserRegistry parserRegistry,
            Mf4Inspector mf4Inspector,
            MdfDynamicSchemaBuilder schemaBuilder
    ) {
        this.parserRegistry = Objects.requireNonNull(parserRegistry, "parserRegistry");
        this.mf4Inspector = Objects.requireNonNull(mf4Inspector, "mf4Inspector");
        this.schemaBuilder = Objects.requireNonNull(schemaBuilder, "schemaBuilder");
    }

    public InputValidationResult validateSelection(WorkspaceFile measurement, WorkspaceFile definition) {
        List<ValidationIssue> issues = new ArrayList<>();

        if (measurement == null) {
            issues.add(ValidationIssue.error(
                    ValidationIssueCode.NO_MEASUREMENT_FILE_SELECTED,
                    "No measurement file is selected. Select an ASC or MF4 row in Measurement Files.",
                    "selectedMeasurement == null"
            ));
            return InputValidationResult.of(false, issues, null, null);
        }

        measurement.setStatus(FileValidationStatus.VALIDATING, "Validating…");
        FileValidationResult measurementResult = validateMeasurementFile(measurement, issues);

        if (MeasurementSourceCapability.supportsDirectProcessing(measurement.kind())) {
            applyFileResult(measurement, measurementResult);
            return InputValidationResult.of(measurementResult.processingAllowed(), issues, measurementResult, null);
        }

        if (definition == null) {
            issues.add(ValidationIssue.error(
                    ValidationIssueCode.MISSING_DBC,
                    "Select/associate a DBC definition file.",
                    "selectedDefinition == null"
            ));
            // Keep measurement status from evidence-based check; do not overwrite with vague message.
            return InputValidationResult.of(false, issues, measurementResult, null);
        }

        definition.setStatus(FileValidationStatus.VALIDATING, "Validating…");
        FileValidationResult definitionResult = validateDefinitionFile(definition, issues);

        boolean processable = measurementResult.processingAllowed() && definitionResult.processingAllowed();
        if (processable) {
            measurement.setAssociatedDefinitionId(definition.id());
        }

        applyFileResult(measurement, measurementResult);
        applyFileResult(definition, definitionResult);
        return InputValidationResult.of(processable, issues, measurementResult, definitionResult);
    }

    private FileValidationResult validateMeasurementFile(WorkspaceFile measurement, List<ValidationIssue> issues) {
        Path path = measurement.path();
        if (!Files.exists(path)) {
            ValidationIssue fatal = ValidationIssue.error(
                    ValidationIssueCode.FILE_MISSING,
                    "Measurement file no longer exists: " + measurement.fileName(),
                    path.toAbsolutePath().toString()
            );
            issues.add(fatal);
            FileValidationResult result = FileValidationResult.invalid(fatal.message(), fatal);
            applyFileResult(measurement, result);
            return result;
        }
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            ValidationIssue fatal = ValidationIssue.error(
                    ValidationIssueCode.FILE_UNREADABLE,
                    "Measurement file is not readable: " + measurement.fileName(),
                    path.toAbsolutePath().toString()
            );
            issues.add(fatal);
            FileValidationResult result = FileValidationResult.invalid(fatal.message(), fatal);
            applyFileResult(measurement, result);
            return result;
        }

        if (MeasurementSourceCapability.supportsDirectProcessing(measurement.kind())) {
            return validateDirect(measurement, issues);
        }

        if (!measurement.kind().implemented() || measurement.kind() == FileKind.UNKNOWN) {
            ValidationIssue fatal = ValidationIssue.error(
                    ValidationIssueCode.UNSUPPORTED_FORMAT,
                    "Measurement format is not supported for processing.",
                    "kind=" + measurement.kind()
            );
            issues.add(fatal);
            FileValidationResult result = FileValidationResult.unsupported(fatal.message(), fatal);
            applyFileResult(measurement, result);
            return result;
        }
        if (measurement.kind() != FileKind.MEASUREMENT_ASC && measurement.kind() != FileKind.MEASUREMENT_MF4) {
            ValidationIssue fatal = ValidationIssue.error(
                    ValidationIssueCode.UNSUPPORTED_FORMAT,
                    measurement.kind().displayName() + " is not yet implemented.",
                    "kind=" + measurement.kind()
            );
            issues.add(fatal);
            FileValidationResult result = FileValidationResult.unsupported(fatal.message(), fatal);
            applyFileResult(measurement, result);
            return result;
        }

        if (parserRegistry.findReader(path).isEmpty()) {
            ValidationIssue fatal = ValidationIssue.error(
                    ValidationIssueCode.NO_PARSER,
                    "No measurement parser is available for this file.",
                    path.toAbsolutePath().toString()
            );
            issues.add(fatal);
            FileValidationResult result = FileValidationResult.unsupported(fatal.message(), fatal);
            applyFileResult(measurement, result);
            return result;
        }

        if (measurement.kind() == FileKind.MEASUREMENT_MF4) {
            return validateMf4(measurement, issues);
        }

        // ASC: readable + parser present is enough for this slice.
        FileValidationResult ready = FileValidationResult.ready("ASC ready for local processing");
        applyFileResult(measurement, ready);
        return ready;
    }

    private FileValidationResult validateDirect(WorkspaceFile measurement, List<ValidationIssue> issues) {
        var detected = parserRegistry.detect(measurement.path());
        if (!detected.parserSupported()) {
            String message = detected.recognized() && detected.content() != null
                    ? detected.content().unsupportedVersionMessage()
                    : detected.unknownMessage();
            ValidationIssue fatal = ValidationIssue.error(
                    ValidationIssueCode.UNSUPPORTED_FORMAT,
                    message,
                    measurement.path().toAbsolutePath().toString()
            );
            issues.add(fatal);
            FileValidationResult result = FileValidationResult.unsupported(fatal.message(), fatal);
            applyFileResult(measurement, result);
            return result;
        }
        if (parserRegistry.findReader(measurement.path()).isEmpty()) {
            String message = "StellarAI could not determine the measurement structure automatically.";
            ValidationIssue fatal = ValidationIssue.error(
                    ValidationIssueCode.NO_PARSER,
                    message,
                    measurement.path().toAbsolutePath().toString()
            );
            issues.add(fatal);
            FileValidationResult result = FileValidationResult.unsupported(fatal.message(), fatal);
            applyFileResult(measurement, result);
            return result;
        }
        FileValidationResult ready = FileValidationResult.ready("Direct processing. DBC is not required.");
        applyFileResult(measurement, ready);
        return ready;
    }

    private FileValidationResult validateMf4(WorkspaceFile measurement, List<ValidationIssue> issues) {
        try {
            MdfInspectionReport report = mf4Inspector.inspect(measurement.path());
            measurement.setMf4FinalizationLabel(report.finalizationStatus().name());
            measurement.setRecoveryCandidate(report.recoveryCandidate());

            MdfDynamicSchemaResult schema = buildSchema(measurement.path());
            List<MdfRecordSchema> usable = schema.usableSchemas();
            boolean hasRecoverySchema = usable.stream()
                    .anyMatch(s -> s.status() == MdfSchemaStatus.RECOVERY_CANDIDATE
                            || s.status() == MdfSchemaStatus.NORMAL
                            || s.status() == MdfSchemaStatus.PARTIAL);

            List<ValidationIssue> warnings = new ArrayList<>();

            if (report.finalizationStatus() == MdfFinalizationStatus.CORRUPTED && usable.isEmpty()) {
                ValidationIssue fatal = ValidationIssue.error(
                        ValidationIssueCode.MF4_INVALID,
                        "Cannot process MF4: file is corrupted and no usable Channel Group/schema was discovered.",
                        "finalization=CORRUPTED, usableSchemas=0"
                );
                issues.add(fatal);
                FileValidationResult result = FileValidationResult.invalid(fatal.message(), fatal);
                applyFileResult(measurement, result);
                return result;
            }

            if (usable.isEmpty()) {
                ValidationIssue fatal = ValidationIssue.error(
                        ValidationIssueCode.MF4_INVALID,
                        "Cannot process MF4: no usable Channel Group / record schema was discovered.",
                        "dg=" + report.dgCount() + ", cg=" + report.cgCount()
                                + ", recoveryCandidate=" + report.recoveryCandidate()
                );
                issues.add(fatal);
                FileValidationResult result = FileValidationResult.invalid(fatal.message(), fatal);
                applyFileResult(measurement, result);
                return result;
            }

            boolean warningState = report.recoveryCandidate()
                    || report.finalizationStatus() == MdfFinalizationStatus.UNFINALIZED
                    || report.finalizationStatus() == MdfFinalizationStatus.INCONSISTENT
                    || report.declaredCycleCountTotal() == 0 && report.physicalDataDetected()
                    || usable.stream().anyMatch(s -> s.status() == MdfSchemaStatus.RECOVERY_CANDIDATE);

            if (warningState) {
                String summary = buildMf4WarningSummary(report, hasRecoverySchema);
                ValidationIssue warning = ValidationIssue.warning(
                        ValidationIssueCode.MF4_RECOVERY_REQUIRED,
                        summary,
                        "finalization=" + report.finalizationStatus()
                                + ", recoveryCandidate=" + report.recoveryCandidate()
                                + ", usableSchemas=" + usable.size()
                                + ", physicalData=" + report.physicalDataDetected()
                );
                warnings.add(warning);
                issues.add(warning);
                if (!report.unsupportedStructures().isEmpty()) {
                    ValidationIssue unsupported = ValidationIssue.warning(
                            ValidationIssueCode.MF4_RECOVERY_REQUIRED,
                            "MF4 contains unsupported structures; some data may be skipped.",
                            String.join("; ", report.unsupportedStructures())
                    );
                    warnings.add(unsupported);
                    issues.add(unsupported);
                }
                FileValidationResult result = FileValidationResult.readyWithWarnings(summary, warnings);
                applyFileResult(measurement, result);
                return result;
            }

            if (!report.unsupportedStructures().isEmpty()) {
                ValidationIssue unsupported = ValidationIssue.warning(
                        ValidationIssueCode.MF4_RECOVERY_REQUIRED,
                        "MF4 contains unsupported structures; some data may be skipped.",
                        String.join("; ", report.unsupportedStructures())
                );
                warnings.add(unsupported);
                issues.add(unsupported);
                FileValidationResult result = FileValidationResult.readyWithWarnings(
                        "MF4 ready with unsupported-structure warnings",
                        warnings
                );
                applyFileResult(measurement, result);
                return result;
            }

            FileValidationResult ready = FileValidationResult.ready("MF4 ready for local processing");
            applyFileResult(measurement, ready);
            return ready;
        } catch (ParserException e) {
            String tech = e.getMessage() == null ? e.toString() : e.getMessage();
            ValidationIssue fatal = ValidationIssue.error(
                    ValidationIssueCode.MF4_INVALID,
                    "Cannot process MF4: " + friendlyMf4Failure(tech),
                    tech
            );
            issues.add(fatal);
            FileValidationResult result = FileValidationResult.invalid(fatal.message(), fatal);
            applyFileResult(measurement, result);
            return result;
        }
    }

    private MdfDynamicSchemaResult buildSchema(Path path) throws ParserException {
        try (MdfFileAccess access = MdfFileAccess.open(path)) {
            var identification = MdfIdentificationReader.read(access);
            var graph = MdfBlockGraphDiscovery.discover(access, CancellationToken.NONE);
            var metadata = MdfSchemaExtractor.extract(access, graph, CancellationToken.NONE);
            var analysis = MdfConsistencyAnalyser.analyse(identification, graph, metadata);
            return schemaBuilder.build(graph, metadata, analysis);
        }
    }

    private FileValidationResult validateDefinitionFile(WorkspaceFile definition, List<ValidationIssue> issues) {
        Path path = definition.path();
        if (!Files.exists(path)) {
            ValidationIssue fatal = ValidationIssue.error(
                    ValidationIssueCode.FILE_MISSING,
                    "DBC file no longer exists: " + definition.fileName(),
                    path.toAbsolutePath().toString()
            );
            issues.add(fatal);
            FileValidationResult result = FileValidationResult.invalid(fatal.message(), fatal);
            applyFileResult(definition, result);
            return result;
        }
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            ValidationIssue fatal = ValidationIssue.error(
                    ValidationIssueCode.FILE_UNREADABLE,
                    "DBC file is not readable: " + definition.fileName(),
                    path.toAbsolutePath().toString()
            );
            issues.add(fatal);
            FileValidationResult result = FileValidationResult.invalid(fatal.message(), fatal);
            applyFileResult(definition, result);
            return result;
        }
        String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (definition.kind() != FileKind.DEFINITION_DBC || !name.endsWith(".dbc")) {
            ValidationIssue fatal = ValidationIssue.error(
                    ValidationIssueCode.DBC_INVALID,
                    "Definition must be a DBC file.",
                    "kind=" + definition.kind()
            );
            issues.add(fatal);
            FileValidationResult result = definition.kind().implemented()
                    ? FileValidationResult.invalid(fatal.message(), fatal)
                    : FileValidationResult.unsupported(fatal.message(), fatal);
            applyFileResult(definition, result);
            return result;
        }

        List<String> warnings = definitionImportWarnings(path);
        if (!warnings.isEmpty()) {
            List<ValidationIssue> warningIssues = new ArrayList<>();
            for (String warning : warnings) {
                ValidationIssue issue = ValidationIssue.warning(
                        codeForDbcWarning(warning),
                        warning,
                        path.toAbsolutePath().toString());
                issues.add(issue);
                warningIssues.add(issue);
            }
            FileValidationResult warned = FileValidationResult.readyWithWarnings(
                    String.join(System.lineSeparator(), warnings),
                    warningIssues);
            applyFileResult(definition, warned);
            return warned;
        }

        FileValidationResult ready = FileValidationResult.ready("DBC ready");
        applyFileResult(definition, ready);
        return ready;
    }

    /**
     * Re-reads a DBC that already passed import. Nonfatal diagnostics stay visible.
     * A parse failure stays on the existing extension check so selection validation
     * does not invent a second rejection path.
     */
    private static List<String> definitionImportWarnings(Path path) {
        try {
            DbcDatabase parsed = new DbcParser().parse(path);
            return DbcParser.importWarnings(parsed);
        } catch (DbcParseException ex) {
            return List.of();
        }
    }

    private static ValidationIssueCode codeForDbcWarning(String warning) {
        if (warning.startsWith(DbcParser.WINDOWS_1252_WARNING_PREFIX)) {
            return ValidationIssueCode.DBC_WINDOWS_1252_ENCODING;
        }
        // Only an unterminated value statement uses this prefix. A VAL_ that spans
        // lines and ends with a semicolon is loaded and is not classified here.
        if (warning.startsWith(DbcParser.MULTILINE_VAL_WARNING_PREFIX)) {
            return ValidationIssueCode.DBC_MULTILINE_VAL;
        }
        if (warning.startsWith(DbcParser.SIG_VALTYPE_WARNING_PREFIX)) {
            return ValidationIssueCode.DBC_SIG_VALTYPE;
        }
        return ValidationIssueCode.DBC_IGNORED_RECORDS;
    }

    private static void applyFileResult(WorkspaceFile file, FileValidationResult result) {
        file.setStatus(result.status(), result.summaryMessage());
    }

    private static String buildMf4WarningSummary(MdfInspectionReport report, boolean usableSchema) {
        StringBuilder sb = new StringBuilder();
        if (report.finalizationStatus() == MdfFinalizationStatus.UNFINALIZED) {
            sb.append("Unfinalized MF4");
        } else if (report.finalizationStatus() == MdfFinalizationStatus.INCONSISTENT) {
            sb.append("Inconsistent MF4");
        } else {
            sb.append("MF4 requires recovery");
        }
        if (report.physicalDataDetected()) {
            sb.append("; physical data detected");
        }
        if (report.recoveryCandidate() && usableSchema) {
            sb.append("; recovery will be attempted");
        }
        if (report.declaredCycleCountTotal() == 0 && report.physicalDataDetected()) {
            sb.append("; declared cycle count is 0");
        }
        return sb.toString();
    }

    private static String friendlyMf4Failure(String technical) {
        String lower = technical.toLowerCase(Locale.ROOT);
        if (lower.contains("signature") || lower.contains("not an mdf")) {
            return "file identification signature is not a recognized MDF/UnFinMF header.";
        }
        if (lower.contains("unsupported") && lower.contains("version")) {
            return "unsupported MDF version.";
        }
        if (technical.length() > 180) {
            return technical.substring(0, 180) + "…";
        }
        return technical;
    }
}
