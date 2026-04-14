package com.jdktomcat.showcase.ai.code.chunk.service;

import com.jdktomcat.showcase.ai.code.chunk.domain.EntryPoint;
import com.jdktomcat.showcase.ai.code.chunk.domain.EntryPointType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;
import spoon.support.compiler.VirtualFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Uses Spoon to detect entry points in Java source files.
 */
@Service
public class SpoonEntryPointAnalyzer {

    private static final Pattern QUOTED_STRING_PATTERN = Pattern.compile("\"([^\"]*)\"");
    private static final Set<String> RPC_TYPE_ANNOTATIONS = Set.of(
            "DubboService",
            "GrpcService"
    );
    private static final Set<String> RPC_METHOD_ANNOTATIONS = Set.of(
            "RpcEntry"
    );

    private final Path repoRoot;

    public SpoonEntryPointAnalyzer(@Value("${app.impact-analysis.repo-root:#{null}}") String repoRoot) {
        this.repoRoot = repoRoot != null && !repoRoot.isBlank() ? Path.of(repoRoot) : Path.of(".").toAbsolutePath();
    }

    public List<EntryPoint> analyze(Path file) throws IOException {
        List<EntryPoint> entryPoints = new ArrayList<>();
        String source = Files.readString(file);
        CtModel model = parseSingleFile(file, source);
        String relativePath = repoRoot.relativize(file).toString();
        String module = extractModule(relativePath);

        for (CtType<?> ctType : model.getElements(new TypeFilter<>(CtType.class))) {
            if (!ctType.isTopLevel()) {
                continue;
            }
            String packageName = ctType.getPackage() == null ? "" : ctType.getPackage().getQualifiedName();
            String typeName = ctType.getSimpleName();
            String typeFqn = packageName.isBlank() ? typeName : packageName + "." + typeName;
            List<String> typeAnnotations = getTypeAnnotations(ctType);

            for (CtMethod<?> method : ctType.getMethods()) {
                EntryPoint entryPoint = analyzeMethod(method, ctType, typeFqn, typeAnnotations, relativePath, module);
                if (entryPoint != null) {
                    entryPoints.add(entryPoint);
                }
            }
        }

        return entryPoints;
    }

    private static CtModel parseSingleFile(Path file, String source) throws IOException {
        Launcher launcher = new Launcher();
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setComplianceLevel(17);
        launcher.getEnvironment().setIgnoreSyntaxErrors(true);
        launcher.addInputResource(new VirtualFile(source, file.getFileName().toString()));
        try {
            launcher.buildModel();
        } catch (Exception ex) {
            throw new IOException("Parse failed for " + file + ": " + ex.getMessage(), ex);
        }
        return launcher.getModel();
    }

    private EntryPoint analyzeMethod(
            CtMethod<?> method,
            CtType<?> ownerType,
            String typeFqn,
            List<String> typeAnnotations,
            String relativePath,
            String module
    ) {
        String methodName = method.getSimpleName();
        String methodSignature = buildMethodSignature(typeFqn, method);
        int lineNumber = SpoonAstSupport.startLine(method);

        List<String> methodAnnotations = getMethodAnnotations(method);
        EntryPointType entryPointType = determineEntryPointType(methodAnnotations, typeAnnotations);
        if (entryPointType == null) {
            return null;
        }

        String metadata = buildMetadata(ownerType, method, methodAnnotations, typeAnnotations, entryPointType);
        return new EntryPoint(
                entryPointType.name() + ":" + methodSignature,
                entryPointType,
                typeFqn,
                methodName,
                methodSignature,
                relativePath,
                module,
                lineNumber,
                metadata
        );
    }

