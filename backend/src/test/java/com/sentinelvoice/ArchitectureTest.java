package com.sentinelvoice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Service;
import org.springframework.util.ClassUtils;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards defect #10 (orphaned @Service beans) and hardcoded controller risk scores.
 * ArchUnit is not added as a dependency; this is the reflection + source equivalent.
 */
class ArchitectureTest {

    private static final Pattern CONTROLLER_RISK_LITERAL = Pattern.compile("0\\.[0-9]");

    @Test
    void controllersContainNoRiskScoreLiterals() throws IOException {
        Path dir = controllerSourceDir();
        assertTrue(Files.isDirectory(dir), "controller source dir missing: " + dir.toAbsolutePath());
        // Allow VoicePassport cosine placeholder 0.0 in identity stubs — not a fusion threshold.
        Pattern banned = Pattern.compile(
                "(?i)(smoothedRisk|instantaneous|upThreshold|downThreshold|familyThreshold)\\s*[=:(].*0\\.[0-9]"
                        + "|\\b0\\.(3[5-9]|[4-9][0-9]|[1-9][0-9]{2,})\\b"
        );
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (banned.matcher(source).find()) {
                    hits.add(file.getFileName().toString());
                }
            }
        }
        if (!hits.isEmpty()) {
            fail("controller risk-score literals (0.x) found in: " + hits);
        }
    }

    @Test
    void everyServiceIsReferencedByAnotherMainClass() throws Exception {
        Set<Class<?>> services = loadAnnotatedServices();
        Set<Class<?>> mainTypes = loadMainTypes();
        List<String> orphans = new ArrayList<>();
        for (Class<?> service : services) {
            boolean referenced = mainTypes.stream()
                    .filter(type -> !type.equals(service))
                    .anyMatch(type -> references(type, service));
            if (!referenced) {
                orphans.add(service.getName());
            }
        }
        if (!orphans.isEmpty()) {
            fail("orphaned @Service beans (not referenced by another main class): " + orphans);
        }
    }

    @Test
    void javaMainSourcesNeverTouchPcmOrRawAudioApis() throws IOException {
        Path root = Path.of("src/main/java/com/sentinelvoice");
        if (!Files.isDirectory(root)) {
            root = Path.of("backend/src/main/java/com/sentinelvoice");
        }
        assertTrue(Files.isDirectory(root), "main source tree missing: " + root.toAbsolutePath());
        Pattern banned = Pattern.compile(
                "(?i)\\b(AudioFormat|AudioInputStream|TargetDataLine|SourceDataLine|javax\\.sound\\.sampled|"
                        + "short\\[\\]\\s+pcm|byte\\[\\]\\s+pcm|rawPcm|writeWav|AudioSystem)\\b"
        );
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (banned.matcher(source).find()) {
                    hits.add(root.relativize(file).toString().replace('\\', '/'));
                }
            }
        }
        if (!hits.isEmpty()) {
            fail("Decision Plane must not touch PCM / javax.sound — found in: " + hits);
        }
    }

    private static Path controllerSourceDir() {
        Path module = Path.of("src/main/java/com/sentinelvoice/controller");
        if (Files.isDirectory(module)) {
            return module;
        }
        return Path.of("backend/src/main/java/com/sentinelvoice/controller");
    }

    private static Set<Class<?>> loadAnnotatedServices() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Service.class));
        Set<Class<?>> services = new HashSet<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.sentinelvoice")) {
            String className = definition.getBeanClassName();
            if (className == null) {
                continue;
            }
            Class<?> type = ClassUtils.resolveClassName(className, ArchitectureTest.class.getClassLoader());
            if (isMainCode(type)) {
                services.add(type);
            }
        }
        return services;
    }

    private static Set<Class<?>> loadMainTypes() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath*:com/sentinelvoice/**/*.class");
        Set<Class<?>> types = new HashSet<>();
        for (Resource resource : resources) {
            if (!resource.getURL().getPath().replace('\\', '/').contains("/test-classes/")) {
                String url = resource.getURL().toString().replace('\\', '/');
                int marker = url.lastIndexOf("com/sentinelvoice/");
                if (marker < 0) {
                    continue;
                }
                String className = url.substring(marker)
                        .replace(".class", "")
                        .replace('/', '.');
                if (className.contains("$")) {
                    continue;
                }
                types.add(ClassUtils.resolveClassName(className, ArchitectureTest.class.getClassLoader()));
            }
        }
        return types;
    }

    private static boolean references(Class<?> host, Class<?> service) {
        for (Constructor<?> constructor : host.getDeclaredConstructors()) {
            for (Class<?> parameter : constructor.getParameterTypes()) {
                if (parameter.equals(service)) {
                    return true;
                }
            }
        }
        for (Field field : host.getDeclaredFields()) {
            if (field.getType().equals(service)) {
                return true;
            }
        }
        for (Method method : host.getDeclaredMethods()) {
            for (Class<?> parameter : method.getParameterTypes()) {
                if (parameter.equals(service)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isMainCode(Class<?> type) {
        if (type.getProtectionDomain().getCodeSource() == null) {
            return false;
        }
        String path = type.getProtectionDomain().getCodeSource().getLocation().getPath().replace('\\', '/');
        return path.contains("/classes") && !path.contains("/test-classes");
    }
}
