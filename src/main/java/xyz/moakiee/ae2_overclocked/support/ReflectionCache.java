package xyz.moakiee.ae2_overclocked.support;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized reflection cache for AE2 Overclocked.
 * <p>
 * All lookups are cached in static {@link ConcurrentHashMap}s keyed by
 * {@code "fully.qualified.ClassName#memberName[,paramType]*"}.
 * Results (including negative lookups) are cached as {@link Optional} to
 * avoid repeated exception-throwing on classes that lack the target member.
 * <p>
 * Every returned {@link Method} / {@link Field} has already had
 * {@code setAccessible(true)} called on it.
 */
public final class ReflectionCache {

    private ReflectionCache() {
    }

    private static final ConcurrentHashMap<String, Optional<Method>> METHODS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Optional<Field>> FIELDS = new ConcurrentHashMap<>();

    // ── Public Method lookups ──────────────────────────────────────────

    /**
     * Cached version of {@link Class#getMethod(String, Class[])}.
     *
     * @return the method, or {@code null} if not found
     */
    public static Method getMethod(Class<?> clazz, String name, Class<?>... params) {
        String key = buildMethodKey("M:", clazz, name, params);
        return METHODS.computeIfAbsent(key, k -> {
            try {
                Method m = clazz.getMethod(name, params);
                m.setAccessible(true);
                return Optional.of(m);
            } catch (NoSuchMethodException e) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    /**
     * Cached version of {@link Class#getDeclaredMethod(String, Class[])}.
     *
     * @return the method, or {@code null} if not found
     */
    public static Method getDeclaredMethod(Class<?> clazz, String name, Class<?>... params) {
        String key = buildMethodKey("D:", clazz, name, params);
        return METHODS.computeIfAbsent(key, k -> {
            try {
                Method m = clazz.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return Optional.of(m);
            } catch (NoSuchMethodException e) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    // ── Field lookups ──────────────────────────────────────────────────

    /**
     * Cached version of {@link Class#getDeclaredField(String)}.
     *
     * @return the field, or {@code null} if not found on the exact class
     */
    public static Field getField(Class<?> clazz, String name) {
        String key = clazz.getName() + "#F:" + name;
        return FIELDS.computeIfAbsent(key, k -> {
            try {
                Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                return Optional.of(f);
            } catch (NoSuchFieldException e) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    /**
     * Recursively searches the class hierarchy (declared fields only) and
     * caches the result.  This replaces the common {@code ae2oc_getField()}
     * recursive helper found in many Mixin classes.
     *
     * @return the field, or {@code null} if not found anywhere in the hierarchy
     */
    public static Field getFieldHierarchy(Class<?> clazz, String name) {
        String key = clazz.getName() + "#H:" + name;
        return FIELDS.computeIfAbsent(key, k -> {
            Class<?> c = clazz;
            while (c != null) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return Optional.of(f);
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
            return Optional.empty();
        }).orElse(null);
    }

    /**
     * Recursively searches the class hierarchy for a declared method and
     * caches the result.  This replaces the common {@code ae2oc_getMethod()}
     * recursive helper found in many Mixin classes.
     *
     * @return the method, or {@code null} if not found anywhere in the hierarchy
     */
    public static Method getDeclaredMethodHierarchy(Class<?> clazz, String name, Class<?>... params) {
        String key = buildMethodKey("DH:", clazz, name, params);
        return METHODS.computeIfAbsent(key, k -> {
            Class<?> c = clazz;
            while (c != null) {
                try {
                    Method m = c.getDeclaredMethod(name, params);
                    m.setAccessible(true);
                    return Optional.of(m);
                } catch (NoSuchMethodException e) {
                    c = c.getSuperclass();
                }
            }
            return Optional.empty();
        }).orElse(null);
    }

    // ── Convenience invokers ───────────────────────────────────────────

    /**
     * Invoke a no-arg method by name, returning {@code null} on any failure.
     * Both the method lookup and the invocation are exception-safe.
     */
    public static Object invokeNoArg(Object target, String methodName) {
        if (target == null) return null;
        Method m = getMethod(target.getClass(), methodName);
        if (m == null) return null;
        try {
            return m.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Read a declared field value by name, returning {@code null} on any failure.
     * Searches the class hierarchy.
     */
    public static Object getFieldValue(Object target, String fieldName) {
        if (target == null) return null;
        Field f = getFieldHierarchy(target.getClass(), fieldName);
        if (f == null) return null;
        try {
            return f.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ── Key builders ───────────────────────────────────────────────────

    private static String buildMethodKey(String prefix, Class<?> clazz, String name, Class<?>... params) {
        StringBuilder sb = new StringBuilder(prefix)
                .append(clazz.getName())
                .append('#')
                .append(name);
        for (Class<?> p : params) {
            sb.append(',').append(p.getName());
        }
        return sb.toString();
    }
}
