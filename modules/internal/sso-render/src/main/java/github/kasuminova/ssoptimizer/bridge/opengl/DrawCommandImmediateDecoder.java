package github.kasuminova.ssoptimizer.bridge.opengl;

import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;

/**
 * {@link DrawCommand}（glDrawArrays/glDrawElements/glArrayElement）在 display list
 * 编译窗口内的 immediate 数据捕获解码器。
 * <p>
 * 动机：display list 编译对客户端数组（glVertexPointer/glTexCoordPointer 等）按
 * <b>指针捕获</b>、不回拷数据。{@link DrawCommand} 的 buffer 形式 pointer 快照是
 * {@link BufferSnapshotPoolImpl} 的池化缓冲，draw 执行完（finally）即归还池复用——
 * 列表回放时 glDrawArrays 会读到后续命令覆盖的陈旧内容（与
 * {@link VertexArrayBatch} 共享缓冲同一类问题，实机风险点：舰队列表受损舰船的
 * 损伤贴花 SpriteBatch 客户端数组路径）。本类把快照数据解码为 immediate 调用
 * （glBegin/glTexCoord2f/glVertex2f/glEnd），display list 在编译期按值捕获，回放正确。
 * <p>
 * 适用边界：仅当快照组<b>全部</b>为 buffer 形式（{@code data != null}）时解码——
 * VBO 偏移形式（{@code data == null}）的顶点数据在服务器端（VBO 内容），display
 * list 按指针捕获后重放读到的仍是同一 VBO 的当前内容，语义正确，无需解码。
 * <p>
 * 解码支持的数据格式与 {@link VertexSink} 方法集一一对应（即 bridge 流式录制族
 * 的格式）：顶点 2/3 分量 FLOAT/DOUBLE、纹理坐标 2 分量 FLOAT/DOUBLE、颜色 3/4
 * 分量 UNSIGNED_BYTE/FLOAT/DOUBLE、法线 3 分量 FLOAT。游戏实际客户端数组路径
 * （SpriteBatch/粒子）只使用 2F 顶点 + 2F 纹理坐标 + 4UB 颜色；其余组合（如
 * INTERLEAVED、4 分量顶点）在本类中显式拒绝——静默错绘或指针捕获都是更坏的失败
 * 形态，抛异常使错误在渲染线程首现即暴露。
 */
final class DrawCommandImmediateDecoder {

    private DrawCommandImmediateDecoder() {
    }

    /**
     * 判定快照组是否可解码：全部为 buffer 形式且无 INTERLEAVED（INTERLEAVED
     * 格式表庞大且游戏真实路径不使用，见类 javadoc）。
     *
     * @return true 表示 {@link DrawCommand} 在编译窗口内应走本解码器
     */
    static boolean canDecode(final PointerSnapshotGroup group) {
        for (int i = 0; i < group.size(); i++) {
            final PointerSnapshot snapshot = group.get(i);
            if (snapshot.data == null) {
                // VBO 偏移形式：服务器端数据，display list 指针捕获语义正确
                return false;
            }
            if (snapshot.kind == PointerSnapshot.Kind.INTERLEAVED) {
                return false;
            }
        }
        return group.size() > 0;
    }

    /**
     * glDrawArrays(mode, first, count) 的解码重放。
     *
     * @param group pointer 快照组（全部 buffer 形式，调用方已判 {@link #canDecode}）
     * @param mode  图元模式（glBegin 直接透传）
     */
    static void replayDrawArrays(final PointerSnapshotGroup group, final int mode,
                                 final int first, final int count, final VertexSink sink) {
        sink.begin(mode);
        for (int i = first; i < first + count; i++) {
            emitVertex(group, i, sink);
        }
        sink.end();
    }

    /**
     * glDrawElements(mode, indices) 的解码重放。
     *
     * @param group         pointer 快照组
     * @param mode          图元模式
     * @param indexSnapshot 索引快照（ByteBuffer 池化拷贝）
     * @param view          索引视图种类（{@link DrawCommand#VIEW_BYTE}/{@code _INT}/{@code _SHORT}）
     */
    static void replayDrawElements(final PointerSnapshotGroup group, final int mode,
                                   final ByteBuffer indexSnapshot, final int view,
                                   final VertexSink sink) {
        final int elementCount = indexSnapshot.remaining() / indexBytes(view);
        sink.begin(mode);
        for (int i = 0; i < elementCount; i++) {
            emitVertex(group, readIndex(indexSnapshot, view, i), sink);
        }
        sink.end();
    }