    private EntryPointType determineEntryPointType(List<String> methodAnnotations, List<String> typeAnnotations) {
        if (methodAnnotations.contains("HttpEntry")) {
            return EntryPointType.HTTP;
        }
        if (methodAnnotations.contains("RpcEntry")) {
            return EntryPointType.RPC;
        }
        if (methodAnnotations.contains("MqEntry")) {
            return EntryPointType.MQ;
        }
        if (methodAnnotations.contains("ScheduledEntry")) {
            return EntryPointType.SCHEDULED;
        }
        if (methodAnnotations.contains("EventEntry")) {
            return EntryPointType.EVENT;
        }

        boolean isController = typeAnnotations.contains("RestController") || typeAnnotations.contains("Controller");
        if (isController && methodAnnotations.stream().anyMatch(a -> a.contains("Mapping"))) {
            return EntryPointType.HTTP;
        }
        if (methodAnnotations.stream().anyMatch(a -> a.contains("Mapping") && !a.contains("Exception"))) {
            return EntryPointType.HTTP;
        }

        if (typeAnnotations.stream().anyMatch(RPC_TYPE_ANNOTATIONS::contains)) {
            return EntryPointType.RPC;
        }
        if (methodAnnotations.stream().anyMatch(RPC_METHOD_ANNOTATIONS::contains)) {
            return EntryPointType.RPC;
        }

        if (methodAnnotations.contains("RabbitListener")) {
            return EntryPointType.MQ;
        }
        if (methodAnnotations.contains("KafkaListener")) {
            return EntryPointType.MQ;
        }
        if (methodAnnotations.contains("RocketMQMessageListener")) {
            return EntryPointType.MQ;
        }

        if (methodAnnotations.contains("Scheduled")) {
            return EntryPointType.SCHEDULED;
        }

        if (methodAnnotations.contains("EventListener")) {
            return EntryPointType.EVENT;
        }
        if (methodAnnotations.contains("TransactionalEventListener")) {
            return EntryPointType.EVENT;
        }

        return null;
    }

    private List<String> getTypeAnnotations(CtType<?> typeDecl) {
        List<String> annotations = new ArrayList<>();
        for (CtAnnotation<?> annotation : typeDecl.getAnnotations()) {
            annotations.add(annotationSimpleName(annotation));
        }
        return annotations;
    }

    private List<String> getMethodAnnotations(CtMethod<?> method) {
        List<String> annotations = new ArrayList<>();
        for (CtAnnotation<?> annotation : method.getAnnotations()) {
            annotations.add(annotationSimpleName(annotation));
        }
        return annotations;
    }

    private String buildMetadata(
            CtType<?> ownerType,
            CtMethod<?> method,
            List<String> methodAnnotations,
            List<String> typeAnnotations,
            EntryPointType type
    ) {
        StringBuilder metadata = new StringBuilder();

        switch (type) {
            case HTTP -> appendHttpMetadata(metadata, ownerType, method, typeAnnotations);
            case RPC -> {
                if (typeAnnotations.stream().anyMatch(RPC_TYPE_ANNOTATIONS::contains)
                        || methodAnnotations.stream().anyMatch(RPC_METHOD_ANNOTATIONS::contains)) {
                    metadata.append("protocol=DUBBO;");
                }
            }
            case MQ -> {
                for (String annotation : methodAnnotations) {
                    if (annotation.contains("Listener")) {
                        metadata.append(annotation).append(";");
                    }
                }
            }
            case SCHEDULED -> {
                if (methodAnnotations.contains("Scheduled")) {
                    metadata.append("annotation=@Scheduled;");
                }
            }
            case EVENT -> {
                for (String annotation : methodAnnotations) {
                    if (annotation.contains("EventListener")) {
                        metadata.append(annotation).append(";");
                    }
                }
            }
        }

        return metadata.toString();
    }

    private void appendHttpMetadata(
            StringBuilder metadata,
            CtType<?> ownerType,
            CtMethod<?> method,
            List<String> typeAnnotations
    ) {
        if (!(typeAnnotations.contains("RestController") || typeAnnotations.contains("Controller"))) {
            return;
        }

        List<String> classPaths = new ArrayList<>();
        for (CtAnnotation<?> annotation : ownerType.getAnnotations()) {
            if (isMappingAnnotation(annotationSimpleName(annotation))) {
                classPaths.addAll(extractPaths(annotation));
            }
        }

        List<String> methodPaths = new ArrayList<>();
        Set<String> httpMethods = new LinkedHashSet<>();
        Set<String> mappingAnnotations = new LinkedHashSet<>();
        for (CtAnnotation<?> annotation : method.getAnnotations()) {
            String annotationName = annotationSimpleName(annotation);
            if (!isMappingAnnotation(annotationName)) {
                continue;
            }
            methodPaths.addAll(extractPaths(annotation));
            httpMethods.addAll(extractHttpMethods(annotation, annotationName));
            mappingAnnotations.add(annotationName);
        }

        if (httpMethods.isEmpty()) {
            httpMethods.add("REQUEST");
        }

        List<String> fullPaths = combinePaths(classPaths, methodPaths);
        if (!fullPaths.isEmpty()) {
            metadata.append("path=").append(String.join(",", fullPaths)).append(";");
        }
        if (!httpMethods.isEmpty()) {
            metadata.append("httpMethod=").append(String.join(",", httpMethods)).append(";");
        }
        if (!mappingAnnotations.isEmpty()) {
            metadata.append("mapping=").append(String.join(",", mappingAnnotations)).append(";");
        }
    }

