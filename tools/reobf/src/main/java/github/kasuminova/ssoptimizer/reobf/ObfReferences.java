package github.kasuminova.ssoptimizer.reobf;

import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.List;
import java.util.Set;

import static github.kasuminova.ssoptimizer.reobf.GameLinkage.samePackage;
import static org.objectweb.asm.Opcodes.*;

final class ObfReferences {
    private final GameLinkage index;
    private final ClassNode type;
    private final GameLinkage.Header caller;
    private final Set<String> errors;
    private String site;
    private MethodNode method;
    private int instructionIndex;
    private Frame<BasicValue>[] frames;
    private boolean analyzed;

    ObfReferences(GameLinkage index, ClassNode type, Set<String> errors) {
        this.index = index;
        this.type = type;
        this.caller = index.find(type.name);
        this.errors = errors;
    }

    void verify() {
        site = type.name + ".class";
        if (type.superName != null) inheritance(type.superName, false);
        for (String name : type.interfaces) inheritance(name, true);
        overrides();
        signature(type.signature, false);
        gameType(type.outerClass, false);
        gameType(type.nestHostClass, false);
        if (type.nestMembers != null) for (String name : type.nestMembers) gameType(name, false);
        if (type.permittedSubclasses != null) for (String name : type.permittedSubclasses) gameType(name, false);
        for (var inner : type.innerClasses) {
            gameType(inner.name, false);
            gameType(inner.outerName, false);
        }
        if (type.outerMethodDesc != null) descriptor(type.outerMethodDesc, false);
        annotations(type.visibleAnnotations, type.invisibleAnnotations, type.visibleTypeAnnotations, type.invisibleTypeAnnotations);
        if (type.recordComponents != null) {
            for (var component : type.recordComponents) {
                site = type.name + ".class." + component.name;
                descriptor(component.descriptor, false);
                signature(component.signature, true);
                annotations(component.visibleAnnotations, component.invisibleAnnotations, component.visibleTypeAnnotations, component.invisibleTypeAnnotations);
            }
        }
        for (var field : type.fields) {
            site = type.name + ".class." + field.name + ":" + field.desc;
            descriptor(field.desc, false);
            signature(field.signature, true);
            constant(field.value);
            annotations(field.visibleAnnotations, field.invisibleAnnotations, field.visibleTypeAnnotations, field.invisibleTypeAnnotations);
        }
        for (MethodNode member : type.methods) verifyMethod(member);
    }

    private void verifyMethod(MethodNode member) {
        method = member;
        analyzed = false;
        frames = null;
        String methodSite = type.name + ".class." + member.name + member.desc;
        site = methodSite;
        descriptor(member.desc, false);
        signature(member.signature, false);
        for (String thrown : member.exceptions) gameType(thrown, false);
        annotations(member.visibleAnnotations, member.invisibleAnnotations, member.visibleTypeAnnotations, member.invisibleTypeAnnotations,
                member.visibleLocalVariableAnnotations, member.invisibleLocalVariableAnnotations);
        parameters(member.visibleParameterAnnotations);
        parameters(member.invisibleParameterAnnotations);
        annotationValue(member.annotationDefault);
        if (member.localVariables != null) {
            for (var local : member.localVariables) {
                descriptor(local.desc, false);
                signature(local.signature, true);
            }
        }
        for (var handler : member.tryCatchBlocks) {
            var caught = gameType(handler.type, true);
            if (caught != null && caught.game && !index.subtype(caught, index.find("java/lang/Throwable"))) {
                error("异常处理器类型未继承 Throwable: " + caught.name);
            }
            annotations(handler.visibleTypeAnnotations, handler.invisibleTypeAnnotations);
        }
        int line = 0;
        int opcodeIndex = 0;
        instructionIndex = 0;
        for (var insn : member.instructions) {
            if (insn instanceof LineNumberNode source) line = source.line;
            site = methodSite + " 指令 " + opcodeIndex + (line == 0 ? "" : " (源行 " + line + ")");
            instruction(insn);
            annotations(insn.visibleTypeAnnotations, insn.invisibleTypeAnnotations);
            if (insn.getOpcode() >= 0) opcodeIndex++;
            instructionIndex++;
        }
    }

