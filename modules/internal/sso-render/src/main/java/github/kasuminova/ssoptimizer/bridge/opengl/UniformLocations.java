package github.kasuminova.ssoptimizer.bridge.opengl;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;

final class UniformLocations {
    private final ConcurrentHashMap<Integer, Program> programs = new ConcurrentHashMap<>();

    synchronized void created(int program) {
        registerCreated(program, false);
    }

    synchronized void createdLinked(int program) {
        registerCreated(program, true);
    }

    private void registerCreated(int program, boolean linked) {
        if (program != 0) {
            Program state = programs.computeIfAbsent(program, ignored -> new Program());
            state.revision++;
            state.known = true;
            state.locations = linked && state.pending == 0 ? new ConcurrentHashMap<>() : null;
        }
    }

    synchronized LinkToken recordLink(int program) {
        Program state = programs.computeIfAbsent(program, ignored -> new Program());
        registerLink(state);
        return new LinkToken(program, state);
    }

    /** 紧邻真实链接调用前执行，覆盖同一名称上迟到的队列命令。 */
    synchronized void beginLink(LinkToken token) {
        if (token.finished || token.executing != null) {
            throw new IllegalStateException("链接令牌已开始执行或已结束");
        }
        Program executing = programs.computeIfAbsent(token.program, ignored -> new Program());
        token.executing = executing;
        token.knownProgram = executing.known;
        if (executing != token.recorded) {
            // 重建或 program 名称复用后，旧命令仍可能在当前上下文执行。
            registerLink(executing);
            executing.overlap = true;
        }
    }

    /** 执行结束后以真实链接状态完成；未执行的令牌以 false 完成。 */
    synchronized void finishLink(LinkToken token, boolean linked) {
        if (token.finished) {
            throw new IllegalStateException("链接令牌已结束");
        }
        token.finished = true;
        Program state = token.recorded;
        state.pending--;
        // 已请求删除的 program 可能仍是当前程序，链接成功也不能重新确认其生命周期。
        if (linked && state.known && token.executing == state && token.revision == state.revision
                && state.pending == 0 && !state.overlap && programs.get(token.program) == state) {
            state.locations = new ConcurrentHashMap<>();
        }
        finishPending(token.program, state);
        if (token.executing != null && token.executing != state) {
            token.executing.pending--;
            finishPending(token.program, token.executing);
        }
    }

    synchronized void invalidate(int program) {
        Program state = programs.get(program);
        if (state != null) {
            state.locations = null;
            state.revision++;
            state.known = false;
            finishPending(program, state);
        }
    }

    synchronized void clear() {
        programs.clear();
    }

    int resolve(int program, String name, IntSupplier query) {
        Program state = programs.get(program);
        ConcurrentHashMap<String, Integer> locations = state == null ? null : state.locations;
        if (locations == null) {
            return query.getAsInt();
        }
        Integer cached = locations.get(name);
        if (cached != null) {
            return cached;
        }
        int location = query.getAsInt();
        if (state.locations == locations && programs.get(program) == state) {
            locations.putIfAbsent(name, location);
        }
        return location;
    }

    private static void registerLink(Program state) {
        state.locations = null;
        if (++state.pending > 1) {
            state.overlap = true;
        }
    }

    private void finishPending(int program, Program state) {
        if (state.pending == 0) {
            state.overlap = false;
            if (!state.known) {
                programs.remove(program, state);
            }
        }
    }

    static final class LinkToken {
        private final int program;
        private final Program recorded;
        private final long revision;
        private Program executing;
        private boolean knownProgram;
        private boolean finished;

        private LinkToken(int program, Program recorded) {
            this.program = program;
            this.recorded = recorded;
            this.revision = recorded.revision;
            this.knownProgram = recorded.known;
        }

        boolean knownProgram() {
            return knownProgram;
        }
    }

    private static final class Program {
        private volatile ConcurrentHashMap<String, Integer> locations;
        private long revision;
        private int pending;
        private boolean overlap;
        private boolean known;
    }
}
