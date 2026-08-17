package github.kasuminova.ssoptimizer.bridge.opengl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GL20 最小集 bridge 的录制行为抽查：运行期命令（useProgram/uniform）按普通
 * 命令录制；创建/编译/链接期回读走阻塞通道。
 */
class ShaderBridgeTest {

    private FakeRenderQueue queue;

    @BeforeEach
    void setUp() {
        queue = new FakeRenderQueue();
        GL20.install(queue);
    }

    @AfterEach
    void tearDown() {
        GL20.uninstall();
    }

    @Test
    void runtimeShaderCallsAreRecorded() {
        GL20.glUseProgram(1);
        GL20.glUniform1i(0, 2);
        GL20.glUniform1f(0, 1f);
        GL20.glUniform2f(0, 1f, 2f);
        GL20.glUniform3f(0, 1f, 2f, 3f);
        GL20.glUniform4f(0, 1f, 2f, 3f, 4f);
        GL20.glCompileShader(2);
        GL20.glAttachShader(1, 2);
        GL20.glLinkProgram(1);
        GL20.glValidateProgram(1);
        GL20.glDeleteShader(2);
        GL20.glDeleteProgram(1);
        assertEquals(12, queue.recorded.size());
        assertEquals(0, queue.getCallCount);
    }

    @Test
    void shaderSourceIsSnapshottedAsImmutableString() {
        StringBuilder mutable = new StringBuilder("void main() {}");
        GL20.glShaderSource(2, mutable);
        mutable.setLength(0);
        mutable.append("corrupted");
        GL20.glShaderSource(2, new CharSequence[]{new StringBuilder("a"), "b"});
        // 源码在录制时刻已固化为 String，调用方改写 StringBuilder 不影响命令
        assertEquals(2, queue.recorded.size());
    }

    @Test
    void compileAndLinkReadbacksRouteThroughBlockingChannel() {
        queue.getHandler = callable -> 11;
        assertEquals(11, GL20.glCreateShader(org.lwjgl.opengl.GL20.GL_VERTEX_SHADER));
        assertEquals(11, GL20.glCreateProgram());
        assertEquals(11, GL20.glGetUniformLocation(1, "u_tex"));
        assertEquals(11, GL20.glGetShaderi(2, org.lwjgl.opengl.GL20.GL_COMPILE_STATUS));
        assertEquals(11, GL20.glGetProgrami(1, org.lwjgl.opengl.GL20.GL_LINK_STATUS));
        queue.getHandler = callable -> "log";
        assertEquals("log", GL20.glGetShaderInfoLog(2, 1024));
        assertEquals("log", GL20.glGetProgramInfoLog(1, 1024));
        assertEquals(5, queue.uncountedGetCallCount, "创建/名称查询/InfoLog 归资源申请类不计数");
        assertEquals(2, queue.getCallCount, "编译/链接状态轮询（glGetShaderi/glGetProgrami）保持计数");
        assertEquals(0, queue.recorded.size());
    }
}
