package com.springsentinel.spotbugs;

import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.BugReporter;
import edu.umd.cs.findbugs.BytecodeScanningDetector;
import org.apache.bcel.classfile.AnnotationEntry;
import org.apache.bcel.classfile.Field;

import java.util.Set;

public class FieldInjectionDetector extends BytecodeScanningDetector {

    static final String BUG_TYPE = "SS_ARCH_002_FIELD_INJECTION";
    static final String SPRING_AUTOWIRED = "Lorg/springframework/beans/factory/annotation/Autowired;";
    static final String SPRING_VALUE = "Lorg/springframework/beans/factory/annotation/Value;";
    static final String JAKARTA_INJECT = "Ljakarta/inject/Inject;";
    static final String JAVAX_INJECT = "Ljavax/inject/Inject;";
    static final String JAKARTA_RESOURCE = "Ljakarta/annotation/Resource;";
    static final String JAVAX_RESOURCE = "Ljavax/annotation/Resource;";

    private static final Set<String> FIELD_INJECTION_ANNOTATIONS = Set.of(
            SPRING_AUTOWIRED,
            SPRING_VALUE,
            JAKARTA_INJECT,
            JAVAX_INJECT,
            JAKARTA_RESOURCE,
            JAVAX_RESOURCE
    );

    private final BugReporter bugReporter;

    public FieldInjectionDetector(BugReporter bugReporter) {
        this.bugReporter = bugReporter;
    }

    @Override
    public void visit(Field field) {
        if (!hasFieldInjectionAnnotation(field)) {
            return;
        }

        bugReporter.reportBug(new BugInstance(this, BUG_TYPE, NORMAL_PRIORITY)
                .addClass(this)
                .addField(this));
    }

    boolean hasFieldInjectionAnnotation(Field field) {
        for (AnnotationEntry annotation : field.getAnnotationEntries()) {
            if (FIELD_INJECTION_ANNOTATIONS.contains(annotation.getAnnotationType())) {
                return true;
            }
        }
        return false;
    }
}
