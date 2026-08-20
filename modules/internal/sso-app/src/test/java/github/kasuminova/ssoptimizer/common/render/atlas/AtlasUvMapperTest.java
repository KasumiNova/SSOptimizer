package github.kasuminova.ssoptimizer.common.render.atlas;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@link AtlasUvMapper} 图集 UV 重映射计算的验证（纯计算，无游戏类依赖）。
 * <p>
 * 覆盖：换算公式正确性（原始纹理空间 → 图集空间）、幂等重推导（同贴图重复
 * setTexture 从缓存原始值重新推导 == 首次结果）、叠加 bug 机制复现（把图集值
 * 当原始值再换算会二次平移进入相邻 Region——修复前 setTexture 的串图根因）。
 */
class AtlasUvMapperTest {

    /** 原纹理 GL 尺寸（imageWidth / uScale 推得）。 */
    private static final float SRC_W = 256.0f;
    private static final float SRC_H = 256.0f;
    /** 图集页边长（像素）。 */
    private static final int ATLAS_SIZE = 1024;
    /** 图集区域左下角（像素）。 */
    private static final int REGION_X = 512;
    private static final int REGION_Y = 768;

    @Test
    void remapFromOriginProducesAtlasUv() {
        // 原始 UV：原点 (0,0)、宽高各占原纹理一半（0.5/0.5）
        AtlasUvMapper.RemappedUv uv = AtlasUvMapper.remapFromOrigin(
                0.0f, 0.0f, 0.5f, 0.5f, SRC_W, SRC_H, REGION_X, REGION_Y, ATLAS_SIZE);

        // (512 + 0 * 256) / 1024 = 0.5；（768 + 0 * 256) / 1024 = 0.75
        assertEquals(0.5f, uv.texX(), 1e-6f);
        assertEquals(0.75f, uv.texY(), 1e-6f);
        // 0.5 * 256 / 1024 = 0.125
        assertEquals(0.125f, uv.texWidth(), 1e-6f);
        assertEquals(0.125f, uv.texHeight(), 1e-6f);
        // 0.001 * 256 / 1024 = 0.00025（原版 renderRegion 0.001F 内缩的像素等价）
        assertEquals(0.00025f, uv.insetU(), 1e-6f);
        assertEquals(0.00025f, uv.insetV(), 1e-6f);
    }

    @Test
    void remapFromOriginHonorsNonZeroOriginUv() {
        // setTexX/setTexY 设过非零原始 UV（0.25/0.5）：换算必须叠加区域原点
        AtlasUvMapper.RemappedUv uv = AtlasUvMapper.remapFromOrigin(
                0.25f, 0.5f, 0.5f, 0.5f, SRC_W, SRC_H, REGION_X, REGION_Y, ATLAS_SIZE);

        // (512 + 0.25 * 256) / 1024 = 0.5625；（768 + 0.5 * 256) / 1024 = 0.875
        assertEquals(0.5625f, uv.texX(), 1e-6f);
        assertEquals(0.875f, uv.texY(), 1e-6f);
    }

    @Test
    void reDerivationFromCachedOriginIsIdempotent() {
        // 幂等修复路径：同贴图重复 setTexture 时从缓存的原始值重新推导——
        // 结果必须与首次换算完全一致（float 运算无状态，同输入必同输出）
        AtlasUvMapper.RemappedUv first = AtlasUvMapper.remapFromOrigin(
                0.0f, 0.0f, 0.5f, 0.5f, SRC_W, SRC_H, REGION_X, REGION_Y, ATLAS_SIZE);
        AtlasUvMapper.RemappedUv second = AtlasUvMapper.remapFromOrigin(
                0.0f, 0.0f, 0.5f, 0.5f, SRC_W, SRC_H, REGION_X, REGION_Y, ATLAS_SIZE);

        assertEquals(first.texX(), second.texX(), 1e-6f);
        assertEquals(first.texY(), second.texY(), 1e-6f);
        assertEquals(first.texWidth(), second.texWidth(), 1e-6f);
        assertEquals(first.texHeight(), second.texHeight(), 1e-6f);
    }

    @Test
    void reMappingAtlasUvAsOriginShiftsIntoAdjacentRegion() {
        // 修复前 bug 机制复现：setTexture 不重置 texX/texY，重复调用时当前值
        // 已是图集值——若继续按当前值换算（修复前行为）会再次叠加平移。
        // 首次换算产物（图集值）当作「原始值」再换算：
        AtlasUvMapper.RemappedUv first = AtlasUvMapper.remapFromOrigin(
                0.0f, 0.0f, 0.5f, 0.5f, SRC_W, SRC_H, REGION_X, REGION_Y, ATLAS_SIZE);
        AtlasUvMapper.RemappedUv buggy = AtlasUvMapper.remapFromOrigin(
                first.texX(), first.texY(), first.texWidth(), first.texHeight(),
                SRC_W, SRC_H, REGION_X, REGION_Y, ATLAS_SIZE);

        // (512 + 0.5 * 256) / 1024 = 0.625 ≠ 0.5：UV 原点二次平移，已滑出
        // 本区域进入相邻 Region——「从缓存原始值重新推导」正是消除此平移的
        // 修复路径（见 reDerivationFromCachedOriginIsIdempotent）
        assertNotEquals(first.texX(), buggy.texX(), 1e-6f);
        assertNotEquals(first.texY(), buggy.texY(), 1e-6f);
        assertEquals(0.625f, buggy.texX(), 1e-6f);
    }
}
