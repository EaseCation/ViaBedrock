package net.raphimc.viabedrock.experimental.inventory;

import com.viaversion.viaversion.api.minecraft.BlockPosition;

/**
 * 复刻 Java 1.21.8 放置时的“玩家自身阻挡”判定：
 * {@code EntityGetter#isUnobstructed} 对目标方块碰撞形状与玩家 AABB 做 AND 求交。
 *
 * <p>沿轴求交时使用 {@code Shapes} 的 1.0E-7 容差；仅边界相接（交集宽度不超过该容差）
 * 不算相交，因此玩家中心正好距离方块边缘 0.30 时仍允许放置。</p>
 */
public final class PlacementPrediction {

    private static final double EPSILON = 1.0E-7;
    private static final double PLAYER_WIDTH = 0.6;
    private static final double PLAYER_STANDING_HEIGHT = 1.8;
    private static final double PLAYER_CROUCHING_HEIGHT = 1.5;

    private PlacementPrediction() {
    }

    /**
     * @param target    待放置方块位置
     * @param feetX     玩家脚底 X（Java 双精度坐标）
     * @param feetY     玩家脚底 Y
     * @param feetZ     玩家脚底 Z
     * @param crouching 是否潜行，决定原版玩家盒高度
     * @return 目标格完整立方体是否与玩家盒真正重叠
     */
    public static boolean intersectsPlayer(final BlockPosition target, final double feetX, final double feetY,
                                           final double feetZ, final boolean crouching) {
        final double radius = PLAYER_WIDTH / 2.0;
        final double height = crouching ? PLAYER_CROUCHING_HEIGHT : PLAYER_STANDING_HEIGHT;
        return overlap(target.x(), target.x() + 1.0, feetX - radius, feetX + radius)
                && overlap(target.y(), target.y() + 1.0, feetY, feetY + height)
                && overlap(target.z(), target.z() + 1.0, feetZ - radius, feetZ + radius);
    }

    private static boolean overlap(final double shapeMin, final double shapeMax,
                                   final double entityMin, final double entityMax) {
        return Math.min(shapeMax, entityMax) - Math.max(shapeMin, entityMin) > EPSILON;
    }
}
