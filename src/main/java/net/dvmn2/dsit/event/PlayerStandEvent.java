package net.dvmn2.dsit.event;

import net.dvmn2.dsit.SitType;
import net.dvmn2.dsit.StandReason;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Вызывается, когда игрок перестаёт сидеть (или отпущен после /grab).
 * Отменить нельзя: к этому моменту сиденье уже признано недействительным.
 */
public class PlayerStandEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final SitType type;
    private final StandReason reason;

    public PlayerStandEvent(@NotNull Player who, @NotNull SitType type, @NotNull StandReason reason) {
        super(who);
        this.type = type;
        this.reason = reason;
    }

    public @NotNull SitType getType() {
        return type;
    }

    public @NotNull StandReason getReason() {
        return reason;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