    private void instruction(AbstractInsnNode insn) {
        switch (insn) {
            case FieldInsnNode field -> member(field.owner, field.name, field.desc, field.getOpcode(), null, false);
            case MethodInsnNode call -> member(call.owner, call.name, call.desc, call.getOpcode(), call.itf, false);
            case TypeInsnNode operand -> {
                var target = gameType(operand.desc, true);
                if (operand.getOpcode() == NEW && target != null && target.game && (target.access & (ACC_ABSTRACT | ACC_INTERFACE)) != 0) {
                    error("NEW 目标不可实例化: " + target.name);
                }
            }
            case MultiANewArrayInsnNode array -> descriptor(array.desc, true);
            case LdcInsnNode ldc -> constant(ldc.cst);
            case InvokeDynamicInsnNode dynamic -> {
                descriptor(dynamic.desc, true);
                handle(dynamic.bsm);
                for (Object argument : dynamic.bsmArgs) constant(argument);
                var factory = dynamic.bsm;
                if (factory.getOwner().equals("java/lang/invoke/LambdaMetafactory")
                        && (factory.getName().equals("metafactory") || factory.getName().equals("altMetafactory"))
                        && dynamic.bsmArgs.length > 0 && dynamic.bsmArgs[0] instanceof Type sam
                        && Type.getReturnType(dynamic.desc).getSort() == Type.OBJECT) {
                    member(Type.getReturnType(dynamic.desc).getInternalName(), dynamic.name, sam.getDescriptor(), INVOKEINTERFACE, true, false);
                }
            }
            case FrameNode frame -> {
                frameTypes(frame.local);
                frameTypes(frame.stack);
            }
            default -> { }
        }
    }

    private void frameTypes(List<Object> values) {
        if (values != null) for (Object value : values) if (value instanceof String name) gameType(name, false);
    }

    private void inheritance(String name, boolean expectInterface) {
        var parent = gameType(name, true);
        if (parent == null || !index.gameRelated(parent)) return;
        if (parent.isInterface() != expectInterface || !expectInterface && (parent.access & ACC_FINAL) != 0) {
            error("非法" + (expectInterface ? "父接口: " : "父类: ") + name);
        }
        if (!parent.permitted.isEmpty() && !parent.permitted.contains(type.name)) error("封闭类型未允许继承: " + name);
    }

    private void overrides() {
        for (var ancestor : index.ancestry(caller)) {
            if (!ancestor.game) continue;
            for (var declaration : ancestor.methods.entrySet()) {
                var key = declaration.getKey();
                int flags = declaration.getValue();
                if (key.name().startsWith("<") || (flags & (ACC_STATIC | ACC_PRIVATE)) != 0
                        || (flags & (ACC_PUBLIC | ACC_PROTECTED)) == 0 && !samePackage(caller, ancestor)) continue;
                Integer own = caller.methods.get(key);
                String symbol = ancestor.name + "." + key;
                if (own != null && (own & ACC_PRIVATE) == 0 && ((flags & ACC_FINAL) != 0 || (own & ACC_STATIC) != 0)) {
                    error("方法声明与继承的游戏方法冲突: " + symbol);
                }
                if ((caller.access & (ACC_INTERFACE | ACC_ABSTRACT)) != 0 || (flags & ACC_ABSTRACT) == 0) continue;
                var resolved = resolve(caller, key, false);
                if (resolved == null || (resolved.access() & (ACC_ABSTRACT | ACC_STATIC | ACC_PRIVATE)) != 0
                        || ancestor.isInterface() && (resolved.access() & ACC_PUBLIC) == 0) {
                    error("缺少游戏抽象方法的实现: " + symbol);
                }
            }
        }
    }

