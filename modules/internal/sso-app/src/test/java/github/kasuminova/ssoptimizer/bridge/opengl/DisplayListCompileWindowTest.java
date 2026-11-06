package github.kasuminova.ssoptimizer.bridge.opengl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplayListCompileWindowTest {

    @BeforeEach
    @AfterEach
    void resetCompileState() {
        while (BridgeSupport.isCompilingDisplayList()) {
            BridgeSupport.onDisplayListCompileEnd();
        }
    }

    @Test
    void compileDepthTracksNestedNewListEndList() {
        assertFalse(BridgeSupport.isCompilingDisplayList(), "初始必须处于编译窗口外");

        BridgeSupport.onDisplayListCompileStart();
        assertTrue(BridgeSupport.isCompilingDisplayList(), "glNewList 命令体执行后进入编译窗口");

        BridgeSupport.onDisplayListCompileStart();
        assertTrue(BridgeSupport.isCompilingDisplayList());

        BridgeSupport.onDisplayListCompileEnd();
        assertTrue(BridgeSupport.isCompilingDisplayList(), "外层窗口仍开放，深度 >0");

        BridgeSupport.onDisplayListCompileEnd();
        assertFalse(BridgeSupport.isCompilingDisplayList(), "深度归零后恢复窗口外（数组化回放）");
    }

    @Test
    void strayEndListResetsDepthToZero() {
        BridgeSupport.onDisplayListCompileEnd();
        assertFalse(BridgeSupport.isCompilingDisplayList());

        BridgeSupport.onDisplayListCompileStart();
        assertTrue(BridgeSupport.isCompilingDisplayList());
        BridgeSupport.onDisplayListCompileEnd();
        assertFalse(BridgeSupport.isCompilingDisplayList());
    }

    @Test
    void requiresImmediateReplayCombinesWindowAndOpenSegmentFlag() {
        assertFalse(VertexBatchCommand.requiresImmediateReplay(false));

        assertTrue(VertexBatchCommand.requiresImmediateReplay(true));

        BridgeSupport.onDisplayListCompileStart();
        try {
            assertTrue(VertexBatchCommand.requiresImmediateReplay(false), "编译窗口内批次必须走 immediate");
            assertTrue(VertexBatchCommand.requiresImmediateReplay(true));
        } finally {
            BridgeSupport.onDisplayListCompileEnd();
        }
        assertFalse(VertexBatchCommand.requiresImmediateReplay(false));
    }

    @Test
    void compileWindowSkipsVertexArrayDrain() {
        assertTrue(VertexBatchCommand.shouldDrainVertexArraysBeforeImmediate());

        BridgeSupport.onDisplayListCompileStart();
        try {
            assertFalse(VertexBatchCommand.shouldDrainVertexArraysBeforeImmediate(),
                    "编译窗口内批次必须跳过合并器排干");
        } finally {
            BridgeSupport.onDisplayListCompileEnd();
        }
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
