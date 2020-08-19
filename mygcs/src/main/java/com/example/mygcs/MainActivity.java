package com.example.mygcs;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.ActivityInfo;
import android.graphics.PointF;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v4.app.FragmentManager;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.naver.maps.geometry.LatLng;
import com.naver.maps.map.CameraUpdate;
import com.naver.maps.map.MapFragment;
import com.naver.maps.map.NaverMap;
import com.naver.maps.map.OnMapReadyCallback;
import com.naver.maps.map.overlay.LocationOverlay;
import com.naver.maps.map.overlay.Marker;
import com.naver.maps.map.overlay.OverlayImage;
import com.naver.maps.map.overlay.PolylineOverlay;
import com.o3dr.android.client.ControlTower;
import com.o3dr.android.client.Drone;
import com.o3dr.android.client.apis.ControlApi;
import com.o3dr.android.client.apis.ExperimentalApi;
import com.o3dr.android.client.apis.VehicleApi;
import com.o3dr.android.client.apis.solo.SoloCameraApi;
import com.o3dr.android.client.interfaces.DroneListener;
import com.o3dr.android.client.interfaces.LinkListener;
import com.o3dr.android.client.interfaces.TowerListener;
import com.o3dr.android.client.utils.video.DecoderListener;
import com.o3dr.android.client.utils.video.MediaCodecManager;
import com.o3dr.services.android.lib.coordinate.LatLong;
import com.o3dr.services.android.lib.coordinate.LatLongAlt;
import com.o3dr.services.android.lib.drone.attribute.AttributeEvent;
import com.o3dr.services.android.lib.drone.attribute.AttributeType;
import com.o3dr.services.android.lib.drone.companion.solo.SoloAttributes;
import com.o3dr.services.android.lib.drone.companion.solo.SoloState;
import com.o3dr.services.android.lib.drone.connection.ConnectionParameter;
import com.o3dr.services.android.lib.drone.connection.ConnectionType;
import com.o3dr.services.android.lib.drone.mission.item.command.YawCondition;
import com.o3dr.services.android.lib.drone.property.Altitude;
import com.o3dr.services.android.lib.drone.property.Battery;
import com.o3dr.services.android.lib.drone.property.Gps;
import com.o3dr.services.android.lib.drone.property.GuidedState;
import com.o3dr.services.android.lib.drone.property.Home;
import com.o3dr.services.android.lib.drone.property.Speed;
import com.o3dr.services.android.lib.drone.property.State;
import com.o3dr.services.android.lib.drone.property.Type;
import com.o3dr.services.android.lib.drone.property.DroneAttribute;
import com.o3dr.services.android.lib.drone.property.Attitude;
import com.o3dr.services.android.lib.drone.property.VehicleMode;
import com.o3dr.services.android.lib.gcs.link.LinkConnectionStatus;
import com.o3dr.services.android.lib.model.AbstractCommandListener;
import com.o3dr.services.android.lib.model.SimpleCommandListener;

import java.util.ArrayList;
import java.util.List;

import static com.o3dr.services.android.lib.drone.attribute.AttributeType.BATTERY;

public class MainActivity extends AppCompatActivity implements OnMapReadyCallback, DroneListener, TowerListener, LinkListener {

    private static final String TAG = MainActivity.class.getSimpleName();

    NaverMap mymap;

    private Drone drone;
    private int droneType = Type.TYPE_UNKNOWN;
    private ControlTower controlTower;
    private boolean connectDrone = false;
    private LinearLayout armingbtn ;
    private final Handler handler = new Handler();
    private Spinner modeSelector;
    private LocationOverlay locationOverlay;
    private double droneAltitude = 5.5;
    private GuideMode guide;
    private ArrayList<LatLng> pathcoords = new ArrayList<>();
    PolylineOverlay dronePath = new PolylineOverlay();

    private MediaCodecManager mediaCodecManager;


