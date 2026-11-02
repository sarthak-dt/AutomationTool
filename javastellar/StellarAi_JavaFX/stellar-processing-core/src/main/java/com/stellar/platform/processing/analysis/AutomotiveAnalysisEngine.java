package com.stellar.platform.processing.analysis;

import com.stellar.platform.processing.graph.GraphBucket;
import com.stellar.platform.processing.graph.GraphRenderSeries;

import java.util.*;

/**
 * Renderer-neutral Phase 13/14 analysis over bounded graph representations.
 * It never mutates canonical samples. Findings are evidence-backed observations,
 * and rules are supplied as data rather than embedded signal-specific Java branches.
 */
public final class AutomotiveAnalysisEngine {
    private AutomotiveAnalysisEngine() {}

    public enum Operator { GT, GTE, LT, LTE, EQ, NE }
    public enum Severity { INFO, LOW, MEDIUM, HIGH, CRITICAL }

    public record RuleCondition(String signalName, Operator operator, double threshold) {
        public RuleCondition { Objects.requireNonNull(signalName); Objects.requireNonNull(operator); }
        boolean matches(double value) { return switch (operator) {
            case GT -> value > threshold; case GTE -> value >= threshold;
            case LT -> value < threshold; case LTE -> value <= threshold;
            case EQ -> Double.compare(value, threshold) == 0; case NE -> Double.compare(value, threshold) != 0;
        }; }
    }

    public record DiagnosticRule(String id, String version, String title, Severity severity,
                                 List<RuleCondition> allConditions, boolean enabled, double confidence) {
        public DiagnosticRule {
            Objects.requireNonNull(id); Objects.requireNonNull(version); Objects.requireNonNull(title);
            Objects.requireNonNull(severity); allConditions = List.copyOf(allConditions == null ? List.of() : allConditions);
            confidence = Math.max(0, Math.min(1, confidence));
        }
    }

    public record Evidence(String signal, long timestampNanos, double value, String condition) {}
    public record DiagnosticFinding(String ruleId, String ruleVersion, String title, Severity severity,
                                    double confidence, long timestampNanos, List<Evidence> evidence, String explanation) {
        public DiagnosticFinding { evidence = List.copyOf(evidence); }
    }

    public record SignalFeatureSet(String signal, String unit, long representedSamples,
                                   double min, double max, double mean, double median, double variance,
                                   double standardDeviation, double rms, double p05, double p95,
                                   double slopePerSecond, double driftPerMinute, double meanAbsRatePerSecond,
                                   int peakCount, int valleyCount, int stepChangeCount, int outlierCount,
                                   double jitter, double oscillationHz) {}

    public static List<SignalFeatureSet> extractFeatures(List<GraphRenderSeries> series) {
        if (series == null || series.isEmpty()) return List.of();
        ArrayList<SignalFeatureSet> out = new ArrayList<>();
        for (GraphRenderSeries s : series) {
            List<Point> p = points(s); if (p.isEmpty()) continue;
            double[] v = p.stream().mapToDouble(Point::value).toArray(); Arrays.sort(v);
            double min=v[0], max=v[v.length-1], mean=mean(v), median=percentile(v,.5), variance=variance(v,mean);
            double sd=Math.sqrt(variance), rms=Math.sqrt(Arrays.stream(v).map(x->x*x).average().orElse(0));
            double slope=slope(p), mar=meanAbsRate(p), jitter=jitter(p), span=Math.max(1e-12,max-min);
            int peaks=0,valleys=0,steps=0,outliers=0; double stepThreshold=span*.25;
            for(int i=1;i<p.size()-1;i++){double a=p.get(i-1).value,b=p.get(i).value,c=p.get(i+1).value;if(b>a&&b>c)peaks++;if(b<a&&b<c)valleys++;}
            for(int i=1;i<p.size();i++)if(Math.abs(p.get(i).value-p.get(i-1).value)>=stepThreshold)steps++;
            if(sd>0) for(double x:v) if(Math.abs(x-mean)>3*sd) outliers++;
            double seconds=(p.get(p.size()-1).time-p.get(0).time)/1e9; double hz=seconds>0?peaks/seconds:0;
            long represented=s.buckets().stream().mapToLong(GraphBucket::sampleCount).sum();
            out.add(new SignalFeatureSet(s.key().signalName(),s.unit(),represented,min,max,mean,median,variance,sd,rms,
                    percentile(v,.05),percentile(v,.95),slope,slope*60,mar,peaks,valleys,steps,outliers,jitter,hz));
        }
        return List.copyOf(out);
    }

