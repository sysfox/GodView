package dev.godview.integration;

import dev.godview.compat.Compat;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Player balances through Vault.
 *
 * <p>Reached entirely by reflection. Vault's API is not on Maven Central and adding a
 * repository plus a jar to the build for two getters is not a trade worth making — this
 * integration reads a balance and formats it, nothing more.
 *
 * <p>Vault being installed is not the same as an economy existing: Vault is a bridge, and
 * plenty of servers have it for its permissions bridge alone. Availability therefore means
 * "an economy provider is registered", not "the plugin is present".
 *
 * <p>Read-only, and deliberately so. Editing balances from a monitoring panel invites
 * exactly the kind of unlogged, unreviewed change that economy plugins have their own
 * audited commands for.
 */
public final class VaultIntegration implements Integration {

    private static final String ECONOMY_CLASS = "net.milkbowl.vault.economy.Economy";

    @Override
    public String id() {
        return "vault";
    }

    @Override
    public String displayName() {
        return "Vault";
    }

    @Override
    public boolean available() {
        return economy() != null;
    }

    @Override
    public String version() {
        Plugin plugin = plugin();
        return plugin == null ? "" : Compat.pluginVersion(plugin);
    }

    private Plugin plugin() {
        try {
            return Bukkit.getPluginManager().getPlugin("Vault");
        } catch (Throwable ignored) {
            // Only reachable outside a running server, but availability checks must never
            // be the thing that throws -- see the Integration contract.
            return null;
        }
    }

    @Override
    public String problem() {
        Plugin plugin = plugin();
        if (plugin == null) return "";
        if (!plugin.isEnabled()) return "Vault is installed but disabled";
        if (Reflect.clazz(ECONOMY_CLASS) == null) return "Vault's economy API is missing";
        return economy() == null ? "Vault is present but no economy plugin registered with it" : "";
    }

    /** The registered economy provider, or null. Looked up per call — providers can change. */
    private Object economy() {
        try {
            Class<?> economyClass = Reflect.clazz(ECONOMY_CLASS);
            if (economyClass == null) return null;
            RegisteredServiceProvider<?> registration =
                    Bukkit.getServicesManager().getRegistration(economyClass);
            return registration == null ? null : registration.getProvider();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** The economy plugin's name, e.g. {@code EssentialsX Economy}. */
    public String economyName() {
        Object economy = economy();
        if (economy == null) return "";
        return Reflect.string(Reflect.call(economy, "getName", new Class<?>[0]), "");
    }

    /** A player's balance, or {@link Double#NaN} when there is no economy to ask. */
    public double balance(OfflinePlayer player) {
        Object economy = economy();
        if (economy == null || player == null) return Double.NaN;
        Object value = Reflect.call(economy, "getBalance",
                new Class<?>[]{OfflinePlayer.class}, player);
        return value instanceof Number n ? n.doubleValue() : Double.NaN;
    }

    /** The balance rendered the way the economy plugin would render it, e.g. {@code $1,250}. */
    public String formatted(OfflinePlayer player) {
        Object economy = economy();
        if (economy == null || player == null) return "";
        double amount = balance(player);
        if (Double.isNaN(amount)) return "";
        Object formatted = Reflect.call(economy, "format", new Class<?>[]{double.class}, amount);
        return Reflect.string(formatted, String.valueOf(amount));
    }

    /** Plural currency name, e.g. {@code dollars}. Empty when unknown. */
    public String currency() {
        Object economy = economy();
        if (economy == null) return "";
        return Reflect.string(Reflect.call(economy, "currencyNamePlural", new Class<?>[0]), "");
    }
}
