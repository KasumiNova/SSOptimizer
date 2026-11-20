package github.kasuminova.ssoptimizer.reobf;

import github.kasuminova.ssoptimizer.standalone.obf.ObfClasses;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

final class GameClasses {
    private final List<Path> archives;

    GameClasses(List<Path> archives) {
        this.archives = archives;
    }

    List<String> methodDescriptors(String owner, String name) throws IOException {
        String resource = owner + ".class";
        for (Path path : archives) {
            try (var jar = new JarFile(path.toFile(), false, ZipFile.OPEN_READ, Runtime.version())) {
                var entry = jar.getJarEntry(resource);
                if (entry == null) continue;
                try (var stream = jar.getInputStream(entry)) {
                    var descriptors = new ArrayList<String>();
                    new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override
                        public MethodVisitor visitMethod(int access, String method, String desc, String signature, String[] exceptions) {
                            if (ObfClasses.memberName(method, true).equals(name)) descriptors.add(desc);
                            return null;
                        }
                    }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    return descriptors;
                }
            }
        }
        return List.of();
    }
}
