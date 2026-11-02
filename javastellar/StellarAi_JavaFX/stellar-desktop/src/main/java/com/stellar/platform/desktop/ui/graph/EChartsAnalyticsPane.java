package com.stellar.platform.desktop.ui.graph;

import com.stellar.platform.desktop.ui.theme.StellarTheme;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.graph.GraphBucket;
import com.stellar.platform.processing.graph.GraphRenderSeries;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.layout.Region;
import javafx.scene.web.WebView;
import netscape.javascript.JSObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;

/**
 * Offline ECharts engineering analytics.
 *
 * <p>WebKit is created only when Analytics is selected. ECharts/HTML is loaded once per active
 * WebView and viewport changes update the existing chart through JavaScript instead of repeatedly
 * rebuilding the entire WebEngine document.</p>
 */
public final class EChartsAnalyticsPane extends Region {
    private WebView webView;
    private String echartsJs;
    private LongConsumer drillDown = ignored -> { };
    private List<GraphRenderSeries> current = List.of();
    private Map<SignalCatalogKey, String> labels = Map.of();
    private String scopeNote = "";
    private boolean active;
    private boolean pageReady;
    private int layoutCount;

    public EChartsAnalyticsPane() {
        setMinHeight(320);
    }

    public void setDrillDownHandler(LongConsumer handler) {
        drillDown = handler == null ? ignored -> { } : handler;
    }

    public void setSeries(List<GraphRenderSeries> series) {
        setScopedSeries(series, Map.of(), "");
    }

    public void setScopedSeries(List<GraphRenderSeries> series, Map<SignalCatalogKey, String> labels, String scopeNote) {
        current = series == null ? List.of() : List.copyOf(series);
        this.labels = labels == null ? Map.of() : Map.copyOf(labels);
        this.scopeNote = scopeNote == null ? "" : scopeNote;
        if (active) renderCurrent();
    }

    public String modelForTest() {
        return dataObject(current);
    }

    /**
     * Graph Clear: drop large option/data payloads without destroying WebView/HTML.
     * Uses the existing updateStellarData empty payload (equivalent to clearing the chart model).
     */
    public void clearChartData() {
        current = List.of();
        labels = Map.of();
        scopeNote = "";
        if (webView == null || !pageReady) {
            return;
        }
        try {
            webView.getEngine().executeScript(
                    "window.updateStellarData({cats:[],actual:[],coverage:[],pts:[]});");
        } catch (RuntimeException ignored) {
            // Tab/page transition can invalidate WebKit between schedule and execution.
        }
    }

    /** Materializes WebKit only when the Analytics tab is actually selected. */
    public void activate() {
        active = true;
        ensureWebView();
        renderCurrent();
    }

    public void deactivate() {
        active = false;
    }

    /** Drop native WebKit references when the Graph page is left. */
    public void release() {
        active = false;
        pageReady = false;
        current = List.of();
        if (webView != null) {
            try { webView.getEngine().load(null); } catch (RuntimeException ignored) { }
            getChildren().clear();
            webView = null;
        }
        echartsJs = null;
    }

    boolean webViewMaterializedForTest() {
        return webView != null;
    }

    @Override
    protected void layoutChildren() {
        layoutCount++;
        if (webView != null) webView.resizeRelocate(0, 0, getWidth(), getHeight());
    }

    public int layoutCountForTest() {
        return layoutCount;
    }

    private void ensureWebView() {
        if (webView != null) return;
        WebView created = new WebView();
        created.setContextMenuEnabled(false);
        created.setFocusTraversable(false);
        created.getEngine().getLoadWorker().stateProperty().addListener((o, a, b) -> {
            if (b == Worker.State.SUCCEEDED && created == webView) {
                JSObject window = (JSObject) created.getEngine().executeScript("window");
                window.setMember("stellarBridge", new Bridge());
                pageReady = true;
                renderCurrent();
            }
        });
        webView = created;
        getChildren().setAll(created);
        if (echartsJs == null) echartsJs = readEcharts();
        created.getEngine().loadContent(baseHtml(echartsJs));
        requestLayout();
    }

