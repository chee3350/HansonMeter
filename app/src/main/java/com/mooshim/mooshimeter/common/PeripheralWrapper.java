/*
 * Copyright (c) Mooshim Engineering LLC 2015.
 *
 * This file is part of Mooshimeter-AndroidApp.
 *
 * Foobar is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Foobar is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with Mooshimeter-AndroidApp.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.mooshim.mooshimeter.common;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class PeripheralWrapper {
    private static final String TAG="PeripheralWrapper";
    private static final Lock bleLock= new ReentrantLock();

    private static final Lock conditionLock= new ReentrantLock();

    protected Context mContext;
    private BluetoothGatt mBluetoothGatt;
    private BluetoothDevice mDevice;
    private BluetoothGattCallback mGattCallbacks;
    protected Map<UUID,BluetoothGattService> mServices;
    protected Map<UUID,BluetoothGattCharacteristic> mCharacteristics;
    private Map<UUID,NotifyCallback> mNotifyCB;
    private final HashMap<Integer, List<Runnable>> mConnectionStateCB;
    private HashMap<Integer, Runnable> mConnectionStateCBByHandle;
    private List<Runnable> mRSSICallbacks;

    private int connectionStateCBHandle = 0;

    private StatLockManager bleStateCondition    ;
    private StatLockManager bleDiscoverCondition ;
    private StatLockManager bleReadCondition     ;
    private StatLockManager bleWriteCondition    ;
    private StatLockManager bleChangedCondition  ;
    private StatLockManager bleDReadCondition    ;
    private StatLockManager bleDWriteCondition   ;
    private StatLockManager bleRWriteCondition   ;
    private StatLockManager bleRSSICondition     ;

    public int mRssi;
    public int mConnectionState;

    public static abstract class NotifyCallback {
        public abstract void notify(double timestamp_utc, byte[] payload);
    }

    private class Interruptable implements Callable<Void> {
        public int mRval = 0;
        @Override
        public Void call() throws InterruptedException {
            return null;
        }
    }

    // Anything that has to do with the BluetoothGatt needs to go through here
    private int protectedCall(final Interruptable r) {
        Util.blockUntilRunOnMainThread(new Runnable() {
            @Override
            public void run() {
                try {
                    bleLock.lock();
                    conditionLock.lock();
                    r.call();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } finally {
                    conditionLock.unlock();
                    bleLock.unlock();
                }
            }
        });
        return r.mRval;
    }
    
    public PeripheralWrapper(final BluetoothDevice device, final Context context) {
        mContext = context;
        mDevice = device;
        mConnectionState = BluetoothProfile.STATE_DISCONNECTED;

        mCharacteristics   = new HashMap<UUID, BluetoothGattCharacteristic>();
        mServices          = new HashMap<UUID, BluetoothGattService>();
        mNotifyCB          = new HashMap<UUID, NotifyCallback>();
        mConnectionStateCB = new HashMap<Integer, List<Runnable>>();
        mConnectionStateCB.put(BluetoothProfile.STATE_DISCONNECTED,new ArrayList<Runnable>());
        mConnectionStateCB.put(BluetoothProfile.STATE_DISCONNECTING,new ArrayList<Runnable>());
        mConnectionStateCB.put(BluetoothProfile.STATE_CONNECTED,new ArrayList<Runnable>());
        mConnectionStateCB.put(BluetoothProfile.STATE_CONNECTING,new ArrayList<Runnable>());
        mConnectionStateCBByHandle = new HashMap<Integer, Runnable>();

        bleStateCondition    = new StatLockManager(conditionLock);
        bleDiscoverCondition = new StatLockManager(conditionLock);
        bleReadCondition     = new StatLockManager(conditionLock);
        bleWriteCondition    = new StatLockManager(conditionLock);
        bleChangedCondition  = new StatLockManager(conditionLock);
        bleDReadCondition    = new StatLockManager(conditionLock);
        bleDWriteCondition   = new StatLockManager(conditionLock);
        bleRWriteCondition   = new StatLockManager(conditionLock);
        bleRSSICondition     = new StatLockManager(conditionLock);
        
        mGattCallbacks = new BluetoothGattCallback() {
            @Override public void onServicesDiscovered(BluetoothGatt g, int stat)                                 { Log.d(TAG,"GATTCB:DISCOVER");bleDiscoverCondition.l(stat);               bleDiscoverCondition.sig(); bleDiscoverCondition.ul();}
            @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int stat)  { Log.d(TAG,"GATTCB:READ");    bleReadCondition    .l(stat);               bleReadCondition    .sig(); bleReadCondition    .ul();}
            @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int stat) { Log.d(TAG,"GATTCB:WRITE");   bleWriteCondition   .l(stat);               bleWriteCondition   .sig(); bleWriteCondition   .ul();}
            @Override public void onDescriptorRead(BluetoothGatt g, BluetoothGattDescriptor d, int stat)          { Log.d(TAG,"GATTCB:DREAD");   bleDReadCondition   .l(stat);               bleDReadCondition   .sig(); bleDReadCondition   .ul();}
            @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int stat)         { Log.d(TAG,"GATTCB:DWRITE");  bleDWriteCondition  .l(stat);               bleDWriteCondition  .sig(); bleDWriteCondition  .ul();}
            @Override public void onReliableWriteCompleted(BluetoothGatt g, int stat)                             { Log.d(TAG,"GATTCB:RWRITE");  bleRWriteCondition  .l(stat);               bleRWriteCondition  .sig(); bleRWriteCondition  .ul();}
            @Override public void onReadRemoteRssi(BluetoothGatt g, int rssi, int stat)                           { Log.d(TAG,"GATTCB:RSSI");    bleRSSICondition    .l(stat); mRssi = rssi; bleRSSICondition    .sig(); bleRSSICondition    .ul();}
            @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c)         { Log.d(TAG,"GATTCB:CCHANGE");
                bleChangedCondition .l(0);
                final byte[] val = c.getValue();
                // The BLE stack sometimes gives us a null here, unclear why.
                if( val != null ) {
                    final NotifyCallback cb = mNotifyCB.get(c.getUuid());
                    if (cb != null) {
                        final byte[] payload = val.clone();
                        final double timestamp = Util.getNanoTime();
                        Util.dispatch(new Runnable() {
                            @Override
                            public void run() {
                                cb.notify(timestamp,payload);
                            }
                        });
                    }
                }
                bleChangedCondition .ul();
            }
            @Override
            public void onConnectionStateChange(BluetoothGatt g, int stat, int newState) {
                Log.d(TAG,"GATTCB:CONN");
                bleStateCondition   .l(stat);
                mConnectionState = newState;
                synchronized (mConnectionStateCB) {
                    List<Runnable> cbs = mConnectionStateCB.get(mConnectionState);
                    for(Runnable cb : cbs) {
                        Util.dispatch(cb);
                    }
                }
                bleStateCondition   .sig();
                bleStateCondition   .ul();
            }
        };
    }

    public int addConnectionStateCB(int state,Runnable cb) {
        synchronized (mConnectionStateCB) {
            List<Runnable> l = mConnectionStateCB.get(state);
            l.add(cb);
            mConnectionStateCBByHandle.put(connectionStateCBHandle, cb);
        }
        connectionStateCBHandle++;
        return connectionStateCBHandle;
    }

    public void cancelConnectionStateCB(int handle) {
        synchronized (mConnectionStateCB) {
            Runnable cb = mConnectionStateCBByHandle.get(handle);
            mConnectionStateCBByHandle.remove(handle);
            if (cb != null) {
                for (List<Runnable> l : mConnectionStateCB.values()) {
                    for (Runnable r : l) {
                        if (r == cb) {
                            l.remove(r);
                            return;
                        }
                    }
                }
            }
        }
    }

    public boolean isConnected() {
        return (mConnectionState == BluetoothProfile.STATE_CONNECTED);
    }

    public boolean isConnecting() {
        return (mConnectionState == BluetoothProfile.STATE_CONNECTING);
    }

    public boolean isDisconnected() {
        return ((mConnectionState == BluetoothProfile.STATE_DISCONNECTED) || (mConnectionState == BluetoothProfile.STATE_DISCONNECTING));
    }

    public BluetoothGattCharacteristic getChar(UUID uuid) {
        return mCharacteristics.get(uuid);
    }

    public int connect() {
        if( isConnected() || isConnecting()) {
            return 0;
        }

        protectedCall(new Interruptable() {
            @Override
            public Void call() throws InterruptedException {
                if(BluetoothAdapter.getDefaultAdapter().isDiscovering()) {
                    Log.e(TAG,"Trying to connect while the adapter is discovering!  Going to cancel discovery.");
                    BluetoothAdapter.getDefaultAdapter().cancelDiscovery();
                }
                // Try to connect
                Log.d(TAG,"CONNECTGATT");
                mBluetoothGatt = mDevice.connectGatt(mContext.getApplicationContext(),false,mGattCallbacks);
                refreshDeviceCache();
                return null;
                /*
                // Start a periodic RSSI poller
                Util.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        protectedCall(new Interruptable() {
                            @Override
                            public Void call() throws InterruptedException {
                                if (isConnected()) {
                                    mBluetoothGatt.readRemoteRssi();
                                    if(bleRSSICondition.awaitMilli(500)) {
                                        Log.e(TAG, "RSSI read timed out!");
                                    }
                                }
                                return null;
                            }
                        });
                        Util.postDelayed(this,5000);
                    }
                }, 5000);*/
            }
        });

        while (!isConnected()) {
            //If we time out in connection or the connect routine returns an error
            if (bleStateCondition.awaitMilli(5000) ) {
                return -1;
            }
            if (bleStateCondition.stat != 0) {
                return bleStateCondition.stat;
            }
        }
        return 0;
    }

    public int discover() {
        if(!isConnected()) {
            new Exception().printStackTrace();
            return -1;
        }
        protectedCall(new Interruptable() {
            @Override
            public Void call() throws InterruptedException {
                // Discover services
                Log.d(TAG,"DISCOVER");
                mBluetoothGatt.discoverServices();
                return null;
            }
        });
        if(bleDiscoverCondition.awaitMilli(5000)) {
            // Timed out
            return -1;
        }
        // Build a local dictionary of all characteristics and their UUIDs
        for (BluetoothGattService s : mBluetoothGatt.getServices()) {
            mServices.put(s.getUuid(), s);
            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                mCharacteristics.put(c.getUuid(), c);
            }
        }
        return bleDiscoverCondition.stat;
    }

    public int disconnect() {
        if(isDisconnected()) {
            new Exception().printStackTrace();
            return 0;
        }
        return protectedCall(new Interruptable() {
            @Override
            public Void call() throws InterruptedException {
                Log.d(TAG, "DISCONNECT");
                mBluetoothGatt.disconnect();
                while (mConnectionState != BluetoothProfile.STATE_DISCONNECTED) {
                    bleStateCondition.await();
                }
                return null;
            }
        });
    }

    public byte[] req(UUID uuid) {
        if(!isConnected()) {
            Log.e(TAG,"Trying to read from a disconnected peripheral");
            new Exception().printStackTrace();
            return null;
        }
        final BluetoothGattCharacteristic c = getChar(uuid);
        protectedCall(new Interruptable() {
            @Override
            public Void call() throws InterruptedException {
                Log.d(TAG,"READ");
                mBluetoothGatt.readCharacteristic(c);
                if(bleReadCondition.awaitMilli(1000)) {
                    mRval = -1;
                } else {
                    mRval = bleReadCondition.stat;
                }
                return null;
            }
        });
        return c.getValue();
    }

    public int send(final UUID uuid, final byte[] value) {
        if(!isConnected()) {
            Log.e(TAG,"Trying to send to a disconnected peripheral");
            new Exception().printStackTrace();
            return -1;
        }
        final BluetoothGattCharacteristic c = getChar(uuid);
        return protectedCall(new Interruptable() {
            @Override
            public Void call() throws InterruptedException {
                Log.d(TAG, "WRITE");
                c.setValue(value);
                mBluetoothGatt.writeCharacteristic(c);
                if (bleWriteCondition.awaitMilli(1000)) {
                    mRval = -1;
                } else {
                    mRval = bleWriteCondition.stat;
                }
                return null;
            }
        });
    }

    public NotifyCallback getNotificationCallback(UUID uuid) {
        return mNotifyCB.get(uuid);
    }

    public boolean isNotificationEnabled(UUID uuid) {
        if(!isConnected()) {
            Log.e(TAG,"Trying to read notification on a disconnected peripheral");
            new Exception().printStackTrace();
            return false;
        }
        BluetoothGattCharacteristic c = getChar(uuid);
        if(c == null) {
            Log.e(TAG, "Asked for a characteristic that doesn't exist!");
            return false;
        }
        final BluetoothGattDescriptor d = c.getDescriptor(GattInfo.CLIENT_CHARACTERISTIC_CONFIG);
        final byte[] dval = d.getValue();
        return (dval == BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
    }

    private HashMap<UUID,Boolean> notification_disable_preempted = new HashMap<UUID, Boolean>();

    private int enableNotifyDirect(final UUID uuid, final boolean enable) {
        final BluetoothGattCharacteristic c = getChar(uuid);
        return protectedCall(new Interruptable() {
            @Override
            public Void call() throws InterruptedException {
                // Only bother setting the notification if the status has changed
                if (mBluetoothGatt.setCharacteristicNotification(c, enable)) {
                    final BluetoothGattDescriptor clientConfig = c.getDescriptor(GattInfo.CLIENT_CHARACTERISTIC_CONFIG);
                    final byte[] enable_val = enable?BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE:BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
                    while(!clientConfig.setValue(enable_val)) {
                        Log.e(TAG, "setValue Fail!");
                    }
                    Log.d(TAG, "DWRITE");
                    mBluetoothGatt.writeDescriptor(clientConfig);
                    if(bleDWriteCondition.awaitMilli(1000)) {
                        mRval = -1;
                    } else {
                        mRval = bleDWriteCondition.stat;
                    }
                } else {
                    mRval = 0;
                }
                return null;
            }
        });
    }

    public int enableNotify(final UUID uuid, final boolean enable, final NotifyCallback on_notify) {
        if(!isConnected()) {
            Log.e(TAG,"Trying to set notification on a disconnected peripheral");
            new Exception().printStackTrace();
            return -1;
        }
        // Set up the notify callback
        if(on_notify != null) {
            mNotifyCB.put(uuid, on_notify);
        } else {
            mNotifyCB.remove(uuid);
        }
        if(enable) {
            notification_disable_preempted.put(uuid,Boolean.TRUE);
        }
        if(isNotificationEnabled(uuid) != enable) {
            if(enable==true) {
                // Enable immediately
                enableNotifyDirect(uuid,true);
            } else {
                // Disable only after a delay
                notification_disable_preempted.put(uuid,Boolean.FALSE);
                Util.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if(notification_disable_preempted.get(uuid).equals(Boolean.TRUE)) {
                            // If the disable was preempted, don't disable
                            return;
                        }
                        enableNotifyDirect(uuid, false);
                    }
                }, 3000);
            }
        }
        return 0;
    }

    public String getAddress() {
        return mDevice.getAddress();
    }

    public BluetoothDevice getBLEDevice() {
        return mDevice;
    }

    private boolean refreshDeviceCache(){
        // Forces the BluetoothGATT layer to dump what it knows about the connected device
        // If this is not called during connection, the GATT layer will simply return the last cached
        // services and refuse to do the service discovery process.
        try {
            Method localMethod = mBluetoothGatt.getClass().getMethod("refresh", new Class[0]);
            if (localMethod != null) {
                final boolean b = ((Boolean) localMethod.invoke(mBluetoothGatt, new Object[0])).booleanValue();
                return b;
            } else {
                Log.e(TAG, "Unable to wipe the GATT Cache");
            }
        }
        catch (Exception localException) {
            Log.e(TAG, "An exception occured while refreshing device");
        }
        return false;
    }
}