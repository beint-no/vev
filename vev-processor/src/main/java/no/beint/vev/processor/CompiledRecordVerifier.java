package no.beint.vev.processor;

import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.attribute.RecordComponentInfo;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.StandardLocation;

/** Build-time proof of the same direct snapshot operations accepted from Java source. */
final class CompiledRecordVerifier {
    private static final int MAXIMUM_CLASS_BYTES = 1_048_576;
    private CompiledRecordVerifier() {
    }

    static void verify(ProcessingEnvironment environment, TypeElement entity) throws IOException {
        require(environment.getElementUtils().getModuleOf(entity).isUnnamed(),
                "Compiled record verification currently requires the ordinary class path");
        var file = environment.getFiler().getResource(StandardLocation.CLASS_PATH,
                environment.getElementUtils().getPackageOf(entity).getQualifiedName(), entity.getSimpleName() + ".class");
        byte[] bytes;
        try (var input = file.openInputStream()) {
            bytes = input.readNBytes(MAXIMUM_CLASS_BYTES + 1);
        }
        require(bytes.length <= MAXIMUM_CLASS_BYTES, "Compiled record exceeds the one-MiB class-file bound");
        ClassModel model = ClassFile.of().parse(bytes);
        ClassDesc owner = ClassDesc.of(entity.getQualifiedName().toString());
        require(model.thisClass().matches(owner) && model.flags().has(AccessFlag.PUBLIC)
                && model.flags().has(AccessFlag.FINAL)
                && model.superclass().orElseThrow().asInternalName().equals("java/lang/Record")
                && model.interfaces().isEmpty(), "Compiled snapshot must be a public final record without interfaces");
        List<RecordComponentInfo> components = model.findAttribute(Attributes.record()).orElseThrow().components();
        require(components.size() == entity.getRecordComponents().size(), "Compiled record component metadata differs");
        for (int index = 0; index < components.size(); index++) {
            var expected = entity.getRecordComponents().get(index);
            require(components.get(index).name().equalsString(expected.getSimpleName().toString())
                    && components.get(index).descriptorSymbol().equals(descriptor(environment, expected.asType())),
                    "Compiled record component type or order differs from the compiler's resolved declaration");
        }
        verifyFields(model, components);
        MethodTypeDesc constructorType = MethodTypeDesc.of(ConstantDescs.CD_void,
                components.stream().map(RecordComponentInfo::descriptorSymbol).toArray(ClassDesc[]::new));
        Set<String> verified = new HashSet<>();
        for (MethodModel method : model.methods()) {
            String name = method.methodName().stringValue();
            require(!name.equals("<clinit>"), "Compiled record initialization must not execute code");
            if (method.flags().has(AccessFlag.STATIC)) continue;
            if (name.equals("<init>")) {
                if (method.methodTypeSymbol().equals(constructorType)) {
                    verifyConstructor(method, owner, components);
                    verified.add("<init>");
                }
                continue;
            }
            RecordComponentInfo component = components.stream().filter(value -> value.name().equalsString(name))
                    .findFirst().orElse(null);
            if (component != null && method.methodTypeSymbol().equals(MethodTypeDesc.of(component.descriptorSymbol()))) {
                verifyAccessor(method, owner, component);
                require(verified.add(name), "Compiled record declares an ambiguous snapshot accessor");
            }
        }
        require(verified.size() == components.size() + 1 && verified.contains("<init>"),
                "Compiled record is missing canonical snapshot operations");
    }

    private static ClassDesc descriptor(ProcessingEnvironment environment, TypeMirror type) {
        return switch (type.getKind()) {
            case BOOLEAN -> ConstantDescs.CD_boolean;
            case BYTE -> ConstantDescs.CD_byte;
            case SHORT -> ConstantDescs.CD_short;
            case INT -> ConstantDescs.CD_int;
            case LONG -> ConstantDescs.CD_long;
            case CHAR -> ConstantDescs.CD_char;
            case FLOAT -> ConstantDescs.CD_float;
            case DOUBLE -> ConstantDescs.CD_double;
            case DECLARED -> ClassDesc.of(environment.getElementUtils().getBinaryName(
                    (TypeElement) environment.getTypeUtils().asElement(type)).toString());
            default -> throw new IllegalArgumentException("Compiled record requires concrete scalar component types");
        };
    }

