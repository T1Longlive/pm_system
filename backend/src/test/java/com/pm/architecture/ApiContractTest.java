package com.pm.architecture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 前后端接口契约的后端侧校验：
 * 扫描所有 {@link RestController} 的 {@link RequestMapping}，与
 * {@code contracts/openapi.json} 中的路径/方法做双向比对。
 *
 * <p>不启动 Spring 上下文、不连数据库，属于架构/契约层测试；
 * 任何一侧新增、删除或改路径而忘记同步契约，都会在这里失败。
 */
class ApiContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> HTTP_METHODS =
            Set.of("get", "post", "put", "delete", "patch");

    @Test
    @DisplayName("Controller 路由与 contracts/openapi.json 双向一致")
    void controllerRoutesMatchContract() throws Exception {
        Path contractFile = resolveContractFile();

        Set<String> documented = documentedEndpoints(MAPPER.readTree(contractFile.toFile()));
        Set<String> actual = controllerEndpoints();

        assertThat(actual).as("未扫描到任何 Controller 路由").isNotEmpty();
        assertThat(documented).as("契约文件未声明任何接口").isNotEmpty();
        assertThat(actual)
                .as("Controller 与 contracts/openapi.json 不一致（前者为代码实际路由，后者为契约）")
                .containsExactlyInAnyOrderElementsOf(documented);

        assertThat(actual).allMatch(endpoint -> endpoint.split(" ", 2)[1].startsWith("/api/"),
                "所有接口必须使用 /api 前缀（前端代理与网关都依赖该约定）");
    }

    private static Path resolveContractFile() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("contracts").resolve("openapi.json");
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("未找到 contracts/openapi.json（请从仓库内运行测试）");
    }

    private static Set<String> documentedEndpoints(JsonNode root) {
        Set<String> endpoints = new TreeSet<>();
        JsonNode paths = root.path("paths");
        Iterator<Map.Entry<String, JsonNode>> pathIterator = paths.fields();
        while (pathIterator.hasNext()) {
            Map.Entry<String, JsonNode> pathEntry = pathIterator.next();
            String path = normalizePath(pathEntry.getKey());
            Iterator<String> fieldNames = pathEntry.getValue().fieldNames();
            while (fieldNames.hasNext()) {
                String method = fieldNames.next().toLowerCase(Locale.ROOT);
                if (HTTP_METHODS.contains(method)) {
                    endpoints.add(method.toUpperCase(Locale.ROOT) + " " + path);
                }
            }
        }
        return endpoints;
    }

    private static Set<String> controllerEndpoints() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        Set<String> endpoints = new TreeSet<>();
        for (var definition : scanner.findCandidateComponents("com.pm.controller")) {
            Class<?> controller = Class.forName(definition.getBeanClassName());
            String prefix = classPrefix(controller);
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null || mapping.method().length == 0) {
                    continue;
                }
                List<String> paths = mappingPaths(mapping);
                for (RequestMethod requestMethod : mapping.method()) {
                    for (String path : paths) {
                        endpoints.add(requestMethod.name() + " " + joinPath(prefix, path));
                    }
                }
            }
        }
        return endpoints;
    }

    private static String classPrefix(Class<?> controller) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
        if (mapping == null) {
            return "";
        }
        return mappingPaths(mapping).get(0);
    }

    private static List<String> mappingPaths(RequestMapping mapping) {
        List<String> paths = new ArrayList<>();
        paths.addAll(Arrays.asList(mapping.value()));
        paths.addAll(Arrays.asList(mapping.path()));
        if (paths.isEmpty()) {
            paths.add("");
        }
        return new LinkedHashSet<>(paths).stream().toList();
    }

    private static String joinPath(String prefix, String path) {
        return normalizePath("/" + prefix + "/" + path);
    }

    private static String normalizePath(String path) {
        String normalized = path.replaceAll("/{2,}", "/");
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
