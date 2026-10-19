package net.dvmn2.dsit;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Настройки плагина из config.yml. Читаются при старте и по /dsit reload.
 * Способы посадки (sit.command / sit.block / sit.player) включаются независимо.
 */
public final class DSitSettings {

    private static final String ROOT = "settings.";

    private final JavaPlugin plugin;

    private volatile String language;

    private volatile boolean commandEnabled;
    private volatile boolean blockEnabled;
    private volatile boolean playerEnabled;

    private volatile boolean blockRequireEmptyHand;
    private volatile boolean blockDenyWhenSneaking;
    private volatile Set<Material> allowedBlocks = Collections.emptySet();

    private volatile boolean playerRequireEmptyHand;
    private volatile boolean playerRequireSneaking;

    private volatile boolean layEnabled;
    private volatile boolean crawlEnabled;

    private volatile double seatYOffset;

    public DSitSettings(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        FileConfiguration config = plugin.getConfig();

        language = config.getString(ROOT + "language", "auto");

        commandEnabled = config.getBoolean(ROOT + "sit.command", true);
        blockEnabled = config.getBoolean(ROOT + "sit.block", true);
        playerEnabled = config.getBoolean(ROOT + "sit.player", true);

        blockRequireEmptyHand = config.getBoolean(ROOT + "block.require-empty-hand", true);
        blockDenyWhenSneaking = config.getBoolean(ROOT + "block.deny-when-sneaking", true);
        allowedBlocks = parseAllowed(config.getStringList(ROOT + "block.allowed"));

        playerRequireEmptyHand = config.getBoolean(ROOT + "player.require-empty-hand", true);
        playerRequireSneaking = config.getBoolean(ROOT + "player.require-sneaking", false);

        layEnabled = config.getBoolean(ROOT + "pose.lay", true);
        crawlEnabled = config.getBoolean(ROOT + "pose.crawl", true);

        seatYOffset = config.getDouble(ROOT + "seat.y-offset", 0.0);
    }

    /** Разбирает список блоков: имена материалов и теги вида "#stairs" / "#minecraft:slabs". */
    private Set<Material> parseAllowed(List<String> entries) {
        Set<Material> result = EnumSet.noneOf(Material.class);
        for (String raw : entries) {
            String entry = raw.trim();
            if (entry.isEmpty()) {
                continue;
            }
            if (entry.startsWith("#")) {
                NamespacedKey key = NamespacedKey.fromString(entry.substring(1).toLowerCase(Locale.ROOT));
                Tag<Material> tag = key == null ? null : Bukkit.getTag(Tag.REGISTRY_BLOCKS, key, Material.class);
                if (tag == null) {
                    plugin.getLogger().warning("Неизвестный тег блоков в block.allowed: " + entry);
                    continue;
                }
                result.addAll(tag.getValues());
            } else {
                Material material = Material.matchMaterial(entry);
                if (material == null || !material.isBlock()) {
                    plugin.getLogger().warning("Неизвестный блок в block.allowed: " + entry);
                    continue;
                }
                result.add(material);
            }
        }
        return result;
    }

    public String getLanguage() {
        return language;
    }

    public boolean isCommandEnabled() {
        return commandEnabled;
    }

    public boolean isBlockEnabled() {
        return blockEnabled;
    }

    public boolean isPlayerEnabled() {
        return playerEnabled;
    }

    public boolean isBlockRequireEmptyHand() {
        return blockRequireEmptyHand;
    }

    public boolean isBlockDenyWhenSneaking() {
        return blockDenyWhenSneaking;
    }

    public boolean isAllowedBlock(Material material) {
        return allowedBlocks.contains(material);
    }

    public boolean isPlayerRequireEmptyHand() {
        return playerRequireEmptyHand;
    }

    public boolean isPlayerRequireSneaking() {
        return playerRequireSneaking;
    }

    public boolean isLayEnabled() {
        return layEnabled;
    }

    public boolean isCrawlEnabled() {
        return crawlEnabled;
    }

    public double getSeatYOffset() {
        return seatYOffset;
    }
}
