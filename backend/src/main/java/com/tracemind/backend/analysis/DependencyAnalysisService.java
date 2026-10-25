package com.tracemind.backend.analysis;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DependencyAnalysisService {

    private static final int MAX_FILES = 100;
    private static final int MAX_FILE_BYTES = 200_000;

    private static final Set<String> SOURCE_EXTENSIONS = Set.of(
            ".java", ".js", ".jsx", ".ts", ".tsx"
    );

    private static final List<String> RESOLUTION_EXTENSIONS = List.of(
            ".js", ".jsx", ".ts", ".tsx", ".java",
            ".json", ".css", ".scss"
    );

    private static final Pattern JS_IMPORT_PATTERN = Pattern.compile(
            "(?m)^\\s*(?:import\\s+(?:[\\s\\S]*?\\s+from\\s+)?"
                    + "|export\\s+[\\s\\S]*?\\s+from\\s+)"
                    + "[\"']([^\"']+)[\"']"
    );

    private static final Pattern JS_REQUIRE_PATTERN = Pattern.compile(
            "\\brequire\\s*\\(\\s*[\"']([^\"']+)[\"']\\s*\\)"
    );

    private static final Pattern JAVA_IMPORT_PATTERN = Pattern.compile(
            "(?m)^\\s*import\\s+(static\\s+)?"
                    + "([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*\\.\\*|[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+)"
                    + "\\s*;"
    );

    private static final Pattern JAVA_PACKAGE_PATTERN = Pattern.compile(
            "(?m)^\\s*package\\s+"
                    + "([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;"
    );

    private static final Pattern JAVA_TYPE_PATTERN = Pattern.compile(
            "\\b(?:class|interface|enum|record)\\s+"
                    + "([A-Za-z_$][\\w$]*)\\b"
    );

    private final RestClient github;

    public DependencyAnalysisService() {
        this(RestClient.builder());
    }
    DependencyAnalysisService(RestClient.Builder builder) {
    RestClient.Builder configuredBuilder = builder
            .baseUrl("https://api.github.com")
            .defaultHeader("Accept", "application/vnd.github+json")
            .defaultHeader("X-GitHub-Api-Version", "2022-11-28");

    String githubToken = System.getenv("GITHUB_TOKEN");

    if (githubToken != null && !githubToken.isBlank()) {
        configuredBuilder.defaultHeader(
                "Authorization",
                "Bearer " + githubToken.trim()
        );
    }

    this.github = configuredBuilder.build();
    }


    public Map<String, Object> analyze(String owner, String repo) {
        validateRepositoryName(owner, "owner");
        validateRepositoryName(repo, "repository");

        try {
            Map<?, ?> metadata = github.get()
                    .uri("/repos/{owner}/{repo}", owner, repo)
                    .retrieve()
                    .body(Map.class);

            if (metadata == null
                    || !(metadata.get("default_branch") instanceof String branch)
                    || branch.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "GitHub returned invalid repository metadata."
                );
            }

            Map<?, ?> treeResponse = github.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/repos/{owner}/{repo}/git/trees/{branch}")
                            .queryParam("recursive", "1")
                            .build(owner, repo, branch))
                    .retrieve()
                    .body(Map.class);

            if (treeResponse == null
                    || !(treeResponse.get("tree") instanceof List<?> tree)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "GitHub returned an invalid repository tree."
                );
            }

            // Source files and resolvable resources are indexed separately.
            Set<String> repositoryFiles = new LinkedHashSet<>();
            Set<String> sourceFiles = new LinkedHashSet<>();
            List<Map<String, Object>> fileEntries = new ArrayList<>();

            for (Object item : tree) {
                if (!(item instanceof Map<?, ?> entry)
                        || !"blob".equals(entry.get("type"))
                        || !(entry.get("path") instanceof String path)
                        || isExcludedPath(path)) {
                    continue;
                }

                if (isResolvablePath(path)) {
                    repositoryFiles.add(path);
                }

                if (!isSupportedSource(path)) {
                    continue;
                }

                sourceFiles.add(path);
                fileEntries.add(toFileEntry(entry, path));
            }

            fileEntries.sort(Comparator.comparing(
                    entry -> String.valueOf(entry.get("path"))
            ));

            List<String> analyzedFiles = new ArrayList<>();
            Map<String, String> analyzedSources = new LinkedHashMap<>();

            // Qualified Java type name -> source file path.
            Map<String, String> javaTypes = new LinkedHashMap<>();

            // Source file path -> declared Java package.
            Map<String, String> javaPackages = new LinkedHashMap<>();

            int skippedFiles = 0;

            // Pass 1: fetch source files and build the Java index.
            for (Map<String, Object> entry : fileEntries) {
                if (analyzedSources.size() >= MAX_FILES) {
                    skippedFiles++;
                    continue;
                }

                Object sizeValue = entry.get("size");

                if (sizeValue instanceof Number size
                        && size.longValue() > MAX_FILE_BYTES) {
                    skippedFiles++;
                    continue;
                }

                String path = String.valueOf(entry.get("path"));
                String sha = String.valueOf(entry.get("sha"));

                Map<?, ?> blob = github.get()
                        .uri("/repos/{owner}/{repo}/git/blobs/{sha}",
                                owner, repo, sha)
                        .retrieve()
                        .body(Map.class);

                if (blob == null
                        || !(blob.get("content") instanceof String content)) {
                    skippedFiles++;
                    continue;
                }

                byte[] bytes = Base64.getMimeDecoder().decode(content);

                if (bytes.length > MAX_FILE_BYTES) {
                    skippedFiles++;
                    continue;
                }

                String source = new String(bytes, StandardCharsets.UTF_8);

                analyzedSources.put(path, source);
                analyzedFiles.add(path);

                if (".java".equals(extensionOf(path))) {
                    indexJavaTypes(path, source, javaPackages, javaTypes);
                }
            }

            List<Map<String, Object>> dependencies = new ArrayList<>();
            List<Map<String, Object>> unresolvedImports = new ArrayList<>();

            // Pass 2: resolve imports after the Java index is complete.
            for (Map.Entry<String, String> sourceEntry
                    : analyzedSources.entrySet()) {
                extractDependencies(
                        sourceEntry.getKey(),
                        sourceEntry.getValue(),
                        repositoryFiles,
                        javaTypes,
                        javaPackages,
                        dependencies,
                        unresolvedImports
                );
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("repository", metadata.get("full_name"));
            response.put("defaultBranch", branch);
            response.put("sourceFileCount", sourceFiles.size());
            response.put("analyzedFileCount", analyzedFiles.size());
            response.put("skippedFileCount", skippedFiles);
            response.put("truncated", Boolean.TRUE.equals(
                    treeResponse.get("truncated")));
            response.put("dependencyCount", dependencies.size());
            response.put("unresolvedImportCount", unresolvedImports.size());
            response.put("analyzedFiles", analyzedFiles);
            response.put("dependencies", dependencies);
            response.put("unresolvedImports", unresolvedImports);
            response.put(
                    "note",
                    "Static import analysis only. Dynamic imports, aliases, "
                            + "and language-specific resolution rules may be incomplete."
            );

            return response;

        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (RestClientResponseException ex) {
            int code = ex.getStatusCode().value();

            if (code == 404) {
                throw new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Repository or source file not found, or not publicly accessible."
                );
            }

            if (code == 403 || code == 429) {
                throw new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "GitHub API rate limit or access restriction reached."
                );
            }

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "GitHub source retrieval failed."
            );

        } catch (RestClientException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Unable to connect to GitHub."
            );

        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "A source file could not be decoded."
            );
        }
    }

    private Map<String, Object> toFileEntry(
            Map<?, ?> entry,
            String path) {

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", path);
        result.put("sha", entry.get("sha"));
        result.put("size", entry.get("size"));
        return result;
    }

    private void indexJavaTypes(
            String path,
            String source,
            Map<String, String> javaPackages,
            Map<String, String> javaTypes) {

        Matcher packageMatcher = JAVA_PACKAGE_PATTERN.matcher(source);

        String packageName = packageMatcher.find()
                ? packageMatcher.group(1)
                : "";

        javaPackages.put(path, packageName);

        Matcher typeMatcher = JAVA_TYPE_PATTERN.matcher(source);

        while (typeMatcher.find()) {
            String typeName = typeMatcher.group(1);

            String qualifiedName = packageName.isEmpty()
                    ? typeName
                    : packageName + "." + typeName;

            javaTypes.putIfAbsent(qualifiedName, path);
        }
    }

    private void extractDependencies(
            String sourcePath,
            String source,
            Set<String> repositoryFiles,
            Map<String, String> javaTypes,
            Map<String, String> javaPackages,
            List<Map<String, Object>> dependencies,
            List<Map<String, Object>> unresolvedImports) {

        String extension = extensionOf(sourcePath);
        Set<ImportReference> imports = new LinkedHashSet<>();

        if (".java".equals(extension)) {
            Matcher matcher = JAVA_IMPORT_PATTERN.matcher(source);

            while (matcher.find()) {
                String importPath = matcher.group(2);
                String resolutionPath = importPath;

                // Keep the original import for display, but resolve a static
                // member import against its declaring type.
                if (matcher.group(1) != null) {
                    int lastDot = resolutionPath.lastIndexOf('.');

                    if (lastDot > 0) {
                        resolutionPath = resolutionPath.substring(0, lastDot);
                    }
                }

                imports.add(new ImportReference(importPath, resolutionPath));
            }
        } else {
            Matcher importsMatcher = JS_IMPORT_PATTERN.matcher(source);

            while (importsMatcher.find()) {
                String importPath = importsMatcher.group(1);
                imports.add(new ImportReference(importPath, importPath));
            }

            Matcher requireMatcher = JS_REQUIRE_PATTERN.matcher(source);

            while (requireMatcher.find()) {
                String importPath = requireMatcher.group(1);
                imports.add(new ImportReference(importPath, importPath));
            }
        }

        for (ImportReference reference : imports) {
            String importPath = reference.importPath();

            if (".java".equals(extension)) {
                List<String> resolvedPaths = resolveJavaImport(
                        sourcePath,
                        reference.resolutionPath(),
                        javaTypes,
                        javaPackages
                );

                if (resolvedPaths.isEmpty()) {
                    addJavaDependency(
                            sourcePath,
                            importPath,
                            null,
                            dependencies
                    );
                } else {
                    for (String resolvedPath : resolvedPaths) {
                        addJavaDependency(
                                sourcePath,
                                importPath,
                                resolvedPath,
                                dependencies
                        );
                    }
                }

            } else if (importPath.startsWith(".")) {
                String resolved = resolveRelativeImport(
                        sourcePath,
                        importPath,
                        repositoryFiles
                );

                addDependency(
                        sourcePath,
                        importPath,
                        resolved,
                        dependencies,
                        unresolvedImports
                );

            } else {
                Map<String, Object> dependency = new LinkedHashMap<>();
                dependency.put("source", sourcePath);
                dependency.put("import", importPath);
                dependency.put("kind", "external");
                dependencies.add(dependency);
            }
        }
    }

    private record ImportReference(
            String importPath,
            String resolutionPath) {
    }

    private void addJavaDependency(
            String sourcePath,
            String importPath,
            String resolvedPath,
            List<Map<String, Object>> dependencies) {

        Map<String, Object> dependency = new LinkedHashMap<>();
        dependency.put("source", sourcePath);
        dependency.put("import", importPath);

        if (resolvedPath != null) {
            dependency.put("target", resolvedPath);
            dependency.put("kind", "internal");
        } else {
            // JDK and third-party imports are external unless they resolve
            // to a source file present in this repository.
            dependency.put("kind", "external");
        }

        dependencies.add(dependency);
    }

    private List<String> resolveJavaImport(
            String sourcePath,
            String importPath,
            Map<String, String> javaTypes,
            Map<String, String> javaPackages) {

        // A wildcard can refer to a package or to a type's nested types.
        if (importPath.endsWith(".*")) {
            String wildcardBase = importPath.substring(
                    0, importPath.length() - 2
            );

            // For example, Outer.* can refer to nested types declared
            // inside Outer.java.
            String enclosingType = javaTypes.get(wildcardBase);

            if (enclosingType != null && !enclosingType.equals(sourcePath)) {
                return List.of(enclosingType);
            }

            // A package wildcard can match multiple repository files.
            List<String> packageMatches = javaPackages.entrySet().stream()
                    .filter(entry -> wildcardBase.equals(entry.getValue()))
                    .map(Map.Entry::getKey)
                    .filter(path -> !path.equals(sourcePath))
                    .sorted()
                    .toList();

            return packageMatches;
        }

        // Exact fully qualified type import.
        String exactMatch = javaTypes.get(importPath);

        if (exactMatch != null && !exactMatch.equals(sourcePath)) {
            return List.of(exactMatch);
        }

        // Nested type imports such as com.acme.Outer.Inner resolve to
        // the source file declaring the longest known enclosing type.
        String candidate = importPath;

        while (candidate.contains(".")) {
            int lastDot = candidate.lastIndexOf('.');
            candidate = candidate.substring(0, lastDot);

            String resolved = javaTypes.get(candidate);

            if (resolved != null && !resolved.equals(sourcePath)) {
                return List.of(resolved);
            }
        }

        return List.of();
    }

    private void addDependency(
            String source,
            String importPath,
            String resolvedPath,
            List<Map<String, Object>> dependencies,
            List<Map<String, Object>> unresolvedImports) {

        Map<String, Object> dependency = new LinkedHashMap<>();
        dependency.put("source", source);
        dependency.put("import", importPath);

        if (resolvedPath != null) {
            dependency.put("target", resolvedPath);
            dependency.put("kind", "internal");
            dependencies.add(dependency);

        } else if (!importPath.startsWith(".")) {
            dependency.put("kind", "external");
            dependencies.add(dependency);

        } else {
            Map<String, Object> unresolved = new LinkedHashMap<>();
            unresolved.put("source", source);
            unresolved.put("import", importPath);
            unresolvedImports.add(unresolved);
        }
    }

    private String resolveRelativeImport(
            String sourcePath,
            String importPath,
            Set<String> repositoryFiles) {

        int lastSlash = sourcePath.lastIndexOf('/');

        String directory = lastSlash >= 0
                ? sourcePath.substring(0, lastSlash + 1)
                : "";

        String normalized = normalizePath(directory + importPath);

        if (normalized == null) {
            return null;
        }

        if (repositoryFiles.contains(normalized)) {
            return normalized;
        }

        for (String extension : RESOLUTION_EXTENSIONS) {
            String candidate = normalized + extension;

            if (repositoryFiles.contains(candidate)) {
                return candidate;
            }
        }

        for (String extension : RESOLUTION_EXTENSIONS) {
            String candidate = normalized + "/index" + extension;

            if (repositoryFiles.contains(candidate)) {
                return candidate;
            }
        }

        return null;
    }

    private String normalizePath(String path) {
        List<String> segments = new ArrayList<>();

        for (String segment : path.replace('\\', '/').split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }

            if ("..".equals(segment)) {
                if (segments.isEmpty()) {
                    return null;
                }

                segments.remove(segments.size() - 1);
            } else {
                segments.add(segment);
            }
        }

        return String.join("/", segments);
    }

    private boolean isSupportedSource(String path) {
        return SOURCE_EXTENSIONS.contains(extensionOf(path));
    }

    private boolean isResolvablePath(String path) {
        String extension = extensionOf(path);

        return SOURCE_EXTENSIONS.contains(extension)
                || RESOLUTION_EXTENSIONS.contains(extension);
    }

    private String extensionOf(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');

        if (dot <= slash) {
            return "";
        }

        return path.substring(dot).toLowerCase(Locale.ROOT);
    }

    private boolean isExcludedPath(String path) {
        String lower = "/" + path.toLowerCase(Locale.ROOT) + "/";

        return lower.contains("/node_modules/")
                || lower.contains("/vendor/")
                || lower.contains("/.git/")
                || lower.contains("/build/")
                || lower.contains("/dist/")
                || lower.contains("/target/")
                || lower.contains("/.next/");
    }

    private void validateRepositoryName(String value, String label) {
        if (value == null
                || !value.matches("[A-Za-z0-9_.-]{1,100}")
                || ".".equals(value)
                || "..".equals(value)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid GitHub " + label + " name."
            );
        }
    }
}