    private void member(String name, String memberName, String desc, int opcode, Boolean isInterface, boolean handle) {
        boolean isField = isInterface == null;
        boolean isStatic = opcode == GETSTATIC || opcode == PUTSTATIC || opcode == INVOKESTATIC;
        boolean isWrite = opcode == PUTFIELD || opcode == PUTSTATIC;
        descriptor(desc, handle);
        var owner = gameType(name, true);
        if (owner == null || !index.gameRelated(owner)) return;
        var key = new GameLinkage.Member(memberName, desc);
        String symbol = name + "." + key;
        if (!isField && (isInterface != owner.isInterface() || opcode == INVOKEINTERFACE && !isInterface
                || opcode == INVOKEVIRTUAL && isInterface)) {
            error("类/接口调用形状不匹配: " + symbol);
            return;
        }
        boolean constructor = memberName.equals("<init>");
        if (!isField && (memberName.equals("<clinit>") || constructor && (opcode != INVOKESPECIAL || owner.isInterface()
                || Type.getReturnType(desc).getSort() != Type.VOID))) {
            error("初始化方法调用形状不匹配: " + symbol);
            return;
        }
        var target = resolve(owner, key, isField);
        if (target == null) {
            error("找不到" + (isField ? "字段: " : "方法: ") + symbol);
            return;
        }
        if (((target.access() & ACC_STATIC) != 0) != isStatic) {
            error("静态/实例调用不匹配: " + symbol);
            return;
        }
        var declaring = target.owner();
        int access = target.access();
        if ((access & ACC_PUBLIC) == 0 && caller != declaring) {
            boolean protectedSubclass = (access & ACC_PROTECTED) != 0 && index.subtype(caller, declaring);
            boolean accessible = (access & ACC_PRIVATE) != 0 ? index.nestmates(caller, declaring)
                    : samePackage(caller, declaring) || protectedSubclass;
            if (!accessible) {
                error("成员不可访问: " + symbol + "，声明于 " + declaring.name);
                return;
            }
            if (protectedSubclass && !isStatic && !samePackage(caller, declaring)) {
                if (!index.subtype(caller, owner) && !index.subtype(owner, caller)) {
                    error("跨包 protected 成员的符号 owner 与调用类无继承关系: " + symbol);
                } else if (!handle && (opcode != INVOKESPECIAL || constructor)) {
                    protectedReceiver(symbol, desc, opcode);
                }
            }
        }
        if (isWrite && (access & ACC_FINAL) != 0 && (handle || caller != declaring
                || !method.name.equals(isStatic ? "<clinit>" : "<init>"))) error("不能写入 final 字段: " + symbol);
        if (!isField && opcode == INVOKESPECIAL && !constructor) {
            if (owner != caller && (owner.isInterface() ? !caller.interfaces.contains(owner.name) : !index.subtype(caller, owner))) {
                error("INVOKESPECIAL 的 owner 不在调用类的父类链或直接父接口中: " + symbol);
                return;
            }
            var selected = target;
            if (owner != caller && !owner.isInterface() && !declaring.isInterface() && (caller.access & ACC_SUPER) != 0) {
                var direct = index.find(caller.parent);
                if (direct != null) selected = resolve(direct, key, false);
            }
            if (selected != null && (selected.access() & ACC_ABSTRACT) != 0) error("INVOKESPECIAL 选中了抽象方法: " + symbol);
        }
    }

    private GameLinkage.Declaration resolve(GameLinkage.Header owner, GameLinkage.Member key, boolean field) {
        try {
            return field ? index.field(owner, key) : index.method(owner, key);
        } catch (IllegalArgumentException e) {
            error(e.getMessage());
            return null;
        }
    }