    private void renderCurrent() {
        if (!active) return;
        ensureWebView();
        if (!pageReady || webView == null) return;
        try {
            webView.getEngine().executeScript("window.updateStellarData(" + dataObject(current) + ");");
        } catch (RuntimeException ignored) {
            // A tab/page transition can invalidate WebKit between scheduling and execution.
        }
    }

    private String dataObject(List<GraphRenderSeries> all) {
        StringBuilder categories = new StringBuilder("[");
        StringBuilder actualRanges = new StringBuilder("[");
        StringBuilder coverage = new StringBuilder("[");
        StringBuilder scatter = new StringBuilder("[");
        boolean first = true;
        boolean firstPoint = true;
        int index = 0;
        for (GraphRenderSeries s : all) {
            double min = s.buckets().stream().filter(GraphBucket::minUsable).mapToDouble(GraphBucket::minValue).min().orElse(Double.NaN);
            double max = s.buckets().stream().filter(GraphBucket::maxUsable).mapToDouble(GraphBucket::maxValue).max().orElse(min);
            String categorical = s.buckets().stream().map(GraphBucket::categoricalLabel).filter(label -> label != null && !label.isBlank()).findFirst().orElse(null);
            double span = Double.isFinite(min) && Double.isFinite(max) ? Math.max(1e-12, max - min) : Double.NaN;
            if (!first) { categories.append(','); actualRanges.append(','); coverage.append(','); }
            first = false;
            String category = labels.get(s.key());
            if (category == null || category.isBlank()) {
                category = s.key().signalName();
            }
            categories.append(js(shortName(category)));
            long viewportSamples = s.buckets().stream().mapToLong(GraphBucket::sampleCount).sum();
            actualRanges.append('[').append(jsNum(min)).append(',').append(jsNum(max))
                    .append(',').append(js(s.unit())).append(',')
                    .append(viewportSamples).append(',').append(s.sourceSampleCount()).append(',')
                    .append(js(categorical == null ? "" : categorical)).append(']');
            coverage.append(viewportSamples);
            for (GraphBucket b : s.buckets()) {
                if (!Double.isFinite(span) || !b.minUsable() || !b.maxUsable()) continue;
                double nmin = (b.minValue() - min) / span * 100.0;
                double nmax = (b.maxValue() - min) / span * 100.0;
                if (!firstPoint) scatter.append(',');
                firstPoint = false;
                scatter.append('[').append(b.minTimestampNanos() / 1e9).append(',').append(nmin).append(',')
                        .append(js(category)).append(',').append(b.minTimestampNanos()).append(',')
                        .append(b.minValue()).append(',').append(js(s.unit())).append(',').append(index).append(']');
                scatter.append(',').append('[').append(b.maxTimestampNanos() / 1e9).append(',').append(nmax).append(',')
                        .append(js(category)).append(',').append(b.maxTimestampNanos()).append(',')
                        .append(b.maxValue()).append(',').append(js(s.unit())).append(',').append(index).append(']');
            }
            index++;
        }
        categories.append(']'); actualRanges.append(']'); coverage.append(']'); scatter.append(']');
        return "{scope:" + js(scopeNote) + ",cats:" + categories + ",actual:" + actualRanges + ",coverage:" + coverage + ",pts:" + scatter + "}";
    }

