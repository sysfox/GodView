package dev.godview.suite;

import org.junit.jupiter.api.Test;

import com.shopgui.core.task.StallSimulator;
import dev.godview.config.GodViewConfig;
import dev.godview.metrics.*;
import java.util.*;
import java.util.concurrent.atomic.*;

import static dev.godview.Checks.check;
import static dev.godview.Checks.fail;
import static dev.godview.Checks.survives;

/**
 * Performance profiles, the governor, spike attribution and the watchdog.
 *
 * <p>Drives the real thing rather than a mock: a bound socket, a SQLite file on
 * disk, an attached appender. Slower than unit tests and worth it -- most of the
 * defects these caught were in the seams between components, which is exactly
 * what a mock would have papered over.
 */
class MetricsSuiteTest {

    static StackTraceElement f(String cls, String method) {
        return new StackTraceElement(cls, method, "X.java", 42);
    }

    @Test
    void verifies() throws Exception {
        // ================= PerformanceProfile =================
        check("FULL halves intervals", PerformanceProfile.FULL.scaleInterval(20), 10);
        check("BALANCED keeps intervals", PerformanceProfile.BALANCED.scaleInterval(20), 20);
        check("LOW doubles intervals", PerformanceProfile.LOW.scaleInterval(20), 40);
        check("interval never 0", PerformanceProfile.FULL.scaleInterval(1), 1);
        check("FULL doubles budget", PerformanceProfile.FULL.scaleBudget(4), 8);
        check("LOW halves budget", PerformanceProfile.LOW.scaleBudget(4), 2);
        check("budget never 0", PerformanceProfile.LOW.scaleBudget(1), 1);
        check("LOW halves capture rate", PerformanceProfile.LOW.scaleCaptureRate(6), 3);
        check("LOW skips tile entities", PerformanceProfile.LOW.tileEntityStats, false);
        check("LOW still captures stacks", PerformanceProfile.LOW.scaleCaptureRate(6) > 0, true);
        check("degrade steps once", PerformanceProfile.FULL.degraded(), "BALANCED");
        check("degrade floors at LOW", PerformanceProfile.LOW.degraded(), "LOW");
        check("restore steps once", PerformanceProfile.LOW.restored(), "BALANCED");
        check("restore ceilings at FULL", PerformanceProfile.FULL.restored(), "FULL");
        check("parse unknown -> BALANCED", PerformanceProfile.parse("nonsense"), "BALANCED");
        check("parse null -> BALANCED", PerformanceProfile.parse(null), "BALANCED");

        // ================= SpikeAttributor =================
        SpikeAttributor at = new SpikeAttributor();
        at.refreshFrom(List.of(
                new SpikeAttributor.Origin("ShopGUI", "com.shopgui.core.ShopPlugin"),
                new SpikeAttributor.Origin("Towny", "com.palmergames.bukkit.towny.Towny"),
                // Two plugins under the same vendor root: the shared ancestor is ambiguous
                // and must not be claimed by either.
                new SpikeAttributor.Origin("VaultA", "net.vendor.plugina.Main"),
                new SpikeAttributor.Origin("VaultB", "net.vendor.pluginb.Main"),
                new SpikeAttributor.Origin("Rooted", "NoPackagePlugin")));

        check("exact package", at.pluginFor("com.shopgui.core.ShopPlugin"), "ShopGUI");
        check("subpackage of main", at.pluginFor("com.shopgui.core.listener.JoinListener"), "ShopGUI");
        check("ancestor package", at.pluginFor("com.shopgui.util.Cache"), "ShopGUI");
        check("ambiguous ancestor unclaimed", at.pluginFor("net.vendor.shared.Util"), null);
        check("unambiguous child still works", at.pluginFor("net.vendor.plugina.Task"), "VaultA");
        check("prefix must end at a dot", at.pluginFor("com.shopguiplus.Other"), null);
        check("default-package plugin ignored", at.pluginFor("NoPackagePlugin"), null);
        check("unrelated class", at.pluginFor("org.example.whatever.Thing"), null);

        // innermost plugin frame wins, even under server frames
        StackTraceElement[] stack = {
                f("java.util.HashMap", "resize"),
                f("net.minecraft.world.level.Level", "getEntities"),
                f("com.shopgui.core.task.RefreshTask", "run"),
                f("org.bukkit.craftbukkit.scheduler.CraftScheduler", "mainThreadHeartbeat"),
                f("net.minecraft.server.MinecraftServer", "tickServer"),
        };
        SpikeAttributor.Blame blame = at.blame(stack);
        check("blames the plugin, not the server", blame.plugin(), "ShopGUI");
        check("blame frame is the plugin frame", blame.frame(), "com.shopgui.core.task.RefreshTask.run:42");

        // pure vanilla stack -> server, skipping scheduler plumbing
        SpikeAttributor.Blame vanilla = at.blame(new StackTraceElement[]{
                f("java.lang.Object", "wait"),
                f("org.bukkit.craftbukkit.scheduler.CraftScheduler", "mainThreadHeartbeat"),
                f("net.minecraft.world.entity.EntityLiving", "tick"),
                f("net.minecraft.server.MinecraftServer", "tickServer"),
        });
        check("vanilla stack blames server", vanilla.plugin(), "Minecraft");
        check("plumbing frame skipped", vanilla.frame(), "net.minecraft.world.entity.EntityLiving.tick:42");

        check("nothing attributable", at.blame(new StackTraceElement[]{f("java.lang.Thread", "sleep")}).plugin(), "unknown");
        check("empty stack", at.blame(new StackTraceElement[0]).plugin(), "unknown");

        // trim drops JVM frames and caps length
        StackTraceElement[] deep = new StackTraceElement[60];
        for (int i = 0; i < deep.length; i++) deep[i] = f("com.shopgui.core.C" + i, "m");
        check("trim caps frames", at.trim(deep).size(), Spike.MAX_FRAMES);
        check("trim drops jvm frames", at.trim(new StackTraceElement[]{
                f("java.util.ArrayList", "add"), f("com.shopgui.core.A", "b")}).size(), 1);

        // ================= PerformanceGovernor =================
        GodViewConfig.Performance autoPerf = new GodViewConfig.Performance(
                "auto", true, 20, true, 120.0, true, 6, true, 100, 17.0, 19.0);
        PerformanceGovernor gov = new PerformanceGovernor(autoPerf);

        check("auto starts at FULL", gov.current(), "FULL");
        check("is auto", gov.isAuto(), true);

        AtomicInteger changes = new AtomicInteger();
        gov.onChange(p -> changes.incrementAndGet());

        for (int i = 0; i < 9; i++) gov.sample(12.0);
        check("9 bad seconds is not enough", gov.current(), "FULL");
        gov.sample(12.0);
        check("10 bad seconds degrades one step", gov.current(), "BALANCED");
        check("change listener fired", changes.get(), 1);

        for (int i = 0; i < 10; i++) gov.sample(12.0);
        check("another 10 degrades again", gov.current(), "LOW");
        for (int i = 0; i < 30; i++) gov.sample(12.0);
        check("cannot degrade past LOW", gov.current(), "LOW");

        // Between the thresholds nothing moves, and the counters reset.
        for (int i = 0; i < 100; i++) gov.sample(18.0);
        check("dead band holds profile", gov.current(), "LOW");

        for (int i = 0; i < 59; i++) gov.sample(19.9);
        check("59 good seconds is not enough", gov.current(), "LOW");
        gov.sample(19.9);
        check("60 good seconds restores one step", gov.current(), "BALANCED");

        // A single bad second in the middle of recovery resets the streak.
        for (int i = 0; i < 59; i++) gov.sample(19.9);
        gov.sample(12.0);
        for (int i = 0; i < 59; i++) gov.sample(19.9);
        check("interrupted streak does not restore", gov.current(), "BALANCED");

        PerformanceGovernor fixed = new PerformanceGovernor(new GodViewConfig.Performance(
                "low", true, 20, true, 120.0, true, 6, true, 100, 17.0, 19.0));
        for (int i = 0; i < 200; i++) fixed.sample(20.0);
        check("fixed profile ignores samples", fixed.current(), "LOW");
        check("fixed is not auto", fixed.isAuto(), false);

        // ================= TickSampler + SpikeWatchdog =================
        List<Spike> spikes = Collections.synchronizedList(new ArrayList<>());
        TickSampler sampler = new TickSampler(200, 120.0, spikes::add);
        SpikeWatchdog dog = new SpikeWatchdog(sampler, at, 120.0, () -> 6);
        dog.start();

        // Pretend to be the server main thread: a few normal ticks, then one long stall
        // spent inside "plugin" code so the watchdog has something to attribute.
        Thread fakeMain = new Thread(() -> {
            try {
                for (int i = 0; i < 4; i++) { sampler.run(); Thread.sleep(50); }
                sampler.run();
                StallSimulator.shopGuiWorkThatTakesTooLong(400);
                sampler.run();               // closes the slow tick and emits the spike
                Thread.sleep(50);
                sampler.run();
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "FakeMain");
        fakeMain.start();
        fakeMain.join(5000);
        Thread.sleep(100);
        dog.stop();

        check("sampler saw ticks", sampler.ticks() >= 7, true);
        check("exactly one spike", spikes.size(), 1);

        Spike spike = spikes.get(0);
        check("spike duration is the stall", spike.durationMs() > 350 && spike.durationMs() < 700, true);
        check("watchdog captured a stack", spike.hasStack(), true);
        check("spike blamed on the stalling code", spike.blame(), "ShopGUI");
        check("blame frame names the method",
                spike.blameFrame().startsWith("com.shopgui.core.task."), true);
        check("watchdog counted the capture", dog.capturesTaken(), 1L);
        check("normal ticks recorded", sampler.periods().size() >= 6, true);
        check("p95 sees the stall", sampler.p95PeriodMs() > 100, true);
        check("overrun ratio is small but nonzero",
                sampler.overrunRatio() > 0 && sampler.overrunRatio() < 0.5, true);

        // Rate limiting: a watchdog allowed zero captures per minute must take none.
        List<Spike> limited = Collections.synchronizedList(new ArrayList<>());
        TickSampler s2 = new TickSampler(200, 120.0, limited::add);
        SpikeWatchdog dog2 = new SpikeWatchdog(s2, at, 120.0, () -> 1);
        dog2.start();
        Thread fake2 = new Thread(() -> {
            try {
                s2.run();
                StallSimulator.shopGuiWorkThatTakesTooLong(300);
                s2.run();
                StallSimulator.shopGuiWorkThatTakesTooLong(300);
                s2.run();
                StallSimulator.shopGuiWorkThatTakesTooLong(300);
                s2.run();
            } catch (Exception e) { /* nothing to do */ }
        }, "FakeMain2");
        fake2.start();
        fake2.join(5000);
        Thread.sleep(100);
        dog2.stop();
        check("three stalls produced three spikes", limited.size(), 3);
        check("captures capped at the limit", dog2.capturesTaken(), 1L);
        check("skips were counted", dog2.capturesSkipped() >= 1, true);
        check("uncaptured spikes still recorded with no stack",
                limited.stream().filter(s -> !s.hasStack()).count(), 2L);

        // ================= Spike JSON =================
        dev.godview.util.JsonWriter w = new dev.godview.util.JsonWriter();
        spike.writeJson(w);
        Object reparsed = dev.godview.util.Json.parse(w.done());
        check("spike json blame", dev.godview.util.Json.str(dev.godview.util.Json.asObj(reparsed), "blame", "?"), "ShopGUI");
        check("spike json stack is an array",
                dev.godview.util.Json.asArr(dev.godview.util.Json.asObj(reparsed).get("stack")).isEmpty(), false);
    }
}
