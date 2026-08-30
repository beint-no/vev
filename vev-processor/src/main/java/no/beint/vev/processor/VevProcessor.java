package no.beint.vev.processor;

import java.util.HashSet;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;

/**
 * JDK 26 annotation processor for closed Vev models.
 *
 * <p>For each source type annotated with {@code @VevModel}, the processor validates the complete immutable entity
 * mapping and generates strongly typed PostgreSQL plans plus a closed-model registry. Invalid or ambiguous mappings
 * fail compilation instead of deferring mapping discovery to application startup.</p>
 */
@SupportedAnnotationTypes(VevProcessor.VEV_MODEL)
@SupportedSourceVersion(SourceVersion.RELEASE_26)
public final class VevProcessor extends AbstractProcessor {
    static final String VEV_MODEL = "no.beint.vev.VevModel";

    private final Set<String> compiledModels = new HashSet<>();
    private final Set<String> claimedEntities = new HashSet<>();

    /** Creates a processor instance for compiler service loading. */
    public VevProcessor() {
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnvironment) {
        TypeElement modelAnnotation = processingEnv.getElementUtils().getTypeElement(VEV_MODEL);
        if (modelAnnotation == null || roundEnvironment.processingOver()) {
            return false;
        }
        Set<String> sourceTypes = new HashSet<>();
        for (Element root : roundEnvironment.getRootElements()) {
            if (root instanceof TypeElement sourceType) {
                sourceTypes.add(sourceType.getQualifiedName().toString());
            }
        }
        MappingCompiler compiler = new MappingCompiler(processingEnv, claimedEntities, Set.copyOf(sourceTypes));
        for (Element element : roundEnvironment.getElementsAnnotatedWith(modelAnnotation)) {
            if (!(element instanceof TypeElement modelType)) {
                processingEnv.getMessager().printError("@VevModel may only annotate a type", element);
                continue;
            }
            String qualifiedName = modelType.getQualifiedName().toString();
            if (compiledModels.add(qualifiedName)) {
                compiler.compile(modelType);
            }
        }
        return true;
    }
}
