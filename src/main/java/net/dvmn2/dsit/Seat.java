package net.dvmn2.dsit;

import org.bukkit.block.Block;
import org.bukkit.entity.Interaction;

import java.util.UUID;

/**
 * Активное сиденье: игрок + невидимая сущность Interaction, на которой он едет.
 *
 * @param type     способ посадки
 * @param playerId тот, кто сидит (или удерживается)
 * @param entity   сущность-сиденье
 * @param block    блок под сиденьем (только для {@link SitType#BLOCK})
 * @param anchorId игрок, на голове которого сидят (только для {@link SitType#PLAYER})
 */
record Seat(SitType type, UUID playerId, Interaction entity, Block block, UUID anchorId) {
}
