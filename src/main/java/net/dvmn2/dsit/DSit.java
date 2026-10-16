package net.dvmn2.dsit;

import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Главный класс плагина: жизненный цикл, регистрация слушателей и команд.
 */
public final class DSit extends JavaPlugin {

    private DSitSettings settings;
    private SeatManager seats;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        settings = new DSitSettings(this);
        Lang.setLanguage(settings.getLanguage());

        seats = new SeatManager(this, settings);
        seats.start();

        getServer().getPluginManager().registerEvents(new SitListener(seats, settings), this);

        DSitCommands commands = new DSitCommands(this, seats, settings);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands registrar = event.registrar();
            registrar.register(commands.createSit(), "Сесть на месте / встать");
            registrar.register(commands.createGrab(), "Удерживает игрока на месте");
            registrar.register(commands.createUngrab(), "Отпускает удерживаемого игрока");
            registrar.register(commands.createAdmin(), "Управление плагином DSit");
        });

        getLogger().info("DSit enabled!");
    }

    @Override
    public void onDisable() {
        if (seats != null) {
            seats.shutdown();
        }
        getLogger().info("DSit disabled!");
    }

    /** Перечитывает config.yml. Уже сидящие игроки перепроверяются на ближайшем тике. */
    public void reload() {
        reloadConfig();
        settings.reload();
        Lang.setLanguage(settings.getLanguage());
    }

    /** Доступ к управлению сиденьями для других плагинов. */
    public SeatManager getSeatManager() {
        return seats;
    }
}
