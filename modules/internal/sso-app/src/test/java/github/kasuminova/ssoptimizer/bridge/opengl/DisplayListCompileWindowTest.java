package github.kasuminova.ssoptimizer.bridge.opengl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * display list 编译窗口的渲染线程侧状态与顶点批次回放分流验证。
 * <p>
 * 背景：GL 规范中 display list 编译对客户端数组（glVertexPointer 等）按
 * <b>指针捕获</b>、不回拷数据——渲染线程的 {@link VertexArrayBatch} 共享单例
 * 直接缓冲跨批次复用，列表回放时 glDrawArrays 会读到后续批次覆盖的陈旧内容
 * （实机症状：对话框舰队列表舰船图标串图）。修复为：渲染线程在编译窗口
 * （glNewList/glEndList 命令执行序之间）内把 {@link VertexBatchCommand} 强制
 * 切到逐指令 immediate 回放（glBegin/glVertex/glEnd 按数据捕获，回放正确）。
 * <p>
 * 本测试验证状态维护与分流判定接线。immediate/数组化回放路径内部的真实 GL
 * 调用无法在无上下文环境执行（见 {@link VertexArrayBatchTest} 的同类限制），
 * 数组化构建侧的合并语义由 {@link VertexArrayBatchTest} 覆盖。
 */
class DisplayListCompileWindowTest {

    @BeforeEach
    @AfterEach
    void resetCompileState() {
        // 静态状态在用例间归零（uninstall 之外的独立复位：本测试不安装队列）
        while (BridgeSupport.isCompilingDisplayList()) {
            BridgeSupport.onDisplayListCompileEnd();
        }
    }

    @Test
    void compileDepthTracksNestedNewListEndList() {
        assertFalse(BridgeSupport.isCompilingDisplayList(), "初始必须处于编译窗口外");

        BridgeSupport.onDisplayListCompileStart(); // glNewList #1
        assertTrue(BridgeSupport.isCompilingDisplayList(), "glNewList 命令体执行后进入编译窗口");

        // 嵌套列表编译：深度累加，窗口持续开放
        BridgeSupport.onDisplayListCompileStart(); // glNewList #2（嵌套）
        assertTrue(BridgeSupport.isCompilingDisplayList());

        BridgeSupport.onDisplayListCompileEnd(); // glEndList #2
        assertTrue(BridgeSupport.isCompilingDisplayList(), "外层窗口仍开放，深度 >0");

        BridgeSupport.onDisplayListCompileEnd(); // glEndList #1
        assertFalse(BridgeSupport.isCompilingDisplayList(), "深度归零后恢复窗口外（数组化回放）");
    }

    @Test
    void strayEndListResetsDepthToZero() {
        // 防御路径：glEndList 命令体先于 glNewList 出现（非法 GL 序列或命令流被
        // 帧悬挂切割的异常态）——深度不得递减为负（负数会让后续批次误判编译中）
        BridgeSupport.onDisplayListCompileEnd();
        assertFalse(BridgeSupport.isCompilingDisplayList());

        // 归零后正常进出窗口仍准确
        BridgeSupport.onDisplayListCompileStart();
        assertTrue(BridgeSupport.isCompilingDisplayList());
        BridgeSupport.onDisplayListCompileEnd();
        assertFalse(BridgeSupport.isCompilingDisplayList());
    }

    @Test
    void requiresImmediateReplayCombinesWindowAndOpenSegmentFlag() {
        // 窗口外 + 无开放段切割标记：数组化回放（既有合并性能路径）
        assertFalse(VertexBatchCommand.requiresImmediateReplay(false));

        // 窗口外 + 开放段切割标记：逐指令 immediate（既有病态路径）
        assertTrue(VertexBatchCommand.requiresImmediateReplay(true));

        // 编译窗口内：即使无开放段切割标记也强制逐指令 immediate——
        // display list 按指针捕获客户端数组，数组化共享缓冲会在列表回放时
        // 读到陈旧数据（串图根因），immediate 按数据捕获
        BridgeSupport.onDisplayListCompileStart();
        try {
            assertTrue(VertexBatchCommand.requiresImmediateReplay(false), "编译窗口内批次必须走 immediate");
            assertTrue(VertexBatchCommand.requiresImmediateReplay(true));
        } finally {
            BridgeSupport.onDisplayListCompileEnd();
        }
        // 窗口关闭后恢复数组化
        assertFalse(VertexBatchCommand.requiresImmediateReplay(false));
    }

    @Test
    void compileWindowSkipsVertexArrayDrain() {
        // 窗口外（开放段切割批次）：允许先排干合并器中挂起的同串数组批次
        // （保持执行顺序，既有合并语义）
        assertTrue(VertexBatchCommand.shouldDrainVertexArraysBeforeImmediate());

        // 编译窗口内：严禁排干合并器——executeGl 的 current 值回同步
        // （glColor4ub/glTexCoord2f）会把合并器残留的<b>陈旧</b> current 值
        // 按值捕获进 display list，glCallList 重放时覆盖调用方设置的当前
        // 颜色/纹理坐标（实机症状：对话框文字阴影层级反转——主字形 pass 以
        // 阴影色重放）；数组化 glDrawArrays 更会按指针捕获共享缓冲。
        BridgeSupport.onDisplayListCompileStart();
        try {
            assertFalse(VertexBatchCommand.shouldDrainVertexArraysBeforeImmediate(),
                    "编译窗口内批次必须跳过合并器排干");
        } finally {
            BridgeSupport.onDisplayListCompileEnd();
        }
        // 窗口关闭后恢复排干
        assertTrue(VertexBatchCommand.shouldDrainVertexArraysBeforeImmediate());
    }

    @Test
    void uninstallResetsCompileState() {
        BridgeSupport.onDisplayListCompileStart();
        BridgeSupport.onDisplayListCompileStart();
        GL11.uninstall();
        assertFalse(BridgeSupport.isCompilingDisplayList(), "uninstall 必须复位编译深度");
    }
}
