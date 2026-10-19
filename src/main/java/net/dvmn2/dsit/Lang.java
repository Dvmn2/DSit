package net.dvmn2.dsit;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * RU/EN локализация плагина. Язык задаётся в config.yml (settings.language):
 * "ru" / "en" — фиксированный язык; "auto" (по умолчанию) — берётся из
 * клиентской локали игрока (Player#locale()), для не-игроков — английский.
 */
public final class Lang {

    public enum Key {
        ONLY_PLAYERS,
        SIT_COMMAND_DISABLED,
        CANNOT_SIT_NOW,
        CANNOT_SIT_HERE,
        SIT_CANCELLED,
        SIT_FAILED,
        LAY_DISABLED,
        CRAWL_DISABLED,
        CANNOT_POSE_NOW,
        CANNOT_POSE_HERE,
        POSE_CANCELLED,
        SEAT_OCCUPIED,
        TARGET_BUSY,
        YOU_ARE_HELD,
        GRAB_OK,
        GRAB_ALREADY,
        GRAB_CANCELLED,
        GRAB_FAILED,
        GRAB_EXEMPT,
        UNGRAB_OK,
        UNGRAB_NOT_GRABBED,
        TARGET_RESOLVE_FAILED,
        RELOADED
    }

    private static final Map<Key, String> RU = new EnumMap<>(Key.class);
    private static final Map<Key, String> EN = new EnumMap<>(Key.class);

    static {
        RU.put(Key.ONLY_PLAYERS, "§cЭту команду могут использовать только игроки.");
        RU.put(Key.SIT_COMMAND_DISABLED, "§cКоманда /sit отключена на этом сервере.");
        RU.put(Key.CANNOT_SIT_NOW, "§cСейчас вы не можете сесть.");
        RU.put(Key.CANNOT_SIT_HERE, "§cЗдесь нельзя сесть: нужна твёрдая опора под ногами.");
        RU.put(Key.SIT_CANCELLED, "§cПосадка отменена другим плагином.");
        RU.put(Key.SIT_FAILED, "§cНе удалось сесть.");
        RU.put(Key.LAY_DISABLED, "§cКоманда /lay отключена на этом сервере.");
        RU.put(Key.CRAWL_DISABLED, "§cКоманда /crawl отключена на этом сервере.");
        RU.put(Key.CANNOT_POSE_NOW, "§cСейчас вы не можете принять эту позу.");
        RU.put(Key.CANNOT_POSE_HERE, "§cЗдесь это невозможно: нужна ровная опора под ногами, без воды.");
        RU.put(Key.POSE_CANCELLED, "§cДействие отменено другим плагином.");
        RU.put(Key.SEAT_OCCUPIED, "§cЭто место уже занято.");
        RU.put(Key.TARGET_BUSY, "§cНа этом игроке сейчас нельзя сидеть.");
        RU.put(Key.YOU_ARE_HELD, "§cВас удерживают — встать нельзя.");
        RU.put(Key.GRAB_OK, "§aИгрок %s удержан.");
        RU.put(Key.GRAB_ALREADY, "§7Игрок %s уже удерживается.");
        RU.put(Key.GRAB_CANCELLED, "§cУдержание игрока %s отменено другим плагином.");
        RU.put(Key.GRAB_FAILED, "§cНе удалось удержать игрока %s.");
        RU.put(Key.GRAB_EXEMPT, "§cИгрока %s нельзя удерживать.");
        RU.put(Key.UNGRAB_OK, "§aИгрок %s отпущен.");
        RU.put(Key.UNGRAB_NOT_GRABBED, "§7Игрок %s не удерживается.");
        RU.put(Key.TARGET_RESOLVE_FAILED, "§cНе удалось разрешить указанного игрока.");
        RU.put(Key.RELOADED, "§aКонфигурация DSit перезагружена.");

        EN.put(Key.ONLY_PLAYERS, "§cThis command can only be used by players.");
        EN.put(Key.SIT_COMMAND_DISABLED, "§cThe /sit command is disabled on this server.");
        EN.put(Key.CANNOT_SIT_NOW, "§cYou can't sit right now.");
        EN.put(Key.CANNOT_SIT_HERE, "§cYou can't sit here: solid ground under your feet is required.");
        EN.put(Key.SIT_CANCELLED, "§cSitting was cancelled by another plugin.");
        EN.put(Key.SIT_FAILED, "§cFailed to sit down.");
        EN.put(Key.LAY_DISABLED, "§cThe /lay command is disabled on this server.");
        EN.put(Key.CRAWL_DISABLED, "§cThe /crawl command is disabled on this server.");
        EN.put(Key.CANNOT_POSE_NOW, "§cYou can't do that right now.");
        EN.put(Key.CANNOT_POSE_HERE, "§cNot possible here: flat solid ground without water is required.");
        EN.put(Key.POSE_CANCELLED, "§cThe action was cancelled by another plugin.");
        EN.put(Key.SEAT_OCCUPIED, "§cThis seat is already taken.");
        EN.put(Key.TARGET_BUSY, "§cYou can't sit on this player right now.");
        EN.put(Key.YOU_ARE_HELD, "§cYou are being held — you can't stand up.");
        EN.put(Key.GRAB_OK, "§aPlayer %s is now held.");
        EN.put(Key.GRAB_ALREADY, "§7Player %s is already held.");
        EN.put(Key.GRAB_CANCELLED, "§cHolding player %s was cancelled by another plugin.");
        EN.put(Key.GRAB_FAILED, "§cFailed to hold player %s.");
        EN.put(Key.GRAB_EXEMPT, "§cPlayer %s can't be held.");
        EN.put(Key.UNGRAB_OK, "§aPlayer %s was released.");
        EN.put(Key.UNGRAB_NOT_GRABBED, "§7Player %s is not held.");
        EN.put(Key.TARGET_RESOLVE_FAILED, "§cCould not resolve the specified player.");
        EN.put(Key.RELOADED, "§aDSit configuration reloaded.");
    }

    private static volatile String configuredLanguage = "auto";

    private Lang() {
    }

    public static void setLanguage(String language) {
        configuredLanguage = (language == null || language.isBlank())
                ? "auto"
                : language.toLowerCase(Locale.ROOT);
    }

    public static String get(Key key, CommandSender sender, Object... args) {
        Map<Key, String> table = resolveTable(sender);
        String template = table.getOrDefault(key, RU.get(key));
        return args.length == 0 ? template : String.format(Locale.US, template, args);
    }

    private static Map<Key, String> resolveTable(CommandSender sender) {
        return switch (configuredLanguage) {
            case "ru" -> RU;
            case "en" -> EN;
            default -> autoResolve(sender);
        };
    }

    private static Map<Key, String> autoResolve(CommandSender sender) {
        if (sender instanceof Player player) {
            return "ru".equalsIgnoreCase(player.locale().getLanguage()) ? RU : EN;
        }
        return EN;
    }
}
