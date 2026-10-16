package net.dvmn2.dsit;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Команды плагина:
 * <pre>
 * /sit                  — сесть на месте; повторно — встать
 * /grab &lt;игроки&gt;      — удержать игрока на невидимом сиденье (не даёт слезть и телепортироваться)
 * /ungrab &lt;игроки&gt;    — отпустить
 * /dsit reload          — перечитать config.yml
 * </pre>
 */
public final class DSitCommands {

    private static final String ARG_TARGETS = "targets";

    private final DSit plugin;
    private final SeatManager seats;
    private final DSitSettings settings;

    public DSitCommands(DSit plugin, SeatManager seats, DSitSettings settings) {
        this.plugin = plugin;
        this.seats = seats;
        this.settings = settings;
    }

    public LiteralCommandNode<CommandSourceStack> createSit() {
        return Commands.literal("sit")
                .requires(source -> source.getSender().hasPermission(Permissions.SIT))
                .executes(this::runSit)
                .build();
    }

    public LiteralCommandNode<CommandSourceStack> createGrab() {
        return Commands.literal("grab")
                .requires(source -> source.getSender().hasPermission(Permissions.GRAB))
                .then(Commands.argument(ARG_TARGETS, ArgumentTypes.player())
                        .executes(this::runGrab))
                .build();
    }

    public LiteralCommandNode<CommandSourceStack> createUngrab() {
        return Commands.literal("ungrab")
                .requires(source -> source.getSender().hasPermission(Permissions.GRAB))
                .then(Commands.argument(ARG_TARGETS, ArgumentTypes.player())
                        .suggests(this::suggestGrabbed)
                        .executes(this::runUngrab))
                .build();
    }

    public LiteralCommandNode<CommandSourceStack> createAdmin() {
        return Commands.literal("dsit")
                .requires(source -> source.getSender().hasPermission(Permissions.RELOAD))
                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            CommandSender sender = ctx.getSource().getSender();
                            plugin.reload();
                            sender.sendMessage(Lang.get(Lang.Key.RELOADED, sender));
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }

    // ---------------------------------------------------------------- /sit

    private int runSit(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Entity executor = ctx.getSource().getExecutor();
        if (!(executor instanceof Player player)) {
            sender.sendMessage(Lang.get(Lang.Key.ONLY_PLAYERS, sender));
            return 0;
        }
        if (!settings.isCommandEnabled()) {
            player.sendMessage(Lang.get(Lang.Key.SIT_COMMAND_DISABLED, player));
            return 0;
        }

        SitType current = seats.getSitType(player);
        if (current == SitType.GRAB) {
            player.sendMessage(Lang.get(Lang.Key.YOU_ARE_HELD, player));
            return 0;
        }
        if (current != null) {
            seats.stand(player);
            return Command.SINGLE_SUCCESS;
        }

        SitResult result = seats.sitHere(player);
        switch (result) {
            case SUCCESS -> {
                return Command.SINGLE_SUCCESS;
            }
            case CANCELLED -> player.sendMessage(Lang.get(Lang.Key.SIT_CANCELLED, player));
            case SELF_BUSY -> player.sendMessage(Lang.get(Lang.Key.CANNOT_SIT_NOW, player));
            case INVALID_LOCATION -> player.sendMessage(Lang.get(Lang.Key.CANNOT_SIT_HERE, player));
            default -> player.sendMessage(Lang.get(Lang.Key.SIT_FAILED, player));
        }
        return 0;
    }

    // ---------------------------------------------------------------- /grab, /ungrab

    private int runGrab(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        int done = 0;
        for (Player target : resolvePlayers(ctx)) {
            String name = target.getName();
            if (!sender.equals(target) && target.hasPermission(Permissions.GRAB_EXEMPT)) {
                sender.sendMessage(Lang.get(Lang.Key.GRAB_EXEMPT, sender, name));
                continue;
            }
            switch (seats.grab(target)) {
                case SUCCESS -> {
                    sender.sendMessage(Lang.get(Lang.Key.GRAB_OK, sender, name));
                    done++;
                }
                case ALREADY -> sender.sendMessage(Lang.get(Lang.Key.GRAB_ALREADY, sender, name));
                case CANCELLED -> sender.sendMessage(Lang.get(Lang.Key.GRAB_CANCELLED, sender, name));
                default -> sender.sendMessage(Lang.get(Lang.Key.GRAB_FAILED, sender, name));
            }
        }
        return done;
    }

    private int runUngrab(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        int done = 0;
        for (Player target : resolvePlayers(ctx)) {
            if (seats.ungrab(target)) {
                sender.sendMessage(Lang.get(Lang.Key.UNGRAB_OK, sender, target.getName()));
                done++;
            } else {
                sender.sendMessage(Lang.get(Lang.Key.UNGRAB_NOT_GRABBED, sender, target.getName()));
            }
        }
        return done;
    }

    /**
     * Резолвит селектор "targets" в список игроков. Имя аргумента ДОЛЖНО совпадать
     * с тем, что передано в Commands.argument(ARG_TARGETS, ...).
     */
    private static List<Player> resolvePlayers(CommandContext<CommandSourceStack> ctx) {
        try {
            PlayerSelectorArgumentResolver resolver =
                    ctx.getArgument(ARG_TARGETS, PlayerSelectorArgumentResolver.class);
            return resolver.resolve(ctx.getSource());
        } catch (CommandSyntaxException | IndexOutOfBoundsException e) {
            CommandSender sender = ctx.getSource().getSender();
            sender.sendMessage(Lang.get(Lang.Key.TARGET_RESOLVE_FAILED, sender));
            return Collections.emptyList();
        }
    }

    /** В /ungrab подсказываем только тех, кто сейчас удерживается. */
    private CompletableFuture<Suggestions> suggestGrabbed(CommandContext<CommandSourceStack> ctx,
                                                          SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (String name : seats.grabbedNames()) {
            if (name.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                builder.suggest(name);
            }
        }
        return builder.buildFuture();
    }
}
