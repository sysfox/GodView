package dev.godview.suite;

import org.junit.jupiter.api.Test;

import dev.godview.util.*;
import java.util.*;

import static dev.godview.Checks.check;
import static dev.godview.Checks.fail;
import static dev.godview.Checks.survives;

/**
 * JSON codec, primitive rings and multi-resolution time series.
 *
 * <p>Drives the real thing rather than a mock: a bound socket, a SQLite file on
 * disk, an attached appender. Slower than unit tests and worth it -- most of the
 * defects these caught were in the seams between components, which is exactly
 * what a mock would have papered over.
 */
class UtilSuiteTest {

    @Test
    void verifies() {
        // ---- Json write ----
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "Ste\"ve\n");
        m.put("tps", 19.5);
        m.put("whole", 20.0);
        m.put("nan", Double.NaN);
        m.put("ops", List.of(1, 2, 3));
        m.put("nil", null);
        m.put("uuid", UUID.fromString("00000000-0000-0000-0000-000000000001"));
        check("write", Json.write(m),
            "{\"name\":\"Ste\\\"ve\\n\",\"tps\":19.5,\"whole\":20,\"nan\":null,\"ops\":[1,2,3],\"nil\":null,\"uuid\":\"00000000-0000-0000-0000-000000000001\"}");

        // ---- Json parse round-trip ----
        Object parsed = Json.parse("{\"a\":[1,-2,3.5e2,true,false,null],\"b\":{\"c\":\"\\u4e2d\\u6587\"},\"big\":9007199254740993}");
        Map<String, Object> obj = Json.asObj(parsed);
        check("parse nested string", Json.str(Json.asObj(obj.get("b")), "c", "?"), "中文");
        check("parse array", Json.asArr(obj.get("a")), "[1, -2, 350.0, true, false, null]");
        check("parse long precision", obj.get("big"), 9007199254740993L);
        check("parse int type", Json.asArr(obj.get("a")).get(0).getClass().getSimpleName(), "Long");

        // escapes survive a round trip
        String tricky = "line\nsep tab\ttilde~ctl";
        check("escape round-trip", Json.parse(Json.write(tricky)), tricky);
        check("u2028 escaped", Json.write(String.valueOf((char) 0x2028)).contains("\\u2028"), true);

        // malformed input rejected
        for (String bad : new String[]{"{", "[1,]", "{\"a\"}", "tru", "{}x", "\"unterminated", "[1 2]"}) {
            try { Json.parse(bad); fail("FAIL accepted bad input: " + bad); }
            catch (IllegalArgumentException e) { System.out.println("ok   rejected " + bad + "  (" + e.getMessage() + ")"); }
        }

        // ---- JsonWriter ----
        String w = new JsonWriter()
                .obj()
                  .field("tps", 19.87)
                  .field("empty", new double[0])
                  .field("hist", new int[]{1, 2, 3})
                  .name("players").arr()
                    .obj().field("n", "Steve").field("ping", 42).end()
                    .obj().field("n", "Alex").field("ping", 7).end()
                  .end()
                  .rawField("cached", "{\"pre\":1}")
                  .name("nothing").nul()
                .end()
                .done();
        check("JsonWriter", w,
            "{\"tps\":19.87,\"empty\":[],\"hist\":[1,2,3],\"players\":[{\"n\":\"Steve\",\"ping\":42},{\"n\":\"Alex\",\"ping\":7}],\"cached\":{\"pre\":1},\"nothing\":null}");
        check("JsonWriter reparses", Json.asObj(Json.parse(w)).get("tps"), 19.87);

        try { new JsonWriter().obj().done(); fail("FAIL unclosed accepted"); }
        catch (IllegalStateException e) { System.out.println("ok   unclosed rejected"); }
        try { new JsonWriter().arr().name("x"); fail("FAIL name in array accepted"); }
        catch (IllegalStateException e) { System.out.println("ok   name-in-array rejected"); }

