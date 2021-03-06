package de.locked.cellmapper;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.TextView;
import android.widget.ToggleButton;

import com.actionbarsherlock.app.SherlockActivity;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuItem;

import de.locked.cellmapper.exporter.FileExporter;
import de.locked.cellmapper.exporter.UrlExporter;
import de.locked.cellmapper.model.DbHandler;
import de.locked.cellmapper.model.MobileStatusUtils;
import de.locked.cellmapper.model.Preferences;

public class CellMapperMain extends SherlockActivity {
    private static final String LOG_TAG = CellMapperMain.class.getName();
    private static final int UI_REFRESH_INTERVAL = 150; // ms
    private final Handler handler = new Handler();
    private Thread refresher;
    private boolean informedUserAboutProblems = false;
    private DbHandler db;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // set defaults
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
        db = DbHandler.get(this);

        ((ToggleButton) findViewById(R.id.activeToggleButton)).setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View arg0) {
                if (((ToggleButton) arg0).isChecked()) {
                    ensureServiceStarted();
                } else {
                    stopActiveService();
                }
            }
        });
        ((ToggleButton) findViewById(R.id.passiveToggleButton)).setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View arg0) {
                if (((ToggleButton) arg0).isChecked()) {
                    startPassiveService();
                } else {
                    stopPassiveService();
                }
            }
        });

        startUiUpdates();
        ensureServiceStarted();
        startPassiveService();

        // show what's new dialog once per version
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        // preferences.edit().putBoolean(Preferences.showWhatsNew, true).commit();
        if (preferences.getBoolean(Preferences.showWhatsNew, true)) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.whatsNewTitle)
                    .setMessage(R.string.whatsNewBody)
                    .setPositiveButton(android.R.string.ok, null)
                    .create().show();
            preferences.edit().putBoolean(Preferences.showWhatsNew, false).commit();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        setContentView(R.layout.activity_main);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(LOG_TAG, "resume activity");
        informedUserAboutProblems = false;
        startUiUpdates();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(LOG_TAG, "pause activity");
        stopUiUpdates();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(LOG_TAG, "destroy activity");
        stopActiveService();
        stopUiUpdates();
        stopPassiveService();
        db.close();
    }

    private void stopActiveService() {
        boolean success = stopService(new Intent(this, ActiveListenerService.class));
        Log.i(LOG_TAG, "stopping service succeeded: " + success);
    }

    private void ensureServiceStarted() {
        Log.d(LOG_TAG, "ensuring that the service is up");
        if (MobileStatusUtils.isServiceRunning(this, ActiveListenerService.class)) {
            return;
        }

        // check if location check is allowed at all
        String locationProvidersAllowed = Secure.getString(getContentResolver(), Secure.LOCATION_PROVIDERS_ALLOWED);
        if (locationProvidersAllowed == null || locationProvidersAllowed.length() == 0) {
            String msg = "Positioning is completely disabled in your device. Please enable it.";
            Log.i(LOG_TAG, msg);
            maybeShowGPSDisabledAlertToUser(msg);
            return;
        }

        // GPS disabled
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        boolean gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        if (!gpsEnabled) {
            maybeShowGPSDisabledAlertToUser("GPS is disabled in your device. Please enable it.");
            return;
        }

        // now start!
        Log.i(LOG_TAG, "starting service");
        startService(new Intent(this, ActiveListenerService.class));
    }

    private void startPassiveService() {
        if (MobileStatusUtils.isServiceRunning(this, PassiveListenerService.class)) {
            return;
        }
        startService(new Intent(this, PassiveListenerService.class));
    }

    private void stopPassiveService() {
        stopService(new Intent(this, PassiveListenerService.class));
    }

    private void startUiUpdates() {
        // do nothing if we're alive
        if (refresher != null && refresher.isAlive()) {
            return;
        }
        final Context context = getApplicationContext();

        refresher = new Thread() {
            @Override
            public void run() {
                try {
                    Looper.prepare();
                    while (!interrupted()) {
                        refresh();
                        sleep(UI_REFRESH_INTERVAL); // all Xs
                    }
                    Looper.loop();
                } catch (Exception e) {
                    Log.i(LOG_TAG, "interrupted refresh thread");
                }
            }

            private void refresh() {
                final StringBuilder sb = new StringBuilder(300);
                sb.append("Active  service running: " + MobileStatusUtils.isServiceRunning(context, ActiveListenerService.class) + "\n");
                sb.append("Passive service running: " + MobileStatusUtils.isServiceRunning(context, PassiveListenerService.class) + "\n");
                sb.append(db.getLastEntryString()).append("\n");
                sb.append("Data rows: " + db.getRows()).append("\n");
                sb.append("------\n");
                sb.append(db.getLastRowAsString());

                final boolean isActiveRunning = MobileStatusUtils.isServiceRunning(context, ActiveListenerService.class);
                final boolean isPassiveRunning = MobileStatusUtils.isServiceRunning(context, PassiveListenerService.class);

                // update UI
                handler.post(new Runnable() {

                    @Override
                    public void run() {
                        ((TextView) findViewById(R.id.textfield)).setText(sb.toString());
                        ((ToggleButton) findViewById(R.id.activeToggleButton)).setChecked(isActiveRunning);
                        ((ToggleButton) findViewById(R.id.passiveToggleButton)).setChecked(isPassiveRunning);
                    }
                });
            }

        };
        refresher.start();
    }

    private void stopUiUpdates() {
        if (refresher != null) {
            refresher.interrupt();
            refresher = null;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getSupportMenuInflater().inflate(R.menu.activity_main, menu);
        return true;
    }

    @Override
    public boolean onMenuItemSelected(int featureId, MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_settings:
                startActivity(new Intent(this, MyPreferenceActivity.class));
                return true;

            case R.id.menu_upload:
                upload();
                return true;

            case R.id.menu_saveSD:
                new FileExporter(this, "SignalStrength/data").execute();
                return true;

            default:
                return false;
        }
    }

    private void upload() {
        Log.i(LOG_TAG, "upload data");

        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        String url = preferences.getString(Preferences.uploadURL, null);

        if (null == url || url.trim().length() == 0) {
            openSettings("You need to set an upload URL before you can upload data\n"
                    + "Do you want to enter an upload URL now?");
            return;
        }

        if (!preferences.getBoolean(Preferences.licenseAgreed, false)) {
            openDialog("In order to upload, you first must agree that you agree to the license of the "
                    + "service to which you are uploading your data. Do you agree to the "
                    + "license to the service to which you are uploading?", android.R.string.ok,
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int id) {
                            preferences.edit().putBoolean(Preferences.licenseAgreed, true).commit();
                            dialog.cancel();
                        }
                    });
            Log.d(LOG_TAG, "agreed: " + preferences.getBoolean(Preferences.licenseAgreed, false));
            return;
        }

        new UrlExporter(this).execute();
    }

    /**
     * show a pop up that tells the user that he wants GPS but it's disabled in
     * the phone. - I want lambdas :-(
     */
    private void maybeShowGPSDisabledAlertToUser(String message) {
        if (informedUserAboutProblems) {
            return;
        }
        openDialog(message, R.string.open_settings, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int id) {
                dialog.dismiss();
                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            }
        });
        informedUserAboutProblems = true;
    }

    private void openDialog(String message, int okId, DialogInterface.OnClickListener okListener) {
        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);
        alertDialogBuilder.setMessage(message).setCancelable(false).setPositiveButton(okId, okListener)
                .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        dialog.cancel();
                    }
                });
        alertDialogBuilder.create().show();
    }

    private void openSettings(String message) {
        final Context context = this;
        openDialog(message, R.string.open_settings, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int id) {
                dialog.dismiss();
                startActivity(new Intent(context, MyPreferenceActivity.class));
            }
        });
    }
}
