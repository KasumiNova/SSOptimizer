package github.kasuminova.ssoptimizer.modopt.dcr;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DcrBatchSaveWindowTest {

    @AfterEach
    void closeWindow() {
        DcrBatchSaveWindow.end();
    }

    @Test
    void defersEverySaveExceptTheLastOne() {
        final int pending = 4;
        DcrBatchSaveWindow.begin(pending);

        for (int i = 0; i < pending - 1; i++) {
            assertTrue(DcrBatchSaveWindow.deferCurrentSave(), "窗口内非最后一次保存应被跳过");
        }
        assertFalse(DcrBatchSaveWindow.deferCurrentSave(), "最后一次保存必须真正执行");
        assertFalse(DcrBatchSaveWindow.deferCurrentSave(), "窗口耗尽后不得再跳过保存");
    }

    @Test
    void singleSaveDoesNotOpenWindow() {
        DcrBatchSaveWindow.begin(1);

        assertFalse(DcrBatchSaveWindow.deferCurrentSave());
    }

    @Test
    void endBeforeCompletionRestoresPerSaveBehavior() {
        DcrBatchSaveWindow.begin(3);
        assertTrue(DcrBatchSaveWindow.deferCurrentSave());

        DcrBatchSaveWindow.end();

        assertFalse(DcrBatchSaveWindow.deferCurrentSave(), "提前结束后每次保存都应真正执行");
    }

    @Test
    void otherThreadsAreNeverDeferred() throws Exception {
        DcrBatchSaveWindow.begin(3);
        final AtomicBoolean deferredOnOtherThread = new AtomicBoolean(true);

        final Thread other = new Thread(() -> deferredOnOtherThread.set(DcrBatchSaveWindow.deferCurrentSave()));
        other.start();
        other.join();

        assertFalse(deferredOnOtherThread.get(), "非窗口持有线程的保存不得被跳过");
        assertTrue(DcrBatchSaveWindow.deferCurrentSave(), "其他线程的调用不应消耗持有线程的窗口计数");
    }
}
