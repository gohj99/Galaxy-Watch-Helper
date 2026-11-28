/*
 * VpnService preparation, establishment and cleanup adapted from Android 16 Vpn.java.
 * Copyright (C) 2011-2025 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0: https://www.apache.org/licenses/LICENSE-2.0
 */
package com.gohj99.galaxywatchhelper.utils.vpn;

import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.InetAddresses;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.SystemClock;
import android.os.UserHandle;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Owns ordinary app VpnService sessions. Platform IKEv2/legacy profiles and lockdown are unsupported. */
@android.annotation.TargetApi(36)
public final class WearVpnService {
    static final String TAG = "GWSystemServerHook";
    static final String ACTIVATE = "android:activate_vpn";
    static final String ESTABLISH = "android:establish_vpn_service";
    static final String CONTROL = "android.permission.CONTROL_VPN";
    private static final String LEGACY = "[Legacy VPN]";
    final VpnPlatform platform;
    private final Context context;
    private final AppOpsManager appOps;
    private final HandlerThread thread;
    private final Handler handler;
    private final Map<Integer, Session> sessions = new HashMap<>();
    private final java.util.Set<Integer> pendingProtectDenials = new java.util.HashSet<>();
    private boolean ready, closed, receiverRegistered, appOpsWatching, monitorScheduled;
    private Object provider;
    private XC_MethodHook.Unhook agentHook;
    private final WearVpnBinder binder;

    public WearVpnService(Context context, ClassLoader loader) throws ReflectiveOperationException {
        this.context = context;
        platform = new VpnPlatform(loader);
        appOps = context.getSystemService(AppOpsManager.class);
        binder = new WearVpnBinder(this); // validates the complete platform transaction table before publication
        thread = new HandlerThread("GWWearVpn");
        thread.start();
        handler = new Handler(thread.getLooper());
        try {
            installAgentCallback(loader);
            IntentFilter filter = new IntentFilter();
            for (String action : new String[] {"android.intent.action.USER_STOPPED", "android.intent.action.USER_REMOVED",
                    "android.intent.action.USER_ADDED", "android.intent.action.USER_STARTED", "android.intent.action.USER_UNLOCKED"}) {
                filter.addAction(action);
            }
            register(filter);
            IntentFilter packages = new IntentFilter();
            packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
            packages.addAction(Intent.ACTION_PACKAGE_REPLACED);
            packages.addAction(Intent.ACTION_PACKAGE_ADDED);
            packages.addDataScheme("package");
            register(packages);
            receiverRegistered = true;
            appOps.startWatchingMode(ACTIVATE, null, authorizationListener);
            appOpsWatching = true;
            Log.i(TAG, "[WearVpn] native loopback check passed; platform ABI validated (TUN creation not yet tested)");
        } catch (Throwable error) {
            shutdown();
            throw error;
        }
    }

    public IBinder asBinder() { return binder; }

    public synchronized void systemReady() {
        if (ready || closed) return;
        provider = platform.newProvider(context, thread.getLooper());
        ready = true;
        Log.i(TAG, "[WearVpn] systemReady completed; app VPN calls enabled");
    }

    private void register(IntentFilter filter) {
        VpnPlatform.invoke(VpnPlatform.method(Context.class, "registerReceiverAsUser", BroadcastReceiver.class,
                UserHandle.class, IntentFilter.class, String.class, Handler.class, int.class), context,
                receiver, platform.userHandle(-1), filter, null, handler, Context.RECEIVER_EXPORTED);
        receiverRegistered = true;
    }

