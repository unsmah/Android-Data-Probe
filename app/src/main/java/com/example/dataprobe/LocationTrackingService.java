package com.example.dataprobe;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class LocationTrackingService extends Service {

    public static final String ACTION_START   = "com.example.dataprobe.START_TRACKING";
    public static final String ACTION_STOP    = "com.example.dataprobe.STOP_TRACKING";
    public static final String ACTION_UPDATE  = "com.example.dataprobe.TRACKING_UPDATE";
    public static final String ACTION_STOPPED = "com.example.dataprobe.TRACKING_STOPPED";
    public static final String CHANNEL_ID     = "location_tracking";
    public static final int    NOTIF_ID       = 4242;
    private static final String ACTIVE_FILE   = "tracking_active.json";
    private static final String SESSIONS_FILE = "tracking_sessions.json";
    private static final int    MAX_SESSION_POINTS = 20000;
    private static final int    MAX_SESSIONS = 200;

    public static volatile boolean isRunning = false;

    private FusedLocationProviderClient fusedClient;
    private LocationCallback locationCallback;
    private final List<Location> points = new ArrayList<>();
    private long startTime = 0;
    private double distanceMeters = 0;
    private double maxSpeed = 0;
    private double totalSpeed = 0;
    private int speedSamples = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        fusedClient = LocationServices.getFusedLocationProviderClient(this);
        createChannel();
    }

    @Nullable @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_START.equals(action)) {
            if (!isRunning) startTracking();
        } else if (ACTION_STOP.equals(action)) {
            stopTracking();
            stopForeground(true);
            stopSelf();
        }
        return START_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Location tracking", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Live location tracking");
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Intent stopIntent = new Intent(this, LocationTrackingService.class);
        stopIntent.setAction(ACTION_STOP);
        int piFlags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent, piFlags);

        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 1, openIntent, piFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Tracking your location")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    private void startTracking() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return;
        }
        startTime = System.currentTimeMillis();
        points.clear();
        distanceMeters = 0; maxSpeed = 0; totalSpeed = 0; speedSamples = 0;
        isRunning = true;

        Notification n = buildNotification("Starting…");
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIF_ID, n);
        }

        LocationRequest req = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY, 3000)
            .setMinUpdateIntervalMillis(1000)
            .setMinUpdateDistanceMeters(2)
            .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                for (Location loc : result.getLocations()) addPoint(loc);
            }
        };
        try {
            fusedClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper());
        } catch (SecurityException e) {
            stopSelf();
        }
    }

    private void addPoint(Location loc) {
        if (points.size() >= MAX_SESSION_POINTS) return;
        if (!points.isEmpty()) {
            Location prev = points.get(points.size() - 1);
            distanceMeters += prev.distanceTo(loc);
        }
        if (loc.hasSpeed() && loc.getSpeed() > 0) {
            if (loc.getSpeed() > maxSpeed) maxSpeed = loc.getSpeed();
            totalSpeed += loc.getSpeed();
            speedSamples++;
        }
        points.add(loc);
        saveActive();
        updateNotification();
        broadcastPoint(loc);
    }

    private void updateNotification() {
        String text = points.size() + " pts · " +
            String.format(java.util.Locale.US, "%.0f m", distanceMeters);
        Notification n = buildNotification(text);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, n);
    }

    private void broadcastPoint(Location loc) {
        try {
            JSONObject o = new JSONObject();
            o.put("lat", loc.getLatitude());
            o.put("lon", loc.getLongitude());
            o.put("acc", loc.getAccuracy());
            o.put("alt", loc.getAltitude());
            o.put("speed", loc.getSpeed());
            o.put("t", loc.getTime());
            Intent i = new Intent(ACTION_UPDATE);
            i.putExtra("point", o.toString());
            i.putExtra("count", points.size());
            i.putExtra("distance", distanceMeters);
            i.putExtra("maxSpeed", maxSpeed);
            sendBroadcast(i);
        } catch (Exception ignored) {}
    }

    private File activeFile() { return new File(getFilesDir(), ACTIVE_FILE); }
    private File sessionsFile() { return new File(getFilesDir(), SESSIONS_FILE); }

    private JSONArray pointsToJson() throws Exception {
        JSONArray arr = new JSONArray();
        for (Location l : points) {
            JSONArray p = new JSONArray();
            p.put(l.getLatitude());
            p.put(l.getLongitude());
            p.put(l.getAccuracy());
            p.put(l.getAltitude());
            p.put(l.getSpeed());
            p.put(l.getTime());
            arr.put(p);
        }
        return arr;
    }

    private void saveActive() {
        try {
            JSONObject root = new JSONObject();
            root.put("id", String.valueOf(startTime));
            root.put("startTime", startTime);
            root.put("distanceMeters", distanceMeters);
            root.put("maxSpeed", maxSpeed);
            root.put("avgSpeed", speedSamples > 0 ? totalSpeed / speedSamples : 0);
            root.put("count", points.size());
            root.put("points", pointsToJson());
            Files.write(activeFile().toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    private void stopTracking() {
        if (locationCallback != null) {
            try { fusedClient.removeLocationUpdates(locationCallback); } catch (Exception ignored) {}
            locationCallback = null;
        }
        isRunning = false;
        long endTime = System.currentTimeMillis();
        if (points.isEmpty()) {
            activeFile().delete();
            return;
        }
        try {
            JSONObject root = new JSONObject();
            root.put("id", String.valueOf(startTime));
            root.put("startTime", startTime);
            root.put("endTime", endTime);
            root.put("durationMs", endTime - startTime);
            root.put("distanceMeters", distanceMeters);
            root.put("maxSpeed", maxSpeed);
            root.put("avgSpeed", speedSamples > 0 ? totalSpeed / speedSamples : 0);
            root.put("points", pointsToJson());

            JSONArray sessions = new JSONArray();
            if (sessionsFile().exists()) {
                try {
                    sessions = new JSONArray(new String(
                        Files.readAllBytes(sessionsFile().toPath()), StandardCharsets.UTF_8));
                } catch (Exception ignored) {}
            }
            sessions.put(root);
            while (sessions.length() > MAX_SESSIONS) sessions.remove(0);
            Files.write(sessionsFile().toPath(), sessions.toString().getBytes(StandardCharsets.UTF_8));
            activeFile().delete();
            sendBroadcast(new Intent(ACTION_STOPPED));
        } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        if (locationCallback != null) {
            try { fusedClient.removeLocationUpdates(locationCallback); } catch (Exception ignored) {}
            locationCallback = null;
        }
        isRunning = false;
        super.onDestroy();
    }
}