    private static String baseHtml(String echartsJs) {
        String surface = StellarTheme.css(StellarTheme.SURFACE);
        String text = StellarTheme.css(StellarTheme.TEXT);
        String muted = StellarTheme.css(StellarTheme.TEXT_MUTED);
        String border = StellarTheme.css(StellarTheme.BORDER);
        String grid = "#DBE5EC";
        return "<html><head><meta charset='utf-8'><style>" +
                "html,body,#c{margin:0;width:100%;height:100%;background:" + surface + ";color:" + text + ";font-family:" + StellarTheme.FONT_UI + "}" +
                "</style><script>" + echartsJs + "</script></head><body><div id='c'></div><script>" +
                "const chart=echarts.init(document.getElementById('c'));" +
                "window.updateStellarData=d=>{const cats=d.cats||[],actual=d.actual||[],coverage=d.coverage||[],pts=d.pts||[];" +
                "const maxCoverage=Math.max(1,...coverage);const coveragePct=coverage.map(v=>v/maxCoverage*100);" +
                "chart.setOption({backgroundColor:'" + surface + "',animation:false," +
                "tooltip:{trigger:'item',backgroundColor:'" + surface + "',borderColor:'" + border + "',textStyle:{color:'" + text + "'},formatter:p=>{if(p.seriesName==='Viewport coverage'){const r=actual[p.dataIndex];const label=r[5]||'';const minText=label?'n/a':r[0];const maxText=label?'n/a':r[1];return '<b>'+cats[p.dataIndex]+'</b>'+(label?'<br/>Label: '+label:'')+'<br/>Min: '+minText+' '+(label?'':r[2])+'<br/>Max: '+maxText+' '+(label?'':r[2])+'<br/>Viewport samples: '+r[3].toLocaleString()+'<br/>Source samples: '+r[4].toLocaleString();}if(p.seriesName==='Extrema'){return '<b>'+p.data[2]+'</b><br/>Time: '+p.data[0].toFixed(6)+' s<br/>Value: '+p.data[4]+' '+p.data[5];}return '';}}," +
                "title:[{text:'Viewport signal coverage',subtext:((d.scope&&d.scope.length)?('Measurement: '+d.scope+' · '):'')+'Relative samples in current viewport · tooltip shows physical Min / Max',left:58,top:8,textStyle:{color:'" + text + "',fontSize:13},subtextStyle:{color:'" + muted + "'}},{text:'Normalized extrema — percent of each signal, not engineering units',left:58,top:'49%',textStyle:{color:'" + text + "',fontSize:13}}]," +
                "grid:[{left:78,right:34,top:82,height:'24%',containLabel:true},{left:78,right:42,top:'60%',bottom:58,containLabel:true}]," +
                "xAxis:[{type:'category',data:cats,axisLabel:{color:'" + muted + "',interval:0,rotate:cats.length>5?25:0}},{type:'value',gridIndex:1,name:'Time (s)',nameTextStyle:{color:'" + muted + "'},axisLabel:{color:'" + muted + "'}}]," +
                "yAxis:[{type:'value',min:0,max:100,name:'Relative samples',axisLabel:{color:'" + muted + "',formatter:'{value}%'},splitLine:{lineStyle:{color:'" + grid + "'}}},{type:'value',gridIndex:1,min:0,max:100,name:'Normalized %',axisLabel:{color:'" + muted + "',formatter:'{value}%'},splitLine:{lineStyle:{color:'" + grid + "'}}}]," +
                "series:[{name:'Viewport coverage',type:'bar',data:coveragePct,itemStyle:{color:'#0f8a96'},barMaxWidth:52},{name:'Extrema',type:'scatter',xAxisIndex:1,yAxisIndex:1,itemStyle:{color:'#52667a'},data:pts,symbolSize:5}]},true);};" +
                "chart.on('click',p=>{if(p.seriesName==='Extrema'&&p.data&&window.stellarBridge){window.stellarBridge.drillDown(String(Math.trunc(p.data[3])));}});" +
                "window.addEventListener('resize',()=>chart.resize());window.updateStellarData({cats:[],actual:[],coverage:[],pts:[]});" +
                "</script></body></html>";
    }

    private static String shortName(String s) {
        if (s == null) return "";
        return s.length() > 64 ? s.substring(0, 61) + "…" : s;
    }

    private static String jsNum(double value) {
        return Double.isFinite(value) ? Double.toString(value) : "null";
    }

    private static String js(String s) {
        return "'" + (s == null ? "" : s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ")) + "'";
    }

    private static String readEcharts() {
        String path = "/META-INF/resources/webjars/echarts/6.1.0/dist/echarts.min.js";
        try (InputStream in = EChartsAnalyticsPane.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Packaged ECharts 6.1.0 resource missing");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load packaged ECharts", e);
        }
    }

    public final class Bridge {
        public void drillDown(String nanos) {
            try {
                long t = Long.parseLong(nanos);
                Platform.runLater(() -> drillDown.accept(t));
            } catch (NumberFormatException ignored) { }
        }
    }
}