    /** glArrayElement(index) 的解码重放（单顶点，无 begin/end）。 */
    static void replayArrayElement(final PointerSnapshotGroup group, final int index,
                                   final VertexSink sink) {
        emitVertex(group, index, sink);
    }

    private static int indexBytes(final int view) {
        return switch (view) {
            case DrawCommand.VIEW_BYTE -> Byte.BYTES;
            case DrawCommand.VIEW_SHORT -> Short.BYTES;
            case DrawCommand.VIEW_INT -> Integer.BYTES;
            default -> throw new IllegalStateException("[SSOptimizer] 未知索引快照视图种类 " + view);
        };
    }

    private static int readIndex(final ByteBuffer indexSnapshot, final int view, final int i) {
        final int offset = i * indexBytes(view);
        return switch (view) {
            case DrawCommand.VIEW_BYTE -> indexSnapshot.get(offset) & 0xFF;
            case DrawCommand.VIEW_SHORT -> indexSnapshot.getShort(offset) & 0xFFFF;
            case DrawCommand.VIEW_INT -> indexSnapshot.getInt(offset);
            default -> throw new IllegalStateException("[SSOptimizer] 未知索引快照视图种类 " + view);
        };
    }

    /**
     * 解码单个顶点：先按序重放全部属性 current 值（color/texcoord/normal），
     * 最后重放位置（glVertex*）——与真实 GL immediate 语义一致。
     */
    private static void emitVertex(final PointerSnapshotGroup group, final int vertexIndex,
                                   final VertexSink sink) {
        for (int i = 0; i < group.size(); i++) {
            final PointerSnapshot snapshot = group.get(i);
            if (snapshot.kind != PointerSnapshot.Kind.VERTEX) {
                emitAttribute(snapshot, vertexIndex, sink);
            }
        }
        emitPosition(findVertex(group), vertexIndex, sink);
    }

    private static PointerSnapshot findVertex(final PointerSnapshotGroup group) {
        for (int i = 0; i < group.size(); i++) {
            if (group.get(i).kind == PointerSnapshot.Kind.VERTEX) {
                return group.get(i);
            }
        }
        throw new IllegalStateException("[SSOptimizer] draw 命令缺少 VERTEX pointer 快照，无法解码");
    }

    private static void emitPosition(final PointerSnapshot snapshot, final int vertexIndex,
                                     final VertexSink sink) {
        final int offset = elementOffset(snapshot, vertexIndex);
        final int size = snapshot.size;
        switch (snapshot.type) {
            case GL11.GL_FLOAT -> {
                final float x = snapshot.data.getFloat(offset);
                final float y = snapshot.data.getFloat(offset + Float.BYTES);
                if (size == 2) {
                    sink.vertex2f(x, y);
                } else if (size == 3) {
                    sink.vertex3f(x, y, snapshot.data.getFloat(offset + 2 * Float.BYTES));
                } else {
                    throw unsupported(snapshot, "顶点分量数 " + size);
                }
            }
            case GL11.GL_DOUBLE -> {
                final double x = snapshot.data.getDouble(offset);
                final double y = snapshot.data.getDouble(offset + Double.BYTES);
                if (size == 2) {
                    sink.vertex2d(x, y);
                } else if (size == 3) {
                    sink.vertex3d(x, y, snapshot.data.getDouble(offset + 2 * Double.BYTES));
                } else {
                    throw unsupported(snapshot, "顶点分量数 " + size);
                }
            }
            default -> throw unsupported(snapshot, "顶点类型");
        }
    }

