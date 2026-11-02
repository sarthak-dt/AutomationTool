package com.stellar.platform.analysis.investigation.report;

import com.stellar.platform.analysis.investigation.EngineeringEvidenceItem;
import com.stellar.platform.analysis.investigation.EngineeringObservation;
import com.stellar.platform.analysis.investigation.EngineeringReportTemplate;
import com.stellar.platform.analysis.investigation.HtmlEscaper;
import com.stellar.platform.analysis.investigation.InvestigationHealth;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Headless HTML renderer. Offline: no CDN, no remote fonts, no JavaScript.
 * Renders an already-built {@link EngineeringReportModel}. It does not query stores.
 */
public final class EngineeringHtmlReportRenderer {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private EngineeringHtmlReportRenderer() {
    }

    public static String render(EngineeringReportModel model) {
        if (model != null && model.template() == EngineeringReportTemplate.ENGINEERING_V2) {
            return EngineeringReportV2Html.render(model);
        }
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\"/>");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>");
        html.append("<title>").append(esc(model.header() == null ? "Engineering Analysis Report" : model.header().title()))
                .append("</title><style>").append(css()).append("</style></head><body><main class=\"sheet\">");
        header(html, model);
        if (model.health() != InvestigationHealth.CURRENT) {
            html.append("<section class=\"warn\"><h2>Investigation health: ")
                    .append(esc(model.health().name())).append("</h2>");
            html.append("<p>Some attached evidence is historical. Re-run or keep the reference.</p></section>");
        }
        project(html, model);
        sources(html, model);
        definitions(html, model);
        processing(html, model);
        warnings(html, model);
        investigation(html, model);
        signals(html, model);
        signalAnalysis(html, model);
        routeMap(html, model);
        figures(html, model);
        results(html, "Analysis results", model.analysis());
        results(html, "Diagnostic findings", model.diagnostics());
        results(html, "Frequency-domain analysis", model.frequency());
        observations(html, model);
        conclusion(html, model);
        limitations(html, model);
        appendix(html, model);
        evidence(html, model);
        html.append("</main></body></html>");
        return html.toString();
    }

    private static void header(StringBuilder html, EngineeringReportModel model) {
        var h = model.header();
        html.append("<header>");
        html.append("<p class=\"brand\">StellarAI</p>");
        html.append("<h1>Engineering Analysis Report</h1>");
        if (h != null) {
            if (h.title() != null && !h.title().isBlank()) {
                html.append("<p class=\"report-title\">").append(esc(h.title())).append("</p>");
            }
            html.append("<table class=\"meta\">");
            row(html, "Project", h.projectName());
            if (h.investigationName() != null && !h.investigationName().isBlank()
                    && !h.investigationName().equals(h.title())) {
                row(html, "Investigation", h.investigationName());
            }
            row(html, "Generated", h.generatedAt() == null ? "" : TIME.format(h.generatedAt()));
            row(html, "Application version", h.applicationVersion());
            if (h.author() != null && !h.author().isBlank()) {
                row(html, "Author", h.author());
            }
            html.append("</table>");
        }
        html.append("</header>");
    }

    private static void project(StringBuilder html, EngineeringReportModel model) {
        if (model.project() == null) {
            return;
        }
        var p = model.project();
        section(html, "Project summary");
        html.append("<table>");
        row(html, "Project name", p.name());
        row(html, "Project status", p.processingStatus());
        row(html, "Measurement files", Integer.toString(p.measurementCount()));
        row(html, "DBC definitions", Integer.toString(p.definitionCount()));
        if (model.processing() != null && model.processing().status() != null
                && !model.processing().status().isBlank()) {
            row(html, "Processing result", model.processing().status());
        }
        int processed = p.measurementCount();
        if (model.processing() != null
                && (model.processing().successfulJobs() > 0 || model.processing().failedJobs() > 0)) {
            processed = model.processing().successfulJobs();
        }
        row(html, "Processed measurements", Integer.toString(processed));
        row(html, "Warnings", Integer.toString(p.warningCount()));
        if (model.processing() != null && model.processing().canFrames() > 0) {
            row(html, "CAN frames", Long.toString(model.processing().canFrames()));
        }
        row(html, "Catalog entries", Integer.toString(p.signalCount()));
        html.append("</table>");
        end(html);
    }

    private static void sources(StringBuilder html, EngineeringReportModel model) {
        if (model.sources().isEmpty()) {
            return;
        }
        boolean inventory = model.sources().stream().anyMatch(s -> s.fingerprintSummary() != null
                && s.fingerprintSummary().indexOf('\t') >= 0);
        section(html, "Data sources");
        html.append("<table><thead><tr><th>Measurement file</th><th>Format</th><th>Size</th><th>Processing status</th>");
        if (inventory) {
            html.append("<th>CAN channels</th><th>CAN frames</th>");
        } else {
            html.append("<th>Fingerprint</th>");
        }
        html.append("</tr></thead><tbody>");
        for (var s : model.sources()) {
            html.append("<tr><td>").append(esc(s.displayName())).append("</td><td>").append(esc(s.fileType()))
                    .append("</td><td>").append(esc(s.sizeLabel())).append("</td><td class=\"")
                    .append(statusClass(s.outcome())).append("\">").append(esc(s.outcome())).append("</td>");
            if (inventory) {
                String fp = s.fingerprintSummary() == null ? "" : s.fingerprintSummary();
                int tab = fp.indexOf('\t');
                String channels = tab < 0 ? fp : fp.substring(0, tab);
                String frames = tab < 0 ? "" : fp.substring(tab + 1);
                html.append("<td>").append(esc(channels)).append("</td><td>").append(esc(frames)).append("</td>");
            } else {
                html.append("<td>").append(esc(s.fingerprintSummary())).append("</td>");
            }
            html.append("</tr>");
        }
        html.append("</tbody></table>");
        end(html);
    }

    private static void definitions(StringBuilder html, EngineeringReportModel model) {
        if (model.definitions().isEmpty()) {
            return;
        }
        section(html, "Definitions");
        html.append("<table><thead><tr><th>Filename</th><th>Type</th><th>Status</th></tr></thead><tbody>");
        for (var d : model.definitions()) {
            html.append("<tr><td>").append(esc(d.filename())).append("</td><td>").append(esc(d.type()))
                    .append("</td><td>").append(esc(d.status())).append("</td></tr>");
        }
        html.append("</tbody></table>");
        end(html);
    }

    private static void processing(StringBuilder html, EngineeringReportModel model) {
        var p = model.processing();
        if (p == null) {
            return;
        }
        section(html, "Processing summary");
        html.append("<table>");
        row(html, "Processing status", p.status());
        row(html, "Successful measurements", Integer.toString(p.successfulJobs()));
        row(html, "Failed measurements", Integer.toString(p.failedJobs()));
        row(html, "Warning count", Integer.toString(p.warningCount()));
        if (p.finishedAt() != null && !p.finishedAt().isBlank()) {
            row(html, "Processing timestamp", p.finishedAt());
        }
        html.append("</table>");
        end(html);
    }

    private static void warnings(StringBuilder html, EngineeringReportModel model) {
        if (model.warnings().isEmpty()) {
            return;
        }
        section(html, "Warnings");
        html.append("<ul class=\"warn-list\">");
        for (String w : model.warnings()) {
            html.append("<li>").append(esc(w)).append("</li>");
        }
        html.append("</ul>");
        end(html);
    }

    private static void investigation(StringBuilder html, EngineeringReportModel model) {
        if (model.investigation() == null) {
            return;
        }
        var inv = model.investigation();
        section(html, "Investigation");
        html.append("<table>");
        row(html, "Title", inv.name());
        row(html, "Status", inv.status() == null ? "" : inv.status().name());
        if (model.investigationCreatedAt() != null) {
            row(html, "Created", TIME.format(model.investigationCreatedAt()));
        }
        if (model.investigationUpdatedAt() != null) {
            row(html, "Updated", TIME.format(model.investigationUpdatedAt()));
        }
        if (inv.timeRange() != null && !inv.timeRange().isBlank()) {
            row(html, "Time range", inv.timeRange());
        }
        if (inv.duration() != null && !inv.duration().isBlank()) {
            row(html, "Duration", inv.duration());
        }
        html.append("</table>");
        if (inv.description() != null && !inv.description().isBlank()) {
            html.append("<h3>Description</h3><p>").append(esc(inv.description())).append("</p>");
        }
        end(html);
    }

    private static void signals(StringBuilder html, EngineeringReportModel model) {
        if (model.signals().isEmpty()) {
            return;
        }
        boolean stats = model.signals().stream().anyMatch(s -> present(s.mean()) || present(s.rms()) || present(s.delta()));
        boolean labels = model.signals().stream().anyMatch(s -> present(s.categoricalLabel()));
        section(html, "Signals analysed");
        html.append("<table><thead><tr><th>Signal</th><th>Unit</th><th>Measurement</th>");
        if (labels) {
            html.append("<th>Label</th>");
        }
        html.append("<th>Min</th><th>Max</th>");
        if (stats) {
            html.append("<th>Mean</th><th>RMS</th><th>Delta</th>");
        }
        html.append("</tr></thead><tbody>");
        for (var s : model.signals()) {
            html.append("<tr><td>").append(esc(s.name())).append("</td><td>").append(esc(blankUnit(s.unit())))
                    .append("</td><td>").append(esc(s.source())).append("</td>");
            if (labels) {
                html.append("<td>").append(esc(present(s.categoricalLabel()) ? s.categoricalLabel() : "—")).append("</td>");
            }
            html.append("<td>").append(esc(s.min()))
                    .append("</td><td>").append(esc(s.max()));
            if (stats) {
                html.append("</td><td>").append(esc(s.mean()))
                        .append("</td><td>").append(esc(s.rms()))
                        .append("</td><td>").append(esc(s.delta()));
            }
            html.append("</td></tr>");
        }
        html.append("</tbody></table>");
        end(html);
    }

    private static void signalAnalysis(StringBuilder html, EngineeringReportModel model) {
        if (model.signalAnalysis() == null || model.signalAnalysis().isEmpty()) {
            return;
        }
        section(html, "Signal Analysis");
        for (SignalAnalysisSection visual : model.signalAnalysis()) {
            html.append("<figure class=\"report-figure\">");
            html.append("<h3>").append(esc(visual.title())).append("</h3>");
            if (visual.imageReference() != null && !visual.imageReference().isBlank()) {
                html.append("<a href=\"").append(esc(visual.imageReference())).append("\">");
                html.append("<img class=\"report-waveform\" src=\"").append(esc(visual.imageReference()))
                        .append("\" alt=\"").append(esc(visual.title())).append("\"/>");
                html.append("</a>");
            } else {
                String note = visual.unavailableNote() == null || visual.unavailableNote().isBlank()
                        ? SignalAnalysisSection.UNAVAILABLE
                        : visual.unavailableNote();
                html.append("<p>").append(esc(note)).append("</p>");
            }
            html.append("</figure>");
        }
        end(html);
    }

    private static void figures(StringBuilder html, EngineeringReportModel model) {
        if (model.figures().isEmpty()) {
            return;
        }
        section(html, "Evidence");
        for (var f : model.figures()) {
            html.append("<figure class=\"report-figure\">");
            html.append("<h3>").append(esc(f.caption().isBlank() ? "Captured evidence" : f.caption())).append("</h3>");
            if (f.assetRelativePath() != null && !f.assetRelativePath().isBlank()) {
                html.append("<img class=\"evidence-image\" src=\"").append(esc(f.assetRelativePath()))
                        .append("\" alt=\"").append(esc(f.caption())).append("\"/>");
            } else {
                html.append("<p class=\"muted\">Evidence image unavailable.</p>");
            }
            html.append("<figcaption>Figure ").append(f.number()).append(" — ").append(esc(f.caption()));
            if (f.timeRange() != null && !f.timeRange().isBlank()) {
                html.append(" (").append(esc(f.timeRange())).append(")");
            }
            html.append("</figcaption></figure>");
        }
        end(html);
    }

    private static void routeMap(StringBuilder html, EngineeringReportModel model) {
        EngineeringReportModel.RouteMapSection map = model.routeMap();
        if (map == null) {
            return;
        }
        section(html, map.title());
        if (map.imageReference() != null && !map.imageReference().isBlank()) {
            html.append("<figure class=\"report-figure\">");
            html.append("<a href=\"").append(esc(map.imageReference())).append("\">");
            html.append("<img class=\"report-map\" src=\"").append(esc(map.imageReference()))
                    .append("\" alt=\"Route map\"/>");
            html.append("</a>");
            if (map.additionalImages() != null && !map.additionalImages().isEmpty()) {
                html.append("<figcaption>Overview</figcaption>");
            }
            html.append("</figure>");
            int detail = 1;
            if (map.additionalImages() != null) {
                for (String extra : map.additionalImages()) {
                    if (extra == null || extra.isBlank()) {
                        continue;
                    }
                    html.append("<figure class=\"report-figure\">");
                    html.append("<a href=\"").append(esc(extra)).append("\">");
                    html.append("<img class=\"report-map\" src=\"").append(esc(extra))
                            .append("\" alt=\"Route detail ").append(detail).append("\"/>");
                    html.append("</a>");
                    html.append("<figcaption>Route detail ").append(detail++).append("</figcaption></figure>");
                }
            }
        } else {
            String note = map.unavailableNote() == null || map.unavailableNote().isBlank()
                    ? EngineeringReportModel.RouteMapSection.UNAVAILABLE
                    : map.unavailableNote();
            html.append("<p>").append(esc(note)).append("</p>");
        }
        RouteMapSummary summary = map.summary();
        if (summary != null && summary.projectWide()) {
            html.append("<p>Independent segments. No file-to-file connections.</p>");
        }
        html.append("<h3>Route summary</h3><table class=\"meta\">");
        if (summary != null && summary.described()) {
            if (summary.projectWide()) {
                row(html, "Measurements", Integer.toString(summary.measurementCount()));
                row(html, "GPS-capable", Integer.toString(summary.gpsCapableCount()));
                row(html, "Route segments", Integer.toString(summary.routeSegmentCount()));
            } else {
                row(html, "Measurement", summary.scopeLabel());
            }
            metric(html, "GPS samples", map.sampleCountLabel());
        } else {
            metric(html, "Start", map.startLabel());
            metric(html, "End", map.endLabel());
            metric(html, "Duration", map.durationLabel());
            metric(html, "GPS Points", map.sampleCountLabel());
        }
        metric(html, "Distance", map.distanceLabel());
        html.append("</table>");
        end(html);
    }

    private static void metric(StringBuilder html, String label, String value) {
        if (value != null && !value.isBlank()) {
            row(html, label, value);
        }
    }

    private static void observations(StringBuilder html, EngineeringReportModel model) {
        if (model.observations().isEmpty()) {
            return;
        }
        section(html, "Observations");
        html.append("<p class=\"muted\">User-authored. Not diagnostic engine output.</p>");
        for (EngineeringObservation o : model.observations()) {
            html.append("<h3>").append(esc(o.title().isBlank() ? "Engineer Observation" : o.title()))
                    .append("</h3><p>").append(esc(o.note())).append("</p>");
        }
        end(html);
    }

    private static void conclusion(StringBuilder html, EngineeringReportModel model) {
        boolean hasText = model.conclusion() != null && !model.conclusion().isBlank();
        if (!hasText && model.investigation() == null) {
            return;
        }
        section(html, "Engineering conclusion");
        html.append("<p class=\"muted\">User-authored. Not an automated diagnosis.</p>");
        if (hasText) {
            html.append("<p>").append(esc(model.conclusion())).append("</p>");
        } else {
            html.append("<p class=\"muted\">No conclusion recorded.</p>");
        }
        end(html);
    }

    private static void limitations(StringBuilder html, EngineeringReportModel model) {
        if (model.limitations().isEmpty()) {
            return;
        }
        section(html, "Limitations");
        html.append("<ul>");
        for (String item : model.limitations()) {
            html.append("<li>").append(esc(item)).append("</li>");
        }
        html.append("</ul>");
        end(html);
    }

    private static void appendix(StringBuilder html, EngineeringReportModel model) {
        if (model.appendix().isEmpty()) {
            return;
        }
        section(html, "Appendix");
        html.append("<ul>");
        for (String a : model.appendix()) {
            html.append("<li>").append(esc(a)).append("</li>");
        }
        html.append("</ul>");
        end(html);
    }

    private static void evidence(StringBuilder html, EngineeringReportModel model) {
        if (model.evidence().isEmpty()) {
            return;
        }
        html.append("<h2>Evidence order</h2><ol>");
        for (EngineeringEvidenceItem e : model.evidence()) {
            html.append("<li>");
            if (e.stale()) {
                html.append("<span class=\"stale\">STALE</span> ");
            }
            html.append(esc(e.kind().name())).append(" — ")
                    .append(esc(e.caption().isBlank() ? e.summary() : e.caption()));
            html.append("</li>");
        }
        html.append("</ol>");
    }

    private static void results(StringBuilder html, String title, List<EngineeringReportModel.ResultBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return;
        }
        section(html, title);
        for (var b : blocks) {
            html.append("<article>");
            html.append("<h3>").append(esc(b.heading()));
            if (b.stale()) {
                html.append(" <span class=\"stale\">STALE</span>");
            }
            html.append("</h3>");
            if (b.stale() && b.staleReason() != null && !b.staleReason().isBlank()) {
                html.append("<p class=\"warn\">").append(esc(b.staleReason())).append("</p>");
            }
            html.append("<pre>").append(esc(b.body())).append("</pre>");
            if (b.reproducibility() != null && !b.reproducibility().isBlank()) {
                html.append("<p class=\"muted\">").append(esc(b.reproducibility())).append("</p>");
            }
            html.append("</article>");
        }
        end(html);
    }

    private static void section(StringBuilder html, String title) {
        html.append("<section><h2>").append(esc(title)).append("</h2>");
    }

    private static void end(StringBuilder html) {
        html.append("</section>");
    }

    private static void row(StringBuilder html, String label, String value) {
        html.append("<tr><th>").append(esc(label)).append("</th><td");
        String cls = statusClass(value);
        if (!cls.isEmpty() && isStatusLabel(label)) {
            html.append(" class=\"").append(cls).append("\"");
        }
        html.append(">").append(esc(value)).append("</td></tr>");
    }

    private static boolean isStatusLabel(String label) {
        if (label == null) {
            return false;
        }
        String lower = label.toLowerCase(Locale.ROOT);
        return lower.contains("status") || lower.contains("result");
    }

    private static String statusClass(String status) {
        if (status == null) {
            return "";
        }
        String s = status.toLowerCase(Locale.ROOT);
        if (s.contains("fail")) {
            return "bad";
        }
        if (s.contains("warn")) {
            return "warn-text";
        }
        if (s.contains("complete") || s.contains("processed") || s.equals("ready")) {
            return "ok";
        }
        return "";
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank() && !"—".equals(value.trim());
    }

    private static String blankUnit(String unit) {
        return unit == null || unit.isBlank() ? "—" : unit;
    }

    private static String esc(String v) {
        return HtmlEscaper.escape(v);
    }

    static String css() {
        return """
                body{margin:0;background:#E8EEF5;color:#13243A;
                font-family:"Segoe UI","Helvetica Neue",Arial,sans-serif;font-size:13px;line-height:1.45;}
                .sheet{max-width:1180px;margin:28px auto;background:#fff;padding:32px 36px 48px;
                border:1px solid #D7E2EE;}
                header{border-bottom:3px solid #1F4E79;margin-bottom:8px;padding-bottom:12px;}
                .brand{margin:0;color:#1F4E79;font-weight:700;letter-spacing:0.04em;font-size:13px;}
                h1{margin:4px 0 8px;font-size:26px;color:#0E2A47;font-weight:650;}
                .report-title{margin:0 0 12px;font-size:16px;color:#1F4E79;}
                h2{font-size:16px;color:#0E2A47;margin:28px 0 8px;padding-bottom:4px;border-bottom:1px solid #D7E2EE;
                break-after:avoid;page-break-after:avoid;}
                h3{font-size:13px;margin:14px 0 6px;color:#1F4E79;break-after:avoid;page-break-after:avoid;}
                table{border-collapse:collapse;width:100%;margin:8px 0 4px;}
                th,td{border:1px solid #D7E2EE;padding:6px 8px;text-align:left;vertical-align:top;}
                th{background:#F4F7FB;color:#0E2A47;font-weight:600;}
                tr,figure{break-inside:avoid;page-break-inside:avoid;}
                table.meta{margin:8px 0 0;}
                table.meta th,table.meta td{border:none;padding:2px 8px 2px 0;background:transparent;}
                table.meta th{width:180px;font-weight:600;}
                .muted{color:#5C6E82;}
                .warn{background:#FFF6E8;border:1px solid #F0D7A8;padding:8px 12px;}
                .warn-list li{margin:4px 0;}
                .stale{color:#A12622;font-weight:700;font-size:11px;}
                .ok{color:#1B7F4E;font-weight:600;}
                .warn-text{color:#8A5A00;font-weight:600;}
                .bad{color:#A12622;font-weight:600;}
                pre{white-space:pre-wrap;background:#F7F9FC;padding:10px;border:1px solid #E1E8EF;}
                figure{margin:16px 0 8px;}
                img{max-width:100%;height:auto;border:1px solid #D7E2EE;background:#fff;}
                img.report-waveform,img.report-map{max-width:100%;width:auto;height:auto;max-height:220mm;}
                img.evidence-image{max-width:100%;height:auto;}
                figcaption{font-size:12px;color:#253A52;margin-top:6px;}
                @media print{
                body{margin:0;background:#fff;color:#000;}
                .sheet{margin:0;max-width:none;border:none;padding:0;}
                table,tr,figure{page-break-inside:avoid;}
                h2,h3{page-break-after:avoid;}
                img.evidence-image{max-width:100%;max-height:140mm;height:auto;}
                img.report-waveform,img.report-map{max-width:100%;width:auto;height:auto;max-height:180mm;}
                .warn{background:#fff;border:1px solid #ccc;}
                a{color:inherit;text-decoration:none;}
                }
                """;
    }
}
