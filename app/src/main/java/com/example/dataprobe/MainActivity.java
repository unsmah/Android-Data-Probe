package com.example.dataprobe;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
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
import android.location.Location;
import android.location.LocationManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.LocationSettingsResponse;
import com.google.android.gms.location.Priority;
import com.google.android.gms.location.SettingsClient;
import com.google.android.gms.tasks.Task;
import org.json.JSONArray;
import org.json.JSONObject;
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

    private BroadcastReceiver wifiScanReceiver;
    private BroadcastReceiver bluetoothReceiver;
    private ScanCallback bleCallback;

    private boolean isWifiScanning = false;
    private boolean isBluetoothScanning = false;
    private final Map<String, JSONObject> btSeen = new HashMap<>();
    private final Handler scanHandler = new Handler(Looper.getMainLooper());

    private static final int PERM_REQ = 1001;
    private static final int BT_ENABLE_REQ = 1002;
    private static final int LOC_ENABLE_REQ = 1003;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = (bm != null) ? bm.getAdapter() : BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter != null) bleScanner = bluetoothAdapter.getBluetoothLeScanner();

        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new AndroidBridge(), "Android");
        webView.loadUrl("file:///android_asset/index.html");
        setContentView(webView);

        requestRuntimePermissions();
    }

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
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), PERM_REQ);
        }
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

        // After all permissions granted, if location toggle is off, offer the modal.
        boolean locGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (locGranted && !isLocationEnabled()) {
            new Handler(Looper.getMainLooper()).postDelayed(this::promptEnableLocation, 300);
        }
        pushPermissionStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            final boolean on = isLocationEnabled();
            webView.post(() ->
                webView.evaluateJavascript("window.onLocationStateChanged(" + on + ")", null));
            pushPermissionStatus();
        }
    }

    private void pushPermissionStatus() {
        if (webView == null) return;
        final boolean ok = hasAllPermissions();
        final boolean locOn = isLocationEnabled();
        final boolean btOn = bluetoothAdapter != null && bluetoothAdapter.isEnabled();
        webView.post(() -> webView.evaluateJavascript(
            "window.onPermissionsChanged(" + ok + "," + locOn + "," + btOn + ")", null));
    }

    private boolean isLocationEnabled() {
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 28) return lm.isLocationEnabled();
            return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
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
                    try {
                        ((ResolvableApiException) e).startResolutionForResult(
                            MainActivity.this, LOC_ENABLE_REQ);
                    } catch (Exception ignored) {}
                }
            });
        } catch (Exception ignored) {}
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        pushPermissionStatus();
    }

    public class AndroidBridge {

        /* ============ Device & OS ============ */
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

        /* ============ Permission state ============ */
        @JavascriptInterface
        public String getPermissionStatus() {
            JSONObject o = new JSONObject();
            try {
                o.put("allGranted", hasAllPermissions());
                o.put("locationPermission",
                    checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED);
                o.put("locationEnabled", isLocationEnabled());
                o.put("bluetoothEnabled", bluetoothAdapter != null && bluetoothAdapter.isEnabled());
            } catch (Exception ignored) {}
            return o.toString();
        }

        @JavascriptInterface
        public void requestPermissionsFromUi() {
            runOnUiThread(MainActivity.this::requestRuntimePermissions);
        }

        @JavascriptInterface
        public void enableLocationFromUi() {
            runOnUiThread(MainActivity.this::promptEnableLocation);
        }

        @JavascriptInterface
        public void enableBluetoothFromUi() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                    startActivityForResult(i, BT_ENABLE_REQ);
                } catch (Exception ignored) {}
            });
        }

        /* ============ Wi-Fi: current ============ */
        @JavascriptInterface
        public String getWifiInfo() {
            JSONObject o = new JSONObject();
            try {
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

        /* ============ Wi-Fi: live scan ============ */
        @JavascriptInterface
        public void startWifiScan() {
            runOnUiThread(() -> {
                if (isWifiScanning) return;
                isWifiScanning = true;

                wifiScanReceiver = new BroadcastReceiver() {
                    @Override public void onReceive(Context ctx, Intent intent) { pushWifiResults(); }
                };
                IntentFilter filter = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
                if (Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(wifiScanReceiver, filter, Context.RECEIVER_EXPORTED);
                } else {
                    registerReceiver(wifiScanReceiver, filter);
                }
                try { wifiManager.startScan(); }
                catch (Exception e) {
                    webView.post(() -> webView.evaluateJavascript(
                        "window.onWifiScanError('" + escape(e.getMessage()) + "')", null));
                }
                scanHandler.postDelayed(() -> { if (wifiScanReceiver != null) pushWifiResults(); }, 6000);
            });
        }

        private void pushWifiResults() {
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
                        o.put("ChannelWidth", r.channelWidth);
                        o.put("Timestamp", r.timestamp);
                        arr.put(o);
                    }
                }
                final String js = "window.onWifiScanComplete(" + arr + ")";
                webView.post(() -> webView.evaluateJavascript(js, null));
            } catch (Exception ignored) {
            } finally {
                isWifiScanning = false;
                try { unregisterReceiver(wifiScanReceiver); } catch (Exception ignored) {}
                wifiScanReceiver = null;
            }
        }

        /* ============ Bluetooth: bonded ============ */
        @JavascriptInterface
        public String getBluetoothBondedDevices() {
            JSONArray arr = new JSONArray();
            try {
                if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
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
                }
            } catch (Exception ignored) {}
            return arr.toString();
        }

        /* ============ Bluetooth: live discovery (classic + BLE) ============ */
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
                        "window.onBluetoothError('Bluetooth is off. Tap \"Turn on Bluetooth\" to enable it.')", null);
                    return;
                }
                if (isBluetoothScanning) return;
                isBluetoothScanning = true;
                btSeen.clear();

                /* --- classic discovery --- */
                bluetoothReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context ctx, Intent intent) {
                        String action = intent.getAction();
                        if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                            BluetoothDevice device;
                            if (Build.VERSION.SDK_INT >= 33) {
                                device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                            } else {
                                device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                            }
                            if (device != null) {
                                int rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
                                emitBluetoothDevice(device, rssi, "classic");
                            }
                        } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                            // classic done — BLE may still be running; finalize only if BLE also stopped
                            if (bleCallback == null) finishBluetoothScan();
                        }
                    }
                };
                IntentFilter filter = new IntentFilter();
                filter.addAction(BluetoothDevice.ACTION_FOUND);
                filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
                if (Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED);
                } else {
                    registerReceiver(bluetoothReceiver, filter);
                }
                try { bluetoothAdapter.startDiscovery(); } catch (SecurityException ignored) {}

                /* --- BLE scan in parallel --- */
                if (bleScanner != null) {
                    bleCallback = new ScanCallback() {
                        @Override public void onScanResult(int type, ScanResult result) {
                            emitBluetoothDevice(result.getDevice(), result.getRssi(), "ble");
                        }
                        @Override public void onBatchScanResults(List<ScanResult> results) {
                            for (ScanResult r : results)
                                emitBluetoothDevice(r.getDevice(), r.getRssi(), "ble");
                        }
                    };
                    ScanSettings settings = new ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
                    try { bleScanner.startScan(null, settings, bleCallback); }
                    catch (SecurityException ignored) {}
                }

                /* --- overall stop after 15s --- */
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
        public void stopBluetoothDiscovery() {
            runOnUiThread(this::stopBluetoothDiscoveryInternal);
        }

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

        /* ============ Location ============ */
        @JavascriptInterface
        public String getLocation() {
            JSONObject o = new JSONObject();
            try {
                LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                Location loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (loc == null) loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (loc != null) {
                    o.put("Latitude", loc.getLatitude());
                    o.put("Longitude", loc.getLongitude());
                    o.put("Accuracy", loc.getAccuracy());
                    o.put("Altitude", loc.getAltitude());
                    o.put("Speed", loc.getSpeed());
                } else {
                    o.put("error", "No location available. Ensure GPS is on and wait a moment.");
                }
            } catch (Exception e) {
                try { o.put("error", e.getMessage()); } catch (Exception ignored) {}
            }
            return o.toString();
        }

        /* ============ Installed Apps ============ */
        @JavascriptInterface
        public String getInstalledApps() {
            JSONArray arr = new JSONArray();
            try {
                PackageManager pm = getPackageManager();
                List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
                for (ApplicationInfo app : apps) {
                    JSONObject o = new JSONObject();
                    o.put("Package", app.packageName);
                    o.put("Name", pm.getApplicationLabel(app).toString());
                    arr.put(o);
                }
            } catch (Exception ignored) {}
            return arr.toString();
        }

        /* ============ helpers ============ */
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

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        try { if (wifiScanReceiver != null) unregisterReceiver(wifiScanReceiver); } catch (Exception ignored) {}
        try { if (bluetoothReceiver != null) unregisterReceiver(bluetoothReceiver); } catch (Exception ignored) {}
        try {
            if (bleScanner != null && bleCallback != null) bleScanner.stopScan(bleCallback);
        } catch (Exception ignored) {}
        super.onDestroy();
    }
}