    private static void emitAttribute(final PointerSnapshot snapshot, final int vertexIndex,
                                      final VertexSink sink) {
        final int offset = elementOffset(snapshot, vertexIndex);
        switch (snapshot.kind) {
            case TEX_COORD -> {
                if (snapshot.size != 2) {
                    throw unsupported(snapshot, "纹理坐标分量数 " + snapshot.size);
                }
                switch (snapshot.type) {
                    case GL11.GL_FLOAT -> sink.texCoord2f(
                            snapshot.data.getFloat(offset),
                            snapshot.data.getFloat(offset + Float.BYTES));
                    case GL11.GL_DOUBLE -> sink.texCoord2d(
                            snapshot.data.getDouble(offset),
                            snapshot.data.getDouble(offset + Double.BYTES));
                    default -> throw unsupported(snapshot, "纹理坐标类型");
                }
            }
            case COLOR -> {
                switch (snapshot.type) {
                    case GL11.GL_UNSIGNED_BYTE -> {
                        final byte r = snapshot.data.get(offset);
                        final byte g = snapshot.data.get(offset + 1);
                        final byte b = snapshot.data.get(offset + 2);
                        if (snapshot.size == 4) {
                            sink.color4ub(r, g, b, snapshot.data.get(offset + 3));
                        } else if (snapshot.size == 3) {
                            sink.color3ub(r, g, b);
                        } else {
                            throw unsupported(snapshot, "颜色分量数 " + snapshot.size);
                        }
                    }
                    case GL11.GL_FLOAT -> {
                        final float r = snapshot.data.getFloat(offset);
                        final float g = snapshot.data.getFloat(offset + Float.BYTES);
                        final float b = snapshot.data.getFloat(offset + 2 * Float.BYTES);
                        if (snapshot.size == 4) {
                            sink.color4f(r, g, b, snapshot.data.getFloat(offset + 3 * Float.BYTES));
                        } else if (snapshot.size == 3) {
                            sink.color3f(r, g, b);
                        } else {
                            throw unsupported(snapshot, "颜色分量数 " + snapshot.size);
                        }
                    }
                    case GL11.GL_DOUBLE -> {
                        if (snapshot.size != 3) {
                            throw unsupported(snapshot, "颜色分量数 " + snapshot.size);
                        }
                        sink.color3d(
                                snapshot.data.getDouble(offset),
                                snapshot.data.getDouble(offset + Double.BYTES),
                                snapshot.data.getDouble(offset + 2 * Double.BYTES));
                    }
                    default -> throw unsupported(snapshot, "颜色类型");
                }
            }
            case NORMAL -> {
                if (snapshot.size != 3 || snapshot.type != GL11.GL_FLOAT) {
                    throw unsupported(snapshot, "法线格式");
                }
                sink.normal3f(
                        snapshot.data.getFloat(offset),
                        snapshot.data.getFloat(offset + Float.BYTES),
                        snapshot.data.getFloat(offset + 2 * Float.BYTES));
            }
            default -> throw unsupported(snapshot, "属性种类");
        }
    }

    /**
     * 顶点 i 的元素起始字节偏移：stride 为 0 时按「分量数 × 分量字节数」紧凑
     * 排列（LWJGL2 的 stride=0 语义即紧凑排列），否则按显式 stride 步进。
     */
    private static int elementOffset(final PointerSnapshot snapshot, final int vertexIndex) {
        final int componentBytes = componentBytes(snapshot.type);
        final int elementStride = snapshot.stride > 0
                ? snapshot.stride
                : snapshot.size * componentBytes;
        return vertexIndex * elementStride;
    }

    private static int componentBytes(final int type) {
        return switch (type) {
            case GL11.GL_BYTE, GL11.GL_UNSIGNED_BYTE -> Byte.BYTES;
            case GL11.GL_SHORT, GL11.GL_UNSIGNED_SHORT -> Short.BYTES;
            case GL11.GL_INT, GL11.GL_UNSIGNED_INT -> Integer.BYTES;
            case GL11.GL_FLOAT -> Float.BYTES;
            case GL11.GL_DOUBLE -> Double.BYTES;
            default -> throw new IllegalStateException("[SSOptimizer] 未知 GL 分量类型 " + type);
        };
    }

    private static IllegalStateException unsupported(final PointerSnapshot snapshot,
                                                     final String detail) {
        return new IllegalStateException("[SSOptimizer] display list 编译窗口内无法解码的"
                + "客户端数组快照（kind=" + snapshot.kind + " size=" + snapshot.size
                + " type=" + snapshot.type + "）：" + detail
                + "——此类数据在编译窗口内无法按值捕获，请排查产生该 pointer 的路径");
    }
}
