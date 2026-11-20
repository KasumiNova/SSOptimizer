package github.kasuminova.ssoptimizer.standalone.obf;

import java.lang.classfile.ClassFile;
import java.lang.classfile.Opcode;
import java.lang.classfile.constantpool.InterfaceMethodRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.instruction.ConstantInstruction.LoadConstantInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewMultiArrayInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.NewReferenceArrayInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import org.objectweb.asm.tree.ClassNode;

import static org.objectweb.asm.Opcodes.*;

final class ModClassStructure {
    private ModClassStructure() {}

    static void check(byte[] bytes, ClassNode node) {
        var model = ClassFile.of().parse(bytes);
        int major = model.majorVersion();
        int flags = model.flags().flagsMask();
        boolean itf = (flags & ACC_INTERFACE) != 0;
        require((flags & (ACC_FINAL | ACC_ABSTRACT)) != (ACC_FINAL | ACC_ABSTRACT), "final abstract class");
        require((flags & ACC_ANNOTATION) == 0 || itf, "annotation without interface flag");
        if (itf) {
            require((flags & ACC_ABSTRACT) != 0 && (flags & (ACC_FINAL | ACC_SUPER | ACC_ENUM | ACC_MODULE)) == 0,
                    "invalid interface flags");
            require("java/lang/Object".equals(node.superName), "invalid interface superclass");
        }
        if ((flags & ACC_MODULE) != 0) {
            require(major >= 53 && flags == ACC_MODULE && node.name.equals("module-info") && node.superName == null
                    && node.interfaces.isEmpty() && node.fields.isEmpty() && node.methods.isEmpty(), "invalid module descriptor");
        } else {
            require((node.superName == null) == node.name.equals("java/lang/Object"), "invalid superclass");
        }
        for (var field : model.fields()) {
            int f = field.flags().flagsMask();
            visibility(f);
            require((f & (ACC_FINAL | ACC_VOLATILE)) != (ACC_FINAL | ACC_VOLATILE), "final volatile field");
            if (itf) {
                int required = ACC_PUBLIC | ACC_STATIC | ACC_FINAL;
                require((f & required) == required && (f & (ACC_VOLATILE | ACC_TRANSIENT | ACC_ENUM)) == 0,
                        "invalid interface field flags");
            }
            fieldType(field.fieldType().stringValue());
        }
        for (var method : model.methods()) {
            int f = method.flags().flagsMask();
            String name = method.methodName().stringValue();
            var desc = method.methodTypeSymbol();
            int slots = (f & ACC_STATIC) == 0 ? 1 : 0;
            for (var param : desc.parameterList()) slots += width(param);
            require(slots <= 255, "too many method parameter slots");
            if (!name.equals("<clinit>")) {
                visibility(f);
                int incompatible = ACC_FINAL | ACC_NATIVE | ACC_PRIVATE | ACC_STATIC | ACC_SYNCHRONIZED;
                if (major >= 46 && major <= 60) incompatible |= ACC_STRICT;
                require((f & ACC_ABSTRACT) == 0 || (f & incompatible) == 0, "invalid abstract method flags");
                if (name.equals("<init>")) {
                    require(!itf && desc.returnType().equals(ConstantDescs.CD_void)
                            && (f & (ACC_STATIC | ACC_FINAL | ACC_SYNCHRONIZED | ACC_BRIDGE | ACC_NATIVE | ACC_ABSTRACT)) == 0,
                            "invalid constructor");
                }
                if (itf) {
                    require((f & (ACC_PROTECTED | ACC_FINAL | ACC_SYNCHRONIZED | ACC_NATIVE)) == 0,
                            "invalid interface method flags");
                    require(major >= 52 ? (f & (ACC_PUBLIC | ACC_PRIVATE)) != 0 : (f & (ACC_PUBLIC | ACC_ABSTRACT)) == (ACC_PUBLIC | ACC_ABSTRACT),
                            "invalid interface method visibility");
                }
            } else {
                require(desc.returnType().equals(ConstantDescs.CD_void) && (major < 51 || (f & ACC_STATIC) != 0 && desc.parameterCount() == 0), "invalid class initializer");
            }
            var code = method.code();
            boolean requiresCode = name.equals("<clinit>") || (f & (ACC_ABSTRACT | ACC_NATIVE)) == 0;
            require(code.isPresent() == requiresCode, "invalid Code attribute on " + name);
            if (code.isEmpty()) continue;
            var body = (java.lang.classfile.attribute.CodeAttribute) code.get();
            require(body.codeLength() > 0 && body.codeLength() < 65536 && body.maxLocals() >= slots, "invalid Code size on " + name);
            for (var element : code.get()) {
                if (!(element instanceof java.lang.classfile.Instruction instruction)) continue;
                switch (instruction) {
                    case InvokeInstruction call -> {
                        var ref = call.method();
                        boolean interfaceRef = ref instanceof InterfaceMethodRefEntry;
                        require(interfaceRef || ref instanceof MethodRefEntry, "invalid method reference");
                        require(switch (call.opcode()) {
                            case INVOKEINTERFACE -> interfaceRef;
                            case INVOKEVIRTUAL -> !interfaceRef;
                            default -> !interfaceRef || major >= 52;
                        }, "method reference does not match " + call.opcode());
                        var signature = call.typeSymbol();
                        if (call.opcode() == Opcode.INVOKEINTERFACE) {
                            require(call.count() == 1 + signature.parameterList().stream().mapToInt(ModClassStructure::width).sum(),
                                    "invalid invokeinterface argument count");
                        }
                    }
                    case FieldInstruction field -> fieldType(field.type().stringValue());
                    case InvokeDynamicInstruction indy -> indy.typeSymbol();
                    case LoadConstantInstruction load -> require((load.opcode() == Opcode.LDC2_W) == (load.constantEntry().typeKind().slotSize() == 2), "wrong constant width");
                    case NewObjectInstruction allocation -> require(allocation.className().asSymbol().isClassOrInterface(), "new requires a class");
                    case NewReferenceArrayInstruction array -> array.componentType().asSymbol();
                    case TypeCheckInstruction cast -> cast.type().asSymbol();
                    case NewMultiArrayInstruction array -> {
                        String descriptor = array.arrayType().asInternalName();
                        int dimensions = 0;
                        while (dimensions < descriptor.length() && descriptor.charAt(dimensions) == '[') dimensions++;
                        require(array.dimensions() > 0 && array.dimensions() <= dimensions, "invalid multianewarray dimensions");
                    }
                    default -> { }
                }
            }
        }
    }

    private static void fieldType(String descriptor) {
        require(!ClassDesc.ofDescriptor(descriptor).equals(ConstantDescs.CD_void), "void field descriptor");
    }

    private static int width(ClassDesc type) {
        return type.equals(ConstantDescs.CD_long) || type.equals(ConstantDescs.CD_double) ? 2 : 1;
    }

    private static void visibility(int flags) {
        require(Integer.bitCount(flags & (ACC_PUBLIC | ACC_PRIVATE | ACC_PROTECTED)) <= 1, "conflicting visibility flags");
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }
}