    private void protectedReceiver(String symbol, String desc, int opcode) {
        if (!analyzed) {
            analyzed = true;
            try {
                frames = new Analyzer<>(new ReceiverTypes()).analyze(caller.name, method);
            } catch (AnalyzerException e) {
                error("无法分析 protected 成员的接收者: " + e.getMessage());
            }
        }
        if (frames == null || frames[instructionIndex] == null) return;
        var frame = frames[instructionIndex];
        int arguments = opcode == PUTFIELD ? 1 : opcode == GETFIELD ? 0 : Type.getArgumentTypes(desc).length;
        Type receiver = frame.getStack(frame.getStackSize() - arguments - 1).getType();
        if (receiver == null || receiver.equals(BasicInterpreter.NULL_TYPE)) return;
        var actual = receiver.getSort() == Type.OBJECT ? index.find(receiver.getInternalName()) : null;
        if (actual != null && !index.subtype(actual, caller)) error("跨包 protected 成员的接收者须为 " + caller.name + " 或其子类: " + symbol);
    }

    private final class ReceiverTypes extends BasicInterpreter {
        ReceiverTypes() { super(ASM9); }

        @Override public BasicValue newValue(Type value) {
            return value != null && (value.getSort() == Type.OBJECT || value.getSort() == Type.ARRAY) ? new BasicValue(value) : super.newValue(value);
        }

        @Override public BasicValue binaryOperation(AbstractInsnNode insn, BasicValue a, BasicValue b) throws AnalyzerException {
            Type array = a.getType();
            if (insn.getOpcode() == AALOAD && array != null && array.getSort() == Type.ARRAY) {
                return newValue(Type.getType(array.getDescriptor().substring(1)));
            }
            return super.binaryOperation(insn, a, b);
        }

        @Override public BasicValue merge(BasicValue a, BasicValue b) {
            if (a.equals(b)) return a;
            if (!a.isReference() || !b.isReference()) return super.merge(a, b);
            return newValue(mergeTypes(a.getType(), b.getType()));
        }

        private Type mergeTypes(Type a, Type b) {
            if (a.equals(b) || b.equals(NULL_TYPE)) return a;
            if (a.equals(NULL_TYPE)) return b;
            if (a.getSort() == Type.ARRAY && b.getSort() == Type.ARRAY) {
                Type ea = Type.getType(a.getDescriptor().substring(1));
                Type eb = Type.getType(b.getDescriptor().substring(1));
                if (ea.getSort() >= Type.ARRAY && eb.getSort() >= Type.ARRAY) {
                    return Type.getType("[" + mergeTypes(ea, eb).getDescriptor());
                }
            }
            if (a.getSort() == Type.OBJECT && b.getSort() == Type.OBJECT) {
                var first = index.find(a.getInternalName());
                var second = index.find(b.getInternalName());
                if (first != null && second != null) {
                    if (index.subtype(second, first)) return a;
                    if (index.subtype(first, second)) return b;
                    for (var base = index.find(first.parent); base != null; base = index.find(base.parent)) {
                        if (index.subtype(second, base)) return Type.getObjectType(base.name);
                    }
                }
            }
            return Type.getObjectType("java/lang/Object");
        }
    }

    private void constant(Object value) {
        switch (value) {
            case Type literal -> inspect(literal, true);
            case Handle methodHandle -> handle(methodHandle);
            case ConstantDynamic dynamic -> {
                descriptor(dynamic.getDescriptor(), true);
                handle(dynamic.getBootstrapMethod());
                for (int i = 0; i < dynamic.getBootstrapMethodArgumentCount(); i++) constant(dynamic.getBootstrapMethodArgument(i));
            }
            case null, default -> { }
        }
    }

