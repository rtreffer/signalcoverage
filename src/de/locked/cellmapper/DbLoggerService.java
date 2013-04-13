package de.locked.cellmapper;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.location.Location;
import android.location.LocationManager;
import android.os.IBinder;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import de.locked.cellmapper.model.DataListener;
import de.locked.cellmapper.model.DbHandler;
import de.locked.cellmapper.model.Preferences;

public class DbLoggerService extends Service {
    private static final String LOG_TAG = DbLoggerService.class.getName();

    // max location age
    private final long maxLocationAge = DbHandler.ALLOWED_TIME_DRIFT;
    // get an update every this many meters (min distance)
    private long minLocationDistance = 50; // m
    // get an update every this many milliseconds
    private long minLocationTime = 5000; // ms

    // keep the location lister that long active before unregistering again
    // thanx htc Desire + cyanogen mod
    private long sleepBetweenMeasures = 30000; // ms
    private long updateDuration = 30000; // ms

    private LocationManager locationManager;
    private TelephonyManager telephonyManager;

    private DataListener dataListener;
    private Thread runner;

    private Location lastLocation = null;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(LOG_TAG, "start service");

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        dataListener = new DataListener(this);

        // restart on preference change
        PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(
                new OnSharedPreferenceChangeListener() {
                    @Override
                    public void onSharedPreferenceChanged(SharedPreferences p, String key) {
                        if (isRunning()){
                            restart();
                        }
                    }
                });

        restart();
    }

    /**
     * We want this service to continue running until it is explicitly stopped,
     * so return sticky.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i("LocalService", "Received start id " + startId + ": " + intent);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stop();
    }

    private boolean isRunning(){
        return runner != null && runner.isAlive();
    }
    
    private void stop() {
        removeListener();
        if (runner != null) {
            runner.interrupt();
        }
        runner = null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void loadPreferences() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);

        sleepBetweenMeasures = Preferences.getAsLong(preferences, Preferences.sleep_between_measures, 30) * 1000l;
        updateDuration = Preferences.getAsLong(preferences, Preferences.update_duration, 30) * 1000l;

        minLocationTime = Preferences.getAsLong(preferences, Preferences.min_location_time, 60) * 1000l;
        minLocationDistance = Preferences.getAsLong(preferences, Preferences.min_location_distance, 50);

        // ensure a minimum value
        minLocationTime = Math.max(minLocationTime, 1000);
    }

    private synchronized void restart() {
        stop();
        loadPreferences();

        // this is the main thread
        runner = new Thread() {

            @Override
            public void run() {
                setName("LoggerServiceThread");
                try {
                    Looper.prepare();
                    // Looper.loop();
                    while (!isInterrupted()) {
                        Log.i(LOG_TAG, "start location listening");
                        addListener();

                        // request location updates for a period of
                        // 'updateDuration'ms
                        final long stopPeriod = System.currentTimeMillis() + updateDuration;
                        while (System.currentTimeMillis() < stopPeriod) {
                            Log.d(LOG_TAG, "request location update");
                            dataListener.onLocationChanged(getLocation());
                            sleep(minLocationTime);
                        }

                        // set asleep and wait for the next measurement period
                        Log.d(LOG_TAG, "wait for next iteration in " + sleepBetweenMeasures + "ms and disable updates");
                        removeListener();
                        sleep(sleepBetweenMeasures);
                    }
                } catch (InterruptedException e) {
                    Log.i(LOG_TAG, "location thread interrupted");
                    removeListener();
                } finally {
                    Looper.myLooper().quit();
                }
            }
        };
        runner.start();
    }

    private boolean timeOk(Location l){
        if (l == null) return false;
        
        long limit = System.currentTimeMillis() - maxLocationAge;
        return l.getTime() > limit;
    }
    
    private boolean distOk(Location l){
        if (l == null) return false;
        if (lastLocation == null) return true;
        if (minLocationDistance <= 0) return true;
        
        return l.distanceTo(lastLocation) > minLocationDistance;
    }
    
    private Location getLocation() {
        Location network = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        Location gps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);

        // filter too old / near locations
        if (!timeOk(network) || !distOk(network)) {
            network = null;
        }
        if (!timeOk(gps) || !distOk(gps)) {
            gps = null;
        }

        // get more accurate location (mind the nulls) 
        Location location = null;
        if (gps == null && network == null) {
            return null;
        } else if (gps == null && network != null) {
            location = network;
        } else if (gps != null && network == null){
            location = gps;
        } else if (gps != null && network != null){
            location = gps.getAccuracy() < network.getAccuracy() ? gps : network;
        }
        
        lastLocation = location;
        return location;
    }

    /**
     * Listen updates on location and signal provider
     */
    private void addListener() {
        Log.i(LOG_TAG, "add listeners");
        removeListener();

        // initPhoneState listener
        telephonyManager.listen(dataListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS
                | PhoneStateListener.LISTEN_CELL_LOCATION | PhoneStateListener.LISTEN_DATA_CONNECTION_STATE
                | PhoneStateListener.LISTEN_SERVICE_STATE);

        // init location listeners
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, minLocationTime, minLocationDistance,
                dataListener);
        try {
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, minLocationTime,
                    minLocationDistance, dataListener);
        } catch (IllegalArgumentException iae) {
            Log.w(LOG_TAG, "Network provider is unavailable?! This seems to be an issue with the emulator", iae);
        }
    }

    private void removeListener() {
        Log.i(LOG_TAG, "remove listeners");
        locationManager.removeUpdates(dataListener);
        telephonyManager.listen(dataListener, PhoneStateListener.LISTEN_NONE);
    }
}
