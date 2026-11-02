package com.stellar.platform.testkit.compat;

/** Developer CLI for bounded MF4+DBC compatibility reports. Diagnostic only. */
public final class ParserCompatibilityHarness {
    private ParserCompatibilityHarness() {}
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            HarnessOptions.printUsage();
            System.exit(2);
        }
        HarnessOptions options = HarnessOptions.parse(args);
        CompatibilityHarnessRunner.run(options);
        System.out.println("Compatibility reports written to: " + options.outputDir().toAbsolutePath());
    }
}