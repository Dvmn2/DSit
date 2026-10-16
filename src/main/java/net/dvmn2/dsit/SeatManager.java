package net.dvmn2.dsit;

import net.dvmn2.dsit.event.PlayerSitEvent;
import net.dvmn2.dsit.event.PlayerStandEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Всё, что связано с сиденьями: создание, учёт, проверка на каждом тике и очистка.
 * <p>
 * Сиденье — невидимая сущность {@link Interaction} нулевого размера, на которой "едет" игрок.
 * Для {@link SitType#PLAYER} сущность в свою очередь едет на голове целевого игрока.
 * Для {@link SitType#GRAB} сущность стоит на месте, а игрок удерживается на ней:
 * выход отменяется в {@link SitListener}, а каждый тик {@link #tick()} возвращает игрока на сиденье,
 * если его всё-таки с него убрали.
 * <p>
 * Работает только с основным потоком сервера.
 */
public final class SeatManager {

    /** Сдвиг игрока на ступеньках к нижней стороне, чтобы спина не уходила в верхнюю ступень. */
    private static final double STAIR_SHIFT = 0.1;
    private static final double HALF_WIDTH = 0.3;
    private static final double PLAYER_HEIGHT = 1.8;
    /** Высота сидящего игрока над поверхностью (для проверки свободного места над сиденьем). */
    private static final double SITTER_HEIGHT = 1.2;
    /** Если после слезания игрок дальше этого расстояния (в квадрате) от сиденья — его увели, не возвращаем. */
    private static final double DETACH_DISTANCE_SQUARED = 9.0;

    private final JavaPlugin plugin;
    private final DSitSettings settings;
    private final NamespacedKey seatKey;

    private final Map<UUID, Seat> bySitter = new HashMap<>();
    private final Map<UUID, Seat> byEntity = new HashMap<>();
    private final Map<UUID, Seat> byAnchor = new HashMap<>();
    private final Map<Block, Seat> byBlock = new HashMap<>();

    private BukkitTask tickTask;
    private boolean internalTeleport;

    public SeatManager(JavaPlugin plugin, DSitSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.seatKey = new NamespacedKey(plugin, "seat");
    }

    // ---------------------------------------------------------------- жизненный цикл

    public void start() {
        for (World world : Bukkit.getWorlds()) {
            purgeOrphans(world.getEntitiesByClass(Interaction.class));
        }
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        for (Seat seat : new ArrayList<>(bySitter.values())) {
            remove(seat, StandReason.DISABLE);
        }
    }

    /**
     * Удаляет "осиротевшие" сиденья (помечены нашим ключом, но не числятся активными),
     * например после /reload или аварийного завершения.
     */
    public void purgeOrphans(Collection<? extends Entity> entities) {
        for (Entity entity : new ArrayList<>(entities)) {
            if (entity instanceof Interaction interaction
                    && interaction.getPersistentDataContainer().has(seatKey, PersistentDataType.BYTE)
                    && !byEntity.containsKey(interaction.getUniqueId())) {
                interaction.remove();
            }
        }
    }

    // ---------------------------------------------------------------- запросы

    public boolean isSeated(Player player) {
        return bySitter.containsKey(player.getUniqueId());
    }

    public boolean isGrabbed(Player player) {
        Seat seat = bySitter.get(player.getUniqueId());
        return seat != null && seat.type() == SitType.GRAB;
    }

    /** Как сидит игрок, либо null, если не сидит. */
    public @Nullable SitType getSitType(Player player) {
        Seat seat = bySitter.get(player.getUniqueId());
        return seat == null ? null : seat.type();
    }

    public List<String> grabbedNames() {
        List<String> names = new ArrayList<>();
        for (Seat seat : bySitter.values()) {
            if (seat.type() == SitType.GRAB) {
                Player player = Bukkit.getPlayer(seat.playerId());
                if (player != null) {
                    names.add(player.getName());
                }
            }
        }
        return names;
    }

    /** true, пока плагин сам телепортирует игрока: такие телепорты нельзя блокировать. */
    public boolean isInternalTeleport() {
        return internalTeleport;
    }

    /** Можно ли сесть на этот блок согласно конфигу и форме блока. */
    public boolean isSittable(Block block) {
        if (!settings.isAllowedBlock(block.getType())) {
            return false;
        }
        if (block.getBlockData() instanceof Stairs stairs && stairs.getHalf() != Bisected.Half.BOTTOM) {
            return false; // перевёрнутые ступеньки
        }
        double surface = surfaceY(block);
        int top = (int) Math.floor(surface + SITTER_HEIGHT);
        for (int y = block.getY() + 1; y <= top; y++) {
            if (!block.getWorld().getBlockAt(block.getX(), y, block.getZ()).isPassable()) {
                return false; // над сиденьем нет места
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- посадка

    /** /sit: посадить игрока там, где он стоит. */
    public SitResult sitHere(Player player) {
        if (!canStartSitting(player)) {
            return SitResult.SELF_BUSY;
        }
        Location feet = player.getLocation();
        if (player.isInWater() || player.isInLava() || !hasSupport(player, feet)) {
            return SitResult.INVALID_LOCATION;
        }
        Location seat = feet.clone();
        seat.setY(feet.getY() + settings.getSeatYOffset());
        seat.setPitch(0f);
        return startSeat(player, SitType.COMMAND, seat, null, null, false);
    }

    /** ПКМ по блоку. */
    public SitResult sitOnBlock(Player player, Block block) {
        if (byBlock.containsKey(block)) {
            return SitResult.TARGET_BUSY;
        }
        if (!canStartSitting(player)) {
            return SitResult.SELF_BUSY;
        }
        if (!isSittable(block)) {
            return SitResult.INVALID_LOCATION;
        }
        Location seat = blockSeatLocation(block, player.getLocation().getYaw());
        boolean applyYaw = block.getBlockData() instanceof Stairs;
        return startSeat(player, SitType.BLOCK, seat, block, null, applyYaw);
    }

    /** ПКМ по игроку: сесть ему на голову. */
    public SitResult sitOnPlayer(Player sitter, Player target) {
        if (sitter.getUniqueId().equals(target.getUniqueId())) {
            return SitResult.INVALID_LOCATION;
        }
        if (!canStartSitting(sitter)) {
            return SitResult.SELF_BUSY;
        }
        if (!isValidTarget(target)) {
            return SitResult.TARGET_BUSY;
        }
        return startSeat(sitter, SitType.PLAYER, target.getLocation(), null, target, false);
    }

    /** Игрок встаёт (повторный /sit). Удерживаемых игроков так отпустить нельзя. */
    public boolean stand(Player player) {
        Seat seat = bySitter.get(player.getUniqueId());
        if (seat == null || seat.type() == SitType.GRAB) {
            return false;
        }
        remove(seat, player, StandReason.COMMAND);
        return true;
    }

    // ---------------------------------------------------------------- grab

    /** /grab: удерживает игрока на месте на невидимом сиденье. */
    public SitResult grab(Player target) {
        Seat existing = bySitter.get(target.getUniqueId());
        if (existing != null && existing.type() == SitType.GRAB) {
            return SitResult.ALREADY;
        }
        if (target.isDead() || target.getGameMode() == GameMode.SPECTATOR) {
            return SitResult.FAILED;
        }

        PlayerSitEvent event = new PlayerSitEvent(target, SitType.GRAB, target.getLocation(), null, null);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return SitResult.CANCELLED;
        }

        // Освобождаем всё, что было связано с игроком до этого.
        if (existing != null) {
            remove(existing, target, StandReason.COMMAND);
        }
        Seat carried = byAnchor.get(target.getUniqueId());
        if (carried != null) {
            remove(carried, StandReason.TARGET_LOST);
        }
        if (target.isInsideVehicle()) {
            target.leaveVehicle();
        }

        Interaction entity = spawnSeat(target.getLocation());
        if (!entity.addPassenger(target)) {
            entity.remove();
            return SitResult.FAILED;
        }
        register(new Seat(SitType.GRAB, target.getUniqueId(), entity, null, null));
        return SitResult.SUCCESS;
    }

    /** /ungrab: отпускает игрока. false — он и не удерживался. */
    public boolean ungrab(Player target) {
        Seat seat = bySitter.get(target.getUniqueId());
        if (seat == null || seat.type() != SitType.GRAB) {
            return false;
        }
        remove(seat, target, StandReason.COMMAND);
        return true;
    }

    // ---------------------------------------------------------------- реакции на события

    public void handleQuit(Player player) {
        Seat own = bySitter.get(player.getUniqueId());
        if (own != null) {
            remove(own, player, StandReason.QUIT);
        }
        Seat carried = byAnchor.get(player.getUniqueId());
        if (carried != null) {
            remove(carried, StandReason.TARGET_LOST);
        }
    }

    public void handleDeath(Player player) {
        Seat own = bySitter.get(player.getUniqueId());
        if (own != null) {
            remove(own, player, StandReason.DEATH);
        }
        Seat carried = byAnchor.get(player.getUniqueId());
        if (carried != null) {
            remove(carried, StandReason.TARGET_LOST);
        }
    }

    // ---------------------------------------------------------------- создание сиденья

    private boolean canStartSitting(Player player) {
        return !bySitter.containsKey(player.getUniqueId())
                && player.isOnline()
                && !player.isDead()
                && !player.isSleeping()
                && !player.isGliding()
                && !player.isFlying()
                && player.getGameMode() != GameMode.SPECTATOR
                && player.getVehicle() == null
                && player.getPassengers().isEmpty();
    }

    private boolean isValidTarget(Player target) {
        return target.isOnline()
                && !target.isDead()
                && !target.isSleeping()
                && target.getGameMode() != GameMode.SPECTATOR
                && !target.isInsideVehicle()
                && target.getPassengers().isEmpty()
                && !bySitter.containsKey(target.getUniqueId())
                && !byAnchor.containsKey(target.getUniqueId());
    }

    private SitResult startSeat(Player player, SitType type, Location seatLocation,
                                @Nullable Block block, @Nullable Player anchor, boolean applyYaw) {
        PlayerSitEvent event = new PlayerSitEvent(player, type, seatLocation, block, anchor);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return SitResult.CANCELLED;
        }

        Interaction entity = spawnSeat(seatLocation);
        boolean mounted;
        if (anchor != null) {
            mounted = anchor.addPassenger(entity) && entity.addPassenger(player);
        } else {
            if (applyYaw) {
                // Поворачиваем игрока лицом к нижней стороне ступенек до посадки.
                Location rotated = player.getLocation();
                rotated.setYaw(seatLocation.getYaw());
                player.teleport(rotated);
            }
            mounted = entity.addPassenger(player);
        }
        if (!mounted) {
            entity.remove();
            return SitResult.FAILED;
        }

        register(new Seat(type, player.getUniqueId(), entity, block, anchor == null ? null : anchor.getUniqueId()));
        return SitResult.SUCCESS;
    }

    private Interaction spawnSeat(Location location) {
        return location.getWorld().spawn(location, Interaction.class, entity -> {
            entity.setInteractionWidth(0f);
            entity.setInteractionHeight(0f);
            entity.setResponsive(false);
            entity.setGravity(false);
            entity.setInvulnerable(true);
            entity.setSilent(true);
            entity.setPersistent(false);
            entity.getPersistentDataContainer().set(seatKey, PersistentDataType.BYTE, (byte) 1);
        });
    }

    private Location blockSeatLocation(Block block, float playerYaw) {
        double x = block.getX() + 0.5;
        double y = surfaceY(block) + settings.getSeatYOffset();
        double z = block.getZ() + 0.5;
        float yaw = playerYaw;

        if (block.getBlockData() instanceof Stairs stairs) {
            // facing у ступенек — сторона, в которую они "поднимаются"; садимся лицом вниз по ступеням.
            BlockFace low = stairs.getFacing().getOppositeFace();
            x += low.getModX() * STAIR_SHIFT;
            z += low.getModZ() * STAIR_SHIFT;
            yaw = yawOf(low);
        }
        return new Location(block.getWorld(), x, y, z, yaw, 0f);
    }

    /** Абсолютная высота поверхности, на которую садятся. */
    private double surfaceY(Block block) {
        BlockData data = block.getBlockData();
        if (data instanceof Stairs) {
            return block.getY() + 0.5;
        }
        if (data instanceof Slab slab && slab.getType() == Slab.Type.BOTTOM) {
            return block.getY() + 0.5;
        }
        return block.getBoundingBox().getMaxY();
    }

    /** В Minecraft yaw 0 — на юг (+Z), 90 — на запад, 180 — на север, -90 — на восток. */
    private static float yawOf(BlockFace face) {
        return switch (face) {
            case WEST -> 90f;
            case NORTH -> 180f;
            case EAST -> -90f;
            default -> 0f;
        };
    }

    // ---------------------------------------------------------------- учёт

    private void register(Seat seat) {
        bySitter.put(seat.playerId(), seat);
        byEntity.put(seat.entity().getUniqueId(), seat);
        if (seat.block() != null) {
            byBlock.put(seat.block(), seat);
        }
        if (seat.anchorId() != null) {
            byAnchor.put(seat.anchorId(), seat);
        }
    }

    /** @return false, если сиденье уже было удалено */
    private boolean unregister(Seat seat) {
        if (bySitter.get(seat.playerId()) != seat) {
            return false;
        }
        bySitter.remove(seat.playerId());
        byEntity.remove(seat.entity().getUniqueId());
        if (seat.block() != null) {
            byBlock.remove(seat.block());
        }
        if (seat.anchorId() != null) {
            byAnchor.remove(seat.anchorId());
        }
        return true;
    }

    private void remove(Seat seat, StandReason reason) {
        remove(seat, Bukkit.getPlayer(seat.playerId()), reason);
    }

    /**
     * Единственная точка удаления сиденья. Сначала убираем запись из учёта — чтобы
     * события, которые вызовет само удаление сущности, не считали игрока сидящим.
     */
    private void remove(Seat seat, @Nullable Player player, StandReason reason) {
        if (!unregister(seat)) {
            return;
        }

        Location exit = null;
        if (player != null) {
            if (reason.teleportsOut()) {
                exit = findExit(seat, player);
            }
            Bukkit.getPluginManager().callEvent(new PlayerStandEvent(player, seat.type(), reason));
        }

        if (seat.entity().isValid()) {
            seat.entity().remove();
        }

        if (exit != null) {
            internalTeleport = true;
            try {
                player.teleport(exit);
            } finally {
                internalTeleport = false;
            }
        }
    }

    // ---------------------------------------------------------------- проверка каждый тик

    private void tick() {
        if (bySitter.isEmpty()) {
            return;
        }
        for (Seat seat : new ArrayList<>(bySitter.values())) {
            if (bySitter.get(seat.playerId()) != seat) {
                continue; // удалено по ходу этого же тика
            }
            Player player = Bukkit.getPlayer(seat.playerId());
            if (player == null) {
                remove(seat, null, StandReason.DETACHED);
                continue;
            }
            if (player.isDead()) {
                remove(seat, player, StandReason.DEATH);
                continue;
            }
            switch (seat.type()) {
                case GRAB -> tickGrab(seat, player);
                case BLOCK -> tickBlock(seat, player);
                case COMMAND -> tickCommand(seat, player);
                case PLAYER -> tickPlayer(seat, player);
            }
        }
    }

    private void tickGrab(Seat seat, Player player) {
        Interaction entity = seat.entity();
        if (!entity.isValid()) {
            // Сиденье кто-то удалил (например, /kill @e): создаём новое на том же месте.
            unregister(seat);
            register(new Seat(SitType.GRAB, seat.playerId(), spawnSeat(entity.getLocation()), null, null));
            return;
        }
        if (same(player.getVehicle(), entity)) {
            return;
        }

        // Игрока сняли с сиденья — возвращаем его к сущности и сажаем обратно.
        Location target = entity.getLocation();
        Location current = player.getLocation();
        target.setYaw(current.getYaw());
        target.setPitch(current.getPitch());

        internalTeleport = true;
        try {
            if (player.isInsideVehicle()) {
                player.leaveVehicle();
            }
            player.teleport(target);
        } finally {
            internalTeleport = false;
        }
        entity.addPassenger(player);
    }

    private void tickBlock(Seat seat, Player player) {
        if (!seat.entity().isValid() || !same(player.getVehicle(), seat.entity())) {
            remove(seat, player, detachReason(player, seat));
        } else if (!isSittable(seat.block())) {
            remove(seat, player, StandReason.BLOCK_CHANGED);
        }
    }

    private void tickCommand(Seat seat, Player player) {
        if (!seat.entity().isValid() || !same(player.getVehicle(), seat.entity())) {
            remove(seat, player, detachReason(player, seat));
            return;
        }
        Location ground = seat.entity().getLocation().subtract(0, settings.getSeatYOffset(), 0);
        if (!hasSupport(player, ground)) {
            remove(seat, player, StandReason.BLOCK_CHANGED);
        }
    }

    private void tickPlayer(Seat seat, Player player) {
        Player anchor = Bukkit.getPlayer(seat.anchorId());
        if (anchor == null || anchor.isDead()) {
            remove(seat, player, StandReason.TARGET_LOST);
        } else if (!seat.entity().isValid() || !same(player.getVehicle(), seat.entity())) {
            remove(seat, player, detachReason(player, seat));
        } else if (!same(seat.entity().getVehicle(), anchor)) {
            remove(seat, player, StandReason.TARGET_LOST);
        }
    }

    /**
     * Если игрок слез сам — он рядом с сиденьем, и его надо вынести из блока (DISMOUNT).
     * Если его увели далеко (телепорт) — возвращать нельзя (DETACHED).
     */
    private StandReason detachReason(Player player, Seat seat) {
        Location playerLocation = player.getLocation();
        Location seatLocation = seat.entity().getLocation();
        boolean near = playerLocation.getWorld() == seatLocation.getWorld()
                && playerLocation.distanceSquared(seatLocation) <= DETACH_DISTANCE_SQUARED;
        return near ? StandReason.DISMOUNT : StandReason.DETACHED;
    }

    private static boolean same(@Nullable Entity a, @Nullable Entity b) {
        return a != null && b != null && a.getUniqueId().equals(b.getUniqueId());
    }

    // ---------------------------------------------------------------- геометрия

    /** Под точкой есть что-либо твёрдое (тонкий "зонд" чуть ниже ног). */
    private boolean hasSupport(Player player, Location feet) {
        BoundingBox probe = new BoundingBox(
                feet.getX() - 0.1, feet.getY() - 0.1, feet.getZ() - 0.1,
                feet.getX() + 0.1, feet.getY() - 0.001, feet.getZ() + 0.1);
        return player.wouldCollideUsing(probe);
    }

    private boolean isFree(Player player, double x, double y, double z) {
        BoundingBox box = new BoundingBox(
                x - HALF_WIDTH, y + 0.01, z - HALF_WIDTH,
                x + HALF_WIDTH, y + PLAYER_HEIGHT, z + HALF_WIDTH);
        return !player.wouldCollideUsing(box);
    }

    /**
     * Ищет безопасное место рядом с сиденьем, куда можно поставить игрока после слезания:
     * сначала свободное и с опорой под ногами, затем просто свободное.
     */
    private Location findExit(Seat seat, Player player) {
        Location base = exitBase(seat);
        Location view = player.getLocation();

        Vector forward = seat.entity().getLocation().getDirection().setY(0);
        List<double[]> ring = new ArrayList<>(List.of(
                new double[]{1, 0}, new double[]{-1, 0}, new double[]{0, 1}, new double[]{0, -1},
                new double[]{1, 1}, new double[]{1, -1}, new double[]{-1, 1}, new double[]{-1, -1}));
        // Сначала в сторону, куда смотрит сидящий (для ступенек — вниз по ступеням).
        ring.sort(Comparator.comparingDouble(o -> -(o[0] * forward.getX() + o[1] * forward.getZ())));

        List<double[]> offsets = new ArrayList<>();
        offsets.add(new double[]{0, 0});
        offsets.addAll(ring);

        double[] heights = {0, -0.5, -1, 1};
        Location fallback = null;
        for (double[] offset : offsets) {
            for (double dy : heights) {
                double x = base.getX() + offset[0];
                double y = base.getY() + dy;
                double z = base.getZ() + offset[1];
                if (!isFree(player, x, y, z)) {
                    continue;
                }
                Location candidate = new Location(base.getWorld(), x, y, z, view.getYaw(), view.getPitch());
                if (hasSupport(player, candidate)) {
                    return candidate;
                }
                if (fallback == null) {
                    fallback = candidate;
                }
            }
        }
        if (fallback != null) {
            return fallback;
        }
        Location last = base.clone().add(0, 1, 0);
        last.setYaw(view.getYaw());
        last.setPitch(view.getPitch());
        return last;
    }

    /** Точка, вокруг которой ищем выход: у игрока на голове — сам целевой игрок, иначе — сиденье. */
    private Location exitBase(Seat seat) {
        if (seat.anchorId() != null) {
            Player anchor = Bukkit.getPlayer(seat.anchorId());
            if (anchor != null && anchor.isValid()) {
                return anchor.getLocation();
            }
        }
        return seat.entity().getLocation();
    }
}
