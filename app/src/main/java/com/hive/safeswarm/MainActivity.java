package com.hive.safeswarm;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import dji.sdk.base.BaseProduct;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = MainActivity.class.getName();
    Button deployButton;
    protected BroadcastReceiver mReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            infoRefresh();
        }
    };
    Button summonButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.VIBRATE,
                        Manifest.permission.INTERNET, Manifest.permission.ACCESS_WIFI_STATE,
                        Manifest.permission.WAKE_LOCK, Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.CHANGE_WIFI_STATE, Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS,
                        Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.SYSTEM_ALERT_WINDOW,
                        Manifest.permission.READ_PHONE_STATE,
                }
                , 1);
        setContentView(R.layout.activity_main);

        // Locate the deployButton in activity_main.xml
        deployButton = findViewById(R.id.MyButton);

        // Capture deployButton clicks
        deployButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View arg0) {

                // Start NewActivity.class
                Intent myIntent = new Intent(MainActivity.this,Deployment.class);
                startActivity(myIntent);
            }
        });

        deployButton.setEnabled(false);

        // Locate the summonButton in activity_main.xml
        summonButton = findViewById(R.id.MySummonButton);

        // Capture summonButton clicks
        summonButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View arg0) {

                // Start NewActivity.class
                Intent myIntent = new Intent(MainActivity.this, Summon.class);
                startActivity(myIntent);
            }
        });

        summonButton.setEnabled(true);

        // Register the broadcast receiver for receiving the device connection's changes.
        IntentFilter filter = new IntentFilter();
        filter.addAction(SafeSwarmApplication.FLAG_CONNECTION_CHANGE);
        registerReceiver(mReceiver, filter);
    }

    private void infoRefresh() {
        BaseProduct mProduct = SafeSwarmApplication.getProductInstance();

        if (null != mProduct && mProduct.isConnected()) {
            Log.v(TAG, "refreshSDK: True");
            deployButton.setEnabled(true);
            Toast.makeText(getApplicationContext(), "ALERT: All systems nominal. Ready to launch.", Toast.LENGTH_LONG).show();
            Log.v(TAG, "ALERT: All systems nominal. Ready to launch.");
        } else {
            Log.v(TAG, "refreshSDK: False");
            deployButton.setEnabled(false);
            Log.e(TAG, "WARNING: System connection is not ready. We cannot deploy.");
        }
    }

    @Override
    public void onResume() {
        Log.v(TAG, "onResume");
        super.onResume();
    }

    @Override
    public void onPause() {
        Log.v(TAG, "onPause");
        super.onPause();
    }

    @Override
    public void onStop() {
        Log.v(TAG, "onStop");
        super.onStop();
    }

    public void onReturn(View view) {
        Log.v(TAG, "onReturn");
        this.finish();
    }

    @Override
    protected void onDestroy() {
        Log.v(TAG, "onDestroy");
        unregisterReceiver(mReceiver);
        super.onDestroy();
    }

}