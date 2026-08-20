package github.kasuminova.ssoptimizer.common.render.tessellation;

/**
 * 简单多边形三角化器。
 * <p>
 * 动机：游戏原版 {@code Tesselator.renderTriangles} 每帧为舰船 stencil 蒙版执行一次
 * GLU 单调剖分（gluNewTess + 回调立即模式画三角形），单轮廓即达数百微秒级。
 * 船体 {@code Bounds.origSegments} 是顶点数十级、无孔、内容静态的单轮廓闭合简单多边形，
 * 完全可以用更轻量的算法一次性三角化并缓存结果。<br>
 * 本接口只负责「顶点序列 → 三角形 soup」的纯几何计算，不触碰 GL，
 * 以便独立于渲染环境做单元测试；GL 提交与缓存由 {@link ShipMaskMeshCache} 负责。
 */
public interface Triangulator {

    /**
     * 将单个无孔简单多边形三角化为三角形 soup。
     * <p>
     * 输入顶点默认按轮廓顺序排列（顺/逆时针均可，实现内部统一为逆时针）；
     * 允许首尾存在闭合重复点、连续近似重复点与共线点，实现负责预处理剔除。
     *
     * @param xy          顶点坐标，x,y 交错排列，长度至少为 {@code vertexCount * 2}
     * @param vertexCount 参与三角化的顶点数
     * @return x,y 交错的三角形 soup（每 6 个浮点数一个三角形，坐标与输入同一局部坐标系）；
     *         顶点数不足或面积退化为零的「空多边形」返回长度 0 数组；
     *         自交或数值退化导致无法三角化时返回 {@code null}（调用方负责降级处理）
     */
    float[] triangulate(float[] xy, int vertexCount);
}
