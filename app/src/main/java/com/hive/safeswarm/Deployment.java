package com.hive.safeswarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.widget.Toast;

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
import dji.common.util.CommonCallbacks;
import dji.sdk.base.BaseProduct;
import dji.sdk.flightcontroller.FlightController;
import dji.sdk.mission.waypoint.WaypointMissionOperator;
import dji.sdk.mission.waypoint.WaypointMissionOperatorListener;
import dji.sdk.products.Aircraft;
import dji.sdk.sdkmanager.DJISDKManager;

public class Deployment extends AppCompatActivity {

    public static WaypointMission.Builder waypointMissionBuilder;
    private static boolean FIRST_TRY = true;
    private static Waypoint initWaypoint, secondWaypoint;
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

        }

        @Override
        public void onExecutionUpdate(WaypointMissionExecutionEvent executionEvent) {

        }

        @Override
        public void onExecutionStart() {

        }

        @Override
        public void onExecutionFinish(@Nullable final DJIError error) {
            Toast.makeText(getApplicationContext(), "Execution finished: " + (error == null ? "Success!" : error.getDescription()), Toast.LENGTH_LONG).show();
            if (error != null) {
                mFlightController.startGoHome(new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError djiError) {
                        Toast.makeText(getApplicationContext(), "Go Home Status: " + (djiError == null ? "Success!" : djiError.getDescription()), Toast.LENGTH_LONG).show();
                    }
                });
            } else {
                mFlightController.startLanding(new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError djiError) {
                        Toast.makeText(getApplicationContext(), "Landing Status: " + (djiError == null ? "Success!" : djiError.getDescription()), Toast.LENGTH_LONG).show();
                    }
                });
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_deployment);

        //May not be required for this class, but leave as an example for now.
        Bundle bundle = getIntent().getExtras();
        mProduct = bundle.getParcelable("djiProduct");
        //SDKMan is not parcelable.
        //SDKMan = bundle.getParcelable("djiManager");

        initFlightController();
        addListener();

        // Get a reference to our posts
        final FirebaseDatabase database = FirebaseDatabase.getInstance();
        DatabaseReference ref = database.getReference("users/1/");

        ref.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                //targetLocation = dataSnapshot.getValue(Location.class);
                System.out.println(dataSnapshot.getValue());
                Map<String, Object> targetLocation2 = (Map<String, Object>) dataSnapshot.getValue();
                targetLocation.setLatitude(Double.parseDouble(targetLocation2.get("latitude").toString()));
                targetLocation.setLongitude(Double.parseDouble(targetLocation2.get("longitude").toString()));
                targetLocation.setAltitude(Double.parseDouble(targetLocation2.get("altitude").toString()));
                System.out.println("TARGET");
                Toast.makeText(getApplicationContext(), "ALERT: Target location updated.", Toast.LENGTH_LONG).show();
                if (FIRST_TRY) {
                    FIRST_TRY = false;
                    createInitMission();
                    configWayPointMission();
                    beginTakeOff();
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
                            } else {
                                Toast.makeText(getApplicationContext(), "ALERT: Takeoff success!", Toast.LENGTH_LONG).show();
                                uploadWayPointMission();
                            }
                        }
                    }
            );
        } else {
            Toast.makeText(getApplicationContext(), "WARNING: Takeoff failed due to null FC object!", Toast.LENGTH_LONG).show();
        }
    }

    private void beginLanding() {
        if (mFlightController != null) {
            mFlightController.startLanding(
                    new CommonCallbacks.CompletionCallback() {
                        @Override
                        public void onResult(DJIError djiError) {
                            if (djiError != null) {
                                Toast.makeText(getApplicationContext(), "WARNING: Landing failed! Error: " + djiError.getDescription(), Toast.LENGTH_LONG).show();
                            } else {
                                Toast.makeText(getApplicationContext(), "ALERT: Landing in progress.", Toast.LENGTH_LONG).show();
                            }
                        }
                    }
            );
        }
    }

    private void uploadWayPointMission() {
        getWaypointMissionOperator().uploadMission(new CommonCallbacks.CompletionCallback() {
            @Override
            public void onResult(DJIError error) {
                if (error == null) {
                    Toast.makeText(getApplicationContext(), "ALERT: Mission uploaded succesfully!", Toast.LENGTH_LONG).show();
                    startWaypointMission();
                } else {
                    Toast.makeText(getApplicationContext(), "WARNING: Mission upload failed. Error: " + error.getDescription() + " retrying...", Toast.LENGTH_LONG).show();
                    getWaypointMissionOperator().retryUploadMission(null);
                }
            }
        });
    }

    private void startWaypointMission() {
        getWaypointMissionOperator().startMission(new CommonCallbacks.CompletionCallback() {
            @Override
            public void onResult(DJIError error) {
                Toast.makeText(getApplicationContext(), "Mission Started: " + (error == null ? "Successfully" : error.getDescription()), Toast.LENGTH_LONG).show();
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

    private void createInitMission() {
        initWaypoint = new Waypoint(targetLocation.getLatitude(), targetLocation.getLongitude(), altitude);
        secondWaypoint = new Waypoint(41.6506621, -91.6262974, altitude);
        //Add Waypoints to Waypoint arraylist;
        if (waypointMissionBuilder != null) {
            waypointList.add(secondWaypoint);
            waypointList.add(initWaypoint);
            waypointMissionBuilder.waypointList(waypointList).waypointCount(waypointList.size());
        } else {
            waypointMissionBuilder = new WaypointMission.Builder();
            waypointList.add(secondWaypoint);
            waypointList.add(initWaypoint);
            waypointMissionBuilder.waypointList(waypointList).waypointCount(waypointList.size());
        }
    }

    private void onProductConnectionChange() {
        initFlightController();
    }

    private void initFlightController() {

        if (mProduct != null && mProduct.isConnected()) {
            if (mProduct instanceof Aircraft) {
                mFlightController = ((Aircraft) mProduct).getFlightController();
            }
        }

        if (mFlightController != null) {
            mFlightController.setStateCallback(new FlightControllerState.Callback() {

                @Override
                public void onUpdate(@NonNull FlightControllerState djiFlightControllerCurrentState) {
                    droneLocationLat = djiFlightControllerCurrentState.getAircraftLocation().getLatitude();
                    droneLocationLng = djiFlightControllerCurrentState.getAircraftLocation().getLongitude();
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

        } else {
            Toast.makeText(getApplicationContext(), "WARNING: Mission build and load failed: " + error.getDescription(), Toast.LENGTH_LONG).show();
        }
    }


}