    private static void verifyFields(ClassModel model, List<RecordComponentInfo> components) {
        int instanceFields = 0;
        for (var field : model.fields()) {
            if (field.flags().has(AccessFlag.STATIC)) {
                require(field.flags().has(AccessFlag.FINAL) && field.findAttribute(Attributes.constantValue()).isPresent(),
                        "Compiled record static fields must be compile-time constants");
            } else {
                instanceFields++;
                require(field.flags().flagsMask() == (ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL)
                        && components.stream().anyMatch(component -> component.name().equalsString(field.fieldName().stringValue())
                                && component.descriptorSymbol().equals(field.fieldTypeSymbol())),
                        "Compiled record fields must exactly match its immutable components");
            }
        }
        require(instanceFields == components.size(), "Compiled record contains additional instance state");
    }

    private static List<Instruction> instructions(MethodModel method) {
        require(method.flags().has(AccessFlag.PUBLIC) && !method.flags().has(AccessFlag.SYNCHRONIZED)
                && !method.flags().has(AccessFlag.NATIVE), "Compiled snapshot operation must be public and direct");
        var code = method.code().orElseThrow();
        require(code.exceptionHandlers().isEmpty(), "Compiled snapshot operations must not intercept failures");
        return code.elementStream().filter(Instruction.class::isInstance).map(Instruction.class::cast).toList();
    }

    private static void verifyConstructor(MethodModel method, ClassDesc owner, List<RecordComponentInfo> components) {
        List<Instruction> code = instructions(method);
        require(code.size() == 3 * components.size() + 3, "Compiled canonical constructor must only assign components");
        require(load(code.getFirst(), TypeKind.REFERENCE, 0)
                && code.get(1) instanceof InvokeInstruction invoke && invoke.opcode() == Opcode.INVOKESPECIAL
                && invoke.owner().asInternalName().equals("java/lang/Record") && invoke.name().equalsString("<init>")
                && invoke.type().equalsString("()V"), "Compiled canonical constructor must directly initialize java.lang.Record");
        int slot = 1;
        for (int index = 0; index < components.size(); index++) {
            RecordComponentInfo component = components.get(index);
            TypeKind kind = TypeKind.from(component.descriptorSymbol()).asLoadable();
            int offset = 2 + 3 * index;
            require(load(code.get(offset), TypeKind.REFERENCE, 0) && load(code.get(offset + 1), kind, slot)
                    && field(code.get(offset + 2), Opcode.PUTFIELD, owner, component),
                    "Compiled canonical constructor must store each unmodified argument exactly once in order");
            slot += kind.slotSize();
        }
        require(returns(code.getLast(), TypeKind.VOID), "Compiled canonical constructor must return directly");
    }

    private static void verifyAccessor(MethodModel method, ClassDesc owner, RecordComponentInfo component) {
        List<Instruction> code = instructions(method);
        require(code.size() == 3 && load(code.getFirst(), TypeKind.REFERENCE, 0)
                && field(code.get(1), Opcode.GETFIELD, owner, component)
                && returns(code.getLast(), TypeKind.from(component.descriptorSymbol()).asLoadable()),
                "Compiled record accessor must directly return its unmodified component");
    }

    private static boolean load(Instruction instruction, TypeKind kind, int slot) {
        return instruction instanceof LoadInstruction load && load.typeKind() == kind && load.slot() == slot;
    }

    private static boolean returns(Instruction instruction, TypeKind kind) {
        return instruction instanceof ReturnInstruction returned && returned.typeKind() == kind;
    }

    private static boolean field(Instruction instruction, Opcode opcode, ClassDesc owner, RecordComponentInfo component) {
        return instruction instanceof FieldInstruction field && field.opcode() == opcode && field.owner().matches(owner)
                && field.name().equalsString(component.name().stringValue()) && field.typeSymbol().equals(component.descriptorSymbol());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
