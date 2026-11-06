package github.kasuminova.ssoptimizer.bridge.opengl;

/** 仅在持有 GL 上下文的线程调用。 */
interface RealSyncOps {
    Object fenceSync(int condition, int flags);

    void waitSync(Object sync, int flags, long timeout);

    int clientWaitSync(Object sync, int flags, long timeout);

    void deleteSync(Object sync);

    /** 在非相干持久映射写入后建立 CPU 写入对后续 GPU 命令的可见性。 */
    void memoryBarrier(int barriers);
}
