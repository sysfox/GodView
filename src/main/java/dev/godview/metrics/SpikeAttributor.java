package dev.godview.metrics;

import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a captured main-thread stack into a plugin name.
 *
 * <p>A 200-frame stack trace tells an operator nothing. What they need is one line —
 * "this tick was ShopGUI's fault" — and the frames only as supporting evidence. This
 * class produces that line.
 *
 * <p>Attribution works on package prefixes, built from each loaded plugin's main class.
 * We cannot do better cheaply: a {@link StackTraceElement} from another thread carries
 * only names, and resolving each one to a {@link Class} to ask which plugin loaded it
 * would mean class loading on the watchdog thread — expensive, and able to run static
 * initializers as a side effect of diagnostics.
 *
 * <p>Ancestor prefixes are registered too, so a plugin whose main class sits in
 * {@code com.example.core} is still recognised in {@code com.example.listeners}. An
 * ancestor claimed by two different plugins is dropped for both rather than guessed at,
 * and matching always prefers the longest prefix, so a plugin bundled under another
 * vendor's namespace still resolves to itself.
 */
public final class SpikeAttributor {

    /** The server itself. Not a plugin, but a legitimate answer. */
    public static final String SERVER = "Minecraft";
    /** Nothing in the stack was attributable. */
    public static final String UNKNOWN = "unknown";

    /** Frames from these roots are never a root cause on their own — keep walking past them. */
    private static final String[] JVM_ROOTS = {
            "java.", "javax.", "jdk.", "sun.", "com.sun.", "kotlin.", "scala."
    };

    /** Frames from these roots mean vanilla or server internals. */
    private static final String[] SERVER_ROOTS = {
            "net.minecraft.", "org.bukkit.", "org.spigotmc.", "io.papermc.",
            "com.destroystokyo.", "gg.pufferfish.", "org.purpurmc.", "ca.spottedleaf."
    };

    /** Scheduler plumbing sits between the server loop and plugin code — never the answer. */
    private static final String[] PLUMBING = {
            "org.bukkit.craftbukkit.", "org.bukkit.plugin.", "org.bukkit.scheduler.",
            "io.papermc.paper.plugin."
    };

    /** Longest-first so a nested namespace beats its ancestor. */
    private volatile List<Map.Entry<String, String>> prefixes = List.of();

    /** A plugin reduced to the two things attribution needs. */
    public record Origin(String name, String mainClass) {
    }

    /**
     * Rebuilds the prefix table. Cheap enough to call on every plugin enable/disable —
     * it only reads class names of already-loaded plugins.
     */
    public void refresh(Plugin[] plugins) {
        List<Origin> origins = new ArrayList<>(plugins.length);
        for (Plugin plugin : plugins) {
            origins.add(new Origin(plugin.getName(), plugin.getClass().getName()));
        }
        refreshFrom(origins);
    }

    /**
     * The Bukkit-free half of {@link #refresh}. Split out so the prefix and ambiguity
     * rules can be exercised without a running server.
     */
    public void refreshFrom(List<Origin> origins) {
        Map<String, String> exact = new HashMap<>();
        Map<String, String> ancestors = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();

        for (Origin origin : origins) {
            String name = origin.name();
            String mainClass = origin.mainClass();
            int lastDot = mainClass.lastIndexOf('.');
            if (lastDot < 0) continue;   // default package — nothing safe to match on
            String pkg = mainClass.substring(0, lastDot);

            exact.put(pkg, name);

            // Register every ancestor down to two segments, e.g. com.example.core.impl
            // contributes com.example.core and com.example. A single segment ("com") is
            // useless and would swallow half the stack.
            String candidate = pkg;
            while (true) {
                int dot = candidate.lastIndexOf('.');
                if (dot < 0) break;
                candidate = candidate.substring(0, dot);
                if (candidate.indexOf('.') < 0) break;   // reached a single segment
                String owner = ancestors.putIfAbsent(candidate, name);
                if (owner != null && !owner.equals(name)) ambiguous.add(candidate);
            }
        }

        Map<String, String> merged = new HashMap<>();
        for (Map.Entry<String, String> e : ancestors.entrySet()) {
            if (!ambiguous.contains(e.getKey())) merged.put(e.getKey(), e.getValue());
        }
        // An exact main-class package always wins over an inherited ancestor claim.
        merged.putAll(exact);

        List<Map.Entry<String, String>> sorted = new ArrayList<>(merged.entrySet());
        sorted.sort((a, b) -> b.getKey().length() - a.getKey().length());
        this.prefixes = List.copyOf(sorted);
    }

    /**
     * Walks a stack from the innermost frame outward and names the first plugin it meets.
     *
     * <p>Innermost-first is what makes this correct rather than merely plausible: when a
     * plugin calls into the server and the server is where the time goes, the plugin frame
     * still sits below it and gets the blame — which is the honest answer, since the
     * plugin chose to make that call.
     */
    public Blame blame(StackTraceElement[] stack) {
        String firstServerFrame = null;

        for (StackTraceElement frame : stack) {
            String cls = frame.getClassName();
            if (startsWithAny(cls, JVM_ROOTS)) continue;

            String plugin = pluginFor(cls);
            if (plugin != null) return new Blame(plugin, format(frame));

            if (firstServerFrame == null && startsWithAny(cls, SERVER_ROOTS)
                    && !startsWithAny(cls, PLUMBING)) {
                firstServerFrame = format(frame);
            }
        }

        if (firstServerFrame != null) return new Blame(SERVER, firstServerFrame);
        return new Blame(UNKNOWN, stack.length > 0 ? format(stack[0]) : "");
    }

    /** The plugin owning a class name, or null if it belongs to nobody we know. */
    public String pluginFor(String className) {
        for (Map.Entry<String, String> entry : prefixes) {
            String prefix = entry.getKey();
            // The trailing dot check stops com.exampleother matching com.example.
            if (className.startsWith(prefix) && className.length() > prefix.length()
                    && className.charAt(prefix.length()) == '.') {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Trims a raw stack for storage and display: JVM frames dropped, capped at
     * {@link Spike#MAX_FRAMES}.
     */
    public List<String> trim(StackTraceElement[] stack) {
        List<String> out = new ArrayList<>(Math.min(stack.length, Spike.MAX_FRAMES));
        for (StackTraceElement frame : stack) {
            if (out.size() >= Spike.MAX_FRAMES) break;
            if (startsWithAny(frame.getClassName(), JVM_ROOTS)) continue;
            out.add(format(frame));
        }
        return List.copyOf(out);
    }

    /** Who a spike belongs to, and the frame that says so. */
    public record Blame(String plugin, String frame) {
    }

    private static String format(StackTraceElement frame) {
        return frame.getClassName() + '.' + frame.getMethodName()
                + (frame.getLineNumber() > 0 ? ":" + frame.getLineNumber() : "");
    }

    private static boolean startsWithAny(String value, String[] prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) return true;
        }
        return false;
    }
}
