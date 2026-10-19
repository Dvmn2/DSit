package net.dvmn2.dsit;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.PlayerInventory;

/**
 * Игровые события: посадка по ПКМ на блок/игрока, запрет слезать у удерживаемых,
 * очистка при выходе/смерти. Слезание обычных сидящих отслеживается в {@link SeatManager#start()}
 * (проверка каждый тик), поэтому отдельный обработчик для них не нужен.
 */
public final class SitListener implements Listener {

    private final SeatManager seats;
    private final PoseManager poses;
    private final DSitSettings settings;

    public SitListener(SeatManager seats, PoseManager poses, DSitSettings settings) {
        this.seats = seats;
        this.poses = poses;
        this.settings = settings;
    }

    /**
     * ПКМ по блоку: садимся на ступеньки/плиты и т.д.
     */
    @EventHandler(ignoreCancelled = true)
    public void onInteractBlock(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (!settings.isBlockEnabled() || event.getClickedBlock() == null) {
            return;
        }

        Player player = event.getPlayer();
        if (!player.hasPermission(Permissions.SIT_BLOCK)) {
            return;
        }
        if (settings.isBlockDenyWhenSneaking() && player.isSneaking()) {
            return;
        }
        if (settings.isBlockRequireEmptyHand() && !handsEmpty(player)) {
            return;
        }
        if (!seats.isSittable(event.getClickedBlock())) {
            return;
        }

        SitResult result = seats.sitOnBlock(player, event.getClickedBlock());
        switch (result) {
            case SUCCESS -> event.setCancelled(true);
            case TARGET_BUSY -> player.sendMessage(Lang.get(Lang.Key.SEAT_OCCUPIED, player));
            default -> {
            }
        }
    }

    /**
     * ПКМ по игроку: садимся ему на голову.
     */
    @EventHandler(ignoreCancelled = true)
    public void onInteractPlayer(PlayerInteractEntityEvent event) {
        // PlayerInteractAtEntityEvent — подкласс; обрабатываем только "обычное" событие, чтобы не сработать дважды.
        if (event instanceof PlayerInteractAtEntityEvent || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (!(event.getRightClicked() instanceof Player target) || !settings.isPlayerEnabled()) {
            return;
        }

        Player player = event.getPlayer();
        if (!player.hasPermission(Permissions.SIT_PLAYER)) {
            return;
        }
        if (settings.isPlayerRequireSneaking() && !player.isSneaking()) {
            return;
        }
        if (settings.isPlayerRequireEmptyHand() && !handsEmpty(player)) {
            return;
        }

        SitResult result = seats.sitOnPlayer(player, target);
        switch (result) {
            case SUCCESS -> event.setCancelled(true);
            case TARGET_BUSY -> player.sendMessage(Lang.get(Lang.Key.TARGET_BUSY, player));
            default -> {
            }
        }
    }

    /**
     * Удерживаемому игроку нельзя слезть. Если выход нельзя отменить — вернёт tick в SeatManager.
     */
    @EventHandler(ignoreCancelled = true)
    public void onDismount(EntityDismountEvent event) {
        if (event.getEntity() instanceof Player player && seats.isGrabbed(player) && event.isCancellable()) {
            event.setCancelled(true);
        }
    }

    /**
     * Удерживаемого игрока нельзя телепортировать: сначала /ungrab.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!seats.isInternalTeleport() && seats.isGrabbed(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /**
     * Shift поднимает лежащего (/lay).
     */
    @EventHandler(ignoreCancelled = true)
    public void onToggleSneak(PlayerToggleSneakEvent event) {
        if (event.isSneaking()) {
            poses.handleSneak(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        seats.handleQuit(event.getPlayer());
        poses.handleQuit(event.getPlayer());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        seats.handleDeath(event.getPlayer());
        poses.handleDeath(event.getPlayer());
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        seats.purgeOrphans(event.getEntities());
    }

    private static boolean handsEmpty(Player player) {
        PlayerInventory inventory = player.getInventory();
        return inventory.getItemInMainHand().isEmpty() && inventory.getItemInOffHand().isEmpty();
    }
}