    // This audited field is accessed only inside API36 system_server, never from the UI process.
    @android.annotation.SuppressLint("BlockedPrivateApi")
    private void installAgentCallback(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> type = Class.forName("android.net.NetworkAgent$NetworkAgentHandler", false, loader);
        Field owner = type.getDeclaredField("this$0");
        owner.setAccessible(true);
        agentHook = XposedBridge.hookMethod(type.getDeclaredMethod("handleMessage", android.os.Message.class),
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam call) {
                        if (call.hasThrowable() || ((android.os.Message) call.args[0]).what != 219) return;
                        try {
                            Object agent = owner.get(call.thisObject);
                            synchronized (WearVpnService.this) {
                                for (Session session : sessions.values()) if (session.agent == agent) {
                                    session.disconnect(true);
                                    Log.i(TAG, "[WearVpn] own network agent disconnected");
                                    break;
                                }
                            }
                        } catch (Throwable error) { Log.e(TAG, "[WearVpn] own agent cleanup failed", error); }
                    }
                });
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            try {
                synchronized (WearVpnService.this) {
                    int user = intent.getIntExtra("android.intent.extra.user_handle", -1);
                    String action = intent.getAction();
                    if ("android.intent.action.USER_STOPPED".equals(action) || "android.intent.action.USER_REMOVED".equals(action)) {
                        Session session = sessions.remove(user);
                        if (session != null) session.revoke();
                    } else if (Intent.ACTION_PACKAGE_REMOVED.equals(action) || Intent.ACTION_PACKAGE_REPLACED.equals(action)) {
                        int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                        String pkg = intent.getData() == null ? null : intent.getData().getSchemeSpecificPart();
                        for (Session session : sessions.values()) if (session.uid == uid && session.pkg.equals(pkg)) session.revoke();
                    }
                    // Restricted profiles and installed app UIDs affect routing ranges.
                    if (ready) for (Session session : sessions.values()) session.refreshCapabilities();
                }
            } catch (Throwable error) { Log.e(TAG, "[WearVpn] own lifecycle handling failed", error); }
        }
    };

    private final AppOpsManager.OnOpChangedListener authorizationListener = (op, pkg) -> authorizationChanged(pkg);

    private void authorizationChanged(String pkg) {
        handler.post(() -> {
            try {
                synchronized (WearVpnService.this) {
                    for (Session session : sessions.values()) if (session.uid >= 0 &&
                            (pkg == null || session.pkg.equals(pkg)) && !consented(session.uid, session.pkg)) session.revoke();
                }
            } catch (Throwable error) { Log.e(TAG, "[WearVpn] own authorization cleanup failed", error); }
        });
    }

    private final Runnable monitor = new Runnable() {
        @Override public void run() {
            try {
                synchronized (WearVpnService.this) {
                    monitorScheduled = false;
                    if (closed) return;
                    for (int uid : new java.util.HashSet<>(pendingProtectDenials)) {
                        cleanup("pending protect denial", () -> denyProtect(uid));
                    }
                    for (Session session : sessions.values()) {
                        if (session.uid >= 0 && !consented(session.uid, session.pkg)) {
                            session.revoke();
                            continue;
                        }
                        if (session.iface != null && (platform.nativeVpn.check(session.iface) & 1) == 0) {
                            session.disconnect(false);
                            Log.i(TAG, "[WearVpn] app closed TUN; network cleaned up");
                        }
                    }
                }
            } catch (Throwable error) { Log.e(TAG, "[WearVpn] own TUN monitor failed", error); }
            synchronized (WearVpnService.this) {
                if (!pendingProtectDenials.isEmpty() || sessions.values().stream().anyMatch(s -> s.iface != null)) scheduleMonitor();
            }
        }
    };

    private void scheduleMonitor() {
        if (ready && !closed && !monitorScheduled) {
            monitorScheduled = true;
            handler.postDelayed(monitor, 3000);
        }
    }

    public synchronized void shutdown() {
        closed = true;
        ready = false;
        for (Session session : sessions.values()) {
            try { session.revoke(); } catch (Throwable e) { Log.e(TAG, "[WearVpn] session shutdown failed", e); }
        }
        sessions.clear();
        if (appOpsWatching) cleanup("app-op watcher", () -> appOps.stopWatchingMode(authorizationListener));
        if (receiverRegistered) {
            try { context.unregisterReceiver(receiver); } catch (IllegalArgumentException e) { Log.w(TAG, "[WearVpn] receiver cleanup", e); }
        }
        if (provider != null) {
            try { platform.removeProvider(context, provider); } catch (Throwable e) { Log.e(TAG, "[WearVpn] provider cleanup", e); }
        }
        if (agentHook != null) agentHook.unhook();
        handler.removeCallbacksAndMessages(null);
        thread.quitSafely();
    }

    private void requireReady() { if (!ready || closed) throw new IllegalStateException("GWH VPN service is not ready"); }
    private boolean privileged(int caller) {
        return caller == 0 || caller == 1000 || context.checkCallingPermission(CONTROL) == PackageManager.PERMISSION_GRANTED;
    }
    void enforceControl(int caller) { if (!privileged(caller)) throw new SecurityException("CONTROL_VPN required"); }
    private void enforceUser(int caller, int user) {
        if (user < 0) throw new SecurityException("Invalid VPN user");
        if (caller / 100000 != user && caller != 0 && caller != 1000) {
            context.enforceCallingPermission("android.permission.INTERACT_ACROSS_USERS_FULL", "Cross-user VPN");
        }
    }
    private int packageUid(String pkg, int user) {
        try { return platform.userContext(context, user).getPackageManager().getApplicationInfo(pkg, 0).uid; }
        catch (PackageManager.NameNotFoundException e) { throw new IllegalArgumentException("VPN package not installed", e); }
    }
    private boolean consented(int uid, String pkg) { return appOps.unsafeCheckOpNoThrow(ACTIVATE, uid, pkg) == AppOpsManager.MODE_ALLOWED; }
    private Session session(int user) { return sessions.computeIfAbsent(user, Session::new); }

    private void denyProtect(int uid) {
        pendingProtectDenials.add(uid);
        try {
            platform.protect(uid, false);
            pendingProtectDenials.remove(uid);
        } finally {
            if (!pendingProtectDenials.isEmpty()) scheduleMonitor();
        }
    }

    synchronized boolean prepare(int caller, String oldPkg, String newPkg, int user) {
        requireReady();
        enforceUser(caller, user);
        boolean control = privileged(caller);
        long token = Binder.clearCallingIdentity();
        try {
            platform.enforceEligibleUser(context, user);
            if (!control) {
                if (oldPkg != null && packageUid(oldPkg, user) != caller) throw new SecurityException("VPN package UID mismatch");
                if (newPkg != null && !LEGACY.equals(newPkg) && packageUid(newPkg, user) != caller) throw new SecurityException("VPN package UID mismatch");
            }
            Session session = session(user);
            if (oldPkg != null) {
                int uid = LEGACY.equals(oldPkg) ? -1 : packageUid(oldPkg, user);
                if (uid < 0 || !consented(uid, oldPkg)) {
                    if (session.pkg.equals(oldPkg)) session.revoke();
                    return false;
                }
                if (session.uid != uid || !session.pkg.equals(oldPkg)) session.prepare(oldPkg, uid);
            }
            if (newPkg == null || session.pkg.equals(newPkg)) return true;
            if (!control) throw new SecurityException("CONTROL_VPN required to replace owner");
            if (LEGACY.equals(newPkg)) session.revoke();
            else session.prepare(newPkg, packageUid(newPkg, user));
            return true;
        } finally { Binder.restoreCallingIdentity(token); }
    }

    synchronized void authorize(int caller, String pkg, int user, int type) {
        enforceControl(caller);
        enforceUser(caller, user);
        long token = Binder.clearCallingIdentity();
        try {
            int uid = packageUid(pkg, user);
            if (type != -1 && type != 1 && type != 2) throw new IllegalArgumentException("Unsupported VPN authorization type");
            VpnPlatform.invoke(VpnPlatform.method(AppOpsManager.class, "setMode", String.class, int.class, String.class, int.class),
                    appOps, type == 2 ? "android:activate_platform_vpn" : ACTIVATE, uid, pkg,
                    type == -1 ? AppOpsManager.MODE_IGNORED : AppOpsManager.MODE_ALLOWED);
            if (type == -1) {
                VpnPlatform.invoke(VpnPlatform.method(AppOpsManager.class, "setMode", String.class, int.class, String.class, int.class),
                        appOps, "android:activate_platform_vpn", uid, pkg, AppOpsManager.MODE_IGNORED);
                Session s = sessions.get(user);
                if (s != null && s.pkg.equals(pkg)) s.revoke();
            }
        } finally { Binder.restoreCallingIdentity(token); }
    }

    synchronized ParcelFileDescriptor establish(int caller, Parcelable request) {
        requireReady();
        if (request == null) throw new IllegalArgumentException("VPN configuration is null");
        Session session = sessions.get(caller / 100000);
        if (session == null || session.uid != caller || !consented(caller, session.pkg)) return null;
        long token = Binder.clearCallingIdentity();
        try {
            platform.enforceEligibleUser(context, session.user);
            if (packageUid(session.pkg, session.user) != caller) throw new SecurityException("VPN owner was replaced");
            return session.establish(request);
        } finally { Binder.restoreCallingIdentity(token); }
    }

    synchronized boolean address(int caller, boolean add, String address, int prefix) {
        requireReady();
        Session session = owned(caller);
        if (session == null || session.config == null) return false;
        LinkAddress link = (LinkAddress) VpnPlatform.construct(LinkAddress.class,
                new Class<?>[] {java.net.InetAddress.class, int.class}, InetAddresses.parseNumericAddress(address), prefix);
        if (link.getAddress().isAnyLocalAddress() || link.getAddress().isLoopbackAddress()) throw new IllegalArgumentException("Invalid VPN address");
        long token = Binder.clearCallingIdentity();
        try {
            if (!platform.nativeVpn.address(add, session.iface, address, prefix)) return false;
            @SuppressWarnings("unchecked") List<LinkAddress> addresses = (List<LinkAddress>) VpnPlatform.field(session.config, "addresses");
            if (add && !addresses.contains(link)) addresses.add(link);
            if (!add) addresses.remove(link);
            VpnPlatform.invoke(VpnPlatform.method(platform.agentType, "sendLinkProperties", LinkProperties.class), session.agent, platform.links(session.config));
            return true;
        } finally { Binder.restoreCallingIdentity(token); }
    }

    synchronized boolean underlying(int caller, Network[] networks) {
        requireReady();
        Session session = owned(caller);
        if (session == null || session.config == null) return false;
        long token = Binder.clearCallingIdentity();
        try {
            VpnPlatform.field(session.config, "underlyingNetworks", networks == null ? null : networks.clone());
            VpnPlatform.invoke(VpnPlatform.method(platform.agentType, "setUnderlyingNetworks", List.class), session.agent,
                    networks == null ? null : java.util.Arrays.asList(networks));
            session.refreshCapabilities();
            return true;
        } finally { Binder.restoreCallingIdentity(token); }
    }

    private Session owned(int caller) {
        Session session = sessions.get(caller / 100000);
        if (session == null || session.uid != caller || !consented(caller, session.pkg)) return null;
        return session;
    }

    synchronized Parcelable getConfig(int caller, int user) {
        enforceControl(caller);
        enforceUser(caller, user);
        Session session = sessions.get(user);
        return session == null || session.config == null ? null : platform.copyConfig(session.config);
    }

    synchronized void factoryReset(int caller) {
        enforceControl(caller);
        long token = Binder.clearCallingIdentity();
        try { Session session = sessions.get(caller / 100000); if (session != null) session.revoke(); }
        finally { Binder.restoreCallingIdentity(token); }
    }

    void checkControlUser(int caller, int user) { enforceControl(caller); enforceUser(caller, user); }
    void checkStatusUser(int caller, int user, String permission) {
        if (caller != 0 && caller != 1000) context.enforceCallingPermission(permission, "VPN status permission");
        enforceUser(caller, user);
    }
    synchronized String status() { return "GWH app VPN service: ready=" + ready + ", users=" + sessions.size(); }

    private final class Session {
        final int user;
        String pkg = "";
        int uid = -1;
        Parcelable config;
        String iface;
        Object agent;
        Connection connection;
        boolean appOpStarted;
        Session(int user) { this.user = user; }

        void prepare(String pkg, int uid) {
            revoke();
            // A failed old-owner denial must be retried before allowing a new owner.
            for (int pending : new java.util.HashSet<>(pendingProtectDenials)) denyProtect(pending);
            platform.protect(uid, true);
            this.pkg = pkg;
            this.uid = uid;
            Log.i(TAG, "[WearVpn] prepare hit; owner authorized for user=" + user);
        }

        ParcelFileDescriptor establish(Parcelable request) {
            Parcelable config = platform.copyConfig(request);
            if ((Boolean) VpnPlatform.field(config, "legacy")) throw new UnsupportedOperationException("Legacy VPN is not supported");
            String serviceName = (String) VpnPlatform.field(config, "user");
            if (serviceName == null) throw new IllegalArgumentException("Missing VPN service class");
            Intent intent = new Intent("android.net.VpnService").setClassName(pkg, serviceName);
            Context scoped = platform.userContext(context, user);
            ResolveInfo info = scoped.getPackageManager().resolveService(intent, 0);
            if (info == null || info.serviceInfo == null || !info.serviceInfo.enabled ||
                    !"android.permission.BIND_VPN_SERVICE".equals(info.serviceInfo.permission) ||
                    info.serviceInfo.applicationInfo.uid != uid) throw new SecurityException("Invalid protected VPN service");
            @SuppressWarnings("unchecked") List<LinkAddress> addresses = (List<LinkAddress>) VpnPlatform.field(config, "addresses");
            if (addresses == null || addresses.isEmpty()) throw new IllegalArgumentException("VPN needs an address");
            for (LinkAddress address : addresses) if (address.getAddress().isAnyLocalAddress() || address.getAddress().isLoopbackAddress()) {
                throw new IllegalArgumentException("Invalid VPN address");
            }
            platform.ranges(context, user, config); // validate policy before creating a TUN
            ParcelFileDescriptor tun = ParcelFileDescriptor.adoptFd(platform.nativeVpn.create((Integer) VpnPlatform.field(config, "mtu")));
            String newIface = null;
            Object newAgent = null;
            Connection newConnection = new Connection(this);
            boolean bound = false, started = false;
            try {
                newIface = platform.nativeVpn.getName(tun.getFd());
                // The kernel can reuse a closed TUN name before our monitor runs.
                // Retire every stale agent for that name, including other users,
                // before assigning any addresses or routing to the new interface.
                for (Session previous : sessions.values()) {
                    if (newIface.equals(previous.iface)) previous.disconnect(previous != this, newIface);
                }
                StringBuilder addressText = new StringBuilder();
                for (LinkAddress address : addresses) addressText.append(' ').append(address);
                if (platform.nativeVpn.setAddresses(newIface, addressText.toString()) < 1) throw new IllegalArgumentException("VPN address setup failed");
                bound = platform.bind(context, intent, newConnection, user);
                if (!bound) throw new IllegalStateException("Could not bind protected VPN service");
                VpnPlatform.field(config, "user", pkg);
                VpnPlatform.field(config, "interfaze", newIface);
                VpnPlatform.field(config, "startTime", SystemClock.elapsedRealtime());
                NetworkCapabilities caps = platform.capabilities(context, user, uid, config);
                newAgent = platform.newAgent(context, thread.getLooper(), config, caps, provider);
                VpnPlatform.call(newAgent, "register");
                int flags = Os.fcntlInt(tun.getFileDescriptor(), OsConstants.F_GETFL, 0);
                Os.fcntlInt(tun.getFileDescriptor(), OsConstants.F_SETFL,
                        (Boolean) VpnPlatform.field(config, "blocking") ? flags & ~OsConstants.O_NONBLOCK : flags | OsConstants.O_NONBLOCK);
                if (appOps.startOpNoThrow(ESTABLISH, uid, pkg) != AppOpsManager.MODE_ALLOWED) throw new SecurityException("VPN establishment app-op denied");
                started = true;
                VpnPlatform.call(newAgent, "markConnected");
                // Publish the replacement only after it has registered successfully.
                disconnect(false, newIface);
                this.config = config;
                this.iface = newIface;
                this.agent = newAgent;
                this.connection = newConnection;
                this.appOpStarted = true;
                scheduleMonitor();
                Log.i(TAG, "[WearVpn] establish hit; TUN and VPN network registered for user=" + user);
                return tun;
            } catch (Throwable error) {
                Log.e(TAG, "[WearVpn] establish failed; rolling back own TUN, binding and network agent", error);
                if (started) try { appOps.finishOp(ESTABLISH, uid, pkg); } catch (Throwable cleanup) { error.addSuppressed(cleanup); }
                if (newAgent != null) try { VpnPlatform.call(newAgent, "unregister"); } catch (Throwable cleanup) { error.addSuppressed(cleanup); }
                if (bound) try { context.unbindService(newConnection); } catch (Throwable cleanup) { error.addSuppressed(cleanup); }
                if (newIface != null) try { platform.nativeVpn.reset(newIface); } catch (Throwable cleanup) { error.addSuppressed(cleanup); }
                try { tun.close(); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
                if (error instanceof RuntimeException) throw (RuntimeException) error;
                if (error instanceof Error) throw (Error) error;
                throw new IllegalStateException("VPN establishment failed", error);
            }
        }

        void refreshCapabilities() {
            if (agent != null && config != null) VpnPlatform.invoke(VpnPlatform.method(platform.agentType,
                    "sendNetworkCapabilities", NetworkCapabilities.class), agent, platform.capabilities(context, user, uid, config));
        }

        void disconnect(boolean notify) { disconnect(notify, null); }

        void disconnect(boolean notify, String replacementIface) {
            Object oldAgent = agent;
            String oldIface = iface;
            Connection oldConnection = connection;
            boolean finishOp = appOpStarted;
            agent = null; iface = null; connection = null; config = null; appOpStarted = false;
            if (oldAgent != null) cleanup("network agent", () -> VpnPlatform.call(oldAgent, "unregister"));
            if (oldConnection != null) {
                if (notify && oldConnection.remote != null) cleanup("revoke callback", () -> {
                    Parcel data = Parcel.obtain();
                    try { oldConnection.remote.transact(IBinder.LAST_CALL_TRANSACTION, data, null, IBinder.FLAG_ONEWAY); }
                    catch (android.os.RemoteException e) { throw new IllegalStateException(e); }
                    finally { data.recycle(); }
                });
                cleanup("service binding", () -> context.unbindService(oldConnection));
            }
            if (oldIface != null && !oldIface.equals(replacementIface)) cleanup("TUN reset", () -> platform.nativeVpn.reset(oldIface));
            if (finishOp) cleanup("establish app-op", () -> appOps.finishOp(ESTABLISH, uid, pkg));
        }

        void revoke() {
            disconnect(true);
            int oldUid = uid;
            uid = -1; pkg = "";
            if (oldUid >= 0) denyProtect(oldUid);
        }
    }

    private final class Connection implements ServiceConnection {
        final Session session;
        IBinder remote;
        Connection(Session session) { this.session = session; }
        @Override public void onServiceConnected(ComponentName name, IBinder service) { synchronized (WearVpnService.this) { remote = service; } }
        @Override public void onServiceDisconnected(ComponentName name) { disconnected(); }
        @Override public void onBindingDied(ComponentName name) { disconnected(); }
        @Override public void onNullBinding(ComponentName name) { disconnected(); }
        private void disconnected() {
            handler.post(() -> {
                synchronized (WearVpnService.this) {
                    if (session.connection == this) session.disconnect(false);
                }
            });
        }
    }

    private static void cleanup(String stage, Runnable operation) {
        try { operation.run(); }
        catch (Throwable error) { Log.e(TAG, "[WearVpn] own " + stage + " cleanup failed", error); }
    }
}
