package github.kasuminova.ssoptimizer.common.render.queue;

/**
 * 录制侧编码、渲染线程 native 执行的批次渲染命令
 * （设计见 {@code docs/design/native-render-command.md}）。
 * <p>
 * 动机：渲染线程分离模式下主线程无 GL context，「单次 JNI 完成整段绘制」的
 * native 渲染器（引擎合批、sprite 批次等）无法在主线程直执。本接口把这类
 * 渲染并入队列体系：录制侧只做纯 CPU 编码（实例参数 → 池化直接缓冲），
 * 渲染线程执行侧经单次 JNI（glad 直调）完成绘制。
 * <p>
 * 契约：
 * <ul>
 *   <li>录制侧不得触碰任何 GL 调用（状态设置经 bridge 录制，与命令在帧
 *       列表中的相对顺序即原调用序列顺序）；</li>
 *   <li>实现类<b>不得</b>同时实现 {@link MergedBatchCommand}——native 命令
 *       在命令流中天然切开顶点批次串，其前后串正常收口；</li>
 *   <li>display list 编译窗口由录制侧分流（如
 *       {@code GLListManager.buildingList} 时走 immediate 等价路径），
 *       native 命令保证不出现在编译窗口内（VBO 写入不可编译、客户端数组
 *       按指针捕获，窗口内执行语义必错）；</li>
 *   <li>执行后框架统一失效顶点合并器的外部状态缓存并归还编码缓冲，
 *       实现类无需自行处理（见 bridge 侧的基类实现）。</li>
 * </ul>
 */
public interface NativeRenderCommand extends GlCommand {
}
