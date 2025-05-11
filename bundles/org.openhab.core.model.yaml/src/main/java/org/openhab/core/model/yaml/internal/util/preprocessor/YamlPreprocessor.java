/*
 * Copyright (c) 2010-2025 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.core.model.yaml.internal.util.preprocessor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.representer.Representer;

/**
 * The {@link YamlPreprocessor} is a utility class to load YAML files
 * and preprocess them before they are parsed.
 *
 * It performs the following tasks:
 * - Variable substitution
 * - Including other YAML files using the <code>!include</code> tag.
 *
 * @author Jimmy Tanagra - Initial contribution
 */
@NonNullByDefault
public class YamlPreprocessor {
    private static final int MAX_INCLUDE_DEPTH = 999;

    public static final String SECRETS_FILE = "secrets.yaml";

    private static final String VARIABLES_KEY = "variables";
    private static final String PACKAGES_KEY = "packages";

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(YamlPreprocessor.class);

    public static Object load(Path file) throws IOException {
        return load(file, new HashMap<>(), new HashMap<>(), 0);
    }

    static Object load(Path file, Map<String, String> variables, Map<Path, Map<String, String>> secretsCache, int depth)
            throws IOException {
        LOGGER.trace("Loading file: {} with given vars {}", file, variables);
        if (depth > MAX_INCLUDE_DEPTH) {
            throw new IOException("Maximum include depth exceeded");
        }

        HashMap<String, String> combinedVars = new HashMap<>(variables);
        // first pass: load the file to extract variables
        Object yamlData = loadYaml(file, variables);
        if (yamlData instanceof Map) {
            if (extractVariables((Map<String, Object>) yamlData, combinedVars)) {
                LOGGER.trace("Combined vars: {}", combinedVars);
            } else {
                LOGGER.warn("{}: 'variables' is not a map", file);
            }
        } else {
            return yamlData;
        }

        // second pass: load the file again to perform proper variable substitution
        Map<String, Object> dataMap = (Map<String, Object>) loadYaml(file, combinedVars);

        LOGGER.debug("Loaded data: {} ({})", dataMap, dataMap.getClass().getSimpleName());

        dataMap.remove(VARIABLES_KEY); // we've already extracted the variables in the first pass

        processIncludes(file, dataMap, combinedVars, secretsCache, depth);
        Path secretsFile = file.resolveSibling(SECRETS_FILE);
        processSecrets(secretsFile, dataMap, combinedVars, secretsCache, depth);
        combinePackages(dataMap);
        return dataMap;
    }

    private static Object loadYaml(Path file, Map<String, String> variables) throws IOException {
        try (InputStream inputStream = Files.newInputStream(file)) {
            Yaml yaml = newYaml(variables);
            return yaml.load(inputStream);
        }
    }

    /*
     * Extracts variables from the given map.
     *
     * @param variables the map to store the extracted variables.
     * Only variables that are not already present will be added.
     *
     * @return true if the variables were successfully extracted or if they were not present.
     * false if the variables value is not a map.
     */
    private static boolean extractVariables(Map<String, Object> dataMap, HashMap<String, String> variables) {
        Object variablesSection = dataMap.get(VARIABLES_KEY);
        if (variablesSection instanceof Map<?, ?> variablesMap) {
            variablesMap.forEach((key, value) -> {
                if (value instanceof Map) {
                    LOGGER.warn("Value type for variable '{}' cannot be a map", key);
                } else if (value instanceof List) {
                    LOGGER.warn("Value type for variable '{}' cannot be a list", key);
                } else if (value != null) {
                    variables.putIfAbsent(key.toString(), value.toString());
                }
            });
            return true;
        } else if (variablesSection != null) {
            return false; // not a map, not ok
        }
        return true; // no variables section found, that's ok
    }

    /*
     * Process special nodes in the YAML data that correspond to !include.
     * This method is called recursively for nested objects.
     */
    private static Object processIncludes(Path file, Object data, Map<String, String> variables,
            Map<Path, Map<String, String>> secretsCache, int depth) {
        if (data instanceof IncludeObject includeObject) {
            try {
                Object includedData = loadIncludeFile(file, includeObject, variables, secretsCache, depth);
                data = includedData;
            } catch (IOException e) {
                LOGGER.warn("Error processing include '{}': {}", includeObject.fileName(), e.getMessage());
                data = Map.of();
            }
        } else if (data instanceof Map) {
            Map<String, Object> dataMap = (Map<String, Object>) data;
            dataMap.replaceAll((key, value) -> processIncludes(file, value, variables, secretsCache, depth));
        } else if (data instanceof List) {
            List<Object> dataList = (List<Object>) data;
            dataList.replaceAll(value -> processIncludes(file, value, variables, secretsCache, depth));
        }
        return data;
    }

