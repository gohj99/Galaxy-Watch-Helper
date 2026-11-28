package com.gohj99.galaxywatchhelper.utils.vpn;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;

/** Reflection construction checks kept independent of Android for regression tests. */
final class VpnReflection {
    static Constructor<?> constructor(Class<?> type, Class<?>... signature) {
        if (Modifier.isAbstract(type.getModifiers()) || type.isInterface()) {
            throw new IllegalStateException("Cannot instantiate abstract VPN dependency " + type.getName());
        }
        try {
            Constructor<?> constructor = type.getDeclaredConstructor(signature);
            constructor.setAccessible(true);
            return constructor;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Missing VPN constructor " + type.getName() + ": " + error, error);
        }
    }

    static Object construct(Class<?> type, Class<?>[] signature, Object... args) {
        try {
            return constructor(type, signature).newInstance(args);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException("VPN constructor failed " + type.getName() + ": " + cause, cause);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot create VPN dependency " + type.getName() + ": " + error, error);
        }
    }
}
