package github.kasuminova.ssoptimizer.asm.loading;

import github.kasuminova.ssoptimizer.mapping.GameMemberNames;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

import static org.junit.jupiter.api.Assertions.*;

class ParallelImagePreloadProcessorTest {
    @Test
    void rewritesDeferredLoaderLifecycleMethods() {
        byte[] rewritten = new ParallelImagePreloadProcessor().process(createFakeDeferredLoader());
        assertNotNull(rewritten);

        Inspection inspection = inspect(rewritten);
        assertTrue(inspection.callsStartHelper, "Deferred loader startup should delegate to ParallelImagePreloadCoordinator");
        assertTrue(inspection.callsStopHelper, "Deferred loader shutdown should delegate to ParallelImagePreloadCoordinator");
        assertTrue(inspection.callsQueueClearHelper, "Deferred loader shutdown should clear tracked pending paths");
        assertTrue(inspection.callsDecodeHelper, "Deferred loader image decode should delegate to FastResourceImageDecoder");
        assertTrue(inspection.callsAwaitImageHelper, "Deferred image waits should delegate to the pending-path tracker");
        assertTrue(inspection.callsAwaitBytesHelper, "Deferred byte waits should delegate to the pending-path tracker");
        assertTrue(inspection.hasOriginalAwaitBytesMethod, "Deferred byte waits should preserve the original byte loader implementation for worker threads");
        assertTrue(inspection.callsEnqueueImageHelper, "Deferred image queueing should delegate to the pending-path tracker");
        assertTrue(inspection.callsEnqueueBytesHelper, "Deferred byte queueing should delegate to the pending-path tracker");
        assertTrue(inspection.imageIoReadRemoved, "Deferred loader should no longer call ImageIO.read directly");
        assertEquals(4, inspection.clearCalls, "Shutdown should still clear image maps and pending queues");
    }