    /** Evaluate configurable multi-signal threshold rules at time-aligned represented samples. */
    public static List<DiagnosticFinding> evaluateRules(List<GraphRenderSeries> series, List<DiagnosticRule> rules) {
        if(series==null||rules==null||series.isEmpty()||rules.isEmpty()) return List.of();
        Map<String,List<Point>> bySignal=new HashMap<>(); long start=Long.MAX_VALUE,end=Long.MIN_VALUE;
        for(GraphRenderSeries s:series){List<Point> p=points(s);bySignal.put(s.key().signalName(),p);if(!p.isEmpty()){start=Math.min(start,p.get(0).time);end=Math.max(end,p.get(p.size()-1).time);}}
        if(start>end)return List.of(); long tolerance=Math.max(1,(end-start)/250);
        ArrayList<DiagnosticFinding> findings=new ArrayList<>();
        for(DiagnosticRule rule:rules){if(!rule.enabled()||rule.allConditions().isEmpty())continue;
            List<Point> anchor=bySignal.getOrDefault(rule.allConditions().get(0).signalName(),List.of());
            for(Point a:anchor){ArrayList<Evidence> evidence=new ArrayList<>();boolean ok=true;
                for(RuleCondition c:rule.allConditions()){Point n=nearest(bySignal.getOrDefault(c.signalName(),List.of()),a.time,tolerance);if(n==null||!c.matches(n.value)){ok=false;break;}evidence.add(new Evidence(c.signalName(),n.time,n.value,c.operator()+" "+c.threshold()));}
                if(ok){findings.add(new DiagnosticFinding(rule.id(),rule.version(),rule.title(),rule.severity(),rule.confidence(),a.time,evidence,
                        "Triggered because all configured conditions matched represented samples near "+String.format("%.6f s",a.time/1e9)));break;}
            }
        }
        findings.sort(Comparator.comparing(DiagnosticFinding::severity).reversed().thenComparingLong(DiagnosticFinding::timestampNanos));
        return List.copyOf(findings);
    }

    private static List<Point> points(GraphRenderSeries s){ArrayList<Point> p=new ArrayList<>();for(GraphBucket b:s.buckets()){if(b.firstUsable())p.add(new Point(b.firstTimestampNanos(),b.firstValue()));if(b.lastTimestampNanos()!=b.firstTimestampNanos()&&b.lastUsable())p.add(new Point(b.lastTimestampNanos(),b.lastValue()));}p.sort(Comparator.comparingLong(Point::time));return p;}
    private static Point nearest(List<Point> p,long t,long tolerance){if(p.isEmpty())return null;int lo=0,hi=p.size()-1;while(lo<hi){int m=(lo+hi)/2;if(p.get(m).time<t)lo=m+1;else hi=m;}Point best=p.get(lo);if(lo>0&&Math.abs(p.get(lo-1).time-t)<Math.abs(best.time-t))best=p.get(lo-1);return Math.abs(best.time-t)<=tolerance?best:null;}
    private static double mean(double[]v){return Arrays.stream(v).average().orElse(Double.NaN);}
    private static double variance(double[]v,double mean){double x=0;for(double d:v){double q=d-mean;x+=q*q;}return v.length==0?Double.NaN:x/v.length;}
    private static double percentile(double[]v,double q){if(v.length==0)return Double.NaN;double x=q*(v.length-1);int a=(int)Math.floor(x),b=(int)Math.ceil(x);return v[a]+(v[b]-v[a])*(x-a);}
    private static double slope(List<Point>p){if(p.size()<2)return 0;double t0=p.get(0).time/1e9,mt=0,mv=0;for(Point x:p){mt+=x.time/1e9-t0;mv+=x.value;}mt/=p.size();mv/=p.size();double num=0,den=0;for(Point x:p){double t=x.time/1e9-t0;num+=(t-mt)*(x.value-mv);den+=(t-mt)*(t-mt);}return den==0?0:num/den;}
    private static double meanAbsRate(List<Point>p){double sum=0;int n=0;for(int i=1;i<p.size();i++){double dt=(p.get(i).time-p.get(i-1).time)/1e9;if(dt>0){sum+=Math.abs(p.get(i).value-p.get(i-1).value)/dt;n++;}}return n==0?0:sum/n;}
    private static double jitter(List<Point>p){if(p.size()<3)return 0;double[]dt=new double[p.size()-1];for(int i=1;i<p.size();i++)dt[i-1]=(p.get(i).time-p.get(i-1).time)/1e9;double m=mean(dt);return Math.sqrt(variance(dt,m));}
    private record Point(long time,double value){}
}