    private boolean isMappingAnnotation(String annotationName) {
        return annotationName != null && annotationName.contains("Mapping") && !annotationName.contains("Exception");
    }

    private List<String> combinePaths(List<String> classPaths, List<String> methodPaths) {
        List<String> bases = classPaths.isEmpty() ? List.of("") : classPaths;
        List<String> methods = methodPaths.isEmpty() ? List.of("") : methodPaths;
        Set<String> combinedPaths = new LinkedHashSet<>();
        for (String base : bases) {
            for (String method : methods) {
                combinedPaths.add(normalizeHttpPath(base, method));
            }
        }
        return new ArrayList<>(combinedPaths);
    }

    private String normalizeHttpPath(String base, String method) {
        String normalizedBase = normalizeSinglePath(base);
        String normalizedMethod = normalizeSinglePath(method);
        if ("/".equals(normalizedBase)) {
            normalizedBase = "";
        }
        if ("/".equals(normalizedMethod)) {
            normalizedMethod = "";
        }

        String combined = normalizedBase + normalizedMethod;
        if (combined.isBlank()) {
            return "/";
        }
        if (!combined.startsWith("/")) {
            combined = "/" + combined;
        }
        return combined.replaceAll("//+", "/");
    }

    private String normalizeSinglePath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String normalized = path.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private List<String> extractPaths(CtAnnotation<?> annotation) {
        String raw = annotation.toString();
        int openParen = raw.indexOf('(');
        int closeParen = raw.lastIndexOf(')');
        if (openParen < 0 || closeParen <= openParen) {
            return List.of();
        }

        String args = raw.substring(openParen + 1, closeParen);
        List<String> values = new ArrayList<>();
        if (!args.contains("=")) {
            values.addAll(extractQuotedStrings(args));
        } else {
            Matcher matcher = Pattern.compile("(?:value|path)\\s*=\\s*(\\{[^}]*}|\"[^\"]*\")").matcher(args);
            while (matcher.find()) {
                values.addAll(extractQuotedStrings(matcher.group(1)));
            }
        }
        return values.stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private List<String> extractHttpMethods(CtAnnotation<?> annotation, String annotationName) {
        return switch (annotationName) {
            case "GetMapping" -> List.of("GET");
            case "PostMapping" -> List.of("POST");
            case "PutMapping" -> List.of("PUT");
            case "DeleteMapping" -> List.of("DELETE");
            case "PatchMapping" -> List.of("PATCH");
            case "RequestMapping" -> extractRequestMethods(annotation);
            default -> List.of();
        };
    }

    private List<String> extractRequestMethods(CtAnnotation<?> annotation) {
        Matcher matcher = Pattern.compile("RequestMethod\\.([A-Z]+)").matcher(annotation.toString());
        Set<String> methods = new LinkedHashSet<>();
        while (matcher.find()) {
            methods.add(matcher.group(1).toUpperCase(Locale.ROOT));
        }
        return new ArrayList<>(methods);
    }

    private List<String> extractQuotedStrings(String raw) {
        List<String> values = new ArrayList<>();
        Matcher matcher = QUOTED_STRING_PATTERN.matcher(raw);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    private String annotationSimpleName(CtAnnotation<?> annotation) {
        CtTypeReference<?> ref = annotation.getAnnotationType();
        if (ref == null) {
            return annotation.toString();
        }
        String simple = ref.getSimpleName();
        if (simple != null && !simple.isBlank()) {
            return simple;
        }
        String qualified = ref.getQualifiedName();
        if (qualified != null && !qualified.isBlank()) {
            int lastDot = qualified.lastIndexOf('.');
            return lastDot >= 0 ? qualified.substring(lastDot + 1) : qualified;
        }
        return ref.toString();
    }

    private String buildMethodSignature(String typeFqn, CtMethod<?> method) {
        StringBuilder params = new StringBuilder();
        for (CtParameter<?> param : method.getParameters()) {
            if (!params.isEmpty()) {
                params.append(", ");
            }
            params.append(param.getType().toString());
            if (param.isVarArgs()) {
                params.append("...");
            }
        }
        return typeFqn + "#" + method.getSimpleName() + "(" + params + ")";
    }

    private String extractModule(String relativePath) {
        int index = relativePath.indexOf('/');
        return index < 0 ? relativePath : relativePath.substring(0, index);
    }
}