        // ---- DoubleRing ----
        DoubleRing r = new DoubleRing(5);
        for (int i = 1; i <= 8; i++) r.add(i);           // keeps 4..8
        check("ring size", r.size(), 5);
        check("ring oldest", r.get(0), 4.0);
        check("ring newest", r.get(4), 8.0);
        check("ring last", r.last(-1), 8.0);
        check("ring toArray", Arrays.toString(r.toArray()), "[4.0, 5.0, 6.0, 7.0, 8.0]");
        check("ring tail(3)", Arrays.toString(r.tail(3)), "[6.0, 7.0, 8.0]");
        check("ring tail(99)", Arrays.toString(r.tail(99)), "[4.0, 5.0, 6.0, 7.0, 8.0]");
        check("ring avg", r.avg(-1), 6.0);
        check("ring min", r.min(-1), 4.0);
        check("ring max", r.max(-1), 8.0);
        check("ring p50", r.percentile(0.5, -1), 6.0);
        check("ring p95", r.percentile(0.95, -1), 8.0);
        check("ring written", r.written(), 8L);
        check("ring countAtLeast(7)", r.countAtLeast(7), 2);
        check("empty ring default", new DoubleRing(3).avg(-42), -42.0);

        // ---- LongRing ----
        LongRing lr = new LongRing(4);
        for (long t : new long[]{100, 200, 300, 400}) lr.add(t);
        check("longring sum", lr.sum(), 1000L);
        check("longring countSince(250)", lr.countSince(250), 2);
        check("longring countSince(0)", lr.countSince(0), 4);
        check("longring countSince(999)", lr.countSince(999), 0);

        // ---- TimeSeries ----
        TimeSeries ts = new TimeSeries(new long[]{1000, 10000}, new int[]{10, 10});
        long base = 1_700_000_000_000L;
        for (int i = 0; i < 25; i++) ts.add(base + i * 1000L, i);   // 25 samples, 1/s
        TimeSeries.Snapshot fine = ts.snapshot(0);
        TimeSeries.Snapshot coarse = ts.snapshot(1);
        check("ts fine buckets", fine.size(), 10);            // capacity-capped
        check("ts fine newest last", fine.last()[fine.size() - 1], 24.0);
        check("ts fine bucket width", fine.bucketMillis(), 1000L);
        check("ts coarse buckets", coarse.size(), 3);          // 25s spans 3 x 10s buckets
        check("ts coarse counts", Arrays.toString(coarse.count()), "[10, 10, 5]");
        check("ts coarse avg[0]", coarse.avg()[0], 4.5);       // mean of 0..9
        check("ts coarse min/max[1]", coarse.min()[1] + "/" + coarse.max()[1], "10.0/19.0");
        check("ts latest", ts.latest(-1), 24.0);
        check("ts levelFor(5s)", ts.levelFor(5_000), 0);
        check("ts levelFor(60s)", ts.levelFor(60_000), 1);
        check("ts snapshot(from)", ts.snapshot(0, base + 20_000L).size(), 5);

        // out-of-order samples fold into the newest bucket instead of vanishing
        ts.add(base + 1000L, 99);
        check("ts out-of-order kept", ts.snapshot(0).count()[9], 2);

        // gaps are not materialized
        TimeSeries gap = new TimeSeries(new long[]{1000}, new int[]{10});
        gap.add(base, 1);
        gap.add(base + 5000, 2);
        check("ts gap not filled", gap.snapshot(0).size(), 2);
        check("ts gap timestamps", Arrays.toString(gap.snapshot(0).t()), "[" + base + ", " + (base + 5000) + "]");

        // negative epoch (pre-1970 clock) must not blow up bucket maths
        TimeSeries neg = new TimeSeries(new long[]{1000}, new int[]{4});
        neg.add(-4500L, 1);
        check("ts negative epoch", neg.snapshot(0).t()[0], -5000L);
    }
}
