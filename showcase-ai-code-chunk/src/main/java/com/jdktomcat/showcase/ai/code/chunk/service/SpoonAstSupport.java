package com.jdktomcat.showcase.ai.code.chunk.service;

import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtComment;
import spoon.reflect.cu.SourcePosition;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtElement;
import spoon.support.compiler.VirtualFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

final class SpoonAstSupport {

    private SpoonAstSupport() {
    }

    static CtModel parse(String source) throws IOException {
        Launcher launcher = newLauncherNoClasspath();
        launcher.addInputResource(new VirtualFile("ParsedUnit.java", source));
        try {
            launcher.buildModel();
        } catch (Exception ex) {
            throw new IOException("Parse failed for source: " + ex.getMessage(), ex);
        }
        return launcher.getModel();
    }

    static CtModel parseProject(Path file, Path repoRoot) throws IOException {
        Launcher launcher = new Launcher();
        launcher.getEnvironment().setComplianceLevel(17);
        launcher.getEnvironment().setAutoImports(true);
        launcher.getEnvironment().setIgnoreSyntaxErrors(true);
        String cp = System.getProperty("java.class.path", "");
        if (!cp.isBlank()) {
            launcher.getEnvironment().setSourceClasspath(cp.split(File.pathSeparator));
        }
        for (Path root : collectSourceRoots(repoRoot)) {
            launcher.addInputResource(root.toString());
        }
        try {
            launcher.buildModel();
        } catch (Exception ex) {
            throw new IOException("Failed to build model for " + file + ": " + ex.getMessage(), ex);
        }
        return launcher.getModel();
    }

    static List<Path> collectSourceRoots(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isDirectory).filter(SpoonAstSupport::isJavaSourceRoot).toList();
        } catch (IOException ex) {
            return List.of();
        }
    }

    static boolean isJavaSourceRoot(Path path) {
        String normalized = normalize(path);
        return normalized.endsWith("/src/main/java") || normalized.endsWith("/src/test/java");
    }

    static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    static boolean isSameFile(CtElement element, Path file) {
        SourcePosition pos = element.getPosition();
        if (!pos.isValidPosition() || pos.getFile() == null) {
            return false;
        }
        Path elementPath = pos.getFile().toPath().toAbsolutePath().normalize();
        return elementPath.equals(file.toAbsolutePath().normalize());
    }

    static int startLine(CtElement node) {
        SourcePosition pos = node.getPosition();
        if (!pos.isValidPosition()) {
            return -1;
        }
        int line = pos.getLine();
        return line <= 0 ? -1 : line;
    }

    static int endLine(CtElement node) {
        SourcePosition pos = node.getPosition();
        if (!pos.isValidPosition()) {
            return -1;
        }
        int line = pos.getEndLine();
        return line <= 0 ? -1 : line;
    }

    static String extractSource(String source, CtElement node) {
        SourcePosition pos = node.getPosition();
        if (!pos.isValidPosition()) {
            return node.toString();
        }
        int start = Math.max(0, pos.getSourceStart());
        int endExclusive = pos.getSourceEnd() + 1;
        endExclusive = Math.min(source.length(), endExclusive);
        if (start >= source.length() || start > endExclusive) {
            return node.toString();
        }
        return source.substring(start, endExclusive);
    }

    static String extractJavadocText(CtElement declaration) {
        return declaration.getComments().stream().filter(c -> c.getCommentType() == CtComment.CommentType.JAVADOC).map(CtComment::getContent).findFirst().map(String::trim).orElse("");
    }

    static List<CtAnnotation<?>> annotations(CtElement declaration) {
        return new ArrayList<>(declaration.getAnnotations());
    }

    private static Launcher newLauncherNoClasspath() {
        Launcher launcher = new Launcher();
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setComplianceLevel(17);
        launcher.getEnvironment().setIgnoreSyntaxErrors(false);
        return launcher;
    }
}
