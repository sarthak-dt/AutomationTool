package com.stellar.platform.testkit.compat;

import java.nio.file.Path;
import java.util.Objects;

/**
 * CLI options for {@link ParserCompatibilityHarness}. All paths are dynamic.
 */
public record HarnessOptions(
        Path mf4Path,
        Path dbcPath,
        Path outputDir,
        int frameSamples,
        int signalSamples,
        int matchedFrameSamples,
        boolean exactDuplicateAnalysis
) {
    public HarnessOptions {
        Objects.requireNonNull(mf4Path, "mf4Path");
        Objects.requireNonNull(dbcPath, "dbcPath");
        Objects.requireNonNull(outputDir, "outputDir");
        if (frameSamples < 1) {
            throw new IllegalArgumentException("--frame-samples must be >= 1");
        }
        if (signalSamples < 1) {
            throw new IllegalArgumentException("--signal-samples must be >= 1");
        }
        if (matchedFrameSamples < 1) {
            throw new IllegalArgumentException("--matched-frame-samples must be >= 1");
        }
    }

    public static HarnessOptions parse(String[] args) {
        Path mf4 = null;
        Path dbc = null;
        Path output = null;
        int frameSamples = 50;
        int signalSamples = 100;
        int matchedFrameSamples = 50;
        boolean exactDup = true;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--mf4" -> mf4 = Path.of(requireValue(args, ++i, a));
                case "--dbc" -> dbc = Path.of(requireValue(args, ++i, a));
                case "--output" -> output = Path.of(requireValue(args, ++i, a));
                case "--frame-samples" -> frameSamples = Integer.parseInt(requireValue(args, ++i, a));
                case "--signal-samples" -> signalSamples = Integer.parseInt(requireValue(args, ++i, a));
                case "--matched-frame-samples" -> matchedFrameSamples = Integer.parseInt(requireValue(args, ++i, a));
                case "--exact-duplicates" -> exactDup = Boolean.parseBoolean(requireValue(args, ++i, a));
                case "--help", "-h" -> {
                    printUsage();
                    System.exit(0);
                }
                default -> throw new IllegalArgumentException("Unknown argument: " + a);
            }
        }
        if (mf4 == null || dbc == null) {
            printUsage();
            throw new IllegalArgumentException("--mf4 and --dbc are required");
        }
        if (output == null) {
            output = Path.of("target", "compatibility-report");
        }
        return new HarnessOptions(mf4, dbc, output, frameSamples, signalSamples, matchedFrameSamples, exactDup);
    }

    private static String requireValue(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + flag);
        }
        return args[index];
    }

    public static void printUsage() {
        System.out.println("""
                ParserCompatibilityHarness
                  --mf4 <path>                 Measurement MF4/MDF file
                  --dbc <path>                 DBC file
                  --output <directory>         Report directory (default: target/compatibility-report)
                  --frame-samples <n>          Bounded CAN frame samples (default: 50)
                  --signal-samples <n>         Bounded CanonicalSignal samples (default: 100)
                  --matched-frame-samples <n>  Bounded DBC-matched frame samples (default: 50)
                  --exact-duplicates <true|false>  Exact duplicate identity map (default: true)
                """);
    }
}