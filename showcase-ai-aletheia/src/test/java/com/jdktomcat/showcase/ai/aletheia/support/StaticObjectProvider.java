package com.jdktomcat.showcase.ai.aletheia.support;

import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 测试用 {@link ObjectProvider} 简易实现，用 0~N 个静态实例模拟容器内的 Bean 提供能力。
 */
public final class StaticObjectProvider<T> implements ObjectProvider<T> {

    private final List<T> instances;

    public StaticObjectProvider(List<T> instances) {
        this.instances = List.copyOf(instances);
    }

    public static <T> StaticObjectProvider<T> empty() {
        return new StaticObjectProvider<>(List.of());
    }

    public static <T> StaticObjectProvider<T> of(T instance) {
        return new StaticObjectProvider<>(List.of(instance));
    }

    public static <T> StaticObjectProvider<T> of(List<T> instances) {
        return new StaticObjectProvider<>(instances);
    }

    @Override
    public T getObject(Object... args) {
        return getOrThrow();
    }

    @Override
    public T getIfAvailable() {
        return instances.isEmpty() ? null : instances.get(0);
    }

    @Override
    public T getIfUnique() {
        return instances.size() == 1 ? instances.get(0) : null;
    }

    @Override
    public T getObject() {
        return getOrThrow();
    }

    @Override
    public Iterator<T> iterator() {
        return instances.iterator();
    }

    @Override
    public Stream<T> stream() {
        return instances.stream();
    }

    @Override
    public Stream<T> orderedStream() {
        return instances.stream();
    }

    private T getOrThrow() {
        if (instances.isEmpty()) {
            throw new NoSuchBeanDefinitionException(Object.class);
        }
        return instances.get(0);
    }
}
