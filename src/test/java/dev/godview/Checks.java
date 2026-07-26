package dev.godview;

import org.junit.jupiter.api.Assertions;

/**
 * The one assertion these suites use.
 *
 * <p>Every check reads as a sentence about the system — "cooldown suppresses
 * repeats", "forged left-hand hop is not used" — because a failing assertion is
 * usually read by somebody who did not write it, months later, and the name is
 * the whole explanation they get.
 *
 * <p>Comparison is by string form on purpose. These suites assert against a real
 * server, a real database and a real HTTP stack, where a value may arrive as a
 * {@code Long} where the test wrote an {@code int}, or as an enum where the test
 * wrote its name. Comparing the rendered value keeps the assertions about
 * behaviour rather than about which numeric type a JSON parser happened to pick.
 */
public final class Checks {

    private Checks() {
    }

    public static void check(String what, Object actual, Object expected) {
        Assertions.assertEquals(String.valueOf(expected), String.valueOf(actual), what);
    }

    /** For the cases where the interesting thing is that a call did not throw. */
    public static void survives(String what, Runnable work) {
        Assertions.assertDoesNotThrow(work::run, what);
    }

    public static void fail(String what) {
        Assertions.fail(what);
    }
}
