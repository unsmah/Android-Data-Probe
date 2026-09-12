package com.example.dataprobe;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.LocationSettingsResponse;
import com.google.android.gms.location.Priority;
import com.google.android.gms.location.SettingsClient;
import com.google.android.gms.tasks.Task;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private WifiManager wifiManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;
    private LocationManager locationManager;
    private FusedLocationProviderClient fusedClient;
    private LocationCallback locationCallback;

    private BroadcastReceiver wifiScanReceiver;
    private BroadcastReceiver bluetoothReceiver;
    private BroadcastReceiver stateReceiver;
    private ScanCallback bleCallback;

    private boolean isWifiScanning = false;
    private boolean isBluetoothScanning = false;
    private boolean liveWifiScanning = false;
    private static final int LIVE_SCAN_INTERVAL_MS = 35000;
    private static final String WIFI_HISTORY_FILE = "wifi_scan_history.json";
    private static final int WIFI_HISTORY_MAX = 200;
    private final Map<String, JSONObject> btSeen = new HashMap<>();
    private final Handler scanHandler = new Handler(Looper.getMainLooper());

    private static final int PERM_REQ = 1001;
    private static final int BT_ENABLE_REQ = 1002;
    private static final int LOC_ENABLE_REQ = 1003;
    private static final String HISTORY_FILE = "location_history.json";
    private static final int HISTORY_MAX = 500;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = (bm != null) ? bm.getAdapter() : BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter != null) bleScanner = bluetoothAdapter.getBluetoothLeScanner();
        fusedClient = LocationServices.getFusedLocationProviderClient(this);

        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient() {
            private boolean handleExternal(String url) {
                if (url == null) return false;
                if (url.startsWith("file://") || url.startsWith("javascript:")
                        || url.startsWith("about:")) return false;
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                return handleExternal(req.getUrl().toString());
            }
            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return handleExternal(url);
            }
        });
        webView.addJavascriptInterface(new AndroidBridge(), "Android");
        webView.loadUrl("file:///android_asset/index.html");
        setContentView(webView);

        registerStateReceiver();
        requestRuntimePermissions();
    }

    /* ================= state receivers ================= */

    private void registerStateReceiver() {
        stateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
                scanHandler.postDelayed(() -> {
                    pushPermissionStatus();
                    if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) pushWifiInfo();
                    if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) pushBondedDevices();
                    if (LocationManager.PROVIDERS_CHANGED_ACTION.equals(action)) pushLocation();
                }, 500);
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        f.addAction(LocationManager.PROVIDERS_CHANGED_ACTION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(stateReceiver, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(stateReceiver, f);
    }

    private void pushWifiInfo() {
        if (webView == null) return;
        final String js = "window.onWifiInfoUpdated(" + new AndroidBridge().getWifiInfo() + ")";
        webView.post(() -> webView.evaluateJavascript(js, null));
    }

    private void pushBondedDevices() {
        if (webView == null) return;
        final String js = "window.onBondedDevicesUpdated(" + new AndroidBridge().getBluetoothBondedDevices() + ")";
        webView.post(() -> webView.evaluateJavascript(js, null));
    }

    private void pushLocation() {
        if (webView == null) return;
        final String js = "window.onLocationUpdated(" + new AndroidBridge().getLocation() + ")";
        webView.post(() -> webView.evaluateJavascript(js, null));
    }

    /* ================= permissions ================= */

    private List<String> requiredPermissions() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        perms.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        perms.add(Manifest.permission.READ_PHONE_STATE);
        if (Build.VERSION.SDK_INT >= 31) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
            perms.add(Manifest.permission.BLUETOOTH_SCAN);
        }
        return perms;
    }

    private void requestRuntimePermissions() {
        List<String> missing = new ArrayList<>();
        for (String p : requiredPermissions()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), PERM_REQ);
    }

    private boolean hasAllPermissions() {
        for (String p : requiredPermissions()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != PERM_REQ) return;

        boolean locGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;

        if (locGranted && !isLocationEnabled()) {
            new Handler(Looper.getMainLooper()).postDelayed(this::promptEnableLocation, 300);
        }

        pushPermissionStatus();

        // Refresh live data after the user grants permissions
        scanHandler.postDelayed(() -> {
            pushWifiInfo();
            pushBondedDevices();
            pushLocation();
            pushPermissionStatus();
        }, 500);
        scanHandler.postDelayed(() -> {
            pushWifiInfo();
            pushBondedDevices();
            pushLocation();
            pushPermissionStatus();
        }, 1500);
    }

    @Override
    protected void onResume() {
        super.onResume();
        pushPermissionStatus();
        pushWifiInfo();
        pushBondedDevices();
        pushLocation();
    }

    private void pushPermissionStatus() {
        if (webView == null) return;
        final boolean ok = hasAllPermissions();
        final boolean locOn = isLocationEnabled();
        final boolean btOn = bluetoothAdapter != null && bluetoothAdapter.isEnabled();
        final boolean wifiOn = wifiManager != null && wifiManager.isWifiEnabled();
        webView.post(() -> webView.evaluateJavascript(
            "window.onPermissionsChanged(" + ok + "," + locOn + "," + btOn + "," + wifiOn + ")", null));
    }

    private boolean isLocationEnabled() {
        try {
            if (Build.VERSION.SDK_INT >= 28) return locationManager.isLocationEnabled();
            return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) { return false; }
    }

    private void promptEnableLocation() {
        try {
            LocationRequest req = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY, 10000).build();
            LocationSettingsRequest.Builder b = new LocationSettingsRequest.Builder()
                .addLocationRequest(req).setAlwaysShow(true);
            SettingsClient client = LocationServices.getSettingsClient(this);
            Task<LocationSettingsResponse> task = client.checkLocationSettings(b.build());
            task.addOnFailureListener(e -> {
                if (e instanceof ResolvableApiException) {
                    try { ((ResolvableApiException) e).startResolutionForResult(this, LOC_ENABLE_REQ); }
                    catch (Exception ignored) {}
                }
            });
        } catch (Exception ignored) {}
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        pushPermissionStatus();
        scanHandler.postDelayed(() -> {
            pushWifiInfo();
            pushBondedDevices();
            pushLocation();
        }, 800);
    }

    /* ================= location history ================= */

    private File historyFile() { return new File(getFilesDir(), HISTORY_FILE); }

    private JSONArray readHistory() {
        try {
            File f = historyFile();
            if (!f.exists()) return new JSONArray();
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            return new JSONArray(s);
        } catch (Exception e) { return new JSONArray(); }
    }

    private void writeHistory(JSONArray arr) {
        try {
            Files.write(historyFile().toPath(), arr.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    private void appendHistory(Location loc) {
        try {
            JSONArray arr = readHistory();
            JSONObject o = new JSONObject();
            o.put("lat", loc.getLatitude());
            o.put("lon", loc.getLongitude());
            o.put("acc", loc.getAccuracy());
            o.put("t", loc.getTime());
            arr.put(o);
            while (arr.length() > HISTORY_MAX) arr.remove(0);
            writeHistory(arr);
        } catch (Exception ignored) {}
    }

    /* ================= bridge ================= */

    public class AndroidBridge {

        @JavascriptInterface
        public String getDeviceInfo() {
            JSONObject o = new JSONObject();
            try {
                o.put("ANDROID_ID", Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID));
                o.put("MANUFACTURER", Build.MANUFACTURER);
                o.put("BRAND", Build.BRAND);
                o.put("MODEL", Build.MODEL);
                o.put("DEVICE", Build.DEVICE);
                o.put("HARDWARE", Build.HARDWARE);
                o.put("BOARD", Build.BOARD);
                o.put("BOOTLOADER", Build.BOOTLOADER);
                o.put("FINGERPRINT", Build.FINGERPRINT);
                o.put("OS_VERSION", Build.VERSION.RELEASE);
                o.put("API_LEVEL", Build.VERSION.SDK_INT);
                o.put("SECURITY_PATCH", Build.VERSION.SECURITY_PATCH);
                o.put("BUILD_ID", Build.ID);
            } catch (Exception ignored) {}
            return o.toString();
        }

        @JavascriptInterface
        public String getPermissionStatus() {
            JSONObject o = new JSONObject();
            try {
                o.put("allGranted", hasAllPermissions());
                o.put("locationPermission",
                    checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED);
                o.put("locationEnabled", isLocationEnabled());
                o.put("bluetoothEnabled", bluetoothAdapter != null && bluetoothAdapter.isEnabled());
                o.put("wifiEnabled", wifiManager != null && wifiManager.isWifiEnabled());
            } catch (Exception ignored) {}
            return o.toString();
        }

        @JavascriptInterface
        public void requestPermissionsFromUi() { runOnUiThread(MainActivity.this::requestRuntimePermissions); }

        @JavascriptInterface
        public void enableLocationFromUi() { runOnUiThread(MainActivity.this::promptEnableLocation); }

        @JavascriptInterface
        public void enableBluetoothFromUi() {
            runOnUiThread(() -> {
                try { startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), BT_ENABLE_REQ); }
                catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void openWifiSettings() {
            runOnUiThread(() -> {
                try { startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)); } catch (Exception ignored) {}
            });
        }

        /* ===== WiFi ===== */
        @JavascriptInterface
        public String getWifiInfo() {
            JSONObject o = new JSONObject();
            try {
                if (!wifiManager.isWifiEnabled()) {
                    o.put("error", "Wi-Fi is off. Turn it on to see the current network.");
                    return o.toString();
                }
                WifiInfo info = wifiManager.getConnectionInfo();
                o.put("SSID", info.getSSID());
                o.put("BSSID", info.getBSSID());
                o.put("RSSI", info.getRssi());
                o.put("LinkSpeed", info.getLinkSpeed());
                o.put("Frequency", info.getFrequency());
                o.put("IPAddress", intToIp(info.getIpAddress()));
                o.put("MACAddress", info.getMacAddress());
            } catch (Exception e) {
                try { o.put("error", e.getMessage()); } catch (Exception ignored) {}
            }
            return o.toString();
        }

        @JavascriptInterface
        public void startWifiScan() {
            runOnUiThread(() -> MainActivity.this.startWifiScanInternal(true, false));
        }

        @JavascriptInterface
        public void startLiveWifiScan() {
            runOnUiThread(() -> {
                if (liveWifiScanning) return;
                liveWifiScanning = true;
                MainActivity.this.startWifiScanInternal(true, true);
            });
        }

        @JavascriptInterface
        public void stopLiveWifiScan() {
            runOnUiThread(() -> { liveWifiScanning = false; });
        }

        @JavascriptInterface
        public String getWifiScanHistory() { return MainActivity.this.readWifiHistory().toString(); }

        @JavascriptInterface
        public void clearWifiScanHistory() {
            runOnUiThread(() -> {
                try { MainActivity.this.wifiHistoryFile().delete(); } catch (Exception ignored) {}
            });
        }

        /* ===== Bluetooth ===== */
        @JavascriptInterface
        public String getBluetoothBondedDevices() {
            JSONArray arr = new JSONArray();
            try {
                if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return arr.toString();
                Set<BluetoothDevice> bonded = bluetoothAdapter.getBondedDevices();
                for (BluetoothDevice d : bonded) {
                    JSONObject o = new JSONObject();
                    String name = null;
                    try { name = d.getName(); } catch (SecurityException ignored) {}
                    o.put("Name", name != null ? name : "(unnamed)");
                    o.put("Address", safeAddress(d));
                    o.put("Type", d.getType());
                    o.put("Bonded", true);
                    arr.put(o);
                }
            } catch (Exception ignored) {}
            return arr.toString();
        }

        @JavascriptInterface
        public void startBluetoothDiscovery() {
            runOnUiThread(() -> {
                if (bluetoothAdapter == null) {
                    webView.evaluateJavascript(
                        "window.onBluetoothError('Bluetooth not supported on this device.')", null);
                    return;
                }
                if (!bluetoothAdapter.isEnabled()) {
                    webView.evaluateJavascript(
                        "window.onBluetoothError('Bluetooth is off. Turn it on in the top banner.')", null);
                    return;
                }
                if (isBluetoothScanning) return;
                isBluetoothScanning = true;
                btSeen.clear();

                bluetoothReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context ctx, Intent intent) {
                        String action = intent.getAction();
                        if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                            BluetoothDevice device;
                            if (Build.VERSION.SDK_INT >= 33)
                                device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                            else
                                device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                            if (device != null) {
                                int rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
                                emitBluetoothDevice(device, rssi, "classic");
                            }
                        } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                            if (bleCallback == null) finishBluetoothScan();
                        }
                    }
                };
                IntentFilter filter = new IntentFilter();
                filter.addAction(BluetoothDevice.ACTION_FOUND);
                filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
                if (Build.VERSION.SDK_INT >= 33) registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED);
                else registerReceiver(bluetoothReceiver, filter);
                try { bluetoothAdapter.startDiscovery(); } catch (SecurityException ignored) {}

                if (bleScanner != null) {
                    bleCallback = new ScanCallback() {
                        @Override public void onScanResult(int type, ScanResult result) {
                            emitBluetoothDevice(result.getDevice(), result.getRssi(), "ble");
                        }
                        @Override public void onBatchScanResults(List<ScanResult> results) {
                            for (ScanResult r : results) emitBluetoothDevice(r.getDevice(), r.getRssi(), "ble");
                        }
                    };
                    ScanSettings settings = new ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
                    try { bleScanner.startScan(null, settings, bleCallback); }
                    catch (SecurityException ignored) {}
                }
                scanHandler.postDelayed(this::stopBluetoothDiscoveryInternal, 15000);
            });
        }

        private void emitBluetoothDevice(BluetoothDevice device, int rssi, String source) {
            try {
                String address = safeAddress(device);
                if (address == null || btSeen.containsKey(address)) return;
                JSONObject o = new JSONObject();
                String name = null;
                try { name = device.getName(); } catch (SecurityException ignored) {}
                o.put("Name", name != null ? name : "(unnamed)");
                o.put("Address", address);
                o.put("RSSI", rssi);
                o.put("Type", device.getType());
                try { o.put("Bonded", device.getBondState() == BluetoothDevice.BOND_BONDED); }
                catch (Exception ignored) {}
                o.put("Source", source);
                btSeen.put(address, o);
                final String js = "window.onBluetoothDeviceFound(" + o + ")";
                webView.post(() -> webView.evaluateJavascript(js, null));
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void stopBluetoothDiscovery() { runOnUiThread(this::stopBluetoothDiscoveryInternal); }

        private void stopBluetoothDiscoveryInternal() {
            try { if (bluetoothAdapter != null && bluetoothAdapter.isDiscovering()) bluetoothAdapter.cancelDiscovery(); }
            catch (SecurityException ignored) {}
            try { if (bleScanner != null && bleCallback != null) bleScanner.stopScan(bleCallback); }
            catch (Exception ignored) {}
            bleCallback = null;
            finishBluetoothScan();
        }

        private void finishBluetoothScan() {
            if (!isBluetoothScanning) return;
            isBluetoothScanning = false;
            webView.post(() -> webView.evaluateJavascript("window.onBluetoothScanComplete()", null));
            if (bluetoothReceiver != null) {
                try { unregisterReceiver(bluetoothReceiver); } catch (Exception ignored) {}
                bluetoothReceiver = null;
            }
        }

        /* ===== Location ===== */
        @JavascriptInterface
        public String getLocation() {
            JSONObject o = new JSONObject();
            try {
                if (!isLocationEnabled()) {
                    o.put("error", "Location is off. Turn it on to see coordinates.");
                    return o.toString();
                }
                Location loc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (loc == null) loc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (loc != null) {
                    o.put("Latitude", loc.getLatitude());
                    o.put("Longitude", loc.getLongitude());
                    o.put("Accuracy", loc.getAccuracy());
                    o.put("Altitude", loc.getAltitude());
                    o.put("Speed", loc.getSpeed());
                    o.put("MapsUrl", "https://www.google.com/maps?q=" + loc.getLatitude() + "," + loc.getLongitude());
                } else {
                    o.put("error", "No location fix yet. Move to an open area and wait.");
                }
            } catch (Exception e) {
                try { o.put("error", e.getMessage()); } catch (Exception ignored) {}
            }
            return o.toString();
        }

        @JavascriptInterface
        public void startLiveTracking() {
            runOnUiThread(() -> {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
                    webView.evaluateJavascript(
                        "window.onLiveLocationError('Location permission not granted')", null);
                    return;
                }
                if (locationCallback != null) return;
                if (!isLocationEnabled()) {
                    webView.evaluateJavascript(
                        "window.onLiveLocationError('Location is off. Turn it on first.')", null);
                    return;
                }

                LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000)
                    .setMinUpdateIntervalMillis(1000).build();

                locationCallback = new LocationCallback() {
                    @Override
                    public void onLocationResult(@NonNull LocationResult result) {
                        Location loc = result.getLastLocation();
                        if (loc == null) return;
                        appendHistory(loc);
                        try {
                            JSONObject o = new JSONObject();
                            o.put("Latitude", loc.getLatitude());
                            o.put("Longitude", loc.getLongitude());
                            o.put("Accuracy", loc.getAccuracy());
                            o.put("Altitude", loc.getAltitude());
                            o.put("Speed", loc.getSpeed());
                            o.put("Timestamp", loc.getTime());
                            final String js = "window.onLiveLocationUpdate(" + o + ")";
                            webView.post(() -> webView.evaluateJavascript(js, null));
                        } catch (Exception ignored) {}
                    }
                };
                try {
                    fusedClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper());
                    webView.post(() -> webView.evaluateJavascript("window.onLiveLocationStarted()", null));
                } catch (SecurityException e) {
                    webView.post(() -> webView.evaluateJavascript(
                        "window.onLiveLocationError('" + escape(e.getMessage()) + "')", null));
                    locationCallback = null;
                }
            });
        }

        @JavascriptInterface
        public void stopLiveTracking() {
            runOnUiThread(() -> {
                if (fusedClient != null && locationCallback != null) {
                    fusedClient.removeLocationUpdates(locationCallback);
                    locationCallback = null;
                }
                webView.post(() -> webView.evaluateJavascript("window.onLiveLocationStopped()", null));
            });
        }

        @JavascriptInterface
        public String getLocationHistory() { return readHistory().toString(); }

        @JavascriptInterface
        public void clearLocationHistory() {
            runOnUiThread(() -> { try { historyFile().delete(); } catch (Exception ignored) {} });
        }

        /* ===== Installed apps (async, streamed) ===== */

        /** Exposed to JS: fetches apps on a background thread and pushes them back. */
        @JavascriptInterface
        public void loadAppsAsync(final String filter) {
            new Thread(() -> {
                final String result = getInstalledAppsInternal(filter);
                writeAppsCache(result);
                webView.post(() -> webView.evaluateJavascript(
                    "window.onAppsLoaded(" + result + ")", null));
            }, "apps-loader").start();
        }

        @JavascriptInterface
        public String getCachedApps() { return readAppsCache(); }

        @JavascriptInterface
        public void clearAppsCache() {
            new Thread(() -> { try { appsCacheFile().delete(); } catch (Exception ignored) {} }).start();
        }

        private File appsCacheFile() { return new File(getFilesDir(), "apps_cache.json"); }

        private void writeAppsCache(String json) {
            try { Files.write(appsCacheFile().toPath(), json.getBytes(StandardCharsets.UTF_8)); }
            catch (Exception ignored) {}
        }

        private String readAppsCache() {
            try {
                File f = appsCacheFile();
                if (!f.exists()) return "[]";
                return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            } catch (Exception e) { return "[]"; }
        }

        /** Synchronous version (kept for backwards compat, but not used by UI). */
        @JavascriptInterface
        public String getInstalledApps(String filter) { return getInstalledAppsInternal(filter); }

        private String getInstalledAppsInternal(String filter) {
            JSONArray arr = new JSONArray();
            try {
                PackageManager pm = getPackageManager();
                List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
                for (ApplicationInfo app : apps) {
                    boolean isSystem = (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                    String type = isSystem ? "system" : "user";
                    if (filter != null && !"all".equals(filter) && !type.equals(filter)) continue;

                    JSONObject o = new JSONObject();
                    o.put("Package", app.packageName);
                    String name;
                    try { name = pm.getApplicationLabel(app).toString(); }
                    catch (Exception e) { name = app.packageName; }
                    o.put("Name", name);
                    o.put("Type", type);
                    try {
                        Drawable d = pm.getApplicationIcon(app);
                        Bitmap bm = drawableToBitmap(d, 56);
                        if (bm != null) {
                            ByteArrayOutputStream bos = new ByteArrayOutputStream();
                            bm.compress(Bitmap.CompressFormat.PNG, 70, bos);
                            o.put("Icon", "data:image/png;base64," +
                                Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP));
                        }
                    } catch (Exception ignored) {}
                    arr.put(o);
                }
            } catch (Exception ignored) {}
            return arr.toString();
        }

        private Bitmap drawableToBitmap(Drawable drawable, int size) {
            try {
                if (drawable instanceof BitmapDrawable) {
                    Bitmap src = ((BitmapDrawable) drawable).getBitmap();
                    if (src != null) return Bitmap.createScaledBitmap(src, size, size, true);
                }
                Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bmp);
                drawable.setBounds(0, 0, size, size);
                drawable.draw(canvas);
                return bmp;
            } catch (Exception e) { return null; }
        }

        /* ===== helpers ===== */
        private String intToIp(int ip) {
            return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." +
                   ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF);
        }
        private String safeAddress(BluetoothDevice d) {
            try { return d.getAddress(); } catch (SecurityException e) { return null; }
        }
        private String escape(String s) {
            return s == null ? "" : s.replace("\\", "\\\\").replace("'", "\\'");
        }
    }

    /* ================= WiFi scan internals ================= */

    private void startWifiScanInternal(boolean saveToHistory, boolean scheduleNext) {
        if (isWifiScanning) return;
        if (!wifiManager.isWifiEnabled()) {
            webView.evaluateJavascript(
                "window.onWifiScanError('Wi-Fi is off. Turn it on in the top banner.')", null);
            liveWifiScanning = false;
            return;
        }
        isWifiScanning = true;
        wifiScanReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                pushWifiResultsInternal(saveToHistory, scheduleNext);
            }
        };
        IntentFilter filter = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(wifiScanReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(wifiScanReceiver, filter);
        }
        try { wifiManager.startScan(); } catch (Exception ignored) {}
        scanHandler.postDelayed(() -> {
            if (wifiScanReceiver != null) pushWifiResultsInternal(saveToHistory, scheduleNext);
        }, 6000);
    }

    private void pushWifiResultsInternal(boolean saveToHistory, boolean scheduleNext) {
        if (wifiScanReceiver == null) return;
        try {
            List<android.net.wifi.ScanResult> results = wifiManager.getScanResults();
            JSONArray arr = new JSONArray();
            if (results != null) {
                for (android.net.wifi.ScanResult r : results) {
                    JSONObject o = new JSONObject();
                    o.put("SSID", r.SSID);
                    o.put("BSSID", r.BSSID);
                    o.put("Level", r.level);
                    o.put("Frequency", r.frequency);
                    o.put("Capabilities", r.capabilities);
                    arr.put(o);
                }
            }
            if (saveToHistory && arr.length() > 0) saveWifiScanToHistory(arr);
            final String js = "window.onWifiScanComplete(" + arr + ")";
            webView.post(() -> webView.evaluateJavascript(js, null));
        } catch (Exception ignored) {
        } finally {
            isWifiScanning = false;
            try { unregisterReceiver(wifiScanReceiver); } catch (Exception ignored) {}
            wifiScanReceiver = null;
            if (scheduleNext && liveWifiScanning) {
                scanHandler.postDelayed(() -> {
                    if (liveWifiScanning) startWifiScanInternal(true, true);
                }, LIVE_SCAN_INTERVAL_MS);
            }
        }
    }

    private File wifiHistoryFile() { return new File(getFilesDir(), WIFI_HISTORY_FILE); }

    private JSONArray readWifiHistory() {
        try {
            File f = wifiHistoryFile();
            if (!f.exists()) return new JSONArray();
            String txt = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            return new JSONArray(txt);
        } catch (Exception e) { return new JSONArray(); }
    }

    private void writeWifiHistory(JSONArray arr) {
        try {
            Files.write(wifiHistoryFile().toPath(), arr.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    private void saveWifiScanToHistory(JSONArray networks) {
        try {
            JSONArray history = readWifiHistory();
            JSONObject entry = new JSONObject();
            entry.put("t", System.currentTimeMillis());
            try {
                Location loc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (loc == null) loc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (loc != null) {
                    entry.put("lat", loc.getLatitude());
                    entry.put("lon", loc.getLongitude());
                    entry.put("acc", loc.getAccuracy());
                }
            } catch (Exception ignored) {}
            entry.put("networks", networks);
            history.put(entry);
            while (history.length() > WIFI_HISTORY_MAX) history.remove(0);
            writeWifiHistory(history);
        } catch (Exception ignored) {}
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        try { if (wifiScanReceiver != null) unregisterReceiver(wifiScanReceiver); } catch (Exception ignored) {}
        try { if (bluetoothReceiver != null) unregisterReceiver(bluetoothReceiver); } catch (Exception ignored) {}
        try { if (stateReceiver != null) unregisterReceiver(stateReceiver); } catch (Exception ignored) {}
        try { if (bleScanner != null && bleCallback != null) bleScanner.stopScan(bleCallback); } catch (Exception ignored) {}
        try {
            if (fusedClient != null && locationCallback != null)
                fusedClient.removeLocationUpdates(locationCallback);
        } catch (Exception ignored) {}
        super.onDestroy();
    }
}
