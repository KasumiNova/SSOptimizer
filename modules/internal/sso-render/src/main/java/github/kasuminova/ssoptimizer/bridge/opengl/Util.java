package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;
import org.lwjgl.opengl.OpenGLException;

public final class Util {
    private Util() {
    }

    public static void install(RenderQueue renderQueue) {
        BridgeSupport.install(renderQueue);
    }

    static void uninstall() {
        BridgeSupport.uninstall();
    }

    public static void checkGLError() throws OpenGLException {
        int error = BridgeSupport.blockingGet(org.lwjgl.opengl.GL11::glGetError);
        if (error != org.lwjgl.opengl.GL11.GL_NO_ERROR) {
            throw new OpenGLException(error);
        }
    }

    public static String translateGLErrorString(int errorCode) {
        return org.lwjgl.opengl.Util.translateGLErrorString(errorCode);
    }
}
