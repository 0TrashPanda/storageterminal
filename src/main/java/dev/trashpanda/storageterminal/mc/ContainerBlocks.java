// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.mc;

import dev.trashpanda.storageterminal.core.BlockPosition;
import dev.trashpanda.storageterminal.core.ContainerKey;
import dev.trashpanda.storageterminal.core.ContainerType;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.TrappedChestBlock;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Recognizes supported container blocks in the client world and knows how they open. */
final class ContainerBlocks {
    /** A supported container found in the world. {@code positions} has 2 entries for a double chest. */
    record Found(ContainerKey key, ContainerType type, List<BlockPosition> positions) {
        boolean isDouble() {
            return positions.size() == 2;
        }

        int slotCount() {
            return isDouble() ? 54 : 27;
        }

        MenuType<?> expectedMenu() {
            if (type == ContainerType.SHULKER_BOX) {
                return MenuType.SHULKER_BOX;
            }
            return isDouble() ? MenuType.GENERIC_9x6 : MenuType.GENERIC_9x3;
        }
    }

    private ContainerBlocks() {
    }

    /** The supported container at {@code pos}, or null. Copper chests count as chests; ender chests are not containers. */
    static Found identify(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        String dimension = level.dimension().identifier().toString();
        if (block instanceof ChestBlock) {
            ContainerType type = block instanceof TrappedChestBlock ? ContainerType.TRAPPED_CHEST : ContainerType.CHEST;
            BlockPosition self = toCore(pos);
            if (state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                BlockPos otherPos = ChestBlock.getConnectedBlockPos(pos, state);
                BlockState other = level.getBlockState(otherPos);
                if (other.getBlock() == block && other.getValue(ChestBlock.TYPE) != ChestType.SINGLE
                        && ChestBlock.getConnectedBlockPos(otherPos, other).equals(pos)) {
                    BlockPosition otherCore = toCore(otherPos);
                    return new Found(new ContainerKey(dimension, BlockPosition.min(self, otherCore)), type,
                            List.of(self, otherCore));
                }
            }
            return new Found(new ContainerKey(dimension, self), type, List.of(self));
        }
        if (block instanceof BarrelBlock) {
            return new Found(new ContainerKey(dimension, toCore(pos)), ContainerType.BARREL, List.of(toCore(pos)));
        }
        if (block instanceof ShulkerBoxBlock) {
            return new Found(new ContainerKey(dimension, toCore(pos)), ContainerType.SHULKER_BOX, List.of(toCore(pos)));
        }
        return null;
    }

    /**
     * Why the container can't be opened right now, judged from the client world, or null if it can.
     * Mirrors the server: a chest (either half) with a solid block or a sitting cat on top; a closed
     * shulker box whose lid has no room.
     */
    static String blockedReason(Level level, Found found) {
        switch (found.type()) {
            case CHEST, TRAPPED_CHEST -> {
                for (BlockPosition p : found.positions()) {
                    if (ChestBlock.isChestBlockedAt(level, toMc(p))) {
                        return "something on top";
                    }
                }
                return null;
            }
            case SHULKER_BOX -> {
                BlockPos pos = toMc(found.positions().get(0));
                BlockState state = level.getBlockState(pos);
                if (level.getBlockEntity(pos) instanceof ShulkerBoxBlockEntity box
                        && box.getAnimationStatus() != ShulkerBoxBlockEntity.AnimationStatus.CLOSED) {
                    return null;
                }
                Direction facing = state.getValue(ShulkerBoxBlock.FACING);
                AABB lid = Shulker.getProgressDeltaAabb(1.0F, facing, 0.0F, 0.5F, Vec3.atBottomCenterOf(pos)).deflate(1.0E-6);
                return level.noCollision(lid) ? null : "no room for the lid";
            }
            default -> {
                return null;
            }
        }
    }

    /** A hit on the face of {@code pos} that points toward the eye, at that face's center. */
    static BlockHitResult hitToward(BlockPos pos, Vec3 eye) {
        Vec3 center = Vec3.atCenterOf(pos);
        Direction face = Direction.getApproximateNearest(eye.x - center.x, eye.y - center.y, eye.z - center.z);
        Vec3 location = center.add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        return new BlockHitResult(location, face, pos, false);
    }

    static BlockPosition toCore(BlockPos pos) {
        return new BlockPosition(pos.getX(), pos.getY(), pos.getZ());
    }

    static BlockPos toMc(BlockPosition pos) {
        return new BlockPos(pos.x(), pos.y(), pos.z());
    }
}
