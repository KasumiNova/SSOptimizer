package github.kasuminova.ssoptimizer.common.render.atlas;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@link AtlasUvMapper} 图集 UV 重映射计算的验证（纯计算，无游戏类依赖）。
 * <p>
 * 覆盖：换算公式正确性（原始纹理空间 → 图集空间）、幂等重推导（同贴图重复
 * setTexture 从缓存原始值重新推导 == 首次结果）、叠加 bug 机制复现（把图集值
 * 当原始值再换算会二次平移进入相邻 Region——修复前 setTexture 的串图根因）、
 * 访问器透明换算（originalToAtlas/atlasToOriginal 往返恒等、跨度量 origin=0
 * 语义、模组写原始空间 UV 仍落在图集区域内——LWE 机甲武器串图修复）。
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

    @Test
    void remapFromOriginExposesAccessorConversionParams() {
        // 访问器换算参数随重映射一并产出：region 原点 + 原始→图集缩放
        AtlasUvMapper.RemappedUv uv = AtlasUvMapper.remapFromOrigin(
                0.0f, 0.0f, 0.5f, 0.5f, SRC_W, SRC_H, REGION_X, REGION_Y, ATLAS_SIZE);

        // 512 / 1024 = 0.5；768 / 1024 = 0.75
        assertEquals(0.5f, uv.originU(), 1e-6f);
        assertEquals(0.75f, uv.originV(), 1e-6f);
        // 256 / 1024 = 0.25
        assertEquals(0.25f, uv.scaleU(), 1e-6f);
        assertEquals(0.25f, uv.scaleV(), 1e-6f);
        // 换算参数与主公式一致：texX = originU + originX * scaleU
        assertEquals(uv.originU(), AtlasUvMapper.originalToAtlas(uv.originU(), uv.scaleU(), 0.0f), 1e-6f);
    }

    @Test
    void accessorConversionRoundTrips() {
        // setter/getter 换算对称：original → atlas → original 恒等（读写往返）
        AtlasUvMapper.RemappedUv uv = AtlasUvMapper.remapFromOrigin(
                0.25f, 0.5f, 0.5f, 0.5f, SRC_W, SRC_H, REGION_X, REGION_Y, ATLAS_SIZE);

        // 坐标量：origin + v * scale，再 (atlas - origin) / scale 还原
        float atlasX = AtlasUvMapper.originalToAtlas(uv.originU(), uv.scaleU(), 0.25f);
        assertEquals(uv.texX(), atlasX, 1e-6f);
        assertEquals(0.25f, AtlasUvMapper.atlasToOriginal(uv.originU(), uv.scaleU(), atlasX), 1e-6f);

        // 跨度量（宽/高）：origin = 0
        float atlasW = AtlasUvMapper.originalToAtlas(0.0f, uv.scaleU(), 0.5f);
        assertEquals(uv.texWidth(), atlasW, 1e-6f);
        assertEquals(0.5f, AtlasUvMapper.atlasToOriginal(0.0f, uv.scaleU(), atlasW), 1e-6f);
    }

    @Test
    void modWrittenOriginalSpaceUvStaysInsideRegion() {
        // LWE 机甲场景复现与修复验证：模组 hullmod 逐帧 setTexWidth(0/1.0)
        // 切换武器显隐——写的是原始空间值。修复前直接落到图集字段：1.0 采满
        // 整页宽度（串图/拉伸条纹）；修复后经访问器换算仍落在本区域内，
        // 且 getter 读回原始空间值。
        AtlasUvMapper.RemappedUv uv = AtlasUvMapper.remapFromOrigin(
                0.0f, 0.0f, 1.0f, 1.0f, SRC_W, SRC_H, REGION_X, REGION_Y, ATLAS_SIZE);

        // setter：mod setTexWidth(1.0) → 图集字段 = 0.25（= scaleU），区域右缘 0.75 未越界
        float writtenWidth = AtlasUvMapper.originalToAtlas(0.0f, uv.scaleU(), 1.0f);
        assertEquals(uv.texWidth(), writtenWidth, 1e-6f);
        assertEquals(uv.originU() + uv.scaleU(), uv.originU() + writtenWidth, 1e-6f);

        // getter：图集值读回原始空间 1.0，模组语义不受图集化影响
        assertEquals(1.0f, AtlasUvMapper.atlasToOriginal(0.0f, uv.scaleU(), writtenWidth), 1e-6f);

        // 隐藏写法 setTexWidth(0) → 图集宽度 0，不采样任何像素
        assertEquals(0.0f, AtlasUvMapper.originalToAtlas(0.0f, uv.scaleU(), 0.0f), 1e-6f);
    }
}
