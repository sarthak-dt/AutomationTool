package com.stellar.platform.parser.mf4;

import com.stellar.platform.parser.mf4.report.MdfInspectionReport;
import com.stellar.platform.parser.mf4.report.MdfInspectionReportFormatter;
import com.stellar.platform.parser.spi.ParserException;

import java.nio.file.Path;

/**
 * Development CLI for MF4 inspection (not a JavaFX concern).
 *
 * <pre>
 * inspect-mf4 &lt;absolute-file-path&gt;
 * </pre>
 */
public final class InspectMf4Main {

    private InspectMf4Main() {
    }

    public static void main(String[] args) {
        if (args.length == 0 || "-h".equals(args[0]) || "--help".equals(args[0])) {
            System.out.println("Usage: inspect-mf4 <absolute-file-path>");
            System.out.println("   or: java ... InspectMf4Main <absolute-file-path>");
            return;
        }

        int pathIndex = 0;
        if ("inspect-mf4".equalsIgnoreCase(args[0])) {
            if (args.length < 2) {
                System.err.println("Usage: inspect-mf4 <absolute-file-path>");
                System.exit(2);
                return;
            }
            pathIndex = 1;
        }

        Path path = Path.of(args[pathIndex]);
        try {
            MdfInspectionReport report = new Mf4Inspector().inspect(path);
            System.out.print(MdfInspectionReportFormatter.formatSummary(report));
        } catch (ParserException e) {
            System.err.println("MF4 inspection failed: " + e.getMessage());
            System.exit(1);
        }
    }
}
