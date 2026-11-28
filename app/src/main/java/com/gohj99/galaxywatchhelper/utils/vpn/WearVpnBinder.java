package com.gohj99.galaxywatchhelper.utils.vpn;

import android.net.Network;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.reflect.Field;

/** Wire-compatible with the audited platform IVpnManager; platform Parcelable classes stay in bootclasspath. */
@android.annotation.TargetApi(36)
final class WearVpnBinder extends Binder {
    private static final String DESCRIPTOR = "android.net.IVpnManager";
    private static final String[] TRANSACTIONS = {
        "prepareVpn", "setVpnPackageAuthorization", "establishVpn", "addVpnAddress", "removeVpnAddress",
        "setUnderlyingNetworksForVpn", "provisionVpnProfile", "deleteVpnProfile", "startVpnProfile",
        "stopVpnProfile", "getProvisionedVpnProfileState", "setAppExclusionList", "getAppExclusionList",
        "isAlwaysOnVpnPackageSupported", "setAlwaysOnVpnPackage", "getAlwaysOnVpnPackage", "isVpnLockdownEnabled",
        "getVpnLockdownAllowlist", "isCallerCurrentAlwaysOnVpnApp", "isCallerCurrentAlwaysOnVpnLockdownApp",
        "startLegacyVpn", "getLegacyVpnInfo", "updateLockdownVpn", "getFromVpnProfileStore",
        "putIntoVpnProfileStore", "removeFromVpnProfileStore", "listFromVpnProfileStore", "getVpnConfig", "factoryReset",
    };
    private final WearVpnService service;

    WearVpnBinder(WearVpnService service) throws ReflectiveOperationException {
        this.service = service;
        Class<?> stub = service.platform.type("android.net.IVpnManager$Stub");
        for (int i = 0; i < TRANSACTIONS.length; i++) {
            Field field = stub.getDeclaredField("TRANSACTION_" + TRANSACTIONS[i]);
            field.setAccessible(true);
            if (field.getInt(null) != i + 1) throw new IllegalStateException("IVpnManager transaction ABI changed");
        }
        // Returning no local IInterface makes even in-process platform callers use
        // their platform Proxy/Parcelable implementation, never a module-defined API copy.
        attachInterface(null, DESCRIPTOR);
    }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == INTERFACE_TRANSACTION) { if (reply == null) return false; reply.writeString(DESCRIPTOR); return true; }
        if (code < 1 || code > TRANSACTIONS.length) return super.onTransact(code, data, reply, flags);
        if (reply == null) return false; // this interface has no oneway transactions
        try {
            data.enforceInterface(DESCRIPTOR);
            int caller = Binder.getCallingUid();
            switch (code) {
                case 1: {
                    String oldPkg = data.readString(), newPkg = data.readString();
                    int user = data.readInt(); data.enforceNoDataAvail();
                    boolean result = service.prepare(caller, oldPkg, newPkg, user);
                    reply.writeNoException(); reply.writeBoolean(result); break;
                }
                case 2: {
                    String pkg = data.readString(); int user = data.readInt(), type = data.readInt();
                    data.enforceNoDataAvail(); service.authorize(caller, pkg, user, type);
                    reply.writeNoException(); break;
                }
                case 3: {
                    Parcelable config = data.readTypedObject(service.platform.configCreator);
                    data.enforceNoDataAvail();
                    ParcelFileDescriptor tun = service.establish(caller, config);
                    reply.writeNoException();
                    try { reply.writeTypedObject(tun, Parcelable.PARCELABLE_WRITE_RETURN_VALUE); }
                    finally { if (tun != null) try { tun.close(); } catch (java.io.IOException e) { Log.w(WearVpnService.TAG, "[WearVpn] return-fd close failed", e); } }
                    break;
                }
                case 4: case 5: {
                    String address = data.readString(); int prefix = data.readInt(); data.enforceNoDataAvail();
                    boolean result = service.address(caller, code == 4, address, prefix);
                    reply.writeNoException(); reply.writeBoolean(result); break;
                }
                case 6: {
                    Network[] networks = data.createTypedArray(Network.CREATOR); data.enforceNoDataAvail();
                    boolean result = service.underlying(caller, networks);
                    reply.writeNoException(); reply.writeBoolean(result); break;
                }
                case 14: {
                    int user = data.readInt(); data.readString(); data.enforceNoDataAvail();
                    service.checkStatusUser(caller, user, "android.permission.NETWORK_SETTINGS");
                    reply.writeNoException(); reply.writeBoolean(false); break;
                }
                case 16: case 17: case 18: case 22: {
                    int user = data.readInt(); data.enforceNoDataAvail();
                    if (code == 22) service.checkControlUser(caller, user);
                    else service.checkStatusUser(caller, user, "android.permission.CONTROL_ALWAYS_ON_VPN");
                    reply.writeNoException();
                    if (code == 16) reply.writeString(null);
                    else if (code == 17) reply.writeBoolean(false);
                    else if (code == 18) reply.writeStringList(java.util.Collections.emptyList());
                    else reply.writeTypedObject(null, 0);
                    break;
                }
                case 19: case 20: {
                    data.enforceNoDataAvail(); reply.writeNoException(); reply.writeBoolean(false); break;
                }
                case 23: {
                    data.enforceNoDataAvail(); service.enforceControl(caller);
                    reply.writeNoException(); reply.writeBoolean(false); break;
                }
                case 28: {
                    int user = data.readInt(); data.enforceNoDataAvail();
                    Parcelable result = service.getConfig(caller, user);
                    reply.writeNoException(); reply.writeTypedObject(result, 0); break;
                }
                case 29: {
                    data.enforceNoDataAvail(); service.factoryReset(caller); reply.writeNoException(); break;
                }
                default:
                    // Never claim successful IKEv2, legacy, profile storage or lockdown support.
                    throw new UnsupportedOperationException("GWH restores app VpnService only; " + TRANSACTIONS[code - 1] + " is unsupported");
            }
            return true;
        } catch (RuntimeException error) {
            reply.setDataSize(0); reply.setDataPosition(0); reply.writeException(error);
            Log.w(WearVpnService.TAG, "[WearVpn] " + TRANSACTIONS[code - 1] + " rejected: " + error.getClass().getSimpleName());
            return true;
        } catch (LinkageError error) {
            reply.setDataSize(0); reply.setDataPosition(0);
            reply.writeException(new IllegalStateException("GWH VPN platform ABI failed", error));
            Log.e(WearVpnService.TAG, "[WearVpn] own platform bridge failed", error);
            return true;
        }
    }

    @Override protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        service.enforceControl(Binder.getCallingUid());
        writer.println(service.status());
        writer.println("Supported: app VpnService, explicit app-op authorization. No platform/legacy profiles or lockdown.");
    }
}
