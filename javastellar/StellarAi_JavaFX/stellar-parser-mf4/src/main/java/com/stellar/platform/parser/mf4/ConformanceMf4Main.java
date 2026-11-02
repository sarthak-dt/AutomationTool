package com.stellar.platform.parser.mf4;

import com.stellar.platform.parser.mf4.report.MdfConformanceAuditor;
import com.stellar.platform.parser.mf4.report.MdfConformanceReport;
import com.stellar.platform.parser.mf4.report.MdfConformanceReportFormatter;
import com.stellar.platform.parser.spi.ParserException;

import java.nio.file.Path;

/**
 * Diagnostic CLI for MF4 conformance reports (not JavaFX / not DBC).
 * <p>
 * Path resolution order:
 * <ol>
 *   <li>command-line argument</li>
 *   <li>system property {@code stellar.mf4.conformance.path}</li>
 *   <li>environment variable {@code STELLAR_MF4_CONFORMANCE_PATH}</li>
 * </ol>
 */
public final class ConformanceMf4Main {

    public static final String SYSTEM_PROPERTY = "stellar.mf4.conformance.path";
    public static final String ENV_VARIABLE = "STELLAR_MF4_CONFORMANCE_PATH";

    private ConformanceMf4Main() {
    }

    public static void main(String[] args) {
        Path path = resolvePath(args);
        if (path == null) {
            System.err.println("Usage: ConformanceMf4Main <mf4-path>");
            System.err.println("   or: -D" + SYSTEM_PROPERTY + "=<mf4-path>");
            System.err.println("   or: set " + ENV_VARIABLE + "=<mf4-path>");
            System.exit(2);
            return;
        }
        try {
            MdfConformanceReport report = new MdfConformanceAuditor().audit(path);
            System.out.print(MdfConformanceReportFormatter.formatSummary(report));
        } catch (ParserException e) {
            System.err.println("MF4 conformance audit failed: " + e.getMessage());
            System.exit(1);
        }
    }

    public static Path resolvePath(String[] args) {
        if (args != null && args.length > 0 && args[0] != null && !args[0].isBlank()) {
            return Path.of(args[0]);
        }
        String prop = System.getProperty(SYSTEM_PROPERTY);
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop);
        }
        String env = System.getenv(ENV_VARIABLE);
        if (env != null && !env.isBlank()) {
            return Path.of(env);
        }
        return null;
    }
}
