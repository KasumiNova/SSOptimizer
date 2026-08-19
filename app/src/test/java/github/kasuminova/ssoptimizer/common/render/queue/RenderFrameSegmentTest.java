package github.kasuminova.ssoptimizer.common.render.queue;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RenderFrame} 的分段模型语义：默认单段行为与单列表时代一致；
 * 并行段按登记序（而非完成序）拼接；提交封存；池化重置回收段对象。
 */
class RenderFrameSegmentTest {

    @Test
    void defaultPathIsSingleSerialSegmentAndZeroCopy() {
        RenderFrame frame = new RenderFrame();
        frame.add(named("a"));
        frame.add(named("b"));

        frame.flatten();
        List<GlCommand> commands = frame.commands();

        assertEquals(2, commands.size());
        assertSame(frame.serialSegment().commands(), commands,
                "单段帧扁平化必须零拷贝直指段命令列表");
    }

    @Test
    void reserveSegmentsAppendsInRegistrationOrder() {
        RenderFrame frame = new RenderFrame();
        int base = frame.reserveSegments(3);

        assertEquals(1, base, "首个并行段紧跟默认串行段（索引 0）");
        assertNotSame(frame.segment(base), frame.segment(base + 1));
        assertNotSame(frame.segment(base + 1), frame.segment(base + 2));
        assertThrows(IllegalArgumentException.class, () -> frame.reserveSegments(0));
    }

    @Test
    void flattenOrderIsRegistrationOrderNotCompletionOrder() {
        RenderFrame frame = new RenderFrame();
        frame.add(named("serial-0"));
        int base = frame.reserveSegments(2);
        RenderSegment segB = frame.segment(base + 1);
        RenderSegment segA = frame.segment(base);
        // 刻意乱序写入：B 先完成，A 后完成——拼接必须仍按登记序
        segB.add(named("par-B"));
        segA.add(named("par-A"));
        frame.openNextSerialSegment();
        frame.add(named("serial-1"));

        frame.flatten();
        List<GlCommand> commands = frame.commands();

        assertEquals(4, commands.size());
        assertEquals("serial-0", nameOf(commands.get(0)));
        assertEquals("par-A", nameOf(commands.get(1)), "并行段按登记序拼接，与完成先后无关");
        assertEquals("par-B", nameOf(commands.get(2)));
        assertEquals("serial-1", nameOf(commands.get(3)), "后续串行段落在并行段之后");
    }

    @Test
    void flattenSealsAllSegmentsAgainstLateWrites() {
        RenderFrame frame = new RenderFrame();
        int base = frame.reserveSegments(1);
        RenderSegment parallel = frame.segment(base);
        frame.flatten();

        assertThrows(IllegalStateException.class, () -> parallel.add(named("late")),
                "迟到的 worker 写入必须被封存拦截");
        assertThrows(IllegalStateException.class, () -> frame.add(named("late")),
                "已提交帧的串行段同样封存");
    }

    @Test
    void openNextSerialSegmentSwitchesAppendTarget() {
        RenderFrame frame = new RenderFrame();
        RenderSegment first = frame.serialSegment();
        frame.add(named("a"));

        RenderSegment next = frame.openNextSerialSegment();
        assertNotSame(first, next);
        assertSame(next, frame.serialSegment());
        frame.add(named("b"));

        assertEquals(1, first.commandCount(), "旧串行段不再接收新命令");
        assertEquals(1, next.commandCount());
    }

    @Test
    void resetRecyclesSegmentsIntoSparePool() {
        RenderFrame frame = new RenderFrame();
        int base = frame.reserveSegments(2);
        RenderSegment segA = frame.segment(base);
        RenderSegment segB = frame.segment(base + 1);
        RenderSegment serial = frame.serialSegment();
        frame.flatten();

        frame.reset();
        assertEquals(0, frame.commandCount(), "reset 后帧命令数归零");
        assertSame(serial, frame.serialSegment(), "默认串行段对象跨重置复用");

        int newBase = frame.reserveSegments(2);
        assertEquals(1, newBase);
        // 备件池从尾部取用：先收回的后用（B 先于 A）
        assertSame(segB, frame.segment(newBase), "段对象必须经备件池回收复用");
        assertSame(segA, frame.segment(newBase + 1));
    }

    @Test
    void commandCountSumsAllSegments() {
        RenderFrame frame = new RenderFrame();
        frame.add(named("s"));
        int base = frame.reserveSegments(2);
        frame.segment(base).add(named("p1"));
        frame.segment(base + 1).add(named("p2"));
        frame.segment(base + 1).add(named("p3"));

        assertEquals(4, frame.commandCount(), "帧命令数为各段合计（帧池预热统计语义）");
    }

    private static GlCommand named(String name) {
        return new NamedCommand(name);
    }

    private static String nameOf(GlCommand command) {
        return ((NamedCommand) command).name;
    }

    /** 带名标记命令（不执行任何动作，仅作拼接序断言的身份）。 */
    private static final class NamedCommand implements GlCommand {
        final String name;

        NamedCommand(String name) {
            this.name = name;
        }

        @Override
        public void execute() {
        }
    }
}
