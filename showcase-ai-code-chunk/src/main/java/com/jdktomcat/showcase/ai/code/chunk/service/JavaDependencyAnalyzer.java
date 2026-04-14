package com.jdktomcat.showcase.ai.code.chunk.service;

import com.jdktomcat.showcase.ai.code.chunk.domain.CodeGraphNode;
import com.jdktomcat.showcase.ai.code.chunk.domain.CodeGraphRelation;
import com.jdktomcat.showcase.ai.code.chunk.domain.NodeType;
import com.jdktomcat.showcase.ai.code.chunk.domain.RelationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtImport;
import spoon.reflect.declaration.CtImportKind;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtArrayTypeReference;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class JavaDependencyAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(JavaDependencyAnalyzer.class);

    private final Path repoRoot;

    public JavaDependencyAnalyzer(@Value("${app.code-rag.repo-root}") String repoRoot) {
        this.repoRoot = Path.of(repoRoot);
    }

    public AnalysisResult analyze(Path file) throws IOException {
        CtModel model = SpoonAstSupport.parseProject(file, repoRoot);
        String relativePath = SpoonAstSupport.normalize(repoRoot.relativize(file));
        String module = extractModule(relativePath);

        List<CtType<?>> typesInFile = model.getElements(new TypeFilter<>(CtType.class)).stream()
                .map(t -> (CtType<?>) t)
                .filter(t -> SpoonAstSupport.isSameFile(t, file))
                .filter(t -> !t.isImplicit())
                .sorted(Comparator.comparingInt(t -> t.getPosition().getSourceStart()))
                .collect(Collectors.toCollection(ArrayList::new));

        if (typesInFile.isEmpty()) {
            throw new IOException("No types found in model for file: " + file);
        }

        CtType<?> primaryType = typesInFile.get(0);
        String packageName = primaryType.getPackage() == null
                ? ""
                : primaryType.getPackage().getQualifiedName();

        Set<CodeGraphNode> nodes = new LinkedHashSet<>();
        Set<CodeGraphRelation> relations = new LinkedHashSet<>();

        for (CtType<?> type : typesInFile) {
            String typeFqn = buildTypeFqn(packageName, type);
            String typeId = nodeId(NodeType.TYPE, typeFqn);
            nodes.add(new CodeGraphNode(
                    typeId,
                    NodeType.TYPE,
                    type.getSimpleName(),
                    typeFqn,
                    typeKind(type),
                    relativePath,
                    module,
                    SpoonAstSupport.startLine(type),
                    SpoonAstSupport.endLine(type)
            ));

            collectTypeAnnotations(type, typeId, nodes, relations);
            collectImports(model, file, typeId, nodes, relations);
            collectInheritance(type, typeId, nodes, relations);
            collectFieldDependencies(type, typeId, nodes, relations);
            collectMethods(type, typeId, typeFqn, relativePath, module, nodes, relations);
        }

        return new AnalysisResult(new ArrayList<>(nodes), new ArrayList<>(relations));
    }

    private void collectImports(
            CtModel model,
            Path file,
            String typeId,
            Set<CodeGraphNode> nodes,
            Set<CodeGraphRelation> relations
    ) {
        for (CtImport imp : model.getElements(new TypeFilter<>(CtImport.class))) {
            if (!SpoonAstSupport.isSameFile(imp, file)) {
                continue;
            }
            if (imp.getImportKind() == CtImportKind.ALL_TYPES || imp.getImportKind() == CtImportKind.ALL_STATIC_MEMBERS) {
                continue;
            }
            if (!(imp.getReference() instanceof CtTypeReference<?> typeRef)) {
                continue;
            }
            String importedType = qualifiedTypeName(typeRef);
            String importedTypeId = nodeId(NodeType.TYPE, importedType);
            nodes.add(externalNode(NodeType.TYPE, importedType));
            relations.add(new CodeGraphRelation(typeId, importedTypeId, RelationType.DEPENDS_ON));
        }
    }

    private void collectInheritance(
            CtType<?> type,
            String typeId,
            Set<CodeGraphNode> nodes,
            Set<CodeGraphRelation> relations
    ) {
        if (!type.isInterface()) {
            CtTypeReference<?> superClass = type.getSuperclass();
            if (superClass != null) {
                String target = qualifiedTypeName(superClass);
                String targetId = nodeId(NodeType.TYPE, target);
                nodes.add(externalNode(NodeType.TYPE, target));
                relations.add(new CodeGraphRelation(typeId, targetId, RelationType.EXTENDS));
            }
        }

        for (CtTypeReference<?> superInterface : type.getSuperInterfaces()) {
            String target = qualifiedTypeName(superInterface);
            String targetId = nodeId(NodeType.TYPE, target);
            nodes.add(externalNode(NodeType.TYPE, target));
            relations.add(new CodeGraphRelation(typeId, targetId, RelationType.IMPLEMENTS));
        }
    }

    private void collectTypeAnnotations(
            CtType<?> type,
            String typeId,
            Set<CodeGraphNode> nodes,
            Set<CodeGraphRelation> relations
    ) {
        for (CtAnnotation<?> annotation : SpoonAstSupport.annotations(type)) {
            String annotationName = annotationDisplayName(annotation);
            String annotationId = nodeId(NodeType.ANNOTATION, annotationName);
            nodes.add(externalNode(NodeType.ANNOTATION, annotationName));
            relations.add(new CodeGraphRelation(typeId, annotationId, RelationType.ANNOTATED_BY));
        }
    }

    private void collectFieldDependencies(
            CtType<?> type,
            String typeId,
            Set<CodeGraphNode> nodes,
            Set<CodeGraphRelation> relations
    ) {
        for (CtField<?> field : type.getFields()) {
            String targetType = qualifiedTypeName(field.getType());
            String targetTypeId = nodeId(NodeType.TYPE, targetType);
            nodes.add(externalNode(NodeType.TYPE, targetType));

            RelationType relationType = hasInjectionAnnotation(field)
                    ? RelationType.INJECTS
                    : RelationType.DEPENDS_ON;
            relations.add(new CodeGraphRelation(typeId, targetTypeId, relationType));
        }
    }

    private void collectMethods(
            CtType<?> type,
            String typeId,
            String typeFqn,
            String relativePath,
            String module,
            Set<CodeGraphNode> nodes,
            Set<CodeGraphRelation> relations
    ) {
        List<CtMethod<?>> methods = new ArrayList<>(type.getMethods());
        methods.sort(Comparator.comparingInt(m -> m.getPosition().getSourceStart()));
        for (CtMethod<?> method : methods) {
            String methodFqn = buildMethodFqn(typeFqn, method);
            String methodId = nodeId(NodeType.METHOD, methodFqn);

            nodes.add(new CodeGraphNode(
                    methodId,
                    NodeType.METHOD,
                    method.getSimpleName(),
                    methodFqn,
                    "METHOD",
                    relativePath,
                    module,
                    SpoonAstSupport.startLine(method),
                    SpoonAstSupport.endLine(method)
            ));
            relations.add(new CodeGraphRelation(typeId, methodId, RelationType.DECLARES));

            collectMethodAnnotations(method, methodId, nodes, relations);
            collectMethodSignatureDependencies(method, methodId, nodes, relations);
            collectMethodCalls(method, methodId, nodes, relations);
        }
    }

    private void collectMethodAnnotations(
            CtMethod<?> method,
            String methodId,
            Set<CodeGraphNode> nodes,
            Set<CodeGraphRelation> relations
    ) {
        for (CtAnnotation<?> annotation : SpoonAstSupport.annotations(method)) {
            String annotationName = annotationDisplayName(annotation);
            String annotationId = nodeId(NodeType.ANNOTATION, annotationName);
            nodes.add(externalNode(NodeType.ANNOTATION, annotationName));
            relations.add(new CodeGraphRelation(methodId, annotationId, RelationType.ANNOTATED_BY));
        }
    }

    private void collectMethodSignatureDependencies(
            CtMethod<?> method,
            String methodId,
            Set<CodeGraphNode> nodes,
            Set<CodeGraphRelation> relations
    ) {
        CtTypeReference<?> returnType = method.getType();
        String returnTypeName = returnType == null ? "void" : qualifiedTypeName(returnType);
        String returnTypeId = nodeId(NodeType.TYPE, returnTypeName);
        nodes.add(externalNode(NodeType.TYPE, returnTypeName));
        relations.add(new CodeGraphRelation(methodId, returnTypeId, RelationType.RETURNS));

        for (CtParameter<?> parameter : method.getParameters()) {
            String parameterType = qualifiedTypeName(parameter.getType()) + (parameter.isVarArgs() ? "..." : "");
            String parameterTypeId = nodeId(NodeType.TYPE, parameterType);
            nodes.add(externalNode(NodeType.TYPE, parameterType));
            relations.add(new CodeGraphRelation(methodId, parameterTypeId, RelationType.HAS_PARAM_TYPE));
        }
    }

    private void collectMethodCalls(
            CtMethod<?> method,
            String callerMethodId,
            Set<CodeGraphNode> nodes,
            Set<CodeGraphRelation> relations
    ) {
        for (CtInvocation<?> invocation : method.getElements(new TypeFilter<>(CtInvocation.class))) {
            Optional<String> resolvedTarget = resolveMethodCall(invocation);
            if (resolvedTarget.isEmpty()) {
                continue;
            }
            String calleeFqn = resolvedTarget.get();
            String calleeId = nodeId(NodeType.METHOD, calleeFqn);
            nodes.add(externalNode(NodeType.METHOD, calleeFqn));
            relations.add(new CodeGraphRelation(callerMethodId, calleeId, RelationType.CALLS));
        }
    }

    private Optional<String> resolveMethodCall(CtInvocation<?> call) {
        try {
            CtExecutableReference<?> exec = call.getExecutable();
            if (exec == null) {
                return Optional.empty();
            }
            CtTypeReference<?> owner = exec.getDeclaringType();
            if (owner == null) {
                return Optional.empty();
            }
            String ownerName = qualifiedTypeName(owner);
            String name = exec.getSimpleName();
            String parameters = exec.getParameters().stream()
                    .map(this::qualifiedTypeName)
                    .collect(Collectors.joining(", "));
            return Optional.of(ownerName + "#" + name + "(" + parameters + ")");
        } catch (RuntimeException ex) {
            log.debug("Failed to resolve method call: {}", call, ex);
            return Optional.empty();
        }
    }

    private boolean hasInjectionAnnotation(CtElement declaration) {
        return SpoonAstSupport.annotations(declaration).stream()
                .map(this::annotationDisplayName)
                .anyMatch(name -> name.equals("Autowired") || name.equals("Inject") || name.equals("Resource"));
    }

    private String annotationDisplayName(CtAnnotation<?> ann) {
        CtTypeReference<?> t = ann.getAnnotationType();
        if (t == null) {
            return ann.toString();
        }
        String simple = t.getSimpleName();
        if (simple != null && !simple.isBlank()) {
            return simple;
        }
        String qualified = t.getQualifiedName();
        if (qualified != null && !qualified.isBlank()) {
            int dot = qualified.lastIndexOf('.');
            return dot >= 0 ? qualified.substring(dot + 1) : qualified;
        }
        return t.toString();
    }

    private String buildTypeFqn(String packageName, CtType<?> type) {
        String qn = type.getQualifiedName();
        if (qn != null && !qn.isBlank()) {
            return qn;
        }
        return packageName.isBlank() ? type.getSimpleName() : packageName + "." + type.getSimpleName();
    }

    private String buildMethodFqn(String typeFqn, CtMethod<?> method) {
        String parameters = method.getParameters().stream()
                .map(p -> qualifiedTypeName(p.getType()) + (p.isVarArgs() ? "..." : ""))
                .collect(Collectors.joining(", "));
        return typeFqn + "#" + method.getSimpleName() + "(" + parameters + ")";
    }

    private String qualifiedTypeName(CtTypeReference<?> ref) {
        if (ref == null) {
            return "unknown";
        }
        if (ref instanceof CtArrayTypeReference<?> arr) {
            return qualifiedTypeName(arr.getComponentType()) + "[]";
        }
        String qualifiedName = ref.getQualifiedName();
        if (qualifiedName != null && !qualifiedName.isBlank()) {
            return qualifiedName;
        }
        return ref.toString();
    }

    private String typeKind(CtType<?> type) {
        if (type.isInterface()) {
            return "INTERFACE";
        }
        return type.isAbstract() ? "ABSTRACT_CLASS" : "CLASS";
    }

    private String extractModule(String relativePath) {
        int index = relativePath.indexOf('/');
        return index < 0 ? relativePath : relativePath.substring(0, index);
    }

    private String nodeId(NodeType type, String fqn) {
        return type.name() + ":" + fqn;
    }

    private CodeGraphNode externalNode(NodeType type, String fqn) {
        String name = fqn;
        int index = Math.max(fqn.lastIndexOf('.'), fqn.lastIndexOf('#'));
        if (index >= 0 && index + 1 < fqn.length()) {
            name = fqn.substring(index + 1);
        }
        return new CodeGraphNode(nodeId(type, fqn), type, name, fqn, null, null, null, null, null);
    }

    public record AnalysisResult(
            List<CodeGraphNode> nodes,
            List<CodeGraphRelation> relations
    ) {
    }
}
