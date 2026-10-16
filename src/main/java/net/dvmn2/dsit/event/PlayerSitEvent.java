package net.dvmn2.dsit.event;

import net.dvmn2.dsit.SitType;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Вызывается перед тем, как игрок сядет (или будет схвачен командой /grab).
 * Отмена события запрещает посадку — так плагины защиты регионов и т.п.
 * могут ограничивать сидение, не завися от DSit жёстко.
 */
public class PlayerSitEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final SitType type;
    private final Location seatLocation;
    private final Block block;
    private final Player target;
    private boolean cancelled;

    public PlayerSitEvent(@NotNull Player who, @NotNull SitType type, @NotNull Location seatLocation,
                          @Nullable Block block, @Nullable Player target) {
        super(who);
        this.type = type;
        this.seatLocation = seatLocation;
        this.block = block;
        this.target = target;
    }

    /** Способ посадки. */
    public @NotNull SitType getType() {
        return type;
    }

    /** Где будет находиться сиденье. */
    public @NotNull Location getSeatLocation() {
        return seatLocation.clone();
    }

    /** Блок, на который садятся ({@link SitType#BLOCK}), иначе null. */
    public @Nullable Block getBlock() {
        return block;
    }

    /** Игрок, на голове которого садятся ({@link SitType#PLAYER}), иначе null. */
    public @Nullable Player getTarget() {
        return target;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
