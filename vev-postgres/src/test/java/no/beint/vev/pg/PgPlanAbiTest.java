package no.beint.vev.pg;

import no.beint.vev.ModelIdentity;
import no.beint.vev.pg.spi.PgEntityPlan;
import org.junit.jupiter.api.Test;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.List;

import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.INIT_NAME;
import static java.lang.constant.ConstantDescs.MTD_void;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PgPlanAbiTest {
    @Test
    void staleBinaryWithoutTheCurrentReaderFailsAtModelConstruction() throws ReflectiveOperationException {
        // Model the old binary shape directly: loading a concrete class remains legal even though a newer
        // interface added an abstract method. No ABI, reader, or metadata methods exist in this artifact.
        byte[] bytes = ClassFile.of().build(ClassDesc.of("fixture.LegacyPlan"), builder -> builder
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL)
                .withInterfaceSymbols(ClassDesc.of("no.beint.vev.pg.spi.PgEntityPlan"))
                .withMethodBody(INIT_NAME, MTD_void, ClassFile.ACC_PUBLIC, code -> code
                        .aload(0).invokespecial(CD_Object, INIT_NAME, MTD_void).return_())
                .withMethodBody("instantiate", MethodTypeDesc.of(CD_Object, CD_Object.arrayType()),
                        ClassFile.ACC_PUBLIC, code -> code.aconst_null().areturn()));
        Class<?> staleClass = new FixtureLoader().define(bytes);
        var plan = (PgEntityPlan<?, ?, ?, ?>) staleClass.getConstructor().newInstance();
        assertEquals(0, plan.generatedPlanAbi());
        assertThrows(AbstractMethodError.class, () -> plan.readRow(null, 1));
        var failure = assertThrows(IllegalArgumentException.class, () -> capture(plan));
        assertTrue(failure.getMessage().contains("plan declares 0; recompile mappings"));
    }

    private static <M, E, K, T> void capture(PgEntityPlan<M, E, K, T> plan) {
        var identity = new ModelIdentity("legacy-model",
                "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        new PgModel<>(identity, List.of(plan));
    }

    private static final class FixtureLoader extends ClassLoader {
        FixtureLoader() {
            super(PgPlanAbiTest.class.getClassLoader());
        }

        Class<?> define(byte[] bytes) {
            return defineClass("fixture.LegacyPlan", bytes, 0, bytes.length);
        }
    }
}
