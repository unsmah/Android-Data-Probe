package com.example.dataprobe;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
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
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private WifiManager wifiManager;
    private BluetoothAdapter bluetoothAdapter;
    private BroadcastReceiver wifiScanReceiver;
    private BroadcastReceiver bluetoothReceiver;
    private boolean isWifiScanning = false;
    private boolean isBluetoothScanning = false;

    private static final int PERM_REQ = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new AndroidBridge(), "Android");
        webView.loadUrl("file:///android_asset/index.html");
        setContentView(webView);

        requestRuntimePermissions();
    }

    private void requestRuntimePermissions() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        perms.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        perms.add(Manifest.permission.READ_PHONE_STATE);
        if (Build.VERSION.SDK_INT >= 31) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
            perms.add(Manifest.permission.BLUETOOTH_SCAN);
        }
        requestPermissions(perms.toArray(new String[0]), PERM_REQ);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != PERM_REQ) return;

        boolean locationGranted = false;
        for (int i = 0; i < perms.length; i++) {
            if (Manifest.permission.ACCESS_FINE_LOCATION.equals(perms[i])
                    && results[i] == PackageManager.PERMISSION_GRANTED) {
                locationGranted = true;
            }
        }

        // If location was just granted but the system location toggle is off,
        // open the system settings page so the user can enable it.
        if (locationGranted && !isLocationEnabled()) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                } catch (Exception ignored) {}
            }, 400);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // When the user returns from location settings, tell the page whether
        // location is now on so it can hide/show the banner.
        if (webView != null) {
            final boolean on = isLocationEnabled();
            webView.post(() ->
                webView.evaluateJavascript("window.onLocationStateChanged(" + on + ")", null));
        }
    }

    private boolean isLocationEnabled() {
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 28) return lm.isLocationEnabled();
            return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            return false;
        }
    }

    public class AndroidBridge {

        /* ================= Device & OS ================= */
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

        /* ================= Location state ================= */
        @JavascriptInterface
        public boolean isLocationEnabled() {
            return MainActivity.this.isLocationEnabled();
        }

        @JavascriptInterface
        public void openLocationSettings() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                } catch (Exception ignored) {}
            });
        }

        /* ================= Wi-Fi: current ================= */
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

        /* ================= Wi-Fi: saved networks ================= */
        @JavascriptInterface
        public String getSavedWifiNetworks() {
            JSONArray arr = new JSONArray();
            try {
                List<WifiConfiguration> configs = wifiManager.getConfiguredNetworks();
                if (configs != null) {
                    for (WifiConfiguration c : configs) {
                        JSONObject o = new JSONObject();
                        o.put("SSID", c.SSID != null ? c.SSID.replace("\"", "") : "(hidden)");
                        o.put("BSSID", c.BSSID != null ? c.BSSID : "any");
                        o.put("NetworkId", c.networkId);
                        o.put("Status", c.status);
                        o.put("Priority", c.priority);
                        arr.put(o);
                    }
                }
            } catch (SecurityException e) {
                try {
                    JSONObject err = new JSONObject();
                    err.put("error", "permission: " + e.getMessage());
                    arr.put(err);
                } catch (Exception ignored) {}
            } catch (Exception e) {
                try {
                    JSONObject err = new JSONObject();
                    err.put("error", e.getMessage());
                    arr.put(err);
                } catch (Exception ignored) {}
            }
            return arr.toString();
        }

        /* ================= Wi-Fi: live scan ================= */
        @JavascriptInterface
        public void startWifiScan() {
            runOnUiThread(() -> {
                if (isWifiScanning) return;
                isWifiScanning = true;

                wifiScanReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context ctx, Intent intent) {
                        pushWifiResults();
                    }
                };

                IntentFilter filter = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
                if (Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(wifiScanReceiver, filter, Context.RECEIVER_EXPORTED);
                } else {
                    registerReceiver(wifiScanReceiver, filter);
                }

                try {
                    wifiManager.startScan();
                } catch (Exception e) {
                    webView.post(() -> webView.evaluateJavascript(
                        "window.onWifiScanError('" + e.getMessage() + "')", null));
                }

                // Safety net: if the broadcast never arrives (some OEMs), push
                // whatever the cache holds after 6 seconds.
                webView.postDelayed(() -> {
                    if (wifiScanReceiver != null) pushWifiResults();
                }, 6000);
            });
        }

        private void pushWifiResults() {
            if (wifiScanReceiver == null) return;
            try {
                List<ScanResult> results = wifiManager.getScanResults();
                JSONArray arr = new JSONArray();
                if (results != null) {
                    for (ScanResult r : results) {
                        JSONObject o = new JSONObject();
                        o.put("SSID", r.SSID);
                        o.put("BSSID", r.BSSID);
                        o.put("Level", r.level);
                        o.put("Frequency", r.frequency);
                        o.put("Capabilities", r.capabilities);
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

        /* ================= Bluetooth: bonded ================= */
        @JavascriptInterface
        public String getBluetoothBondedDevices() {
            JSONArray arr = new JSONArray();
            try {
                if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
                    Set<BluetoothDevice> bonded = bluetoothAdapter.getBondedDevices();
                    for (BluetoothDevice device : bonded) {
                        JSONObject o = new JSONObject();
                        String name = null;
                        try { name = device.getName(); } catch (SecurityException ignored) {}
                        o.put("Name", name != null ? name : "(unnamed)");
                        o.put("Address", device.getAddress());
                        o.put("Type", device.getType());
                        arr.put(o);
                    }
                }
            } catch (Exception ignored) {}
            return arr.toString();
        }

        /* ================= Bluetooth: live discovery ================= */
        @JavascriptInterface
        public void startBluetoothDiscovery() {
            runOnUiThread(() -> {
                if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
                    webView.evaluateJavascript(
                        "window.onBluetoothError('Bluetooth is off or unavailable. Turn it on in Quick Settings.')",
                        null);
                    return;
                }
                if (isBluetoothScanning) return;
                isBluetoothScanning = true;

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
                                try {
                                    JSONObject o = new JSONObject();
                                    String name = null;
                                    try { name = device.getName(); } catch (SecurityException ignored) {}
                                    o.put("Name", name != null ? name : "(unnamed)");
                                    o.put("Address", device.getAddress());
                                    try { o.put("Bonded", device.getBondState() == BluetoothDevice.BOND_BONDED); }
                                    catch (Exception ignored) {}
                                    int rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
                                    o.put("RSSI", rssi);
                                    final String js = "window.onBluetoothDeviceFound(" + o + ")";
                                    webView.post(() -> webView.evaluateJavascript(js, null));
                                } catch (Exception ignored) {}
                            }
                        } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                            isBluetoothScanning = false;
                            webView.post(() -> webView.evaluateJavascript(
                                "window.onBluetoothScanComplete()", null));
                            try { unregisterReceiver(bluetoothReceiver); } catch (Exception ignored) {}
                            bluetoothReceiver = null;
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

                try {
                    boolean started = bluetoothAdapter.startDiscovery();
                    if (!started) {
                        webView.evaluateJavascript(
                            "window.onBluetoothError('Discovery failed to start — try again in a few seconds.')",
                            null);
                        isBluetoothScanning = false;
                    }
                } catch (SecurityException e) {
                    webView.evaluateJavascript(
                        "window.onBluetoothError('Permission denied: " + e.getMessage() + "')",
                        null);
                    isBluetoothScanning = false;
                }
            });
        }

        @JavascriptInterface
        public void stopBluetoothDiscovery() {
            runOnUiThread(() -> {
                try {
                    if (bluetoothAdapter != null && bluetoothAdapter.isDiscovering()) {
                        bluetoothAdapter.cancelDiscovery();
                    }
                } catch (SecurityException ignored) {}
            });
        }

        /* ================= Location ================= */
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
                    o.put("error", "No location available. Ensure GPS is on.");
                }
            } catch (Exception e) {
                try { o.put("error", e.getMessage()); } catch (Exception ignored) {}
            }
            return o.toString();
        }

        /* ================= Installed Apps ================= */
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

        /* ================= Accounts ================= */
        @JavascriptInterface
        public String getAccounts() {
            JSONArray arr = new JSONArray();
            try {
                AccountManager am = AccountManager.get(getApplicationContext());
                for (Account account : am.getAccounts()) {
                    JSONObject o = new JSONObject();
                    o.put("Name", account.name);
                    o.put("Type", account.type);
                    arr.put(o);
                }
            } catch (Exception ignored) {}
            return arr.toString();
        }

        /* ================= helpers ================= */
        private String intToIp(int ip) {
            return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." +
                   ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF);
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
        super.onDestroy();
    }
}
