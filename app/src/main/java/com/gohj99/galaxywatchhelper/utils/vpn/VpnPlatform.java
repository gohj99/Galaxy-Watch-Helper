/*
 * Portions of VPN link/capability setup adapted from Android 16 Vpn.java.
 * Copyright (C) 2011-2025 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0: https://www.apache.org/licenses/LICENSE-2.0
 */
package com.gohj99.galaxywatchhelper.utils.vpn;

import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.ConnectivityManager;
import android.net.InetAddresses;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.ProxyInfo;
import android.net.RouteInfo;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcelable;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Range;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Exact hidden-API boundary, checked against BZF4 DEX. Contains no firmware classes or binaries. */
@android.annotation.TargetApi(36)
final class VpnPlatform {
    final Class<?> configType, agentType, providerType;
    final Parcelable.Creator<Parcelable> configCreator;
    final NativeVpnBridge nativeVpn;
    final Object netd;
    private final ClassLoader loader;
    private final Class<?> concreteAgentType;

    @SuppressWarnings("unchecked")
    VpnPlatform(ClassLoader loader) throws ReflectiveOperationException {
        this.loader = loader;
        configType = type("com.android.internal.net.VpnConfig");
        configCreator = (Parcelable.Creator<Parcelable>) configType.getField("CREATOR").get(null);
        for (String field : new String[] {"user", "interfaze", "session", "mtu", "addresses", "routes",
                "dnsServers", "searchDomains", "allowedApplications", "disallowedApplications", "blocking",
                "allowBypass", "allowIPv4", "allowIPv6", "isMetered", "requiresInternetValidation",
                "excludeLocalRoutes", "underlyingNetworks", "proxyInfo", "startTime", "legacy"}) {
            configType.getField(field);
        }
        agentType = type("android.net.NetworkAgent");
        providerType = type("android.net.NetworkProvider");
        agentType.getDeclaredConstructor(Context.class, Looper.class, String.class, NetworkCapabilities.class,
                LinkProperties.class, type("android.net.NetworkScore"), type("android.net.NetworkAgentConfig"), providerType);
        concreteAgentType = Class.forName(GwhNetworkAgent.class.getName(), false, VpnPlatform.class.getClassLoader());
        if (concreteAgentType.getSuperclass() != agentType) {
            throw new IllegalStateException("GWH NetworkAgent platform ClassLoader mismatch");
        }
        // Check both the actual superclass and our instantiable class before service publication.
        VpnReflection.constructor(concreteAgentType, Context.class, Looper.class, String.class,
                NetworkCapabilities.class, LinkProperties.class, type("android.net.NetworkScore"),
                type("android.net.NetworkAgentConfig"), providerType);
        for (String method : new String[] {"register", "markConnected", "unregister", "onNetworkUnwanted"}) {
            agentType.getMethod(method);
        }
        method(type("android.net.NetworkCapabilities$Builder"), "setUids", Set.class);
        method(type("android.net.NetworkCapabilities$Builder"), "setOwnerUid", int.class);
        method(type("android.net.NetworkCapabilities$Builder"), "setAdministratorUids", int[].class);
        method(type("android.net.NetworkCapabilities$Builder"), "setUnderlyingNetworks", List.class);
        method(type("android.net.NetworkCapabilities$Builder"), "setTransportInfo", type("android.net.TransportInfo"));
        Object binder = service("netd");
        if (binder == null) throw new IllegalStateException("netd not published");
        netd = invoke(method(type("android.net.INetd$Stub"), "asInterface", IBinder.class), null, binder);
        method(netd.getClass(), "networkSetProtectAllow", int.class);
        method(netd.getClass(), "networkSetProtectDeny", int.class);
        nativeVpn = new NativeVpnBridge(loader);
    }

    Class<?> type(String name) {
        try { return Class.forName(name, false, loader); }
        catch (ClassNotFoundException e) { throw new IllegalStateException("Missing VPN dependency: " + name, e); }
    }

    Object service(String name) {
        return invoke(method(type("android.os.ServiceManager"), "checkService", String.class), null, name);
    }

    void publish(IBinder binder) {
        invoke(method(type("android.os.ServiceManager"), "addService", String.class, IBinder.class), null,
                "vpn_management", binder);
    }

    Context userContext(Context context, int id) {
        return (Context) invoke(method(Context.class, "createContextAsUser", UserHandle.class, int.class), context,
                userHandle(id), 0);
    }

    UserHandle userHandle(int id) {
        return (UserHandle) invoke(method(UserHandle.class, "of", int.class), null, id);
    }

    @SuppressWarnings("unchecked")
    List<Object> users(Context context) {
        return (List<Object>) call(context.getSystemService(UserManager.class), "getAliveUsers");
    }

    boolean restricted(Object info) { return (Boolean) call(info, "isRestricted"); }