    private byte[] createFakeDeferredLoader() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, ParallelImagePreloadProcessor.TARGET_CLASS,
                null, "java/lang/Object", null);

        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, GameMemberNames.ParallelImagePreloader.IMAGE_RESULTS, "Ljava/util/Map;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, GameMemberNames.ParallelImagePreloader.BYTE_RESULTS, "Ljava/util/Map;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, GameMemberNames.ParallelImagePreloader.IMAGE_QUEUE, "Ljava/util/List;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, GameMemberNames.ParallelImagePreloader.BYTE_QUEUE, "Ljava/util/List;", null, null).visitEnd();

        MethodVisitor start = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, GameMemberNames.ParallelImagePreloader.START, "()V", null, null);
        start.visitCode();
        start.visitInsn(Opcodes.RETURN);
        start.visitMaxs(0, 0);
        start.visitEnd();

        MethodVisitor stop = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, GameMemberNames.ParallelImagePreloader.SHUTDOWN, "()V", null, null);
        stop.visitCode();
        stop.visitInsn(Opcodes.RETURN);
        stop.visitMaxs(0, 0);
        stop.visitEnd();

        MethodVisitor awaitBytes = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, GameMemberNames.ParallelImagePreloader.AWAIT_BYTES, "(Ljava/lang/String;)[B", null, null);
        awaitBytes.visitCode();
        awaitBytes.visitInsn(Opcodes.ACONST_NULL);
        awaitBytes.visitInsn(Opcodes.ARETURN);
        awaitBytes.visitMaxs(0, 1);
        awaitBytes.visitEnd();

        MethodVisitor awaitImage = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, GameMemberNames.ParallelImagePreloader.AWAIT_IMAGE, "(Ljava/lang/String;)Ljava/awt/image/BufferedImage;", null, null);
        awaitImage.visitCode();
        awaitImage.visitInsn(Opcodes.ACONST_NULL);
        awaitImage.visitInsn(Opcodes.ARETURN);
        awaitImage.visitMaxs(0, 1);
        awaitImage.visitEnd();

        MethodVisitor enqueueImage = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, GameMemberNames.ParallelImagePreloader.ENQUEUE_IMAGE, "(Ljava/lang/String;)V", null, null);
        enqueueImage.visitCode();
        enqueueImage.visitInsn(Opcodes.RETURN);
        enqueueImage.visitMaxs(0, 1);
        enqueueImage.visitEnd();

        MethodVisitor enqueueBytes = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, GameMemberNames.ParallelImagePreloader.ENQUEUE_BYTES, "(Ljava/lang/String;)V", null, null);
        enqueueBytes.visitCode();
        enqueueBytes.visitInsn(Opcodes.RETURN);
        enqueueBytes.visitMaxs(0, 1);
        enqueueBytes.visitEnd();

        MethodVisitor decode = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, GameMemberNames.ParallelImagePreloader.DECODE_IMAGE, "(Ljava/lang/String;)Ljava/awt/image/BufferedImage;", null, new String[]{"java/io/IOException"});
        decode.visitCode();
        decode.visitInsn(Opcodes.ACONST_NULL);
        decode.visitMethodInsn(Opcodes.INVOKESTATIC, "javax/imageio/ImageIO", "read", "(Ljava/io/InputStream;)Ljava/awt/image/BufferedImage;", false);
        decode.visitInsn(Opcodes.ARETURN);
        decode.visitMaxs(0, 1);
        decode.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private Inspection inspect(byte[] classBytes) {
        boolean[] startHelper = {false};
        boolean[] stopHelper = {false};
        boolean[] decodeHelper = {false};
        boolean[] queueClearHelper = {false};
        boolean[] awaitImageHelper = {false};
        boolean[] awaitBytesHelper = {false};
        boolean[] originalAwaitBytesMethod = {false};
        boolean[] enqueueImageHelper = {false};
        boolean[] enqueueBytesHelper = {false};
        boolean[] imageIoRead = {false};
        int[] clearCalls = {0};

        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                if (ParallelImagePreloadProcessor.ORIGINAL_AWAIT_BYTES_METHOD.equals(name)
                        && "(Ljava/lang/String;)[B".equals(desc)) {
                    originalAwaitBytesMethod[0] = true;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDesc, boolean itf) {
                        if (ParallelImagePreloadProcessor.HELPER_OWNER.equals(owner)
                                && "startWorkers".equals(methodName)) {
                            startHelper[0] = true;
                        }
                        if (ParallelImagePreloadProcessor.HELPER_OWNER.equals(owner)
                                && "stopWorkers".equals(methodName)) {
                            stopHelper[0] = true;
                        }
                        if (ParallelImagePreloadProcessor.QUEUE_HELPER_OWNER.equals(owner)
                                && "clearPending".equals(methodName)) {
                            queueClearHelper[0] = true;
                        }
                        if (ParallelImagePreloadProcessor.DECODE_HELPER_OWNER.equals(owner)
                                && "decode".equals(methodName)) {
                            decodeHelper[0] = true;
                        }
                        if (ParallelImagePreloadProcessor.QUEUE_HELPER_OWNER.equals(owner)
                                && "awaitImage".equals(methodName)) {
                            awaitImageHelper[0] = true;
                        }
                        if (ParallelImagePreloadProcessor.QUEUE_HELPER_OWNER.equals(owner)
                                && "awaitBytes".equals(methodName)) {
                            awaitBytesHelper[0] = true;
                        }
                        if (ParallelImagePreloadProcessor.QUEUE_HELPER_OWNER.equals(owner)
                                && "enqueueImage".equals(methodName)) {
                            enqueueImageHelper[0] = true;
                        }
                        if (ParallelImagePreloadProcessor.QUEUE_HELPER_OWNER.equals(owner)
                                && "enqueueBytes".equals(methodName)) {
                            enqueueBytesHelper[0] = true;
                        }
                        if ("javax/imageio/ImageIO".equals(owner) && "read".equals(methodName)) {
                            imageIoRead[0] = true;
                        }
                        if (itf && "clear".equals(methodName) && "()V".equals(methodDesc)) {
                            clearCalls[0]++;
                        }
                    }
                };
            }
        }, 0);

        return new Inspection(
                startHelper[0],
                stopHelper[0],
                queueClearHelper[0],
                decodeHelper[0],
                awaitImageHelper[0],
                awaitBytesHelper[0],
                originalAwaitBytesMethod[0],
                enqueueImageHelper[0],
                enqueueBytesHelper[0],
                !imageIoRead[0],
                clearCalls[0]
        );
    }

    private record Inspection(boolean callsStartHelper,
                              boolean callsStopHelper,
                              boolean callsQueueClearHelper,
                              boolean callsDecodeHelper,
                              boolean callsAwaitImageHelper,
                              boolean callsAwaitBytesHelper,
                              boolean hasOriginalAwaitBytesMethod,
                              boolean callsEnqueueImageHelper,
                              boolean callsEnqueueBytesHelper,
                              boolean imageIoReadRemoved,
                              int clearCalls) {
    }
}