/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache license, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the license for the specific language governing permissions and
 * limitations under the license.
 */
package org.apache.logging.log4j.plugins.convert;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.plugins.util.LazyValue;
import org.apache.logging.log4j.plugins.util.PluginManager;
import org.apache.logging.log4j.plugins.util.PluginType;
import org.apache.logging.log4j.plugins.util.TypeUtil;
import org.apache.logging.log4j.status.StatusLogger;
import org.apache.logging.log4j.util.ReflectionUtil;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UnknownFormatConversionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Registry for {@link TypeConverter} plugins.
 *
 * @since 2.1
 */
public class TypeConverterRegistry {

    private static final Logger LOGGER = StatusLogger.getLogger();
    private static final Supplier<TypeConverterRegistry> INSTANCE = LazyValue.forSupplier(TypeConverterRegistry::new);

    private final ConcurrentMap<Type, TypeConverter<?>> registry = new ConcurrentHashMap<>();

    /**
     * Gets the singleton instance of the TypeConverterRegistry.
     *
     * @return the singleton instance.
     */
    public static TypeConverterRegistry getInstance() {
        return INSTANCE.get();
    }

    /**
     * Finds a {@link TypeConverter} for the given {@link Type}, falling back to an assignment-compatible TypeConverter
     * if none exist for the given type. That is, if the given Type does not have a TypeConverter, but another Type
     * which can be assigned to the given Type <em>does</em> have a TypeConverter, then that TypeConverter will be
     * used and registered.
     *
     * @param type the Type to find a TypeConverter for (must not be {@code null}).
     * @return a TypeConverter for the given Type.
     * @throws UnknownFormatConversionException if no TypeConverter can be found for the given type.
     */
    public TypeConverter<?> findCompatibleConverter(final Type type) {
        Objects.requireNonNull(type, "No type was provided");
        final TypeConverter<?> primary = registry.get(type);
        // cached type converters
        if (primary != null) {
            return primary;
        }
        // dynamic enum support
        if (type instanceof Class<?>) {
            final Class<?> clazz = (Class<?>) type;
            if (clazz.isEnum()) {
                @SuppressWarnings({"unchecked","rawtypes"})
                final EnumConverter<? extends Enum> converter = new EnumConverter(clazz.asSubclass(Enum.class));
                synchronized (INSTANCE) {
                    return registerConverter(type, converter);
                }
            }
        }
        // look for compatible converters
        for (final Map.Entry<Type, TypeConverter<?>> entry : registry.entrySet()) {
            final Type key = entry.getKey();
            if (TypeUtil.isAssignable(type, key)) {
                LOGGER.debug("Found compatible TypeConverter<{}> for type [{}].", key, type);
                final TypeConverter<?> value = entry.getValue();
                synchronized (INSTANCE) {
                    return registerConverter(type, value);
                }
            }
        }
        throw new UnknownFormatConversionException(type.toString());
    }

    private TypeConverterRegistry() {
        LOGGER.trace("TypeConverterRegistry initializing.");
        final PluginManager manager = new PluginManager(TypeConverters.CATEGORY);
        manager.collectPlugins();
        loadKnownTypeConverters(manager.getPlugins().values());
        registerPrimitiveTypes();
    }

    private void loadKnownTypeConverters(final Collection<PluginType<?>> knownTypes) {
        for (final PluginType<?> knownType : knownTypes) {
            final Class<?> clazz = knownType.getPluginClass();
            if (TypeConverter.class.isAssignableFrom(clazz)) {
                @SuppressWarnings("rawtypes") final Class<? extends TypeConverter> pluginClass =
                        clazz.asSubclass(TypeConverter.class);
                final Type conversionType = getTypeConverterSupportedType(pluginClass);
                final TypeConverter<?> converter = ReflectionUtil.instantiate(pluginClass);
                registerConverter(conversionType, converter);
            }
        }
    }

    /**
     * Attempts to register the given converter and returns the effective
     * converter associated with the given type.
     * <p>
     * Registration will fail if there already exists a converter for the given
     * type and neither the existing, nor the provided converter extends from {@link Comparable}.
     */
    private TypeConverter<?> registerConverter(
            final Type conversionType,
            final TypeConverter<?> converter) {
        final TypeConverter<?> conflictingConverter = registry.get(conversionType);
        if (conflictingConverter != null) {
            final boolean overridable;
            if (converter instanceof Comparable) {
                @SuppressWarnings("unchecked")
                final Comparable<TypeConverter<?>> comparableConverter =
                        (Comparable<TypeConverter<?>>) converter;
                overridable = comparableConverter.compareTo(conflictingConverter) < 0;
            } else if (conflictingConverter instanceof Comparable) {
                @SuppressWarnings("unchecked")
                final Comparable<TypeConverter<?>> comparableConflictingConverter =
                        (Comparable<TypeConverter<?>>) conflictingConverter;
                overridable = comparableConflictingConverter.compareTo(converter) > 0;
            } else {
                overridable = false;
            }
            if (overridable) {
                LOGGER.debug(
                        "Replacing TypeConverter [{}] for type [{}] with [{}] after comparison.",
                        conflictingConverter, conversionType, converter);
                registry.put(conversionType, converter);
                return converter;
            } else {
                LOGGER.warn(
                        "Ignoring TypeConverter [{}] for type [{}] that conflicts with [{}], since they are not comparable.",
                        converter, conversionType, conflictingConverter);
                return conflictingConverter;
            }
        } else {
            registry.put(conversionType, converter);
            return converter;
        }
    }

    private static Type getTypeConverterSupportedType(@SuppressWarnings("rawtypes") final Class<? extends TypeConverter> typeConverterClass) {
        for (final Type type : typeConverterClass.getGenericInterfaces()) {
            if (type instanceof ParameterizedType) {
                final ParameterizedType pType = (ParameterizedType) type;
                if (TypeConverter.class.equals(pType.getRawType())) {
                    // TypeConverter<T> has only one type argument (T), so return that
                    return pType.getActualTypeArguments()[0];
                }
            }
        }
        return Void.TYPE;
    }

    private void registerPrimitiveTypes() {
        registerTypeAlias(Boolean.class, Boolean.TYPE);
        registerTypeAlias(Byte.class, Byte.TYPE);
        registerTypeAlias(Character.class, Character.TYPE);
        registerTypeAlias(Double.class, Double.TYPE);
        registerTypeAlias(Float.class, Float.TYPE);
        registerTypeAlias(Integer.class, Integer.TYPE);
        registerTypeAlias(Long.class, Long.TYPE);
        registerTypeAlias(Short.class, Short.TYPE);
    }

    private void registerTypeAlias(final Type knownType, final Type aliasType) {
        TypeConverter<?> converter = registry.get(knownType);
        if (converter != null) {
            registry.putIfAbsent(aliasType, converter);
        } else {
            LOGGER.error("Cannot locate converter for {}", knownType);
        }
    }

}
