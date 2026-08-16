package github.kasuminova.ssoptimizer.common.render.engine;

import org.apache.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

/**
 * 引擎合批 INSTANCED 模式的着色器程序组（{@code #version 330 compatibility}）。
 * <p>
 * 三个程序分别对应尾焰条带 / 火焰核心 / 辉光精灵：几何展开在顶点着色器内按
 * {@code gl_VertexID} 模板索引完成（CPU 零展开），顶点属性全部为 per-instance
 * （divisor=1）。兼容 profile 下直接使用 {@code gl_ModelViewProjectionMatrix}，
 * 即「每次 flush 重取矩阵」由固定管线矩阵栈天然保证。
 * <p>
 * 任一程序编译 / 链接失败时记 ERROR 并整体返回 null，调用方按降级链回退。
 */
public final class EngineGlProgram {
    private static final Logger LOGGER = Logger.getLogger(EngineGlProgram.class);

    private static final String GLSL_HEADER = "#version 330 compatibility\n";
    private static final String DEG_TO_RAD  = "0.017453292519943295769";

    /** 共用的角度制二维旋转（度 → 弧度 → 旋转，与 CPU 端 rotate 同式）。 */
    private static final String ROTATE_FUNC =
            "vec2 rotateDeg(vec2 p, float deg) {\n"
            + "    float r = deg * " + DEG_TO_RAD + ";\n"
            + "    float s = sin(r);\n"
            + "    float c = cos(r);\n"
            + "    return vec2(p.x * c - p.y * s, p.x * s + p.y * c);\n"
            + "}\n";

    /** 共用片元着色器：纹理 × 顶点色。 */
    private static final String FRAGMENT_SRC = GLSL_HEADER
            + "uniform sampler2D uTex;\n"
            + "in vec2 vUV;\n"
            + "in vec4 vColor;\n"
            + "void main() {\n"
            + "    gl_FragColor = texture(uTex, vUV) * vColor;\n"
            + "}\n";

    /**
     * 尾焰条带顶点着色器：6 顶点 quad-strip 模板按 12 索引展开为 4 三角形。
     * 矩阵等价链：T(pos)·R(angle)·R(rotation1)·R(rotation2)·T(translateX)·S(scaleX,scaleY)。
     */
    private static final String STRIP_VERTEX_SRC = GLSL_HEADER
            + "layout(location = 0) in vec4 aPosRot;\n"    // posX, posY, angle, rotation1
            + "layout(location = 1) in vec4 aRotScale;\n"  // rotation2, translateX, scaleX, scaleY
            + "layout(location = 2) in vec4 aGeo;\n"       // halfWidth, innerLength, stripLength, texU
            + "layout(location = 3) in vec4 aTexAlpha;\n"  // texSpan, texAdvance, alphaStart, alphaMid
            + "layout(location = 4) in vec4 aColor;\n"     // r, g, b, unused（0..255）
            + "out vec2 vUV;\n"
            + "out vec4 vColor;\n"
            + "const int IDX[12] = int[12](0, 1, 3, 0, 3, 2, 2, 3, 5, 2, 5, 4);\n"
            + ROTATE_FUNC
            + "void main() {\n"
            + "    int vi = IDX[gl_VertexID];\n"
            + "    float px = vi < 2 ? 0.0 : (vi < 4 ? aGeo.y : aGeo.z);\n"
            + "    float py = (vi & 1) == 0 ? -aGeo.x : aGeo.x;\n"
            + "    float u = vi < 2 ? aGeo.w : (vi < 4 ? aGeo.w + aTexAlpha.x : aGeo.w + aTexAlpha.y);\n"
            + "    float v = (vi & 1) == 0 ? 0.01 : 0.99;\n"
            + "    float alpha = vi < 2 ? aTexAlpha.z : (vi < 4 ? aTexAlpha.w : 0.0);\n"
            + "    vec2 p = vec2(px * aRotScale.z + aRotScale.y, py * aRotScale.w);\n"
            + "    p = rotateDeg(p, aRotScale.x);\n"
            + "    p = rotateDeg(p, aPosRot.w);\n"
            + "    p = rotateDeg(p, aPosRot.z);\n"
            + "    p += aPosRot.xy;\n"
            + "    vUV = vec2(u, v);\n"
            + "    vColor = vec4(aColor.rgb / 255.0, alpha / 255.0);\n"
            + "    gl_Position = gl_ModelViewProjectionMatrix * vec4(p, 0.0, 1.0);\n"
            + "}\n";

    /**
     * 火焰核心顶点着色器：4 顶点 quad-strip 模板按 6 索引展开为 2 三角形。
     * 矩阵等价链：T(pos)·R(angle)·R(coreRotation)·S(0.9,1)·R(omegaRotation)。
     */
    private static final String CORE_VERTEX_SRC = GLSL_HEADER
            + "layout(location = 0) in vec4 aPosRot;\n"   // posX, posY, angle, coreRotation
            + "layout(location = 1) in vec4 aGeo;\n"      // omegaRotation, stripLength, halfWidth, alpha
            + "layout(location = 2) in vec4 aColor;\n"    // r, g, b, unused（0..255）
            + "out vec2 vUV;\n"
            + "out vec4 vColor;\n"
            + "const int IDX[6] = int[6](0, 1, 3, 0, 3, 2);\n"
            + ROTATE_FUNC
            + "void main() {\n"
            + "    int vi = IDX[gl_VertexID];\n"
            + "    float px = vi < 2 ? 0.0 : aGeo.y;\n"
            + "    float py = (vi & 1) == 0 ? -aGeo.z : aGeo.z;\n"
            + "    float u = vi < 2 ? 0.01 : 0.99;\n"
            + "    float v = (vi & 1) == 0 ? 0.01 : 0.99;\n"
            + "    vec2 p = rotateDeg(vec2(px, py), aGeo.x);\n"
            + "    p.x *= 0.9;\n"
            + "    p = rotateDeg(p, aPosRot.w);\n"
            + "    p = rotateDeg(p, aPosRot.z);\n"
            + "    p += aPosRot.xy;\n"
            + "    vUV = vec2(u, v);\n"
            + "    vColor = vec4(aColor.rgb / 255.0, aGeo.w / 255.0);\n"
            + "    gl_Position = gl_ModelViewProjectionMatrix * vec4(p, 0.0, 1.0);\n"
            + "}\n";

