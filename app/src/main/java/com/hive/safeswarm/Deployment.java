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
import dji.common.flightcontroller.LocationCoordinate3D;
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
import dji.sdk.flightcontroller.FlightAssistant;
import dji.sdk.flightcontroller.FlightController;
import dji.sdk.mission.waypoint.WaypointMissionOperator;
import dji.sdk.mission.waypoint.WaypointMissionOperatorListener;
import dji.sdk.products.Aircraft;
import dji.sdk.sdkmanager.DJISDKManager;
import dji.sdk.useraccount.UserAccountManager;

public class Deployment extends AppCompatActivity {

    private static final String TAG = Deployment.class.getName();
    public static WaypointMission.Builder waypointMissionBuilder;
    private static boolean FIRST_TRY = true;
    private static Waypoint targetWaypoint, midWaypoint;
    private static Location targetLocation = new Location("fused");
    private static LocationCoordinate3D droneLocation;
    private static double droneLocationLat, droneLocationLng;
    private static FlightAssistant theBrain;
    private static WaypointMissionOperator waypointMissionOperator;
    private static WaypointMissionFinishedAction mFinishedAction = WaypointMissionFinishedAction.NO_ACTION;
    private static WaypointMissionHeadingMode mHeadingMode = WaypointMissionHeadingMode.AUTO;
    private DJISDKManager SDKMan = DJISDKManager.getInstance();
    private BaseProduct mProduct;
    //These values are in units of meters and meters/second respectively.
    private float altitude = 10.0f;
    private float mSpeed = 9.5f;
    private List<Waypoint> waypointList = new ArrayList<>();
    private FlightController mFlightController;
    protected BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            onProductConnectionChange();
        }
    };
    private LatLng startLatLng, endLatLng, midLatLng;
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
                Log.v(TAG, "ALERT: Upload event says current state is: " + getWaypointMissionOperator().getCurrentState().getName());
            }
        }

        @Override
        public void onExecutionUpdate(WaypointMissionExecutionEvent executionEvent) {
            Log.v(TAG, "UPDATE: Execution Event State: " + executionEvent.getCurrentState().getName());
            //TODO: Figure out what to make this second log statement, so it doesn't print a memory address.
            Log.v(TAG, "UPDATE: Execution Event Progress: " + executionEvent.getProgress().targetWaypointIndex);


        }

        @Override
        public void onExecutionStart() {
            Log.v(TAG, "ALERT: Mission execution started!");

        }

        //TODO: We need to further flesh out the logic here for after the mission is executed. Note that if it's successful, the drone has already gone home.
        @Override
        public void onExecutionFinish(@Nullable final DJIError error) {
            if (error != null) {
                Log.e(TAG, "WARNING: Mission execution failure!");
                if (mFlightController != null) {
                    mFlightController.startGoHome(new CommonCallbacks.CompletionCallback() {
                        @Override
                        public void onResult(DJIError djiError) {
                            if (djiError != null) {
                                Log.e(TAG, "WARNING: Go home start failure: " + djiError.getDescription());
                            } else {
                                Log.v(TAG, "ALERT: Go home start successful.");
                            }
                        }
                    });
                } else {
                    Log.e(TAG, "WARNING: Mission execution failure and FC null, reiniting FC now.");
                    initFlightController();
                    mFlightController.startGoHome(new CommonCallbacks.CompletionCallback() {
                        @Override
                        public void onResult(DJIError djiError) {
                            if (djiError != null) {
                                Log.e(TAG, "WARNING: Go home start failure: " + djiError.getDescription());
                            } else {
                                Log.v(TAG, "ALERT: Go home start successful.");
                            }
                        }
                    });
                }
            } else {
                Log.v(TAG, "ALERT: Mission execution success! Returning home now.");
                mFlightController.startGoHome(new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError djiError) {
                        if (djiError != null) {
                            Log.e(TAG, "WARNING: Go home start failure: " + djiError.getDescription());
                            //TODO: Probably should package this up and make it recursive.
                        } else {
                            Log.v(TAG, "ALERT: Go home start successful.");
                            completeGoHome();
                        }
                    }
                });
            }
        }
    };

    //Utility function for calculating the midpoint between the target location and the drones
    //start location, so that we can provide the minimum required N Waypoints s.t. N > 1.
    private static LatLng midWayPoint(LatLng start, LatLng dest) {
        Log.v(TAG, "Start lat: " + start.latitude + " Start lon: " + start.longitude);
        Log.v(TAG, "Dest lat: " + dest.latitude + " Dest lon: " + dest.latitude);
        LatLngBounds tempBound = new LatLngBounds(dest, start);
        return tempBound.getCenter();
    }

    @org.jetbrains.annotations.Contract(pure = true)
    public static boolean validGPS(double latitude, double longitude) {
        return (latitude > -90 && latitude < 90 && longitude > -180 && longitude < 180) && (latitude != 0f && longitude != 0f);
    }

    private void completeGoHome() {
        while (!mFlightController.getState().getGoHomeExecutionState()._equals(7) && mFlightController.getState().getAircraftLocation().getAltitude() > 0.3) {
            Log.v(TAG, "ALERT: Drone not yet home, waiting to land.");
            Log.v(TAG, "ALERT: Current GoHome State: " + mFlightController.getState().getGoHomeExecutionState().name());
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        Log.v(TAG, "ALERT: GoHome Complete, starting landing sequence.");
        beginLanding();
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
                Map<String, Object> targetLocation2 = (Map<String, Object>) dataSnapshot.getValue();
                targetLocation.setLatitude(Double.parseDouble(targetLocation2.get("latitude").toString()));
                targetLocation.setLongitude(Double.parseDouble(targetLocation2.get("longitude").toString()));
                targetLocation.setAltitude(Double.parseDouble(targetLocation2.get("altitude").toString()));
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                Toast.makeText(getApplicationContext(), "WARNING: Target location update failed. Retrying now.", Toast.LENGTH_LONG).show();
            }
        });

    }

    private void teachTheBrain() {
        theBrain.setCollisionAvoidanceEnabled(true, new CommonCallbacks.CompletionCallback() {
            @Override
            public void onResult(DJIError djiError) {
                if (djiError != null) {
                    Log.e(TAG, "WARNING: Collision avoidance not enabled successfully: " + djiError.getDescription());
                } else {
                    Log.v(TAG, "ALERT: Collision avoidance enabled successfully.");
                    theBrain.setActiveObstacleAvoidanceEnabled(true, new CommonCallbacks.CompletionCallback() {
                        @Override
                        public void onResult(DJIError djiError) {
                            if (djiError != null) {
                                Log.e(TAG, "WARNING: Active obstacle avoidance not enabled successfully: " + djiError.getDescription());
                            } else {
                                Log.v(TAG, "ALERT: Active obstacle avoidance enabled successfully.");
                            }
                        }
                    });
                }
            }
        });
        theBrain.getLandingProtectionEnabled(new CommonCallbacks.CompletionCallbackWith<Boolean>() {
            @Override
            public void onSuccess(Boolean lpEnabled) {
                if (lpEnabled) {
                    Log.e(TAG, "WARNING: Landing Protection is enabled. Attempting to disarm.");
                    theBrain.setLandingProtectionEnabled(false, new CommonCallbacks.CompletionCallback() {
                        @Override
                        public void onResult(DJIError djiError) {
                            if (djiError != null) {
                                Log.e(TAG, "WARNING: Error disarming landing protection: " + djiError.getDescription());
                                Log.e(TAG, "WARNING: Manually landing confirmation may be required due to landing protection.");
                            } else {
                                Log.v(TAG, "ALERT: Landing protection disarmed successfully.");
                            }

                        }
                    });
                }
            }

            @Override
            public void onFailure(DJIError djiError) {
                Log.e(TAG, "WARNING: Failed to retrieve Landing Protection State: " + djiError.toString());
                Log.e(TAG, "WARNING: Attempting to disarm Landing Protection despite unknown state." + djiError.getDescription());
                theBrain.setLandingProtectionEnabled(false, new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError djiError) {
                        if (djiError != null) {
                            Log.e(TAG, "WARNING: Error disarming landing protection and state is unknown: " + djiError.getDescription());
                            Log.e(TAG, "WARNING: Manually landing confirmation may be required due to unknown landing protection state.");
                        } else {
                            Log.v(TAG, "ALERT: Landing protection disarmed successfully.");
                        }

                    }
                });
            }
        });
        theBrain.setPrecisionLandingEnabled(true, new CommonCallbacks.CompletionCallback() {
            //Cautious, but optimistic.
            @Override
            public void onResult(DJIError djiError) {
                if (djiError != null) {
                    Log.e(TAG, "WARNING: Precision landing not enabled successfully: " + djiError.getDescription());
                    //This is fine. Everything is fine!
                    beginTakeOff();
                } else {
                    Log.v(TAG, "ALERT: Precision landing enabled successfully.");
                    beginTakeOff();
                }
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
                                Log.e(TAG, "WARNING: Takeoff Failed: " + djiError.getDescription());
                            } else {
                                Toast.makeText(getApplicationContext(), "ALERT: Takeoff success!", Toast.LENGTH_LONG).show();
                                Log.v(TAG, "ALERT: Takeoff Success");
                                //Wait until the aircraft is actually done taking off to begin uploading the mission.
                                while (mFlightController.getState().getAircraftLocation().getAltitude() < 1.2) {
                                    try {
                                        Log.v(TAG, "In the loop, state is not flying currently.");
                                        Thread.sleep(500);
                                    } catch (InterruptedException e) {
                                        e.printStackTrace();
                                    }
                                }
                                Log.v(TAG, "ALERT: We flyin bitch!");
                                createInitMission();
                            }
                        }
                    }
            );
        } else {
            Log.e(TAG, "Takeoff Failure due to null FC!");
            Toast.makeText(getApplicationContext(), "WARNING: Takeoff failed due to null FC object!", Toast.LENGTH_LONG).show();
            Toast.makeText(getApplicationContext(), "ALERT: Attempting to reinit the FC!", Toast.LENGTH_LONG).show();
            initFlightController();
            Toast.makeText(getApplicationContext(), "ALERT: FC reinitialized, attempting takeoff again!", Toast.LENGTH_LONG).show();
            //TODO: Watch the fuck out, there's some infinite loop potential here and there is a path in the next function that resolves in a callback.
            beginTakeOff();
        }
    }

    //TODO: beginLanding and completeLanding need some additional logic to ensure the drone eventually lands if we fail a few times.
    //TODO: Confirm this works when we're already at 0.3 meters in altitude. May need to use cancelGoHome instead.
    private void beginLanding() {
        if (mFlightController != null) {
            mFlightController.startLanding(
                    new CommonCallbacks.CompletionCallback() {
                        @Override
                        public void onResult(DJIError djiError) {
                            if (djiError != null) {
                                Log.e(TAG, "WARNING: Landing start failed: " + djiError.getDescription());
                            } else {
                                Log.v(TAG, "ALERT: Landing starting now.");
                                while (mFlightController.getState().getAircraftLocation().getAltitude() > 0.3) {
                                    try {
                                        Log.v(TAG, "ALERT: Awaiting descent to less than 0.3 meters.");
                                        Thread.sleep(2000);
                                    } catch (InterruptedException e) {
                                        e.printStackTrace();
                                    }
                                }
                                while (mFlightController.getState().isFlying()) {
                                    if (mFlightController.getState().isLandingConfirmationNeeded()) {
                                        Log.v(TAG, "ALERT: Landing confirmation required. Providing now.");
                                        completeLanding();
                                    }
                                }
                                Log.v(TAG, "ALERT: Mission Successful. Ready for redeployment.");
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
                        Log.e(TAG, "Landing confirmation failed with error: " + djiError.getDescription());
                    } else {
                        Log.v(TAG, "Landing confirmed, descending!");

                    }
                }
            });
        }
    }

    private void uploadWayPointMission() {
        Log.v(TAG, "Attempting mission upload!");
        FlightControllerState currentState = mFlightController.getState();
        boolean motorsOn = currentState.areMotorsOn();
        boolean isFlying = currentState.isFlying();
        Log.v(TAG, "FC state retrieved!");
        if (motorsOn && isFlying) {
            getWaypointMissionOperator().uploadMission(new CommonCallbacks.CompletionCallback() {
                @Override
                public void onResult(DJIError error) {
                    if (error == null) {
                        //Toast.makeText(getApplicationContext(), "ALERT: Mission uploaded successfully!", Toast.LENGTH_LONG).show();
                        Log.v(TAG, "Mission upload success, beginning execution");
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
        while (!getWaypointMissionOperator().getCurrentState().getName().equals("READY_TO_EXECUTE")) {
            try {
                Log.v(TAG, "In the loop, aircraft is not ready to execute mission yet.");
                Log.v(TAG, "Current State: " + getWaypointMissionOperator().getCurrentState().getName());
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        getWaypointMissionOperator().startMission(new CommonCallbacks.CompletionCallback() {
            @Override
            public void onResult(DJIError error) {
                if (error == null) {
                    Log.v(TAG, "ALERT: Waypoint Mission started successfully!");
                } else {
                    Log.e(TAG, "WARNING: Attempting to restart mission: " + error.getDescription());
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

    //Turns out this is automatic and not something we have to do manually.
    private void setHome() {
        mFlightController.setHomeLocationUsingAircraftCurrentLocation(new CommonCallbacks.CompletionCallback() {
            @Override
            public void onResult(DJIError djiError) {
                if (djiError != null) {
                    Toast.makeText(getApplicationContext(), "WARNING: Setting home location failed! Error: " + djiError.getDescription(), Toast.LENGTH_LONG).show();
                    Log.e(TAG, "WARNING: Setting home location failed: " + djiError.getDescription());
                } else {
                    Toast.makeText(getApplicationContext(), "ALERT: Setting home location success!", Toast.LENGTH_LONG).show();
                    while (!mFlightController.getState().isHomeLocationSet()) {
                        Log.v(TAG, "ALERT: Home location not yet set, so we wait.");
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    createInitMission();
                }
            }
        });
    }

    private void createInitMission() {
        targetWaypoint = new Waypoint(targetLocation.getLatitude(), targetLocation.getLongitude(), altitude);
        //Manual waypoint only for testing.
        //midWaypoint = new Waypoint(41.6510579, -91.6259367, altitude);
//        int distance_in_lat = 10;// In meters
//        int degree_in_lat = 90;// +90 should mean north
//        Double generated_lat = targetLocation.getLatitude() + (distance_in_lat/6378000)*(180/degree_in_lat);
//        // Currently midWaypoint is set 10 meters north than the target destination that we got from database
//        if(!validGPS(generated_lat, targetLocation.getLongitude())){
//            Log.e(TAG, "WARNING: Generated coordinates are invalid!!!");
//        }
//        else{
//            Log.e(TAG, "ALERT: Generated coordinates were valid.");
//        }
//        //TODO: Log the generated coordinates and check them manually.
//        Log.e(TAG, "Generated Lat: " + generated_lat);
//        Log.e(TAG, "Real Lat: " + targetLocation.getLatitude());
//        Log.e(TAG, "Longitude: " + targetLocation.getLongitude());
//        midWaypoint = new Waypoint(generated_lat, targetLocation.getLongitude(), altitude);
        // Below lat long is used for calculating the middle point between the target and home coordinates
        endLatLng = new LatLng(targetLocation.getLatitude(), targetLocation.getLongitude());
        Log.v(TAG, "ALERT: Waypoints created.");

        if (mFlightController == null) {
            initFlightController();
        }
        droneLocation = mFlightController.getState().getAircraftLocation();

        startLatLng = new LatLng(droneLocation.getLatitude(), droneLocation.getLongitude());
        midLatLng = midWayPoint(endLatLng, startLatLng);
        midWaypoint = new Waypoint(midLatLng.latitude, midLatLng.longitude, altitude);
        Log.v(TAG, "ALERT: Midpoint calculation successful.");

        //Add Waypoints to Waypoint arraylist;
        if (waypointMissionBuilder != null) {
            waypointList.add(midWaypoint);
            waypointList.add(targetWaypoint);
            waypointMissionBuilder.waypointList(waypointList).waypointCount(waypointList.size());
        } else {
            waypointMissionBuilder = new WaypointMission.Builder();
            waypointList.add(midWaypoint);
            waypointList.add(targetWaypoint);
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
                theBrain = mFlightController.getFlightAssistant();
                if (FIRST_TRY) {
                    FIRST_TRY = false;
                    Toast.makeText(getApplicationContext(), "ALERT: First FC Init successful! ", Toast.LENGTH_LONG).show();
                    teachTheBrain();

                } else {
                    Toast.makeText(getApplicationContext(), "ALERT: FC Init successful! ", Toast.LENGTH_LONG).show();
                }
            }
        } else {
            if (mProduct == null) {
                //Toast.makeText(getApplicationContext(), "WARNING: Product object is null! ", Toast.LENGTH_LONG).show();

            } else if (!mProduct.isConnected()) {
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
        if (waypointMissionOperator == null) {
            Log.e(TAG, "ALERT: WaypointMissionOperator was null!");
            waypointMissionOperator = SDKMan.getMissionControl().getWaypointMissionOperator();
        }
        return waypointMissionOperator;
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
            uploadWayPointMission();
        } else {
            Toast.makeText(getApplicationContext(), "WARNING: Mission build and load failed: " + error.getDescription(), Toast.LENGTH_LONG).show();
        }
    }

    private void loginAccount() {

        UserAccountManager.getInstance().logIntoDJIUserAccount(this,
                new CommonCallbacks.CompletionCallbackWith<UserAccountState>() {
                    @Override
                    public void onSuccess(final UserAccountState userAccountState) {
                        Log.v(TAG, "Login Success");
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
        Log.v(TAG, "ALERT: Resuming and logging in now.");
        //TODO: Notice this is the entrance point for the entire cascade.
        loginAccount();
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
