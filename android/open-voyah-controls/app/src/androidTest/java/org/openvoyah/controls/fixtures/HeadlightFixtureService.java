package org.openvoyah.controls.fixtures;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;

/** Independent test-APK process. Its only data is a fixed set of synthetic observations. */
public final class HeadlightFixtureService extends Service {
    public static final String DRIVER_ACTION = "org.openvoyah.controls.test.HEADLIGHT_DRIVER";
    public static final String SOURCE_ACTION = "org.openvoyah.controls.test.HEADLIGHT_SOURCE";
    public static final int REGISTERED = 1;
    public static final int IGNORED_SENT = 2;
    public static final int REMOVED = 3;
    public static final int SEND_IGNORED = 4;
    public static final int SEND_POSITION = 5;
    public static final int CONNECT_REPORTER = 6;
    public static final int DRIVER_READY = 7;
    public static final int SEND_RETIRED = 8;
    public static final int RETIRED_SENT = 9;
    public static final int SEND_LOW_BEAM = 10;
    public static final int SEND_AUTOMATIC = 11;
    private Session session;

    @Override public void onCreate() {
        super.onCreate();
        session = new Session();
    }

    @Override public IBinder onBind(Intent intent) {
        if (!("ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE))
                || !Build.PRODUCT.startsWith("sdk_")) return null;
        if (DRIVER_ACTION.equals(intent.getAction())) return session.commands.getBinder();
        if (SOURCE_ACTION.equals(intent.getAction())) return session;
        return null;
    }

    private final class Session extends Binder {
        private static final String SERVICE = "com.qinggan.canbus.ICanBusService";
        private static final String CALLBACK = "com.qinggan.canbus.ICanBusServiceCallback";
        private Messenger reports;
        private final Messenger commands = new Messenger(new Handler(Looper.getMainLooper(), this::onCommand));
        private IBinder observer;
        private IBinder retiredObserver;
        private int clientUid = -1;
        private int driverUid = -1;

        Session() {
            attachInterface(null, SERVICE);
        }

        @Override protected synchronized boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags);
            if ((code != 28 && code != 29) || (flags & IBinder.FLAG_ONEWAY) != 0 || reply == null) return false;
            int caller = Binder.getCallingUid();
            if (caller != driverUid || reports == null) return false;
            data.enforceInterface(SERVICE);
            IBinder requested = data.readStrongBinder();
            if (requested == null || data.dataAvail() != 0) return false;

            boolean accepted;
            if (code == 28) {
                accepted = observer == null;
                if (accepted) {
                    observer = requested;
                    clientUid = caller;
                    report(REGISTERED, clientUid);
                }
            } else {
                accepted = caller == clientUid && requested.equals(observer);
                if (accepted) {
                    retiredObserver = observer;
                    observer = null;
                    report(REMOVED, clientUid);
                }
            }
            reply.writeNoException();
            reply.writeInt(accepted ? 1 : 0);
            return true;
        }

        private synchronized boolean onCommand(Message message) {
            try {
                if (message.what == CONNECT_REPORTER && message.replyTo != null && observer == null) {
                    int sender = message.sendingUid;
                    if (sender == Process.myUid() || getPackageManager().checkSignatures(sender, Process.myUid())
                            != PackageManager.SIGNATURE_MATCH) return true;
                    driverUid = sender;
                    reports = message.replyTo;
                    report(DRIVER_READY, sender);
                    return true;
                }
                if (message.what == SEND_RETIRED && message.sendingUid == driverUid && retiredObserver != null) {
                    // A packet already addressed to a previous listener may reach it after disconnection.
                    callback(retiredObserver, 36, 897, 1114, 3);
                    report(RETIRED_SENT, driverUid);
                    return true;
                }
                if (message.sendingUid != clientUid || observer == null) return true;
                if (message.what == SEND_IGNORED) {
                    callback(observer, 40, 897, 1114, 1);
                    callback(observer, 41, 897, 1114, 1);
                    callback(observer, 36, 898, 1115, 1);
                    report(IGNORED_SENT, clientUid);
                } else if (message.what == SEND_POSITION) {
                    callback(observer, 36, 897, 1114, 2);
                } else if (message.what == SEND_LOW_BEAM) {
                    callback(observer, 36, 897, 1114, 1);
                } else if (message.what == SEND_AUTOMATIC) {
                    callback(observer, 36, 897, 1114, 0);
                }
            } catch (RemoteException lostClient) {
                observer = null;
            }
            return true;
        }

        private void callback(IBinder recipient, int code, int ordinal, int property, int value) throws RemoteException {
            Parcel packet = Parcel.obtain();
            try {
                packet.writeInterfaceToken(CALLBACK);
                packet.writeInt(1);
                packet.writeInt(ordinal);
                packet.writeInt(property);
                packet.writeInt(value);
                recipient.transact(code, packet, null, IBinder.FLAG_ONEWAY);
            } finally {
                packet.recycle();
            }
        }

        private void report(int event, int callerUid) throws RemoteException {
            Message message = Message.obtain(null, event, Process.myPid(), callerUid);
            reports.send(message);
        }
    }
}