    void enforceEligibleUser(Context context, int user) {
        UserManager um = context.getSystemService(UserManager.class);
        Object info = invoke(method(UserManager.class, "getUserInfo", int.class), um, user);
        if (info == null || restricted(info) || !um.isUserRunning(userHandle(user))) {
            throw new SecurityException("User is not eligible to own a VPN");
        }
        Context scoped = userContext(context, user);
        if (scoped.getSystemService(UserManager.class).hasUserRestriction(UserManager.DISALLOW_CONFIG_VPN)) {
            throw new SecurityException("VPN configuration is restricted for this user");
        }
    }

    Object newProvider(Context context, Looper looper) {
        Object provider = construct(providerType, new Class<?>[] {Context.class, Looper.class, String.class},
                context, looper, "GWHVpnProvider");
        invoke(method(ConnectivityManager.class, "registerNetworkProvider", providerType),
                context.getSystemService(ConnectivityManager.class), provider);
        return provider;
    }

    void removeProvider(Context context, Object provider) {
        invoke(method(ConnectivityManager.class, "unregisterNetworkProvider", providerType),
                context.getSystemService(ConnectivityManager.class), provider);
    }

    void protect(int uid, boolean allowed) {
        invoke(method(netd.getClass(), allowed ? "networkSetProtectAllow" : "networkSetProtectDeny", int.class), netd, uid);
    }

    Parcelable copyConfig(Parcelable config) {
        if (!configType.isInstance(config)) throw new IllegalArgumentException("Not a platform VpnConfig");
        return (Parcelable) construct(configType, new Class<?>[] {configType}, config);
    }

    boolean bind(Context context, Intent intent, ServiceConnection connection, int user) {
        return context.bindServiceAsUser(intent, connection, Context.BIND_AUTO_CREATE | 0x04000000,
                userHandle(user)); // actual BIND_FOREGROUND_SERVICE flag
    }

    @SuppressWarnings("unchecked")
    Set<Range<Integer>> ranges(Context context, int ownerUser, Parcelable config) {
        Set<Range<Integer>> ranges = new HashSet<>();
        List<Integer> users = new ArrayList<>();
        users.add(ownerUser);
        if ((Boolean) call(userContext(context, ownerUser).getSystemService(UserManager.class), "canHaveRestrictedProfile")) {
            for (Object info : users(context)) {
                if (restricted(info) && (Integer) field(info, "restrictedProfileParentId") == ownerUser) {
                    users.add((Integer) field(info, "id"));
                }
            }
        }
        for (int user : users) {
            Context scoped = userContext(context, user);
            List<Integer> allowed = appUids(scoped, (List<String>) field(config, "allowedApplications"));
            List<Integer> denied = appUids(scoped, (List<String>) field(config, "disallowedApplications"));
            for (int[] range : VpnUidPolicy.ranges(user, allowed, denied)) ranges.add(new Range<>(range[0], range[1]));
        }
        return ranges;
    }