    private static Object loadIncludeFile(Path file, IncludeObject includeObject, Map<String, String> variables,
            Map<Path, Map<String, String>> secretsCache, int depth) throws IOException {
        Path includeFile = file.resolveSibling(includeObject.fileName());
        Map<String, String> includeVars = new HashMap<>(variables);
        if (includeObject.vars() != null) {
            includeObject.vars().forEach((k, v) -> {
                if (v != null) {
                    includeVars.put(k, v);
                }
            });
        }
        LOGGER.debug("Including file: {} with vars: {}", includeFile, includeVars);
        return load(includeFile, includeVars, secretsCache, depth + 1);
    }

    /*
     * Process special nodes in the YAML data that correspond to !secret.
     * This method is called recursively for nested objects.
     */
    private static Object processSecrets(Path secretsFile, Object data, Map<String, String> variables,
            Map<Path, Map<String, String>> secretsCache, int depth) {
        if (data instanceof SecretObject secretObject) {
            String secretName = secretObject.name();
            // load secrets() only when we need them
            String secretValue = getSecrets(secretsFile, secretsCache).get(secretName);
            if (secretValue != null) {
                return secretValue;
            } else {
                LOGGER.warn("Secret '{}' not found", secretName);
                return "";
            }
        } else if (data instanceof Map mapData) {
            mapData.replaceAll((key, value) -> processSecrets(secretsFile, value, variables, secretsCache, depth));
            return mapData;
        } else if (data instanceof List listData) {
            listData.replaceAll(value -> processSecrets(secretsFile, value, variables, secretsCache, depth));
            return listData;
        }
        return data;
    }

    private static Map<String, String> getSecrets(Path secretsFile, Map<Path, Map<String, String>> secretsCache) {
        Map<String, String> secrets = secretsCache.computeIfAbsent(secretsFile, f -> {
            if (Files.exists(secretsFile)) {
                try {
                    Object loadedSecrets = loadYaml(secretsFile, new HashMap<>());
                    loadedSecrets = processIncludes(secretsFile, loadedSecrets, new HashMap<>(), secretsCache, 0);

                    if (loadedSecrets instanceof Map) {
                        LOGGER.debug("Loaded secrets: {} ({})", loadedSecrets,
                                loadedSecrets.getClass().getSimpleName());
                        Map<String, Object> loadedSecretsMap = (Map<String, Object>) loadedSecrets;
                        return loadedSecretsMap.entrySet().stream().filter(entry -> {
                            if (entry.getValue() instanceof String) {
                                return true;
                            }
                            // don't log the secret value
                            LOGGER.warn("{}: value for key '{}' is not a string", secretsFile, entry.getKey());
                            return false;
                        }).collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().toString()));
                    }
                } catch (IOException e) {
                    LOGGER.warn("Error loading secrets file {}: {}", secretsFile, e.getMessage());
                }
            }
            return Map.of();
        });

        return Objects.requireNonNull(secrets);
    }

    private static void combinePackages(Map<String, Object> data) {
        Map<String, Object> packages = (Map<String, Object>) data.remove(PACKAGES_KEY);
        if (packages == null) {
            return;
        }
        packages.forEach((packageName, pkg) -> { // packageName (key) is not used
            if (pkg instanceof Map) { // content of the included package, e.g. { things: { ... }, items: { ... } }
                Map<String, Object> packageData = (Map<String, Object>) pkg;
                LOGGER.debug("Combining package: {} with data: {}", packageName, packageData);
                packageData.forEach((mainElement, pkgElements) -> {
                    data.merge(mainElement, pkgElements, (existingValue, newValue) -> {
                        if (existingValue instanceof Map && newValue instanceof Map) {
                            ((Map<String, Object>) existingValue).putAll((Map<String, Object>) newValue);
                            return existingValue;
                        }
                        return newValue;
                    });
                });
            }
        });
    }

    static Yaml newYaml(Map<String, String> variables) {
        return new Yaml(new ModelConstructor(variables), new Representer(new DumperOptions()), new DumperOptions(),
                new ModelResolver());
    }
}
