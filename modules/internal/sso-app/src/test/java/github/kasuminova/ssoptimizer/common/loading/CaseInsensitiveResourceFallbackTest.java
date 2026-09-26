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
                "graphics\\icons\\cargo\\blueprint_hightech.png", exception, null, false)) {
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
                "Graphics/ICONS/cargo/blueprint_midtech.png", exception, null, false)) {
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
                "graphics/icons/cargo/overridden.png", exception, null, false)) {
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
                "graphics/icons/cargo/definitely_missing.png", exception, null, false),
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
                "data/hulls/skins/exact_case_fallback.skin", exception, null, false)) {
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
                "DATA/hulls/skins/case_fixed_fallback.skin", exception, null, false)) {
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

    // ------------------------------------------------------------------
    // 隔离语义：source filter / suppressCustomResources 生效时，
    // 兜底通道不得越过原版 openResource 的根目录门控（ASTD 实机回归：
    // loadCSV(path, modId) 查不存在的 csv 被兜底成按名称排序在前的
    // 其他模组同名文件）。
    // ------------------------------------------------------------------

    /** 在 gameDir/mods 下建两个模组根并返回（modsA=AAA、modsB=BBB）。 */
    private Path[] createTwoModRoots() throws Exception {
        final Path modsA = gameDir.resolve("mods/AAA");
        final Path modsB = gameDir.resolve("mods/BBB");
        Files.createDirectories(modsA);
        Files.createDirectories(modsB);
        return new Path[]{modsA, modsB};
    }

    @Test
    void filteredLoadDoesNotResolveAcrossModBoundary() throws Exception {
        final Path[] mods = createTwoModRoots();
        // 文件只存在于 AAA（按名称排序在前），BBB 以 filter=BBB 隔离加载同名资源
        final Path target = mods[0].resolve("data/csv/only_in_aaa.csv");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "aaa-content", StandardCharsets.UTF_8);

        final RuntimeException exception = new RuntimeException(
                "Error loading [data/csv/only_in_aaa.csv] resource, not found in ["
                        + mods[0].toAbsolutePath() + "," + mods[1].toAbsolutePath() + ",CLASSPATH]");

        assertNull(CaseInsensitiveResourceFallback.tryResolve(
                "data/csv/only_in_aaa.csv", exception, "BBB", false),
                "filter=BBB 的隔离加载不得跨模组解析到 AAA 的同名文件");
    }

    @Test
    void filteredLoadStillResolvesCaseMismatchInsideFilteredMod() throws Exception {
        final Path[] mods = createTwoModRoots();
        // 大小写修正在被过滤模组内部仍然合法（Linux 上 Windows 模组的本修复目标）
        final Path target = mods[1].resolve("data/csv/wrong_case_inside_bbb.csv");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "bbb-content", StandardCharsets.UTF_8);

        final RuntimeException exception = new RuntimeException(
                "Error loading [DATA/CSV/wrong_case_inside_bbb.csv] resource, not found in ["
                        + mods[0].toAbsolutePath() + "," + mods[1].toAbsolutePath() + "]");

        try (InputStream stream = CaseInsensitiveResourceFallback.tryResolve(
                "DATA/CSV/wrong_case_inside_bbb.csv", exception, "BBB", false)) {
            assertNotNull(stream, "filter 模组目录内的大小写修正必须保留");
            assertEquals("bbb-content", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void suppressedLoadExcludesModRootsButKeepsGameDir() throws Exception {
        final Path[] mods = createTwoModRoots();
        final Path inMod = mods[0].resolve("data/csv/vanilla_only.csv");
        Files.createDirectories(inMod.getParent());
        Files.writeString(inMod, "mod-content", StandardCharsets.UTF_8);

        final RuntimeException modOnly = new RuntimeException(
                "Error loading [data/csv/vanilla_only.csv] resource, not found in ["
                        + mods[0].toAbsolutePath() + "," + mods[1].toAbsolutePath() + ",CLASSPATH]");
        assertNull(CaseInsensitiveResourceFallback.tryResolve(
                "data/csv/vanilla_only.csv", modOnly, null, true),
                "suppress 生效时不得命中 mods/ 下的模组根");

        // 游戏根目录资源在 suppress 下仍然可解析（对应原版 ABSOLUTE_AND_CWD 规格不被排除）
        final Path inGame = gameDir.resolve("data/csv/vanilla_core.csv");
        Files.createDirectories(inGame.getParent());
        Files.writeString(inGame, "game-content", StandardCharsets.UTF_8);
        final RuntimeException gameHit = new RuntimeException(
                "Error loading [data/csv/vanilla_core.csv] resource, not found in ["
                        + mods[0].toAbsolutePath() + ",CLASSPATH]");
        try (InputStream stream = CaseInsensitiveResourceFallback.tryResolve(
                "data/csv/vanilla_core.csv", gameHit, null, true)) {
            assertNotNull(stream, "suppress 只排除模组根，游戏根目录必须保留");
            assertEquals("game-content", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void restrictedMissDoesNotPoisonUnrestrictedCache() throws Exception {
        final Path[] mods = createTwoModRoots();
        final Path target = mods[0].resolve("data/csv/cache_miss_isolation.csv");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "aaa-content", StandardCharsets.UTF_8);

        final RuntimeException exception = new RuntimeException(
                "Error loading [data/csv/cache_miss_isolation.csv] resource, not found in ["
                        + mods[0].toAbsolutePath() + "," + mods[1].toAbsolutePath() + "]");

        // 受限未命中（BBB 无此文件）：不得写入「不可解析」缓存
        assertNull(CaseInsensitiveResourceFallback.tryResolve(
                "data/csv/cache_miss_isolation.csv", exception, "BBB", false));

        // 后续非受限调用必须照常从 AAA 解析（被污染则会直接返回 null）
        try (InputStream stream = CaseInsensitiveResourceFallback.tryResolve(
                "data/csv/cache_miss_isolation.csv", exception, null, false)) {
            assertNotNull(stream, "受限未命中不得污染非受限调用的解析缓存");
            assertEquals("aaa-content", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void unrestrictedCachedHitDoesNotLeakIntoFilteredCall() throws Exception {
        final Path[] mods = createTwoModRoots();
        final Path target = mods[0].resolve("data/csv/cache_hit_isolation.csv");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "aaa-content", StandardCharsets.UTF_8);

        final RuntimeException exception = new RuntimeException(
                "Error loading [data/csv/cache_hit_isolation.csv] resource, not found in ["
                        + mods[0].toAbsolutePath() + "," + mods[1].toAbsolutePath() + "]");

        // 非受限调用先命中 AAA 并写入缓存
        try (InputStream stream = CaseInsensitiveResourceFallback.tryResolve(
                "data/csv/cache_hit_isolation.csv", exception, null, false)) {
            assertNotNull(stream);
        }

        // 同路径的受限调用必须绕过缓存（缓存键无隔离上下文），在 BBB 内找不到即 null
        assertNull(CaseInsensitiveResourceFallback.tryResolve(
                "data/csv/cache_hit_isolation.csv", exception, "BBB", false),
                "非受限缓存结果不得流入带 filter 的隔离调用");
    }
}
