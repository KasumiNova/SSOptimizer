package github.kasuminova.ssoptimizer.bridge.opengl;

import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;

/**
 * display list 编译期间将客户端数组快照解码为 immediate 调用，避免池化缓冲在列表回放前被复用。
 * 仅支持 buffer 形式的非 INTERLEAVED 快照；VBO 偏移形式保持服务器端指针捕获语义。
 */
final class DrawCommandImmediateDecoder {

    private DrawCommandImmediateDecoder() {
    }

    static boolean canDecode(final PointerSnapshotGroup group) {
        for (int i = 0; i < group.size(); i++) {
            final PointerSnapshot snapshot = group.get(i);
            if (snapshot.data == null) {
                return false;
            }
            if (snapshot.kind == PointerSnapshot.Kind.INTERLEAVED) {
                return false;
            }
        }
        return group.size() > 0;
    }

    static void replayDrawArrays(final PointerSnapshotGroup group, final int mode,
                                 final int first, final int count, final VertexSink sink) {
        sink.begin(mode);
        for (int i = first; i < first + count; i++) {
            emitVertex(group, i, sink);
        }
        sink.end();
    }

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

    /** 先重放非位置属性，再以位置调用结束当前顶点。 */
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

    /** stride 为 0 时按分量数与分量字节数计算紧凑步长。 */
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
