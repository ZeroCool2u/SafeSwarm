package com.hive.safeswarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dji.common.error.DJIError;
import dji.common.flightcontroller.FlightControllerState;
import dji.common.mission.waypoint.Waypoint;
import dji.common.mission.waypoint.WaypointMission;
import dji.common.mission.waypoint.WaypointMissionDownloadEvent;
import dji.common.mission.waypoint.WaypointMissionExecutionEvent;
import dji.common.mission.waypoint.WaypointMissionFinishedAction;
import dji.common.mission.waypoint.WaypointMissionFlightPathMode;
import dji.common.mission.waypoint.WaypointMissionHeadingMode;
import dji.common.mission.waypoint.WaypointMissionUploadEvent;
import dji.common.useraccount.UserAccountState;
import dji.common.util.CommonCallbacks;
import dji.sdk.base.BaseProduct;
import dji.sdk.flightcontroller.FlightController;
import dji.sdk.mission.waypoint.WaypointMissionOperator;
import dji.sdk.mission.waypoint.WaypointMissionOperatorListener;
import dji.sdk.products.Aircraft;
import dji.sdk.sdkmanager.DJISDKManager;
import dji.sdk.useraccount.UserAccountManager;

public class Deployment extends AppCompatActivity {

    private static final String TAG = MainActivity.class.getName();
    public static WaypointMission.Builder waypointMissionBuilder;
    private static boolean FIRST_TRY = true;
    private static Waypoint initWaypoint, midWaypoint;
    private static Location targetLocation = new Location("fused");
    private DJISDKManager SDKMan = DJISDKManager.getInstance();
    private BaseProduct mProduct;
    private double droneLocationLat, droneLocationLng;
    //These values are in units of meters and meters/second respectively.
    private float altitude = 10.0f;
    private float mSpeed = 9.0f;
    private List<Waypoint> waypointList = new ArrayList<>();
    private FlightController mFlightController;
    protected BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            onProductConnectionChange();
        }
    };
    private LatLng startLatLng, endLatLng, midLatLng;
    private WaypointMissionOperator instance;
    private WaypointMissionFinishedAction mFinishedAction = WaypointMissionFinishedAction.GO_HOME;
    private WaypointMissionHeadingMode mHeadingMode = WaypointMissionHeadingMode.AUTO;
    private FirebaseDatabase fbDataBase;
    private DatabaseReference myRef;
    private WaypointMissionOperatorListener eventNotificationListener = new WaypointMissionOperatorListener() {
        @Override
        public void onDownloadUpdate(WaypointMissionDownloadEvent downloadEvent) {

        }

        @Override
        public void onUploadUpdate(WaypointMissionUploadEvent uploadEvent) {
            if (uploadEvent.getProgress() == null) {
                Log.v(TAG, "WARNING: Null upload event implies there was an error!");
            } else {
                Log.v(TAG, "ALERT: Boolean summary is uploaded: " + uploadEvent.getProgress().isSummaryUploaded);
                Log.v(TAG, "ALERT: Index number of waypoints uploaded (-1 if none): " + uploadEvent.getProgress().uploadedWaypointIndex);
            }
        }

        @Override
        public void onExecutionUpdate(WaypointMissionExecutionEvent executionEvent) {

        }

        @Override
        public void onExecutionStart() {
            Log.v(TAG, "Mission execution started!");

        }

        //TODO: We need to further flesh out the logic here for after the mission is executed. Note that if it's successful, the drone has already gone home.
        @Override
        public void onExecutionFinish(@Nullable final DJIError error) {
            Toast.makeText(getApplicationContext(), "Execution finished: " + (error == null ? "Success!" : error.getDescription()), Toast.LENGTH_LONG).show();
            if (error != null) {
                Log.e(TAG, "WARNING: Mission execution failure!");
                if (mFlightController != null) {
                    mFlightController.startGoHome(new CommonCallbacks.CompletionCallback() {
                        @Override
                        public void onResult(DJIError djiError) {
                            Toast.makeText(getApplicationContext(), "Go Home Status: " + (djiError == null ? "Success!" : djiError.getDescription()), Toast.LENGTH_LONG).show();
                        }
                    });
                } else {
                    Log.e(TAG, "Mission execution failure and FC null, reiniting FC now.");
                    initFlightController();

                }
            } else {
                Log.e(TAG, "Mission execution success, now landing.");
                beginLanding();
            }
        }
    };

    //Utility function for calculating the midpoint between the target location and the drones
    //start location, so that we can provide the minimum required N Waypoints s.t. N > 1.
    private static LatLng midWayPoint(LatLng start, LatLng dest) {
        LatLngBounds tempBound = new LatLngBounds(start, dest);
        return tempBound.getCenter();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_deployment);

        IntentFilter filter = new IntentFilter();
        filter.addAction(SafeSwarmApplication.FLAG_CONNECTION_CHANGE);
        registerReceiver(mReceiver, filter);

        addListener();

        // Get a reference to our posts
        final FirebaseDatabase database = FirebaseDatabase.getInstance();
        DatabaseReference ref = database.getReference("users/1/");

        ref.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                //targetLocation = dataSnapshot.getValue(Location.class);
                Map<String, Object> targetLocation2 = (Map<String, Object>) dataSnapshot.getValue();
                targetLocation.setLatitude(Double.parseDouble(targetLocation2.get("latitude").toString()));
                targetLocation.setLongitude(Double.parseDouble(targetLocation2.get("longitude").toString()));
                targetLocation.setAltitude(Double.parseDouble(targetLocation2.get("altitude").toString()));
                //Toast.makeText(getApplicationContext(), "ALERT: Target location updated.", Toast.LENGTH_LONG).show();
                if (FIRST_TRY) {
                    Log.v(TAG, "Starting first attempt!");
                    loginAccount();
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                Toast.makeText(getApplicationContext(), "WARNING: Target location update failed. Retrying now.", Toast.LENGTH_LONG).show();
            }
        });


    }

    private void beginTakeOff() {
        if (mFlightController != null) {
            mFlightController.startTakeoff(
                    new CommonCallbacks.CompletionCallback() {
                        @Override
                        public void onResult(DJIError djiError) {
                            if (djiError != null) {
                                Toast.makeText(getApplicationContext(), "WARNING: Takeoff failed!", Toast.LENGTH_LONG).show();
                                Log.v(TAG, "Takeoff Failed");
                            } else {
                                Toast.makeText(getApplicationContext(), "ALERT: Takeoff success!", Toast.LENGTH_LONG).show();
                                Log.v(TAG, "Takeoff Success");
                                //TODO: We need to figure out how to pause here before calling the upload function until we're ACTUALLY done taking off.
                                while (mFlightController.getState().getAircraftLocation().getAltitude() < 1.2) {
                                    try {
                                        Log.v(TAG, "In the loop, state is not flying currently.");
                                        Thread.sleep(500);
                                    } catch (InterruptedException e) {
                                        e.printStackTrace();
                                    }
                                }
                                Log.v(TAG, "ALERT: We are in flight!!!");
                                Toast.makeText(getApplicationContext(), "ALERT: WE ARE FLYING!", Toast.LENGTH_LONG).show();
                                uploadWayPointMission();
                            }
                        }
                    }
            );
        } else {
            Log.e(TAG, "Takeoff Failure due to null FC!");
/*            Toast.makeText(getApplicationContext(), "WARNING: Takeoff failed due to null FC object!", Toast.LENGTH_LONG).show();
            Toast.makeText(getApplicationContext(), "ALERT: Attempting to reinit the FC!", Toast.LENGTH_LONG).show();
            initFlightController();
            Toast.makeText(getApplicationContext(), "ALERT: FC reinitialized, attempting takeoff again!", Toast.LENGTH_LONG).show();
            //TODO: Watch the fuck out, there's some infinite loop potential here and there is a path in the next function that resolves in a callback.
            beginTakeOff();*/
        }
    }

    //TODO: beginLanding and completeLanding need some additional logic to ensure the drone eventually lands if we fail a few times.
    //TODO: Also need to decide where/how to actually use this.
    private void beginLanding() {
        if (mFlightController != null) {
            mFlightController.startLanding(
                    new CommonCallbacks.CompletionCallback() {
                        @Override
                        public void onResult(DJIError djiError) {
                            if (djiError != null) {
                                Toast.makeText(getApplicationContext(), "WARNING: Landing start failed! Error: " + djiError.getDescription(), Toast.LENGTH_LONG).show();
                            } else {
                                Toast.makeText(getApplicationContext(), "ALERT: Landing in progress.", Toast.LENGTH_LONG).show();
                                completeLanding();
                            }
                        }
                    }
            );
        } else {
            initFlightController();
            //TODO: Watch the fuck out again for this recursion! Similar caution should be taken to the beginTakeOff TODO above.
            beginLanding();
        }
    }

    private void completeLanding() {
        if (mFlightController != null) {
            mFlightController.confirmLanding(new CommonCallbacks.CompletionCallback() {
                @Override
                public void onResult(DJIError djiError) {
                    if (djiError != null) {
                        Toast.makeText(getApplicationContext(), "WARNING: Landing failed! Error: " + djiError.getDescription(), Toast.LENGTH_LONG).show();
                        Log.e(TAG, "Landing failed with error: " + djiError.getDescription());
                    } else {
                        Toast.makeText(getApplicationContext(), "ALERT: Landing complete!", Toast.LENGTH_LONG).show();
                        Log.e(TAG, "Landing Complete!");

                    }
                }
            });
        }
    }

    private void uploadWayPointMission() {
        Log.e(TAG, "Attempting mission upload!");
        FlightControllerState currentState = mFlightController.getState();
        boolean motorsOn = currentState.areMotorsOn();
        boolean isFlying = currentState.isFlying();
        Log.e(TAG, "FC state retrieved!");
        if (motorsOn && isFlying) {
            getWaypointMissionOperator().uploadMission(new CommonCallbacks.CompletionCallback() {
                @Override
                public void onResult(DJIError error) {
                    if (error == null) {
                        //Toast.makeText(getApplicationContext(), "ALERT: Mission uploaded successfully!", Toast.LENGTH_LONG).show();
                        Log.e(TAG, "Mission upload success, beginning execution");
                        //TODO: ENABLE THIS FOR TESTING!!!
                        startWaypointMission();
                    } else {
                        //Toast.makeText(getApplicationContext(), "WARNING: Mission upload failed. Error: " + error.getDescription() + " retrying...", Toast.LENGTH_LONG).show();
                        Log.e(TAG, "Mission upload failed with error: " + error.getDescription() + " Now retrying.");
                        getWaypointMissionOperator().retryUploadMission(null);
                    }
                }
            });
        } else {
            Log.e(TAG, "FC State not suitable for upload yet!");
            Log.e(TAG, "Current Motor State:" + motorsOn);
            Log.e(TAG, "Current isFlying State: " + isFlying);
            uploadWayPointMission();
        }
    }

    private void startWaypointMission() {
        //TODO: This whole while loop is shot in the dark garbage. UPDATE: Doesn't help for shit.
        while (!getWaypointMissionOperator().getCurrentState().getName().equals("READY_TO_EXECUTE")) {
            try {
                Log.v(TAG, "In the loop, aircraft is not ready to execute mission yet.");
                Log.v(TAG, "Current State: " + getWaypointMissionOperator().getCurrentState().getName());
                Thread.sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        //TODO: Why the fuck doesn't this work?
        getWaypointMissionOperator().startMission(new CommonCallbacks.CompletionCallback() {
            @Override
            public void onResult(DJIError error) {
                Log.e(TAG, "Attempting mission start: " + (error == null ? "Successfully" : error.getDescription()));
                if (error == null) {
                    Log.e(TAG, "Waypoint Mission started successfully!");
                } else {
                    Log.e(TAG, "Attempting to restart mission!");
                    startWaypointMission();
                }
            }
        });
    }

    private void stopWaypointMission() {
        getWaypointMissionOperator().stopMission(new CommonCallbacks.CompletionCallback() {
            @Override
            public void onResult(DJIError error) {
                Toast.makeText(getApplicationContext(), "Mission Stopped: " + (error == null ? "Successfully" : error.getDescription()), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void setHome() {
        mFlightController.setHomeLocationUsingAircraftCurrentLocation(new CommonCallbacks.CompletionCallback() {
            @Override
            public void onResult(DJIError djiError) {
                if (djiError != null) {
                    Toast.makeText(getApplicationContext(), "WARNING: Setting home location failed! Error: " + djiError.getDescription(), Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(getApplicationContext(), "ALERT: Setting home location success!", Toast.LENGTH_LONG).show();
                    createInitMission();
                }
            }
        });
    }

    private void createInitMission() {
        initWaypoint = new Waypoint(targetLocation.getLatitude(), targetLocation.getLongitude(), altitude);
        midWaypoint = new Waypoint(41.6510579, -91.6259367, altitude);
        /*int distance_in_lat = 10;// In meters
        int degree_in_lat = 90;// +90 should mean north
        Double generated_lat = targetLocation.getLatitude() + (distance_in_lat/6378000)*(180/degree_in_lat);
        // Currently midWaypoint is set 10 meters north than the target destination that we got from database
        midWaypoint = new Waypoint(generated_lat, targetLocation.getLongitude(), altitude);
        // Below lat long is used for calculating the middle point between the target and home coordinates
        endLatLng = new LatLng(targetLocation.getLatitude(), targetLocation.getLongitude());*/
        Log.e(TAG, "Waypoints created!");
        if (mFlightController == null) {
            initFlightController();
        }
        /*mFlightController.getHomeLocation(new CommonCallbacks.CompletionCallbackWith<LocationCoordinate2D>() {
            @Override
            public void onSuccess(LocationCoordinate2D locationCoordinate2D) {
                startLatLng = new LatLng(locationCoordinate2D.getLatitude(),locationCoordinate2D.getLongitude());
                midLatLng = midWayPoint(endLatLng, startLatLng);
                midWaypoint = new Waypoint(midLatLng.latitude, midLatLng.longitude, altitude);
                Toast.makeText(getApplicationContext(), "ALERT: GetHomeLocation Successful! ", Toast.LENGTH_LONG).show();

            }

            @Override
            public void onFailure(DJIError djiError) {
                Toast.makeText(getApplicationContext(), "WARNING: getHomeLocation Failed: " + (djiError == null ? "Successfully" : djiError.getDescription()), Toast.LENGTH_LONG).show();
                System.out.println("Getting home location failed!");
            }
        });*/
        //Add Waypoints to Waypoint arraylist;
        if (waypointMissionBuilder != null) {
            waypointList.add(midWaypoint);
            waypointList.add(initWaypoint);
            waypointMissionBuilder.waypointList(waypointList).waypointCount(waypointList.size());
        } else {
            waypointMissionBuilder = new WaypointMission.Builder();
            waypointList.add(midWaypoint);
            waypointList.add(initWaypoint);
            waypointMissionBuilder.waypointList(waypointList).waypointCount(waypointList.size());
        }
        configWayPointMission();
    }

    private void onProductConnectionChange() {
        //initFlightController();
        loginAccount();
    }

    private void initFlightController() {
        BaseProduct mProduct = SafeSwarmApplication.getProductInstance();
        if (mProduct != null && mProduct.isConnected()) {
            if (mProduct instanceof Aircraft) {
                mFlightController = ((Aircraft) mProduct).getFlightController();
                if (FIRST_TRY) {
                    FIRST_TRY = false;
                    Toast.makeText(getApplicationContext(), "ALERT: First FC Init successful! ", Toast.LENGTH_LONG).show();
                    setHome();

                } else {
                    Toast.makeText(getApplicationContext(), "ALERT: FC Init successful! ", Toast.LENGTH_LONG).show();
                }
            }
        } else {
            if (mProduct == null) {
                //Toast.makeText(getApplicationContext(), "WARNING: Product object is null! ", Toast.LENGTH_LONG).show();

            } else if (mProduct != null && !mProduct.isConnected()) {
                //Toast.makeText(getApplicationContext(), "WARNING: Product object is not null and is not connected! ", Toast.LENGTH_LONG).show();
            }
        }

        if (mFlightController != null) {
            mFlightController.setStateCallback(new FlightControllerState.Callback() {

                @Override
                public void onUpdate(@NonNull FlightControllerState djiFlightControllerCurrentState) {
                    droneLocationLat = djiFlightControllerCurrentState.getAircraftLocation().getLatitude();
                    droneLocationLng = djiFlightControllerCurrentState.getAircraftLocation().getLongitude();
                    //Toast.makeText(getApplicationContext(), "ALERT: Drone location updated! ", Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    public WaypointMissionOperator getWaypointMissionOperator() {
        if (instance == null) {
            instance = SDKMan.getMissionControl().getWaypointMissionOperator();
        }
        return instance;
    }

    //Add Listener for WaypointMissionOperator
    private void addListener() {
        if (getWaypointMissionOperator() != null) {
            getWaypointMissionOperator().addListener(eventNotificationListener);
        }
    }

    private void removeListener() {
        if (getWaypointMissionOperator() != null) {
            getWaypointMissionOperator().removeListener(eventNotificationListener);
        }
    }

    private void configWayPointMission() {
        if (waypointMissionBuilder == null) {
            waypointMissionBuilder = new WaypointMission.Builder().finishedAction(mFinishedAction)
                    .headingMode(mHeadingMode)
                    .autoFlightSpeed(mSpeed)
                    .maxFlightSpeed(mSpeed)
                    .flightPathMode(WaypointMissionFlightPathMode.NORMAL);
        } else {
            waypointMissionBuilder.finishedAction(mFinishedAction)
                    .headingMode(mHeadingMode)
                    .autoFlightSpeed(mSpeed)
                    .maxFlightSpeed(mSpeed)
                    .flightPathMode(WaypointMissionFlightPathMode.NORMAL);
        }
        if (waypointMissionBuilder.getWaypointList().size() > 0) {
            for (int i = 0; i < waypointMissionBuilder.getWaypointList().size(); i++) {
                waypointMissionBuilder.getWaypointList().get(i).altitude = altitude;
            }
            Toast.makeText(getApplicationContext(), "ALERT: Waypoint altitude set successfully.", Toast.LENGTH_LONG).show();
        }
        DJIError error = getWaypointMissionOperator().loadMission(waypointMissionBuilder.build());
        if (error == null) {
            Toast.makeText(getApplicationContext(), "ALERT: Mission build and load complete. Execution pending.", Toast.LENGTH_LONG).show();
            //TODO: Remember to turn this on before we test the takeoff.
            beginTakeOff();
        } else {
            Toast.makeText(getApplicationContext(), "WARNING: Mission build and load failed: " + error.getDescription(), Toast.LENGTH_LONG).show();
        }
    }

    private void loginAccount() {

        UserAccountManager.getInstance().logIntoDJIUserAccount(this,
                new CommonCallbacks.CompletionCallbackWith<UserAccountState>() {
                    @Override
                    public void onSuccess(final UserAccountState userAccountState) {
                        Log.e(TAG, "Login Success");
                        Toast.makeText(getApplicationContext(), "ALERT: Login Success", Toast.LENGTH_LONG).show();
                        initFlightController();
                    }

                    @Override
                    public void onFailure(DJIError error) {
                        Log.e(TAG, "Login Failed");
                        Toast.makeText(getApplicationContext(), "WARNING: Login Failed: " + error.getDescription(), Toast.LENGTH_LONG).show();

                    }
                });
    }

    @Override
    protected void onResume() {
        super.onResume();
        initFlightController();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(mReceiver);
        removeListener();
        super.onDestroy();
    }


}
