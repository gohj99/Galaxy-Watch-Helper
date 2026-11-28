package com.gohj99.galaxywatchhelper.utils.vpn;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Reuses only the seven audited Samsung JNI functions, never the stripped Java implementation. */
final class NativeVpnBridge {
    private final Object receiver;
    private final Method create, getName, setAddresses, reset, check, addAddress, delAddress;

    NativeVpnBridge(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> vpn = Class.forName("com.android.server.connectivity.Vpn", false, loader);
        create = nativeMethod(vpn, "jniCreate", int.class, int.class);
        getName = nativeMethod(vpn, "jniGetName", String.class, int.class);
        setAddresses = nativeMethod(vpn, "jniSetAddresses", int.class, String.class, String.class);
        reset = nativeMethod(vpn, "jniReset", void.class, String.class);
        check = nativeMethod(vpn, "jniCheck", int.class, String.class);
        addAddress = nativeMethod(vpn, "jniAddAddress", boolean.class, String.class, String.class, int.class);
        delAddress = nativeMethod(vpn, "jniDelAddress", boolean.class, String.class, String.class, int.class);
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        var singleton = unsafe.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        receiver = unsafe.getMethod("allocateInstance", Class.class).invoke(singleton.get(null), vpn);
        if ((check("lo") & 1) == 0) throw new IllegalStateException("VPN JNI loopback preflight failed");
    }

    private static Method nativeMethod(Class<?> owner, String name, Class<?> result, Class<?>... params)
            throws ReflectiveOperationException {
        Method method = owner.getDeclaredMethod(name, params);
        if (!Modifier.isNative(method.getModifiers()) || Modifier.isStatic(method.getModifiers())
                || method.getReturnType() != result) throw new NoSuchMethodException(name + " ABI mismatch");
        method.setAccessible(true);
        return method;
    }

    synchronized int create(int mtu) { return (Integer) VpnPlatform.invoke(create, receiver, mtu); }
    synchronized String getName(int fd) { return (String) VpnPlatform.invoke(getName, receiver, fd); }
    synchronized int setAddresses(String iface, String addresses) {
        return (Integer) VpnPlatform.invoke(setAddresses, receiver, iface, addresses);
    }
    synchronized void reset(String iface) { VpnPlatform.invoke(reset, receiver, iface); }
    synchronized int check(String iface) { return (Integer) VpnPlatform.invoke(check, receiver, iface); }
    synchronized boolean address(boolean add, String iface, String address, int prefix) {
        return (Boolean) VpnPlatform.invoke(add ? addAddress : delAddress, receiver, iface, address, prefix);
    }
}