    /**
     * 辉光精灵顶点着色器：GL_QUADS 4 顶点模板 (0,0)→(0,S)→(S,S)→(S,0) 按 6 索引展开。
     * 矩阵等价链：T(pos)·R(angle)·R(coreRotation)·S(scaleX,1)·T(-S/2,-S/2)。
     */
    private static final String GLOW_VERTEX_SRC = GLSL_HEADER
            + "layout(location = 0) in vec4 aPosRot;\n"   // posX, posY, angle, coreRotation
            + "layout(location = 1) in vec4 aSize;\n"     // scaleX, size, alpha, unused
            + "layout(location = 2) in vec4 aTex;\n"      // u0, v0, u1, v1
            + "layout(location = 3) in vec4 aColor;\n"    // r, g, b, unused（0..255）
            + "out vec2 vUV;\n"
            + "out vec4 vColor;\n"
            + "const int IDX[6] = int[6](0, 1, 2, 0, 2, 3);\n"
            + ROTATE_FUNC
            + "void main() {\n"
            + "    int vi = IDX[gl_VertexID];\n"
            + "    float cx = (vi == 2 || vi == 3) ? aSize.y : 0.0;\n"
            + "    float cy = (vi == 1 || vi == 2) ? aSize.y : 0.0;\n"
            + "    float u = (vi == 2 || vi == 3) ? aTex.z : aTex.x;\n"
            + "    float v = (vi == 1 || vi == 2) ? aTex.w : aTex.y;\n"
            + "    float halfSize = aSize.y * 0.5;\n"
            + "    vec2 p = vec2((cx - halfSize) * aSize.x, cy - halfSize);\n"
            + "    p = rotateDeg(p, aPosRot.w);\n"
            + "    p = rotateDeg(p, aPosRot.z);\n"
            + "    p += aPosRot.xy;\n"
            + "    vUV = vec2(u, v);\n"
            + "    vColor = vec4(aColor.rgb / 255.0, aSize.z / 255.0);\n"
            + "    gl_Position = gl_ModelViewProjectionMatrix * vec4(p, 0.0, 1.0);\n"
            + "}\n";

    private final int stripProgram;
    private final int coreProgram;
    private final int glowProgram;

    private EngineGlProgram(int stripProgram, int coreProgram, int glowProgram) {
        this.stripProgram = stripProgram;
        this.coreProgram = coreProgram;
        this.glowProgram = glowProgram;
    }

    /**
     * 编译并链接三个程序。
     *
     * @return 成功返回实例；任一程序失败记 ERROR 并返回 null（调用方降级）
     */
    public static EngineGlProgram create() {
        int strip = link("strip", STRIP_VERTEX_SRC);
        int core = link("core", CORE_VERTEX_SRC);
        int glow = link("glow", GLOW_VERTEX_SRC);
        if (strip == 0 || core == 0 || glow == 0) {
            GL20.glDeleteProgram(strip);
            GL20.glDeleteProgram(core);
            GL20.glDeleteProgram(glow);
            return null;
        }
        return new EngineGlProgram(strip, core, glow);
    }

    /** 激活尾焰条带程序并绑定纹理单元 0。 */
    public void useStrip() {
        use(stripProgram);
    }

    /** 激活火焰核心程序并绑定纹理单元 0。 */
    public void useCore() {
        use(coreProgram);
    }

    /** 激活辉光精灵程序并绑定纹理单元 0。 */
    public void useGlow() {
        use(glowProgram);
    }

    /** 释放全部 GL 程序对象。 */
    public void dispose() {
        GL20.glDeleteProgram(stripProgram);
        GL20.glDeleteProgram(coreProgram);
        GL20.glDeleteProgram(glowProgram);
    }

    private void use(int program) {
        GL20.glUseProgram(program);
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "uTex"), 0);
    }

    private static int link(String name, String vertexSrc) {
        int vertexShader = compile(GL20.GL_VERTEX_SHADER, vertexSrc, name + ".vert");
        if (vertexShader == 0) {
            return 0;
        }
        int fragmentShader = compile(GL20.GL_FRAGMENT_SHADER, FRAGMENT_SRC, name + ".frag");
        if (fragmentShader == 0) {
            GL20.glDeleteShader(vertexShader);
            return 0;
        }

        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vertexShader);
        GL20.glAttachShader(program, fragmentShader);
        GL20.glLinkProgram(program);
        GL20.glDeleteShader(vertexShader);
        GL20.glDeleteShader(fragmentShader);

        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            LOGGER.error(String.format("[SSOptimizer] 引擎合批着色器 %s 链接失败：%s",
                    name, GL20.glGetProgramInfoLog(program, 4096)));
            GL20.glDeleteProgram(program);
            return 0;
        }
        return program;
    }

    private static int compile(int type, String src, String name) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, src);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            LOGGER.error(String.format("[SSOptimizer] 引擎合批着色器 %s 编译失败：%s",
                    name, GL20.glGetShaderInfoLog(shader, 4096)));
            GL20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }
}
