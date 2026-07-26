package dev.godview.integration;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * A tiny reflection helper for the integrations that are not build dependencies.
 *
 * <p>LuckPerms, CoreProtect and PlaceholderAPI publish Maven artifacts, so GodView compiles
 * against their APIs directly. Vault, spark and Essentials would each add a repository and
 * a jar to the build for a handful of getters, so those are reached reflectively instead.
 *
 * <p>Everything is cached and everything swallows failure. A missing method means the
 * other plugin changed shape — the right answer is "this integration has nothing for you",
 * not an exception surfacing in a panel request.
 */
final class Reflect {

    private static final Map<String, Method> CACHE = new HashMap<>();
    private static final Map<String, Class<?>> CLASSES = new HashMap<>();
    /** Distinguishes "looked up and absent" from "not looked up yet". */
    private static final Method ABSENT;

    static {
        Method absent;
        try {
            absent = Reflect.class.getDeclaredMethod("absentMarker");
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
        ABSENT = absent;
    }

    @SuppressWarnings("unused")
    private static void absentMarker() {
    }

    private Reflect() {
    }

    /** @return the class, or null when it is not on the classpath */
    static synchronized Class<?> clazz(String name) {
        if (CLASSES.containsKey(name)) return CLASSES.get(name);
        Class<?> found;
        try {
            found = Class.forName(name);
        } catch (Throwable ignored) {
            found = null;
        }
        CLASSES.put(name, found);
        return found;
    }

    /** @return the method, or null when the owner or the method is missing */
    static synchronized Method method(String owner, String name, Class<?>... params) {
        String key = owner + '#' + name + '/' + params.length;
        Method cached = CACHE.get(key);
        if (cached != null) return cached == ABSENT ? null : cached;

        Method resolved = null;
        Class<?> type = clazz(owner);
        if (type != null) {
            try {
                resolved = type.getMethod(name, params);
            } catch (Throwable ignored) {
                resolved = null;
            }
        }
        CACHE.put(key, resolved == null ? ABSENT : resolved);
        return resolved;
    }

    /** Invokes a method, returning null on any failure. */
    static Object call(Method method, Object target, Object... args) {
        if (method == null) return null;
        try {
            return method.invoke(target, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Invokes a method by name on whatever {@code target} happens to be. */
    static Object call(Object target, String name, Class<?>[] params, Object... args) {
        if (target == null) return null;
        try {
            Method method = target.getClass().getMethod(name, params);
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static String string(Object value, String def) {
        return value == null ? def : String.valueOf(value);
    }

    static double number(Object value, double def) {
        return value instanceof Number n ? n.doubleValue() : def;
    }

    static boolean flag(Object value, boolean def) {
        return value instanceof Boolean b ? b : def;
    }
}
