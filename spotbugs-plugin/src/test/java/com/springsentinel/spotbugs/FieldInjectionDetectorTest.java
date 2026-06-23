package com.springsentinel.spotbugs;

import org.apache.bcel.classfile.ClassParser;
import org.apache.bcel.classfile.Field;
import org.apache.bcel.classfile.JavaClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FieldInjectionDetectorTest {

    @TempDir
    Path tempDir;

    private final FieldInjectionDetector detector = new FieldInjectionDetector(null);

    @Test
    void detectsSpringAutowiredFieldInjectionFromBytecode() throws Exception {
        JavaClass javaClass = compileAndParse(
                "org/springframework/beans/factory/annotation/Autowired.java",
                "package org.springframework.beans.factory.annotation; public @interface Autowired {}",
                "example/AutowiredService.java",
                """
                package example;
                import org.springframework.beans.factory.annotation.Autowired;
                class AutowiredService {
                    @Autowired private Object dependency;
                }
                """);

        assertTrue(detector.hasFieldInjectionAnnotation(field(javaClass, "dependency")));
    }

    @Test
    void detectsJakartaInjectFieldInjectionFromBytecode() throws Exception {
        JavaClass javaClass = compileAndParse(
                "jakarta/inject/Inject.java",
                "package jakarta.inject; public @interface Inject {}",
                "example/InjectService.java",
                """
                package example;
                import jakarta.inject.Inject;
                class InjectService {
                    @Inject private Object dependency;
                }
                """);

        assertTrue(detector.hasFieldInjectionAnnotation(field(javaClass, "dependency")));
    }

    @Test
    void ignoresPlainFieldsFromBytecode() throws Exception {
        JavaClass javaClass = compileAndParse(
                "example/PlainService.java",
                """
                package example;
                class PlainService {
                    private Object dependency;
                }
                """);

        assertFalse(detector.hasFieldInjectionAnnotation(field(javaClass, "dependency")));
    }

    private JavaClass compileAndParse(String classFileSourcePath, String source) throws Exception {
        Path sourceFile = writeSource(classFileSourcePath, source);
        compile(List.of(sourceFile));
        return parseClass(classFileSourcePath);
    }

    private JavaClass compileAndParse(String annotationPath,
                                      String annotationSource,
                                      String classFileSourcePath,
                                      String source) throws Exception {
        Path annotationFile = writeSource(annotationPath, annotationSource);
        Path sourceFile = writeSource(classFileSourcePath, source);
        compile(List.of(annotationFile, sourceFile));
        return parseClass(classFileSourcePath);
    }

    private Path writeSource(String relativePath, String source) throws Exception {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        return file;
    }

    private void compile(List<Path> sources) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(null, null, null)) {
            Iterable<? extends JavaFileObject> compilationUnits =
                    fileManager.getJavaFileObjectsFromPaths(sources);
            Boolean success = compiler.getTask(
                    null,
                    fileManager,
                    null,
                    List.of("-d", tempDir.toString()),
                    null,
                    compilationUnits).call();
            assertTrue(success, "Fixture source should compile");
        }
    }

    private JavaClass parseClass(String sourcePath) throws Exception {
        String classPath = sourcePath.replace(".java", ".class");
        return new ClassParser(tempDir.resolve(classPath).toString()).parse();
    }

    private Field field(JavaClass javaClass, String name) {
        for (Field field : javaClass.getFields()) {
            if (field.getName().equals(name)) {
                return field;
            }
        }
        throw new AssertionError("Field not found: " + name);
    }
}
