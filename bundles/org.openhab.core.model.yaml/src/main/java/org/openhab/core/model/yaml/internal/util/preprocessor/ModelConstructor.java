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

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.AbstractConstruct;
import org.yaml.snakeyaml.constructor.Construct;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeId;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.Tag;

/**
 * The {@link ModelConstructor} adds extended functionality to the
 * {@link Constructor} class to support:
 *
 * - Nested variable interpolation
 * - Constructs IncludeObject for <code>!include</code> tag
 * - Constructs SecretObject for <code>!secret</code> tag
 *
 * @author Jimmy Tanagra - Initial contribution
 */
class ModelConstructor extends Constructor {

    private static final Tag INCLUDE_TAG = new Tag("!include");
    private static final Tag SECRET_TAG = new Tag("!secret");
    private static final int MAX_VAR_NESTING_DEPTH = 10;

    // The valid syntax is a subset of bash variable substitution syntax:
    // ${var} - if var is not set, return empty string
    // ${var-default}
    // ${var:-default}
    // ${var?message} - value must not be null, otherwise log "message" warning
    // ${var:?message} - value must not be null or empty, otherwise log "message" warning
    private static final Pattern VARIABLE_PATTERN = Pattern
            .compile("\\$\\{\\s*((?<name>\\w+)((?<separator>:?(-|\\?))(?<default>.*)?)?)\\s*\\}");

    private final Logger logger = LoggerFactory.getLogger(ModelConstructor.class);

    private final Map<String, String> variables;

    public ModelConstructor(Map<String, String> variables) {
        super(new LoaderOptions());

        this.variables = variables;

        this.yamlConstructors.put(INCLUDE_TAG, new ConstructInclude());
        this.yamlConstructors.put(SECRET_TAG, new ConstructSecret());
        this.yamlConstructors.put(Tag.STR, new ConstructInterpolation());
        logger.trace("ModelConstructor created with vars: {}", variables);
    }

    private class ConstructInterpolation extends AbstractConstruct {

        public Object construct(Node node) {
            ScalarNode scalarNode = (ScalarNode) node;

            String value = (String) constructScalar(scalarNode);

            // don't interpolate single quoted strings
            if (scalarNode.getScalarStyle() == DumperOptions.ScalarStyle.SINGLE_QUOTED) {
                return value;
            }

            Matcher matcher = VARIABLE_PATTERN.matcher(value);
            if (!matcher.find()) {
                return value;
            }

            String interpolated = value;
            int nestedLevel = 0;

            do {
                interpolated = matcher.replaceAll(match -> {
                    String variableName = match.group("name");
                    String defaultValue = match.group("default");
                    String separator = match.group("separator");
                    String variableValue = variables.get(variableName);
                    try {
                        String resolved = resolveVariable(variableName, separator, defaultValue);
                        logger.debug("Interpolating variable {} => {}", variableName, resolved);
                        return resolved;
                    } catch (MissingVariableException e) {
                        logger.warn("{}", e.getMessage());
                    }
                    return "";
                });
                if (nestedLevel++ > MAX_VAR_NESTING_DEPTH) {
                    throw new YAMLException("Variable nesting is too deep in " + value);
                }
                matcher = VARIABLE_PATTERN.matcher(interpolated);
            } while (matcher.find());

            // resolve the interpolated node because the type might change e.g.
            // ${var1} => 1: originally a STR, it now becomes !!int 1
            ModelResolver resolver = new ModelResolver();
            Tag newTag = resolver.resolve(NodeId.scalar, interpolated, true);
            ScalarNode replacedNode = new ScalarNode(newTag, interpolated, scalarNode.getStartMark(),
                    scalarNode.getEndMark(), scalarNode.getScalarStyle());
            // now find the correct constructor for the new node
            Construct constructor = yamlConstructors.get(newTag);
            if (constructor == null) {
                throw new YAMLException("No constructor found for substituted value '%s' => '%s' with tag %s"
                        .formatted(value, interpolated, newTag.toString()));
            }
            // finally, construct the new node
            return constructor.construct(replacedNode);
        }

        /**
         * Implement the logic for missing and unset variables
         *
         * @param name - variable name in the template
         * @param separator - separator in the template, can be :-, -, :?, ?
         * @param defaultValue - default value or the error in the template
         * @return the value to resolve in the template
         */
        private String resolveVariable(String name, @Nullable String separator, @Nullable String defaultValue) {
            String value = variables.get(name);
            if (value != null && !value.isEmpty()) {
                return value.toString();
            }
            // variable is either unset or empty
            if (separator != null) {
                // there is a default value or error
                if (separator.equals("?") && (value == null)) {
                    throw new MissingVariableException("Missing mandatory variable " + name + ": " + defaultValue);
                }
                if (separator.equals(":?")) {
                    if (value == null) {
                        throw new MissingVariableException("Missing mandatory variable " + name + ": " + defaultValue);
                    }
                    if (value.isEmpty()) {
                        throw new MissingVariableException("Empty mandatory variable " + name + ": " + defaultValue);
                    }
                }
                if (separator.startsWith(":")) {
                    if (value == null || value.isEmpty()) {
                        return defaultValue;
                    }
                } else {
                    if (value == null) {
                        return defaultValue;
                    }
                }
            }
            return "";
        }

        public class MissingVariableException extends YAMLException {
            private static final long serialVersionUID = 1L;

            public MissingVariableException(String message) {
                super(message);
            }
        }
    }

    public class ConstructInclude extends AbstractConstruct {

        @Override
        public Object construct(Node node) {
            logger.debug("Constructing !include node: {}", node);
            if (node instanceof ScalarNode scalarNode) {
                String value = constructScalar(scalarNode).trim();
                return new IncludeObject(value, Map.of());
            } else if (node instanceof MappingNode mappingNode) {
                Map<Object, Object> includeOptions = constructMapping(mappingNode);

                String fileName = (String) includeOptions.get("file");
                if (fileName == null) {
                    logger.warn("Missing 'file' key in !include: {}", includeOptions);
                    return Map.of();
                }

                Map<String, String> vars = new java.util.HashMap<>(variables);
                Object varsObj = includeOptions.get("vars");
                if (varsObj instanceof Map<?, ?> varsMap) {
                    varsMap.forEach((key, val) -> {
                        if (key instanceof String k && val != null) {
                            vars.put(k, val.toString());
                        }
                    });
                } else if (varsObj != null) {
                    logger.warn("Invalid 'vars' in !include: {}. Expected a map.", varsObj);
                }

                return new IncludeObject(fileName, vars);
            } else {
                logger.warn("Invalid !include argument type: {}", node.getClass().getName());
            }
            return Map.of();
        }
    }

    public class ConstructSecret extends AbstractConstruct {

        @Override
        public Object construct(Node node) {
            if (node instanceof ScalarNode scalarNode) {
                String name = constructScalar(scalarNode).trim();
                return new SecretObject(name);
            } else {
                throw new YAMLException("Invalid secret node type: " + node.getClass().getName());
            }
        }
    }
}
