package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.BufferSnapshotPool;
import github.kasuminova.ssoptimizer.common.render.queue.DiscardableGlCommand;
import org.lwjgl.opengl.ContextCapabilities;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.function.IntSupplier;

final class UniformLookup {
    private static final UniformLocations LOCATIONS = new UniformLocations();
    private static final ThreadLocal<IntBuffer> LINK_STATUS = ThreadLocal.withInitial(() -> org.lwjgl.BufferUtils.createIntBuffer(1));
    private static volatile ContextCapabilities capabilities;

    private UniformLookup() {}

    static void clear() {
        capabilities = null;
        LOCATIONS.clear();
    }

    static int created(int program) {
        if (program == 0) {
            return 0;
        }
        if (!BridgeSupport.queue().isRenderThread()) {
            LOCATIONS.invalidate(program);
            return program;
        }
        observeCapabilities();
        LOCATIONS.created(program);
        return program;
    }

    static int createdLinked(int program) {
        if (program == 0) {
            return 0;
        }
        if (!BridgeSupport.queue().isRenderThread()) {
            LOCATIONS.invalidate(program);
            return program;
        }
        if (linked(program, observeCapabilities(), false)) {
            LOCATIONS.createdLinked(program);
        } else {
            LOCATIONS.created(program);
        }
        return program;
    }

    private static ContextCapabilities observeCapabilities() {
        return capabilities = org.lwjgl.opengl.GLContext.getCapabilities();
    }

    static int core(int program, CharSequence name) {
        String key = name.toString();
        return lookup(program, key, false, () -> org.lwjgl.opengl.GL20.glGetUniformLocation(program, key));
    }

    static int arb(int program, CharSequence name) {
        String key = name.toString();
        return lookup(program, key, true, () -> org.lwjgl.opengl.ARBShaderObjects.glGetUniformLocationARB(program, key));
    }

    static int core(int program, ByteBuffer name) {
        return lookup(program, asciiName(name), false, () -> org.lwjgl.opengl.GL20.glGetUniformLocation(program, name));
    }

    static int arb(int program, ByteBuffer name) {
        return lookup(program, asciiName(name), true, () -> org.lwjgl.opengl.ARBShaderObjects.glGetUniformLocationARB(program, name));
    }

    private static String asciiName(ByteBuffer name) {
        if (name == null || !name.isDirect() || !name.hasRemaining() || name.get(name.limit() - 1) != 0) {
            return null;
        }
        int start = name.position();
        int end = start;
        for (; end < name.limit() - 1; end++) {
            byte b = name.get(end);
            if (b < 0) return null;
            if (b == 0) break;
        }
        return StandardCharsets.US_ASCII.decode(name.slice(start, end - start)).toString();
    }

    private static int lookup(int program, String key, boolean arb, IntSupplier driver) {
        ContextCapabilities caps = capabilities;
        IntSupplier read = () -> BridgeSupport.blockingGetResource(driver::getAsInt);
        if (key == null || caps == null || !(arb ? caps.GL_ARB_shader_objects : caps.OpenGL20)
                || !BridgeSupport.isMainRecordingThread()) return read.getAsInt();
        RecordingContext ctx = BridgeSupport.recordingContext();
        if (ctx.auxNative || ctx.vertexStream.hasOpenSegment() || ctx.isRecordingDisplayList()
                || BridgeSupport.isCompilingDisplayList()) return read.getAsInt();
        return LOCATIONS.resolve(program, key, read);
    }

    static void delete(int program, Runnable operation) {
        LOCATIONS.invalidate(program);
        BridgeSupport.enqueue(() -> {
            try {
                operation.run();
            } finally {
                LOCATIONS.invalidate(program);
            }
        });
    }

    static void link(int program, Runnable operation, boolean arb) {
        submit(new Link(program, arb, operation, null, null));
    }

    static void binary(int program, ByteBuffer data, BridgeSupport.SnapshotCommand operation) {
        BufferSnapshotPool pool = BridgeSupport.snapshotPool();
        ByteBuffer snapshot = pool.snapshot(data);
        Link link;
        try {
            link = new Link(program, false, () -> operation.execute(snapshot), pool, snapshot);
        } catch (RuntimeException | Error failure) {
            pool.release(snapshot);
            throw failure;
        }
        submit(link);
    }

    private static void submit(Link link) {
        try {
            BridgeSupport.enqueue(link);
        } catch (RuntimeException | Error failure) {
            link.discard();
            throw failure;
        }
    }

    private static boolean linked(int program, ContextCapabilities caps, boolean preferArb) {
        IntBuffer status = LINK_STATUS.get();
        // 驱动遇到错误时保留输出缓冲原值，禁止沿用上一次链接的结果。
        status.put(0, 0);
        if (caps.OpenGL20 && (!preferArb || !caps.GL_ARB_shader_objects)) {
            org.lwjgl.opengl.GL20.glGetProgram(program, org.lwjgl.opengl.GL20.GL_LINK_STATUS, status);
        } else if (caps.GL_ARB_shader_objects) {
            org.lwjgl.opengl.ARBShaderObjects.glGetObjectParameterARB(
                    program, org.lwjgl.opengl.ARBShaderObjects.GL_OBJECT_LINK_STATUS_ARB, status);
        }
        return status.get(0) == org.lwjgl.opengl.GL11.GL_TRUE;
    }

    private static final class Link implements DiscardableGlCommand {
        private final int program;
        private final boolean arb;
        private final boolean canQuery;
        private final UniformLocations.LinkToken token;
        private final Runnable operation;
        private final BufferSnapshotPool pool;
        private final ByteBuffer snapshot;
        private boolean finished;

        Link(int program, boolean arb, Runnable operation, BufferSnapshotPool pool, ByteBuffer snapshot) {
            this.program = program;
            this.arb = arb;
            this.operation = operation;
            this.pool = pool;
            this.snapshot = snapshot;
            canQuery = !BridgeSupport.recordingContext().vertexStream.hasOpenSegment();
            token = LOCATIONS.recordLink(program);
        }

        @Override public void execute() {
            boolean success = false;
            try {
                LOCATIONS.beginLink(token);
                operation.run();
                if (canQuery && token.knownProgram() && BridgeSupport.queue().isRenderThread()) {
                    success = linked(program, observeCapabilities(), arb);
                }
            } finally {
                finish(success);
            }
        }

        @Override public void discard() {
            if (!finished) finish(false);
        }

        private void finish(boolean linked) {
            finished = true;
            try {
                LOCATIONS.finishLink(token, linked);
            } finally {
                if (pool != null) pool.release(snapshot);
            }
        }
    }
}