    private List<Integer> appUids(Context scoped, List<String> packages) {
        if (packages == null) return null;
        List<Integer> result = new ArrayList<>();
        for (String pkg : packages) {
            try { result.add(scoped.getPackageManager().getApplicationInfo(pkg, 0).uid); }
            catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
                // A removed app must not cause another UID to be routed through the VPN.
            }
        }
        return result;
    }

    NetworkCapabilities capabilities(Context context, int user, int uid, Parcelable config) {
        Object b = construct(type("android.net.NetworkCapabilities$Builder"), new Class<?>[0]);
        chain(b, "addTransportType", int.class, NetworkCapabilities.TRANSPORT_VPN);
        chain(b, "removeCapability", int.class, NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
        chain(b, "addCapability", int.class, NetworkCapabilities.NET_CAPABILITY_INTERNET);
        chain(b, "addCapability", int.class, 28); // actual NET_CAPABILITY_NOT_VCN_MANAGED
        boolean metered = (Boolean) field(config, "isMetered");
        chain(b, metered ? "removeCapability" : "addCapability", int.class, NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        chain(b, "setOwnerUid", int.class, uid);
        chain(b, "setAdministratorUids", int[].class, new int[] {uid});
        chain(b, "setUids", Set.class, ranges(context, user, config));
        Network[] underlying = (Network[]) field(config, "underlyingNetworks");
        chain(b, "setUnderlyingNetworks", List.class, underlying == null ? null : Arrays.asList(underlying));
        Object transport = construct(type("android.net.VpnTransportInfo"),
                new Class<?>[] {int.class, String.class, boolean.class, boolean.class},
                1, field(config, "session"), field(config, "allowBypass"), false); // TYPE_VPN_SERVICE=1
        chain(b, "setTransportInfo", type("android.net.TransportInfo"), transport);
        return (NetworkCapabilities) call(b, "build");
    }

    @SuppressWarnings("unchecked")
    LinkProperties links(Parcelable config) {
        LinkProperties lp = new LinkProperties();
        lp.setInterfaceName((String) field(config, "interfaze"));
        boolean ipv4 = (Boolean) field(config, "allowIPv4"), ipv6 = (Boolean) field(config, "allowIPv6");
        for (LinkAddress address : (List<LinkAddress>) field(config, "addresses")) {
            invoke(method(LinkProperties.class, "addLinkAddress", LinkAddress.class), lp, address);
            ipv4 |= address.getAddress() instanceof Inet4Address;
            ipv6 |= address.getAddress() instanceof Inet6Address;
        }
        for (RouteInfo route : (List<RouteInfo>) field(config, "routes")) {
            lp.addRoute(route);
            if (route.getType() == RouteInfo.RTN_UNICAST) {
                ipv4 |= route.getDestination().getAddress() instanceof Inet4Address;
                ipv6 |= route.getDestination().getAddress() instanceof Inet6Address;
            }
        }
        List<String> dns = (List<String>) field(config, "dnsServers");
        if (dns != null) for (String value : dns) {
            InetAddress address = InetAddresses.parseNumericAddress(value);
            invoke(method(LinkProperties.class, "addDnsServer", InetAddress.class), lp, address);
            ipv4 |= address instanceof Inet4Address;
            ipv6 |= address instanceof Inet6Address;
        }
        if (!ipv4) lp.addRoute(unreachable("0.0.0.0/0"));
        if (!ipv6) lp.addRoute(unreachable("::/0"));
        List<String> domains = (List<String>) field(config, "searchDomains");
        lp.setDomains(domains == null ? "" : String.join(" ", domains));
        lp.setHttpProxy((ProxyInfo) field(config, "proxyInfo"));
        int mtu = (Integer) field(config, "mtu");
        if (mtu > 0) lp.setMtu(mtu);
        return lp;
    }

    private RouteInfo unreachable(String prefix) {
        return (RouteInfo) construct(RouteInfo.class,
                new Class<?>[] {IpPrefix.class, InetAddress.class, String.class, int.class},
                new IpPrefix(InetAddresses.parseNumericAddress(prefix.substring(0, prefix.indexOf('/'))), 0), null, null, RouteInfo.RTN_UNREACHABLE);
    }

    Object newAgent(Context context, Looper looper, Parcelable config, NetworkCapabilities caps, Object provider) {
        Object agentConfig = construct(type("android.net.NetworkAgentConfig$Builder"), new Class<?>[0]);
        chain(agentConfig, "setLegacyType", int.class, 17); // TYPE_VPN
        chain(agentConfig, "setLegacyTypeName", String.class, "VPN");
        chain(agentConfig, "setLegacyExtraInfo", String.class, "VPN");
        chain(agentConfig, "setBypassableVpn", boolean.class, field(config, "allowBypass"));
        chain(agentConfig, "setVpnRequiresValidation", boolean.class, field(config, "requiresInternetValidation"));
        chain(agentConfig, "setLocalRoutesExcludedForVpn", boolean.class, field(config, "excludeLocalRoutes"));
        Object score = construct(type("android.net.NetworkScore$Builder"), new Class<?>[0]);
        chain(score, "setLegacyInt", int.class, 101);
        return construct(concreteAgentType, new Class<?>[] {Context.class, Looper.class, String.class,
                NetworkCapabilities.class, LinkProperties.class, type("android.net.NetworkScore"),
                type("android.net.NetworkAgentConfig"), providerType}, context, looper, "GWHVpn",
                caps, links(config), call(score, "build"), call(agentConfig, "build"), provider);
    }

    static Object field(Object target, String name) {
        try { return target.getClass().getField(name).get(target); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("Missing VPN field " + name, e); }
    }

    static void field(Object target, String name, Object value) {
        try { target.getClass().getField(name).set(target, value); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("Cannot set VPN field " + name, e); }
    }

    static Method method(Class<?> type, String name, Class<?>... signature) {
        try { Method m = type.getMethod(name, signature); m.setAccessible(true); return m; }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("Missing VPN API " + type.getName() + "." + name, e); }
    }

    static Object call(Object target, String method) { return invoke(method(target.getClass(), method), target); }
    static void chain(Object target, String name, Class<?> signature, Object argument) {
        invoke(method(target.getClass(), name, signature), target, argument);
    }

    static Object construct(Class<?> type, Class<?>[] signature, Object... args) {
        return VpnReflection.construct(type, signature, args);
    }

    static Object invoke(Method method, Object target, Object... args) {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException e) { throw propagate(e.getCause()); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("Cannot call VPN API " + method.getName(), e); }
    }

    private static RuntimeException propagate(Throwable error) {
        if (error instanceof RuntimeException) return (RuntimeException) error;
        if (error instanceof Error) throw (Error) error;
        return new IllegalStateException("VPN platform call failed", error);
    }
}
