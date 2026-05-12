package com.beanspringboot.gradle;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;

public abstract class SpringSentinelExtension {

    public abstract Property<String> getProfile();

    public abstract RegularFileProperty getCustomRules();

    public abstract Property<Integer> getMaxDependencies();

    public abstract Property<String> getSecretPattern();

    public abstract Property<Boolean> getFailOnError();
}
