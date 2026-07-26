package dev.godview.suite;

import org.junit.jupiter.api.Test;

import dev.godview.integration.*;
import dev.godview.util.Json;
import dev.godview.util.JsonWriter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dev.godview.Checks.check;
import static dev.godview.Checks.fail;
import static dev.godview.Checks.survives;

/**
 * The degradation contract: every integration absent.
 *
 * <p>Drives the real thing rather than a mock: a bound socket, a SQLite file on
 * disk, an attached appender. Slower than unit tests and worth it -- most of the
 * defects these caught were in the seams between components, which is exactly
 * what a mock would have papered over.
 */
class IntegrationSuiteTest {

    @Test
    void verifies() {
        System.out.println("========== availability with nothing installed ==========");
        LuckPermsIntegration luckPerms = new LuckPermsIntegration();
        CoreProtectIntegration coreProtect = new CoreProtectIntegration();
        VaultIntegration vault = new VaultIntegration();
        SparkIntegration spark = new SparkIntegration();
        EssentialsIntegration essentials = new EssentialsIntegration();
        PlaceholderIntegration placeholders = new PlaceholderIntegration(null);

        List<Integration> all = List.of(luckPerms, coreProtect, vault, spark, essentials, placeholders);

        for (Integration i : all) {
            check(i.id() + " is unavailable", i.available(), false);
            check(i.id() + " reports no version", i.version(), "");
            survives(i.id() + " problem() is safe", i::problem);
        }

        check("ids are the config keys", all.stream().map(Integration::id).toList().toString(),
                "[luckperms, coreprotect, vault, spark, essentials, placeholderapi]");

        System.out.println("\n========== empty data, not exceptions ==========");
        check("luckperms describe is not loaded",
                luckPerms.describe(UUID.randomUUID()).loaded(), false);
        check("luckperms describe on a null uuid",
                luckPerms.describe(null).loaded(), false);
        check("luckperms groups is empty", luckPerms.groups().size(), 0);

        check("vault balance is NaN", Double.isNaN(vault.balance(null)), true);
        check("vault formatted is empty", vault.formatted(null), "");
        check("vault currency is empty", vault.currency(), "");
        check("vault economy name is empty", vault.economyName(), "");

        check("essentials describe is unknown", essentials.describe(null).known(), false);

        check("spark tps is NaN", Double.isNaN(spark.tps()), true);
        check("spark still names its command",
                spark.profilerCommand().startsWith("/spark"), true);

        check("placeholders resolve is empty",
                placeholders.resolve(null, List.of("%player_name%")).size(), 0);
        check("placeholders resolve on empty input",
                placeholders.resolve(null, List.of()).size(), 0);
        check("placeholders resolve on null input",
                placeholders.resolve(null, null).size(), 0);

        System.out.println("\n========== coreprotect refuses unsafe calls ==========");
        try {
            coreProtect.lookup("world", 0, 64, 0, 5, 3600);
            fail("FAIL lookup should have refused");
        } catch (CoreProtectIntegration.LookupFailure e) {
            System.out.println("ok   lookup refuses when unavailable  ->  " + e.getMessage());
        }

        System.out.println("\n========== json shape ==========");
        for (Integration i : all) {
            JsonWriter w = new JsonWriter(256);
            i.writeJson(w);
            Map<String, Object> parsed = Json.asObj(Json.parse(w.done()));
            check(i.id() + " json has id", parsed.get("id"), i.id());
            check(i.id() + " json has available", parsed.get("available"), false);
            check(i.id() + " json has a name", parsed.get("name"), i.displayName());
        }

        // The record payloads must serialize even when they hold nothing.
        JsonWriter w = new JsonWriter(256);
        luckPerms.describe(null).writeJson(w);
        check("empty permissions payload parses",
                Json.asObj(Json.parse(w.done())).get("loaded"), false);

        JsonWriter w2 = new JsonWriter(256);
        essentials.describe(null).writeJson(w2);
        check("empty essentials payload parses",
                Json.asObj(Json.parse(w2.done())).get("known"), false);

        JsonWriter w3 = new JsonWriter(256);
        new CoreProtectIntegration.Change(1L, "Steve", "STONE", "break", 1, 2, 3, false).writeJson(w3);
        check("change payload parses",
                Json.asObj(Json.parse(w3.done())).get("action"), "break");
    }
}
