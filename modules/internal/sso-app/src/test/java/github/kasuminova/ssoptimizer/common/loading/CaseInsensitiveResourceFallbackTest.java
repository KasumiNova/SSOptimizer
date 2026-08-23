package github.kasuminova.ssoptimizer.common.loading;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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

    @Test
    void exactCaseMatchLogsInfoInsteadOfCaseMismatchWarn() throws Exception {
        // 复现线上误报场景：请求路径大小写完全正确、文件确实存在，
        // 游戏原加载因 filter/suppress 状态干扰失败，兜底精确命中。
        // 期望：不打「大小写不匹配」WARN，只打一次性 INFO 说明兜底接管。
        final Path target = gameDir.resolve("data/hulls/skins/exact_case_fallback.skin");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "skin-json", StandardCharsets.UTF_8);

        final RuntimeException exception = new RuntimeException(
                "Error loading [data/hulls/skins/exact_case_fallback.skin] resource, not found in ["
                        + modDir.toAbsolutePath() + ",CLASSPATH]");

        final Logger logger = Logger.getLogger(CaseInsensitiveResourceFallback.class);
        final List<LoggingEvent> events = new CopyOnWriteArrayList<>();
        final AppenderSkeleton appender = new AppenderSkeleton() {
            @Override
            protected void append(final LoggingEvent event) {
                events.add(event);
            }

            @Override
            public void close() {
            }

            @Override
            public boolean requiresLayout() {
                return false;
            }
        };
        logger.addAppender(appender);
        try (InputStream stream = CaseInsensitiveResourceFallback.tryResolve(
                "data/hulls/skins/exact_case_fallback.skin", exception)) {
            assertNotNull(stream);
        } finally {
            logger.removeAppender(appender);
        }

        final List<LoggingEvent> warns = events.stream()
                .filter(e -> e.getLevel().toInt() >= Level.WARN_INT).toList();
        assertTrue(warns.isEmpty(), "全段精确命中不得打大小写不匹配 WARN: " + warns);

        final List<LoggingEvent> infos = events.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .filter(e -> String.valueOf(e.getMessage()).contains("精确路径存在"))
                .toList();
        assertEquals(1, infos.size(), "精确命中兜底必须打一条可观测性 INFO");
    }

    @Test
    void caseCorrectedMatchStillLogsCaseMismatchWarn() throws Exception {
        // 真实大小写不匹配（Windows 模组在 Linux 运行）：WARN 语义必须保留
        final Path target = gameDir.resolve("data/hulls/skins/case_fixed_fallback.skin");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "skin-json", StandardCharsets.UTF_8);

        final RuntimeException exception = new RuntimeException(
                "Error loading [DATA/hulls/skins/case_fixed_fallback.skin] resource, not found in ["
                        + modDir.toAbsolutePath() + "]");

        final Logger logger = Logger.getLogger(CaseInsensitiveResourceFallback.class);
        final List<LoggingEvent> events = new CopyOnWriteArrayList<>();
        final AppenderSkeleton appender = new AppenderSkeleton() {
            @Override
            protected void append(final LoggingEvent event) {
                events.add(event);
            }

            @Override
            public void close() {
            }

            @Override
            public boolean requiresLayout() {
                return false;
            }
        };
        logger.addAppender(appender);
        try (InputStream stream = CaseInsensitiveResourceFallback.tryResolve(
                "DATA/hulls/skins/case_fixed_fallback.skin", exception)) {
            assertNotNull(stream);
        } finally {
            logger.removeAppender(appender);
        }

        final List<LoggingEvent> warns = events.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> String.valueOf(e.getMessage()).contains("大小写不匹配"))
                .toList();
        assertEquals(1, warns.size(), "发生大小写修正时必须保留 WARN 告警");

        final List<LoggingEvent> infos = events.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .filter(e -> String.valueOf(e.getMessage()).contains("精确路径存在"))
                .toList();
        assertTrue(infos.isEmpty(), "大小写修正场景不得打精确命中 INFO");
    }
}
