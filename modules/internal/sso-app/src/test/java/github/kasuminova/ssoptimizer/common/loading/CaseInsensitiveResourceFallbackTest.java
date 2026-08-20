package github.kasuminova.ssoptimizer.common.loading;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link CaseInsensitiveResourceFallback} 的真实逻辑测试。
 * <p>
 * 复现的场景：模组以 Windows 风格反斜杠路径引用基础游戏资源
 * （如 {@code graphics\icons\cargo\blueprint_hightech.png}），原版异常消息的根目录列表
 * 只包含模组目录与 CLASSPATH，不含游戏根目录（ABSOLUTE_AND_CWD 规格），
 * 回退逻辑必须无条件追加游戏工作目录才能命中文件。
 */
class CaseInsensitiveResourceFallbackTest {
    @TempDir
    Path gameDir;

    @TempDir
    Path modDir;

    private String originalUserDir;

    @BeforeEach
    void setUp() {
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", gameDir.toAbsolutePath().toString());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
    }

    @Test
    void resolvesBackslashPathFromGameDirNotListedInExceptionRoots() throws Exception {
        final Path target = gameDir.resolve("graphics/icons/cargo/blueprint_hightech.png");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "fake-png-bytes", StandardCharsets.UTF_8);

        // 异常消息只含模组根目录（与线上日志一致），文件实际在游戏根目录下
        final RuntimeException exception = new RuntimeException(
                "Error loading [graphics\\icons\\cargo\\blueprint_hightech.png] resource, not found in ["
                        + modDir.toAbsolutePath() + ",CLASSPATH]");

        try (InputStream stream = CaseInsensitiveResourceFallback.tryResolve(
                "graphics\\icons\\cargo\\blueprint_hightech.png", exception)) {
            assertNotNull(stream, "反斜杠路径应通过游戏根目录兜底解析成功");
            assertEquals("fake-png-bytes",
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void resolvesCaseInsensitiveSegmentFromGameDir() throws Exception {
        final Path target = gameDir.resolve("graphics/icons/cargo/blueprint_midtech.png");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "midtech", StandardCharsets.UTF_8);

        final RuntimeException exception = new RuntimeException(
                "Error loading [Graphics/ICONS/cargo/blueprint_midtech.png] resource, not found in ["
                        + modDir.toAbsolutePath() + "]");

        try (InputStream stream = CaseInsensitiveResourceFallback.tryResolve(
                "Graphics/ICONS/cargo/blueprint_midtech.png", exception)) {
            assertNotNull(stream, "大小写不匹配的路径段应逐级不敏感匹配成功");
            assertEquals("midtech", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void prefersModRootOverGameDirToKeepOverrideSemantics() throws Exception {
        final Path inMod = modDir.resolve("graphics/icons/cargo/overridden.png");
        Files.createDirectories(inMod.getParent());
        Files.writeString(inMod, "mod-version", StandardCharsets.UTF_8);

        final Path inGame = gameDir.resolve("graphics/icons/cargo/overridden.png");
        Files.createDirectories(inGame.getParent());
        Files.writeString(inGame, "game-version", StandardCharsets.UTF_8);

        // 模组根目录出现在异常消息里且能命中时，必须优先于游戏根目录
        final RuntimeException exception = new RuntimeException(
                "Error loading [graphics/icons/cargo/overridden.png] resource, not found in ["
                        + modDir.toAbsolutePath() + ",CLASSPATH]");

        try (InputStream stream = CaseInsensitiveResourceFallback.tryResolve(
                "graphics/icons/cargo/overridden.png", exception)) {
            assertNotNull(stream);
            assertEquals("mod-version", new String(stream.readAllBytes(), StandardCharsets.UTF_8),
                    "模组根目录命中时必须优先于游戏根目录，保持原版覆盖语义");
        }
    }

    @Test
    void returnsNullWhenResourceDoesNotExistAnywhere() {
        final RuntimeException exception = new RuntimeException(
                "Error loading [graphics/icons/cargo/definitely_missing.png] resource, not found in ["
                        + modDir.toAbsolutePath() + ",CLASSPATH]");

        assertNull(CaseInsensitiveResourceFallback.tryResolve(
                "graphics/icons/cargo/definitely_missing.png", exception),
                "文件在所有根目录下都不存在时必须返回 null");
    }
}