    Handler mainHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN); // 핸드폰 맨위 시간, 안테나 타이틀 없애기
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE); // 가로모드 고정

        setContentView(R.layout.activity_main);

        FragmentManager fm = getSupportFragmentManager();
        MapFragment mapFragment = (MapFragment)fm.findFragmentById(R.id.map);

        if (mapFragment == null) {
            mapFragment = MapFragment.newInstance();
            fm.beginTransaction().add(R.id.map, mapFragment).commit();
        }

        if(!connectDrone) {
            armingbtn = (LinearLayout)findViewById(R.id.connectmenu) ;
            armingbtn.setVisibility(View.INVISIBLE);
        }

        mapFragment.getMapAsync(this);

        final Context context = getApplicationContext();
        this.controlTower = new ControlTower(context);
        this.drone = new Drone(context);

        this.modeSelector = (Spinner) findViewById(R.id.flightModeSelectorSpinner);
        this.modeSelector.setOnItemSelectedListener(new Spinner.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                onFlightModeSelected(view);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Do nothing
            }

        });

        // Initialize media codec manager to decode video stream packets.
        HandlerThread mediaCodecHandlerThread = new HandlerThread("MediaCodecHandlerThread");
        mediaCodecHandlerThread.start();
        Handler mediaCodecHandler = new Handler(mediaCodecHandlerThread.getLooper());
        mediaCodecManager = new MediaCodecManager(mediaCodecHandler);

        mainHandler = new Handler(getApplicationContext().getMainLooper());

        guide = new GuideMode();

        }

    //Overlay
    public void overlay() {
        try {
            Gps droneGps = this.drone.getAttribute(AttributeType.GPS);
            LatLng dronePosition = new LatLng(droneGps.getPosition().getLatitude(),droneGps.getPosition().getLongitude());

            locationOverlay = mymap.getLocationOverlay();
            locationOverlay.setVisible(true);

            locationOverlay.setIcon(OverlayImage.fromResource(R.drawable.flight));
            locationOverlay.setIconWidth(LocationOverlay.SIZE_AUTO);
            locationOverlay.setIconHeight(LocationOverlay.SIZE_AUTO);

            locationOverlay.setPosition(dronePosition);
            mymap.moveCamera(CameraUpdate.scrollTo(dronePosition));

        } catch (NullPointerException e) {
            Log.d("myLog", "getPosition Error : " + e.getMessage());
            locationOverlay = mymap.getLocationOverlay();
            locationOverlay.setVisible(true);
            locationOverlay.setPosition(new LatLng(35.945378,126.682110));
            mymap.moveCamera(CameraUpdate.scrollTo(new LatLng(35.945378,126.682110)));
            locationOverlay.setIcon(OverlayImage.fromResource(R.drawable.flight));
            locationOverlay.setIconWidth(LocationOverlay.SIZE_AUTO);
            locationOverlay.setIconHeight(LocationOverlay.SIZE_AUTO);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        this.controlTower.connect(this);
        updateVehicleModesForType(this.droneType);
    }

    @Override
    public void onStop() {
        super.onStop();
        if (this.drone.isConnected()) {
            this.drone.disconnect();
            updateConnectedButton(false);
        }

        this.controlTower.unregisterDrone(this.drone);
        this.controlTower.disconnect();
    }

    @Override
    public void onTowerConnected() {
        alertUser("DroneKit-Android Connected");
        this.controlTower.registerDrone(this.drone, this.handler);
        this.drone.registerDroneListener(this);
    }

    @Override
    public void onTowerDisconnected() {
        alertUser("DroneKit-Android Interrupted");
    }

    @Override
    public void onMapReady(@NonNull NaverMap naverMap) {

        this.mymap = naverMap;

        mymap.setOnMapLongClickListener((pointF, latLng) -> {
            runGuideMode(latLng);
        });
        overlay();

    }

    protected void updateDistanceFromHome() {
        TextView distanceTextView = (TextView) findViewById(R.id.yawValueTextView);
        Altitude droneAltitude = this.drone.getAttribute(AttributeType.ALTITUDE);
        double vehicleAltitude = droneAltitude.getAltitude();
        Gps droneGps = this.drone.getAttribute(AttributeType.GPS);
        LatLong vehiclePosition = droneGps.getPosition();

        double distanceFromHome = 0;

        if (droneGps.isValid()) {
            LatLongAlt vehicle3DPosition = new LatLongAlt(vehiclePosition.getLatitude(), vehiclePosition.getLongitude(), vehicleAltitude);
            Home droneHome = this.drone.getAttribute(AttributeType.HOME);
            distanceFromHome = distanceBetweenPoints(droneHome.getCoordinate(), vehicle3DPosition);
        } else {
            distanceFromHome = 0;
        }

        distanceTextView.setText(String.format("%3.1f", distanceFromHome) + "m");
    }

    protected double distanceBetweenPoints(LatLongAlt pointA, LatLongAlt pointB) {
        if (pointA == null || pointB == null) {
            return 0;
        }
        double dx = pointA.getLatitude() - pointB.getLatitude();
        double dy = pointA.getLongitude() - pointB.getLongitude();
        double dz = pointA.getAltitude() - pointB.getAltitude();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }


    @Override
    public void onDroneEvent(String event, Bundle extras) {
        switch (event) {
            case AttributeEvent.STATE_CONNECTED:
                alertUser("Drone Connected");
                updateConnectedButton(this.drone.isConnected());
                updateArmButton();
                updateTakeOffAltitudeButton();
                checkSoloState();
                break;

            case AttributeEvent.STATE_DISCONNECTED:
                alertUser("Drone Disconnected");
                updateConnectedButton(this.drone.isConnected());
                updateArmButton();
                updateTakeOffAltitudeButton();
                break;

            case AttributeEvent.STATE_UPDATED:
                break;
            case AttributeEvent.STATE_ARMING:
                updateArmButton();
                updateTakeOffAltitudeButton();
                break;

            case AttributeEvent.TYPE_UPDATED:
                Type newDroneType = this.drone.getAttribute(AttributeType.TYPE);
                if (newDroneType.getDroneType() != this.droneType) {
                    this.droneType = newDroneType.getDroneType();
                    updateVehicleModesForType(this.droneType);
                }
                break;

            case AttributeEvent.STATE_VEHICLE_MODE:
                updateVehicleMode();
                break;

            case AttributeEvent.SPEED_UPDATED:
                updateSpeed();
                break;

            case AttributeEvent.ALTITUDE_UPDATED:
                updateAltitude();
                break;

            case AttributeEvent.HOME_UPDATED:
                updateDistanceFromHome();
                break;

            case AttributeEvent.BATTERY_UPDATED:
                updateBatteryVolt();
                break;

            case AttributeEvent.ATTITUDE_UPDATED:
                updateYaw();
                break;

            case AttributeEvent.GPS_COUNT:
                updateNumberOfSatellites();
                break;

            case AttributeEvent.GPS_POSITION:
                overlay();
                delGuideMode();
                break;


            default:
                // Log.i("DRONE_EVENT", event); //Uncomment to see events from the drone
                break;
        }

    }

    @Override
    public void onDroneServiceInterrupted(String errorMsg) {

    }

    public void onBtnConnectTap() {
        if (this.drone.isConnected()) {
            this.drone.disconnect();
        } else {
            ConnectionParameter connectionParams = ConnectionParameter.newUdpConnection(null);
            this.drone.connect(connectionParams);
        }
    }

    public void btn_event(View v){
        switch(v.getId()){
            case R.id.btnconnect:
                onBtnConnectTap();
                break;
            case R.id.btnarm:
                onArmButtonTap();
                break;
            case R.id.btnland:
                onLandButtonTap();
                break;
            case R.id.btnarmtakeoff:
                onArmButtonTap();
            case R.id.btnTakeoffAltitude:
                takeoffsetButtonTap();
                break;
            case R.id.btnUpAltitude:
                onUpAltitudeButtonTap();
                break;
            case R.id.btnDownAltitude:
                onDownAltitudeButtonTap();
                break;

        }
    }
    @Override
    public void onLinkStateUpdated(@NonNull LinkConnectionStatus connectionStatus) {
        switch(connectionStatus.getStatusCode()){
            case LinkConnectionStatus.FAILED:
                Bundle extras = connectionStatus.getExtras();
                String msg = null;
                if (extras != null) {
                    msg = extras.getString(LinkConnectionStatus.EXTRA_ERROR_MSG);
                }
                alertUser("Connection Failed:" + msg);
                break;
        }
    }

    protected void alertUser(String message) {
        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();
        Log.d(TAG, message);
    }

    public void onFlightModeSelected(View view) {
        VehicleMode vehicleMode = (VehicleMode) this.modeSelector.getSelectedItem();

        VehicleApi.getApi(this.drone).setVehicleMode(vehicleMode, new AbstractCommandListener() {
            @Override
            public void onSuccess() {
                alertUser("Vehicle mode change successful.");
            }

            @Override
            public void onError(int executionError) {
                alertUser("Vehicle mode change failed: " + executionError);
            }

            @Override
            public void onTimeout() {
                alertUser("Vehicle mode change timed out.");
            }
        });
    }

    protected void updateSpeed() {
        TextView speedTextView = (TextView) findViewById(R.id.speedValueTextView);
        Speed droneSpeed = this.drone.getAttribute(AttributeType.SPEED);
        speedTextView.setText(String.format("%3.1f", droneSpeed.getGroundSpeed()) + "m/s");
    }

    protected void updateAltitude() {
        TextView altitudeTextView = (TextView) findViewById(R.id.altitudeValueTextView);
        Altitude droneAltitude = this.drone.getAttribute(AttributeType.ALTITUDE);
        altitudeTextView.setText(String.format("%3.1f", droneAltitude.getAltitude()) + "m");
    }

    protected void updateBatteryVolt(){
        TextView voltTextView = (TextView)findViewById(R.id.batteryVoltageValueTextView);
        Battery droneVolt = this.drone.getAttribute(BATTERY);
        Log.d("MYLOG","베터리 변화 : " + droneVolt.getBatteryVoltage());
        voltTextView.setText(String.format(" " + droneVolt.getBatteryVoltage()+"V"));
    }

    protected void updateYaw() {
        double yawvalue=0;
        TextView yawTextView = (TextView)findViewById(R.id.yawValueTextView);
        Attitude droneyaw = this.drone.getAttribute(AttributeType.ATTITUDE);
        if(droneyaw.getYaw()<0)
            yawvalue = droneyaw.getYaw()+360;
        else
            yawvalue = droneyaw.getYaw();
        yawTextView.setText(String.format("%3.1f",yawvalue) + "deg");
        locationOverlay.setBearing((float) droneyaw.getYaw());

    }

    protected void updateNumberOfSatellites() {
        TextView numberOfSatellitesTextView = (TextView)findViewById(R.id.numberofSatellitesValueTextView);
        Gps droneNumberOfSatellites = this.drone.getAttribute(AttributeType.GPS);
        Log.d("MYLOG", "위성 수 변화 : " + droneNumberOfSatellites.getSatellitesCount());
        numberOfSatellitesTextView.setText(String.format("%d", droneNumberOfSatellites.getSatellitesCount()));
    }

    protected void updateConnectedButton(Boolean isConnected) {
        Button connectButton = (Button) findViewById(R.id.btnconnect);
        if (isConnected) {
            connectButton.setText("Disconnect");
            connectDrone = false;
            armingbtn.setVisibility(View.INVISIBLE);
        } else {
            connectButton.setText("Connect");
            connectDrone = true;
            armingbtn.setVisibility(View.VISIBLE);
        }
    }

    protected void updateArmButton() {
        State vehicleState = this.drone.getAttribute(AttributeType.STATE);
        Button armButton = (Button) findViewById(R.id.btnarm);

        if (!this.drone.isConnected()) {
            armingbtn.setVisibility(View.INVISIBLE);
        } else {
            armingbtn.setVisibility(View.VISIBLE);
        }

        if (vehicleState.isFlying()) {
            // Land
            armButton.setText("LAND");
        } else if (vehicleState.isArmed()) {
            // Take off
            armButton.setText("TAKE-OFF");
        } else if (vehicleState.isConnected()) {
            // Connected but not Armed
            armButton.setText("ARM");
        }
    }

    protected void updateTakeOffAltitudeButton() {
        Button takeOffAltitudeButton = (Button) findViewById(R.id.btnTakeoffAltitude);
        TextView altitudeTextView = (TextView) findViewById(R.id.btnTakeoffAltitude);

        if (!this.drone.isConnected()) {
            takeOffAltitudeButton.setVisibility(View.INVISIBLE);
            altitudeTextView.setText(String.format("%1.1f"+"M\n이륙고도", droneAltitude));
        } else {
            takeOffAltitudeButton.setVisibility(View.VISIBLE);
        }
    }

    private void checkSoloState() {
        final SoloState soloState = drone.getAttribute(SoloAttributes.SOLO_STATE);
        if (soloState == null){
            alertUser("Unable to retrieve the solo state.");
        }
        else {
            alertUser("Solo state is up to date.");
        }
    }

    protected void updateVehicleMode() {
        State vehicleState = this.drone.getAttribute(AttributeType.STATE);
        VehicleMode vehicleMode = vehicleState.getVehicleMode();
        ArrayAdapter arrayAdapter = (ArrayAdapter) this.modeSelector.getAdapter();
        this.modeSelector.setSelection(arrayAdapter.getPosition(vehicleMode));
    }

    protected void updateVehicleModesForType(int droneType) {

        List<VehicleMode> vehicleModes = VehicleMode.getVehicleModePerDroneType(droneType);
        ArrayAdapter<VehicleMode> vehicleModeArrayAdapter = new ArrayAdapter<VehicleMode>(this, android.R.layout.simple_spinner_item, vehicleModes);
        vehicleModeArrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        this.modeSelector.setAdapter(vehicleModeArrayAdapter);
    }

    public void onArmButtonTap() {
        State vehicleState = this.drone.getAttribute(AttributeType.STATE);

        if (vehicleState.isFlying()) {
            // Land
            VehicleApi.getApi(this.drone).setVehicleMode(VehicleMode.COPTER_LAND, new SimpleCommandListener() {
                @Override
                public void onError(int executionError) {
                    alertUser("Unable to land the vehicle.");
                }

                @Override
                public void onTimeout() {
                    alertUser("Unable to land the vehicle.");
                }
            });
        } else if (vehicleState.isArmed()) {
            // Take off
            ControlApi.getApi(this.drone).takeoff(droneAltitude, new AbstractCommandListener() {

                @Override
                public void onSuccess() {
                    alertUser("Taking off...");
                }

                @Override
                public void onError(int i) {
                    alertUser("Unable to take off.");
                }

                @Override
                public void onTimeout() {
                    alertUser("Unable to take off.");
                }
            });
        } else if (!vehicleState.isConnected()) {
            // Connect
            alertUser("Connect to a drone first");
        } else {
            // Connected but not Armed
            VehicleApi.getApi(this.drone).arm(true, false, new SimpleCommandListener() {
                @Override
                public void onError(int executionError) {
                    alertUser("Unable to arm vehicle.");
                }

                @Override
                public void onTimeout() {
                    alertUser("Arming operation timed out.");
                }
            });
        }
    }

    public void onLandButtonTap() {
        State vehicleState = this.drone.getAttribute(AttributeType.STATE);

        if (vehicleState.isFlying()) {
            // Land
            VehicleApi.getApi(this.drone).setVehicleMode(VehicleMode.COPTER_LAND, new SimpleCommandListener() {
                @Override
                public void onError(int executionError) {
                    alertUser("Unable to land the vehicle.");
                }

                @Override
                public void onTimeout() {
                    alertUser("Unable to land the vehicle.");
                }
            });
        }
    }

    public void takeoffsetButtonTap() {
        Button upAltitudeButton = (Button) findViewById(R.id.btnUpAltitude);
        Button downAltitudeButton = (Button) findViewById(R.id.btnDownAltitude);

        if (upAltitudeButton.getVisibility() == View.INVISIBLE) {
            upAltitudeButton.setVisibility(View.VISIBLE);
            downAltitudeButton.setVisibility(View.VISIBLE);
        } else {
            upAltitudeButton.setVisibility(View.INVISIBLE);
            downAltitudeButton.setVisibility(View.INVISIBLE);
        }
    }

    public void onUpAltitudeButtonTap() {
        TextView upAltitudeValue = (TextView) findViewById(R.id.btnTakeoffAltitude);

        if (droneAltitude <= 9.5) {
            droneAltitude += 0.5;
            upAltitudeValue.setText(String.format("%1.1f"+"M\n이륙고도", droneAltitude));
        } else if (droneAltitude >= 10.0) {
            Toast.makeText(getApplicationContext(),"고도 10m초과 설정 불가", Toast.LENGTH_SHORT).show();
        }
    }

    public void onDownAltitudeButtonTap() {
        TextView downAltitudeValue = (TextView) findViewById(R.id.btnTakeoffAltitude);

        if (droneAltitude >= 3.5) {
            droneAltitude -= 0.5;
            downAltitudeValue.setText(String.format("%1.1f"+"M\n이륙고도", droneAltitude));
        } else if (droneAltitude <= 3.5) {
            Toast.makeText(getApplicationContext(),"고도 3m미만 설정 불가", Toast.LENGTH_SHORT).show();
        }
    }

 //guide mode

    private void runGuideMode(LatLng latLng) {
                if (mydronestate()) {
                        guide.mGuidedPoint = latLng;
                        guide.mMarkerGuide.setPosition(latLng);
                        guide.mMarkerGuide.setMap(mymap);
                        guide.mMarkerGuide.setIcon(OverlayImage.fromResource(R.drawable.marker_guide));
                        guide.DialogSimple(drone, new LatLong(latLng.latitude, latLng.longitude));
                    }
                else {
                    Toast.makeText(this, "비행중이 아니군요.", Toast.LENGTH_SHORT).show();
                    return;
                }

    }

    public boolean mydronestate(){
        State vehiclestate = this.drone.getAttribute(AttributeType.STATE);
        if(vehiclestate.isFlying())
            return true;
        else
            return false;
    }

    public void delGuideMode() {
        Drone mydrone = this.drone;

        try {
            if (guide.CheckGoal(mydrone, guide.mGuidedPoint)) {
                VehicleApi.getApi(drone).setVehicleMode(VehicleMode.COPTER_LOITER, new AbstractCommandListener() {
                    @Override
                    public void onSuccess() {
                        guide.mMarkerGuide.setMap(null);
                        alertUser("mode changed / 현재고도를 유지하며 이동합니다.");
                    }

                    @Override
                    public void onError(int executionError) {
                        alertUser("이동 할 수 없습니다.");
                    }

                    @Override
                    public void onTimeout() {
                        alertUser("시간 초과입니다.");
                    }
                });
            }
        } catch (NullPointerException e) {
            Log.d("NONMARKER", "no marker exist");
        }
    }

    //경로선
    public void pathLine(){
        Gps droneGps = this.drone.getAttribute(AttributeType.GPS);
        LatLng dronePosition = new LatLng(droneGps.getPosition().getLatitude(),droneGps.getPosition().getLongitude());

        try{
            pathcoords.add(dronePosition);
            dronePath.setCoords(pathcoords);
            dronePath.setPattern(10, 5);
            dronePath.setMap(mymap);

            Log.d("DRONEPATH","list size:"+pathcoords.size());
        }catch(NullPointerException e){
            Log.d("DRONEPATH","gps position list is null");
        }


    }

    class GuideMode {
        LatLng mGuidedPoint; //가이드모드 목적지 저장
        Marker mMarkerGuide = new Marker(); //GCS 위치 표마커 옵션

        void DialogSimple(final Drone drone, final LatLong point) {
            AlertDialog.Builder alt_bld = new AlertDialog.Builder(MainActivity.this);
            alt_bld.setMessage("확인하시면 가이드모드로 전환후 기체가 이동합니다.").setCancelable(false).setPositiveButton("확인", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
// Action for 'Yes' Button
                    VehicleApi.getApi(drone).setVehicleMode(VehicleMode.COPTER_GUIDED,
                            new AbstractCommandListener() {
                                @Override

                                public void onSuccess() {
                                    ControlApi.getApi(drone).goTo(point, true, null);
                                    alertUser("Success");
                                }
                                @Override
                                public void onError(int i) {
                                    alertUser("Error");
                                }
                                @Override
                                public void onTimeout() {
                                    alertUser("Timeout(잠시중단)");
                                }
                            });
                }
            }).setNegativeButton("취소", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    dialog.cancel();

                }
            });
            AlertDialog alert = alt_bld.create();
            // Title for AlertDialog
            alert.setTitle("Title");
            // Icon for AlertDialog

            alert.show();
        }
        public boolean CheckGoal(final Drone drone, LatLng recentLatLng) {
            GuidedState guidedState = drone.getAttribute(AttributeType.GUIDED_STATE);
            LatLng target = new LatLng(guidedState.getCoordinate().getLatitude(),
                    guidedState.getCoordinate().getLongitude());
            return target.distanceTo(recentLatLng) <= 1;
        }
    }
}

