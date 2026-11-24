package io.github.izakyl.jpi.ai;

@FunctionalInterface
public interface ApiKeyResolver {

    String resolve(Model model) throws Exception;
}
