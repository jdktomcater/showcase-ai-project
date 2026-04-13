package com.jdktomcat.showcase.ai.code.chunk.service;

import com.jdktomcat.showcase.ai.code.chunk.domain.CodeChunk;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtTypeParameter;
import spoon.reflect.declaration.ModifierKind;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class JavaCodeChunker {

    @Value("${app.code-rag.repo-name}")
    private String repoName;

    @Value("${app.code-rag.repo-root}")
    private String repoRoot;

    public List<CodeChunk> chunk(Path file) throws IOException {
        String source = Files.readString(file);
        CtModel model = SpoonAstSupport.parse(source);

        List<CtType<?>> types = model.getElements(new TypeFilter<>(CtType.class)).stream()
                .map(t -> (CtType<?>) t)
                .filter(t -> !t.isImplicit())
                .sorted(Comparator.comparing(t -> t.getPosition().getSourceStart()))
                .collect(Collectors.toCollection(ArrayList::new));

        String packageName = types.stream()
                .findFirst()
                .map(t -> t.getPackage() == null ? "" : t.getPackage().getQualifiedName())
                .orElse("");

        String relativePath = Path.of(repoRoot).relativize(file).toString().replace('\\', '/');
        List<CodeChunk> chunks = new ArrayList<>();

        for (CtType<?> type : types) {
            if (type.getQualifiedName() == null) {
                continue;
            }
            String className = type.getSimpleName();

            chunks.add(buildChunk(
                    relativePath,
                    packageName,
                    className,
                    null,
                    "class",
                    SpoonAstSupport.startLine(type),
                    SpoonAstSupport.endLine(type),
                    SpoonAstSupport.extractJavadocText(type),
                    buildClassSignature(type)
            ));

            List<CtMethod<?>> methods = new ArrayList<>(type.getMethods());
            methods.sort(Comparator.comparing(m -> m.getPosition().getSourceStart()));
            for (CtMethod<?> method : methods) {
                chunks.add(buildChunk(
                        relativePath,
                        packageName,
                        className,
                        method.getSimpleName(),
                        "method",
                        SpoonAstSupport.startLine(method),
                        SpoonAstSupport.endLine(method),
                        SpoonAstSupport.extractJavadocText(method),
                        SpoonAstSupport.extractSource(source, method)
                ));
            }
        }

        return chunks;
    }

    private CodeChunk buildChunk(
            String relativePath,
            String packageName,
            String className,
            String methodName,
            String symbolType,
            int startLine,
            int endLine,
            String summary,
            String content
    ) {
        String id = String.join(":",
                repoName,
                relativePath,
                className == null ? "" : className,
                methodName == null ? "" : methodName,
                String.valueOf(startLine),
                String.valueOf(endLine)
        );

        return new CodeChunk(
                id,
                repoName,
                relativePath,
                "java",
                packageName,
                className,
                methodName,
                symbolType,
                startLine,
                endLine,
                summary,
                content,
                DigestUtils.sha256Hex(content)
        );
    }

    private String buildClassSignature(CtType<?> type) {
        String modifiers = type.getModifiers().stream()
                .map(ModifierKind::toString)
                .collect(Collectors.joining(" "));

        String typeParameters = type.getFormalCtTypeParameters().isEmpty()
                ? ""
                : type.getFormalCtTypeParameters().stream()
                .map(CtTypeParameter::toString)
                .collect(Collectors.joining(", ", "<", ">"));

        String keyword = type.isInterface() ? "interface " : "class ";
        String extendsClause = "";
        if (!type.isInterface()) {
            CtTypeReference<?> superClass = type.getSuperclass();
            if (superClass != null) {
                extendsClause = " extends " + superClass;
            }
        }

        String superTypesKeyword = type.isInterface() ? " extends " : " implements ";
        List<CtTypeReference<?>> interfaces = new ArrayList<>(type.getSuperInterfaces());
        String implementsClause = interfaces.isEmpty()
                ? ""
                : interfaces.stream()
                .map(CtTypeReference::toString)
                .collect(Collectors.joining(", ", superTypesKeyword, ""));

        return (modifiers.isBlank() ? "" : modifiers + " ")
                + keyword
                + type.getSimpleName()
                + typeParameters
                + extendsClause
                + implementsClause;
    }
}
