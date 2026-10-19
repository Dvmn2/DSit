package net.dvmn2.dsit;

import net.dvmn2.dsit.event.PlayerSitEvent;
import net.dvmn2.dsit.event.PlayerStandEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Позы /lay (лежание) и /crawl (ползанье). Работает только на Paper API, без пакетов и NMS.
 * <p>
 * <b>Лежание.</b> На сервере фиксируется поза {@link Pose#SLEEPING} — так лежащего видят другие игроки.
 * Сам игрок свою позу видит обычной: клиент пересчитывает её сам. Чтобы лежащий не ходил и не прыгал,
 * ему на время обнуляются скорость и сила прыжка (временные модификаторы атрибутов, они не сохраняются
 * в данных игрока). Выход — shift или повторная команда.
 * <p>
 * <b>Ползанье.</b> Над головой игрока только ему (через sendBlockChange) показываются невидимые барьеры:
 * клиент сам переходит в ползание. На сервере фиксируется поза {@link Pose#SWIMMING}, чтобы другие видели то же.
 * Барьеры переставляются за игроком; выход — повторная команда.
 * <p>
 * Работает только с основным потоком сервера.
 */
public final class PoseManager {

    private static final int NO_LAYER = Integer.MIN_VALUE;
    /** Как часто (в тиках) заново отправлять барьеры: на случай, если сервер перезаписал их обновлением блока. */
    private static final int REFRESH_INTERVAL = 5;
    /** Насколько (в квадрате) лежащего могут сдвинуть, прежде чем он перестанет считаться лежащим. */
    private static final double LAY_MAX_DRIFT_SQUARED = 2.25;

    private final JavaPlugin plugin;
    private final DSitSettings settings;
    private final SeatManager seats;

    private final BlockData barrier = Material.BARRIER.createBlockData();
    private final NamespacedKey speedKey;
    private final NamespacedKey jumpKey;

    private final Map<UUID, LayState> laying = new HashMap<>();
    private final Map<UUID, CrawlState> crawling = new HashMap<>();

    private BukkitTask tickTask;
    private int ticks;

    public PoseManager(JavaPlugin plugin, DSitSettings settings, SeatManager seats) {
        this.plugin = plugin;
        this.settings = settings;
        this.seats = seats;
        this.speedKey = new NamespacedKey(plugin, "lay_speed");
        this.jumpKey = new NamespacedKey(plugin, "lay_jump");
    }

    // ---------------------------------------------------------------- жизненный цикл

    public void start() {
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        Set<UUID> ids = new HashSet<>(laying.keySet());
        ids.addAll(crawling.keySet());
        for (UUID id : ids) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                stop(player, StandReason.DISABLE);
            }
        }
        laying.clear();
        crawling.clear();
    }

    // ---------------------------------------------------------------- запросы

    public boolean isLaying(Player player) {
        return laying.containsKey(player.getUniqueId());
    }

    public boolean isCrawling(Player player) {
        return crawling.containsKey(player.getUniqueId());
    }

    public boolean isPosed(Player player) {
        return isLaying(player) || isCrawling(player);
    }

    // ---------------------------------------------------------------- старт / стоп

    /** /lay: лечь на месте. */
    public SitResult startLay(Player player) {
        SitResult result = begin(player);
        if (result != SitResult.SUCCESS) {
            return result;
        }
        Location feet = player.getLocation();
        if (!fireSit(player, SitType.LAY, feet)) {
            return SitResult.CANCELLED;
        }

        AttributeModifier speed = new AttributeModifier(speedKey, -1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1);
        AttributeModifier jump = new AttributeModifier(jumpKey, -1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1);
        addModifier(player, Attribute.MOVEMENT_SPEED, speed);
        addModifier(player, Attribute.JUMP_STRENGTH, jump);

        laying.put(player.getUniqueId(), new LayState(feet, speed, jump));
        player.setSprinting(false);
        player.setPose(Pose.SLEEPING, true);
        return SitResult.SUCCESS;
    }

    /** /crawl: начать ползти. */
    public SitResult startCrawl(Player player) {
        SitResult result = begin(player);
        if (result != SitResult.SUCCESS) {
            return result;
        }
        Location feet = player.getLocation();
        int layer = barrierLayer(feet.getY());
        if (layer == NO_LAYER) {
            return SitResult.INVALID_LOCATION;
        }
        if (!fireSit(player, SitType.CRAWL, feet)) {
            return SitResult.CANCELLED;
        }

        CrawlState state = new CrawlState(feet.getWorld());
        crawling.put(player.getUniqueId(), state);
        player.setSprinting(false);
        player.setPose(Pose.SWIMMING, true);
        refreshBarriers(player, state, feet.getBlockX(), feet.getBlockZ(), layer);
        return SitResult.SUCCESS;
    }

    /** Снимает любую позу игрока. false — он ни лежал, ни полз. */
    public boolean stop(Player player, StandReason reason) {
        UUID id = player.getUniqueId();

        LayState lay = laying.remove(id);
        if (lay != null) {
            removeModifier(player, Attribute.MOVEMENT_SPEED, lay.speed());
            removeModifier(player, Attribute.JUMP_STRENGTH, lay.jump());
            player.setPose(Pose.STANDING, false);
            Bukkit.getPluginManager().callEvent(new PlayerStandEvent(player, SitType.LAY, reason));
            return true;
        }

        CrawlState crawl = crawling.remove(id);
        if (crawl != null) {
            for (BlockPos pos : crawl.fakes) {
                restore(player, crawl.world, pos);
            }
            player.setPose(Pose.STANDING, false);
            Bukkit.getPluginManager().callEvent(new PlayerStandEvent(player, SitType.CRAWL, reason));
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- реакции на события

    /** Shift поднимает лежащего. Ползущего не трогает. */
    public void handleSneak(Player player) {
        if (isLaying(player)) {
            stop(player, StandReason.DISMOUNT);
        }
    }

    public void handleQuit(Player player) {
        stop(player, StandReason.QUIT);
    }

    public void handleDeath(Player player) {
        stop(player, StandReason.DEATH);
    }

    // ---------------------------------------------------------------- общие проверки

    /**
     * Готовит игрока к позе: убирает прежнее сиденье/позу и проверяет, что он может лечь/ползти.
     * Удерживаемого командой /grab трогать нельзя.
     */
    private SitResult begin(Player player) {
        if (seats.isGrabbed(player)) {
            return SitResult.SELF_BUSY;
        }
        if (seats.isSeated(player)) {
            seats.stand(player);
        }
        if (isPosed(player)) {
            stop(player, StandReason.COMMAND);
        }

        if (!player.isOnline()
                || player.isDead()
                || player.isSleeping()
                || player.isGliding()
                || player.isFlying()
                || player.getGameMode() == GameMode.SPECTATOR
                || player.isInsideVehicle()
                || !player.getPassengers().isEmpty()) {
            return SitResult.SELF_BUSY;
        }
        if (player.isInWater() || player.isInLava() || !seats.hasSupport(player, player.getLocation())) {
            return SitResult.INVALID_LOCATION;
        }
        return SitResult.SUCCESS;
    }

    private boolean fireSit(Player player, SitType type, Location location) {
        PlayerSitEvent event = new PlayerSitEvent(player, type, location, null, null);
        Bukkit.getPluginManager().callEvent(event);
        return !event.isCancelled();
    }

    private void addModifier(Player player, Attribute attribute, AttributeModifier modifier) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance != null) {
            instance.addTransientModifier(modifier);
        }
    }

    private void removeModifier(Player player, Attribute attribute, AttributeModifier modifier) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance != null) {
            instance.removeModifier(modifier);
        }
    }

    // ---------------------------------------------------------------- проверка каждый тик

    private void tick() {
        if (laying.isEmpty() && crawling.isEmpty()) {
            return;
        }
        ticks++;

        for (UUID id : new ArrayList<>(laying.keySet())) {
            LayState state = laying.get(id);
            Player player = Bukkit.getPlayer(id);
            if (state == null) {
                continue;
            }
            if (player == null) {
                laying.remove(id);
                continue;
            }
            tickLay(player, state);
        }

        for (UUID id : new ArrayList<>(crawling.keySet())) {
            CrawlState state = crawling.get(id);
            Player player = Bukkit.getPlayer(id);
            if (state == null) {
                continue;
            }
            if (player == null) {
                crawling.remove(id);
                continue;
            }
            tickCrawl(player, state);
        }
    }

    private void tickLay(Player player, LayState state) {
        if (player.isDead()) {
            stop(player, StandReason.DEATH);
            return;
        }
        if (!settings.isLayEnabled()) {
            stop(player, StandReason.COMMAND);
            return;
        }
        if (player.isInsideVehicle() || player.isFlying() || player.isGliding() || player.isSleeping()
                || player.getGameMode() == GameMode.SPECTATOR) {
            stop(player, StandReason.DETACHED);
            return;
        }
        Location now = player.getLocation();
        Location anchor = state.anchor();
        if (now.getWorld() != anchor.getWorld() || now.distanceSquared(anchor) > LAY_MAX_DRIFT_SQUARED) {
            stop(player, StandReason.DETACHED);
            return;
        }
        if (player.isInWater() || player.isInLava() || !seats.hasSupport(player, now)) {
            stop(player, StandReason.BLOCK_CHANGED);
        }
    }

    private void tickCrawl(Player player, CrawlState state) {
        if (player.isDead()) {
            stop(player, StandReason.DEATH);
            return;
        }
        if (!settings.isCrawlEnabled()) {
            stop(player, StandReason.COMMAND);
            return;
        }
        if (player.isInsideVehicle() || player.isFlying() || player.isGliding() || player.isSleeping()
                || player.getGameMode() == GameMode.SPECTATOR) {
            stop(player, StandReason.DETACHED);
            return;
        }
        if (player.isInWater() || player.isInLava()) {
            stop(player, StandReason.BLOCK_CHANGED);
            return;
        }

        Location loc = player.getLocation();
        if (loc.getWorld() != state.world) {
            // Игрок в другом мире: старые подмены там уже не нужны.
            state.world = loc.getWorld();
            state.fakes = new HashSet<>();
            state.layer = NO_LAYER;
            state.x = Integer.MIN_VALUE;
        }

        // Высоту слоя пересчитываем только на земле: в падении оставляем прежнюю.
        int layer = state.layer;
        if (seats.hasSupport(player, loc)) {
            layer = barrierLayer(loc.getY());
            if (layer == NO_LAYER) {
                stop(player, StandReason.BLOCK_CHANGED);
                return;
            }
        }

        int cx = loc.getBlockX();
        int cz = loc.getBlockZ();
        boolean changed = layer != state.layer || cx != state.x || cz != state.z;
        if (changed || ticks % REFRESH_INTERVAL == 0) {
            refreshBarriers(player, state, cx, cz, layer);
        }
    }

    // ---------------------------------------------------------------- барьеры для ползанья

    /**
     * Индекс блока, в котором нужны барьеры, чтобы клиент перешёл в ползание: зазор между ногами
     * и барьером должен быть не меньше 0.6 (поза плавания) и меньше 1.5 (не помещается присед).
     * На полублоках (ноги на 0.4–0.51 выше целого) подходящего слоя нет.
     */
    private static int barrierLayer(double feetY) {
        int base = (int) Math.floor(feetY);
        double fraction = feetY - base;
        if (fraction < 0.39) {
            return base + 1;
        }
        if (fraction > 0.51) {
            return base + 2;
        }
        return NO_LAYER;
    }

    /** Ставит барьеры 3x3 вокруг игрока на нужной высоте; прежние, ставшие ненужными, возвращает к настоящим блокам. */
    private void refreshBarriers(Player player, CrawlState state, int cx, int cz, int layer) {
        World world = state.world;
        Set<BlockPos> wanted = new HashSet<>();
        if (layer != NO_LAYER && layer >= world.getMinHeight() && layer < world.getMaxHeight()) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int x = cx + dx;
                    int z = cz + dz;
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                        continue;
                    }
                    if (!world.getBlockAt(x, layer, z).isPassable()) {
                        continue; // там уже есть настоящий потолок
                    }
                    wanted.add(new BlockPos(x, layer, z));
                }
            }
        }

        for (BlockPos old : state.fakes) {
            if (!wanted.contains(old)) {
                restore(player, world, old);
            }
        }
        for (BlockPos pos : wanted) {
            player.sendBlockChange(new Location(world, pos.x(), pos.y(), pos.z()), barrier);
        }

        state.fakes = wanted;
        state.layer = layer;
        state.x = cx;
        state.z = cz;
    }

    private void restore(Player player, World world, BlockPos pos) {
        if (!player.getWorld().equals(world) || !world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) {
            return;
        }
        Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
        player.sendBlockChange(block.getLocation(), block.getBlockData());
    }

    // ---------------------------------------------------------------- состояния

    private record LayState(Location anchor, AttributeModifier speed, AttributeModifier jump) {
    }

    private record BlockPos(int x, int y, int z) {
    }

    private static final class CrawlState {
        World world;
        int layer = NO_LAYER;
        int x = Integer.MIN_VALUE;
        int z = Integer.MIN_VALUE;
        Set<BlockPos> fakes = new HashSet<>();

        CrawlState(World world) {
            this.world = world;
        }
    }
}