    private void handle(Handle h) {
        int opcode = switch (h.getTag()) {
            case H_GETFIELD -> GETFIELD;
            case H_GETSTATIC -> GETSTATIC;
            case H_PUTFIELD -> PUTFIELD;
            case H_PUTSTATIC -> PUTSTATIC;
            case H_INVOKEVIRTUAL -> INVOKEVIRTUAL;
            case H_INVOKESTATIC -> INVOKESTATIC;
            case H_INVOKESPECIAL, H_NEWINVOKESPECIAL -> INVOKESPECIAL;
            case H_INVOKEINTERFACE -> INVOKEINTERFACE;
            default -> throw new IllegalArgumentException("未知方法句柄类型: " + h);
        };
        boolean field = h.getTag() <= H_PUTSTATIC;
        if (!field && (h.getTag() == H_NEWINVOKESPECIAL) != h.getName().equals("<init>")) {
            var owner = index.find(h.getOwner());
            if (owner != null && index.gameRelated(owner)) {
                error("构造器句柄类型不匹配: " + h);
                return;
            }
        }
        member(h.getOwner(), h.getName(), h.getDesc(), opcode, field ? null : h.isInterface(), true);
    }

    private GameLinkage.Header gameType(String name, boolean access) {
        if (name == null) return null;
        if (name.startsWith("[")) {
            descriptor(name, access);
            return null;
        }
        var target = index.find(name);
        if (target == null && GameLinkage.gameNamespace(name)) error("游戏类型不存在: " + name);
        if (target != null && target.game && access && (target.access & ACC_PUBLIC) == 0 && !samePackage(caller, target)) {
            error("游戏类型不可访问: " + name);
        }
        return target;
    }

    private void descriptor(String desc, boolean access) { inspect(Type.getType(desc), access); }

    private void inspect(Type value, boolean access) {
        switch (value.getSort()) {
            case Type.OBJECT -> gameType(value.getInternalName(), access);
            case Type.ARRAY -> inspect(value.getElementType(), access);
            case Type.METHOD -> {
                for (Type arg : value.getArgumentTypes()) inspect(arg, access);
                inspect(value.getReturnType(), access);
            }
            default -> { }
        }
    }

    private void signature(String signature, boolean typeOnly) {
        if (signature == null) return;
        var reader = new SignatureReader(signature);
        if (typeOnly) reader.acceptType(new SignatureTypes());
        else reader.accept(new SignatureTypes());
    }

    private final class SignatureTypes extends SignatureVisitor {
        private String enclosing;

        SignatureTypes() { super(ASM9); }
        @Override public SignatureVisitor visitClassBound() { return new SignatureTypes(); }
        @Override public SignatureVisitor visitInterfaceBound() { return new SignatureTypes(); }
        @Override public SignatureVisitor visitSuperclass() { return new SignatureTypes(); }
        @Override public SignatureVisitor visitInterface() { return new SignatureTypes(); }
        @Override public SignatureVisitor visitParameterType() { return new SignatureTypes(); }
        @Override public SignatureVisitor visitReturnType() { return new SignatureTypes(); }
        @Override public SignatureVisitor visitExceptionType() { return new SignatureTypes(); }
        @Override public SignatureVisitor visitArrayType() { return new SignatureTypes(); }
        @Override public SignatureVisitor visitTypeArgument(char wildcard) { return new SignatureTypes(); }
        @Override public void visitClassType(String name) { enclosing = name; gameType(name, false); }
        @Override public void visitInnerClassType(String name) { enclosing += "$" + name; gameType(enclosing, false); }
    }

    @SafeVarargs
    private final void annotations(List<? extends AnnotationNode>... groups) {
        for (var group : groups) if (group != null) for (var annotation : group) annotation(annotation);
    }

    private void parameters(List<AnnotationNode>[] groups) {
        if (groups != null) for (var group : groups) annotations(group);
    }

    private void annotation(AnnotationNode annotation) {
        descriptor(annotation.desc, false);
        if (annotation.values != null) for (int i = 1; i < annotation.values.size(); i += 2) annotationValue(annotation.values.get(i));
    }

    private void annotationValue(Object value) {
        switch (value) {
            case Type type -> inspect(type, false);
            case AnnotationNode nested -> annotation(nested);
            case String[] enumeration -> descriptor(enumeration[0], false);
            case List<?> array -> array.forEach(this::annotationValue);
            case null, default -> { }
        }
    }

    private void error(String detail) { errors.add(site + ": " + detail); }
}
