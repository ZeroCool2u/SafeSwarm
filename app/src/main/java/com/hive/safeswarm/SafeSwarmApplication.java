package com.hive.safeswarm;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.support.multidex.MultiDex;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.widget.Toast;

import dji.common.error.DJIError;
import dji.common.error.DJISDKError;
import dji.sdk.base.BaseComponent;
import dji.sdk.base.BaseProduct;
import dji.sdk.sdkmanager.DJISDKManager;

/**
 * Created by Theo on 11/19/17.
 */

public class SafeSwarmApplication extends Application {
    public static final String FLAG_CONNECTION_CHANGE = "dji_sdk_connection_change";
    private static final String TAG = SafeSwarmApplication.class.getName();
    private static BaseProduct mProduct;

    private Handler mHandler;
    private Runnable updateRunnable = new Runnable() {

        @Override
        public void run() {
            Intent intent = new Intent(FLAG_CONNECTION_CHANGE);
            sendBroadcast(intent);
        }
    };
    private BaseComponent.ComponentListener mDJIComponentListener = new BaseComponent.ComponentListener() {

        @Override
        public void onConnectivityChange(boolean isConnected) {
            notifyStatusChange();
        }

    };
    private BaseProduct.BaseProductListener mDJIBaseProductListener = new BaseProduct.BaseProductListener() {

        @Override
        public void onComponentChange(BaseProduct.ComponentKey key, BaseComponent oldComponent, BaseComponent newComponent) {
            if (newComponent != null) {
                newComponent.setComponentListener(mDJIComponentListener);
            }
            notifyStatusChange();
        }

        @Override
        public void onConnectivityChange(boolean isConnected) {
            notifyStatusChange();
        }

    };
    private DJISDKManager.SDKManagerCallback mDJISDKManagerCallback = new DJISDKManager.SDKManagerCallback() {

        @Override
        public void onRegister(DJIError error) {

            Log.d(TAG, error == null ? "Success" : error.getDescription());
            if (error == DJISDKError.REGISTRATION_SUCCESS) {
                DJISDKManager.getInstance().startConnectionToProduct();
                Handler handler = new Handler(Looper.getMainLooper());
                handler.post(new Runnable() {

                    @Override
                    public void run() {
                        Toast.makeText(getApplicationContext(), "ALERT: Registration success!", Toast.LENGTH_LONG).show();
                    }
                });
                Log.d(TAG, "Registration success");

            } else {
                Handler handler = new Handler(Looper.getMainLooper());
                handler.post(new Runnable() {

                    @Override
                    public void run() {
                        Toast.makeText(getApplicationContext(), "WARNING: SDK registration failed, check if network is available.", Toast.LENGTH_LONG).show();
                    }
                });

                Log.d(TAG, "Registration failed");

            }
            Log.e(TAG, error == null ? "Success" : error.getDescription());
        }

        @Override
        public void onProductChange(BaseProduct oldProduct, BaseProduct newProduct) {

            mProduct = newProduct;
            if (mProduct != null) {
                mProduct.setBaseProductListener(mDJIBaseProductListener);
            }

            notifyStatusChange();
        }
    };

    public static synchronized BaseProduct getProductInstance() {
        if (null == mProduct) {
            mProduct = DJISDKManager.getInstance().getProduct();
        }
        return mProduct;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mHandler = new Handler(Looper.getMainLooper());

        //Check the permissions before registering the application for android system 6.0 above.
        int permissionCheck = ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        int permissionCheck2 = ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || (permissionCheck == 0 && permissionCheck2 == 0)) {
            Log.e(TAG, "Attempting registration now.");
            //This is used to start SDK services and initiate SDK.
            DJISDKManager.getInstance().registerApp(this, mDJISDKManagerCallback);
        } else {
            Log.e(TAG, "Registration failed due to permissions.");
            Toast.makeText(getApplicationContext(), "WARNING: Yeah, we're gonna need those permissions.", Toast.LENGTH_LONG).show();
        }

    }

    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        MultiDex.install(this);
    }

    private void notifyStatusChange() {
        mHandler.removeCallbacks(updateRunnable);
        mHandler.postDelayed(updateRunnable, 500);
    }

}
