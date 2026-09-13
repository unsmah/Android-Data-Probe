package com.example.dataprobe;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
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
import android.net.NetworkInfo;
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
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
    private BroadcastReceiver wifiConnectionReceiver;
    private ScanCallback bleCallback;

    private boolean isWifiScanning = false;
    private boolean isBluetoothScanning = false;
    private boolean liveWifiScanning = false;
    private final Map<String, JSONObject> btSeen = new HashMap<>();
    private final Handler scanHandler = new Handler(Looper.getMainLooper());

    /* OUI cache: prefix (no colons, uppercase) -> manufacturer */
    private final ConcurrentHashMap<String, String> ouiMap = new ConcurrentHashMap<>();
    private volatile boolean ouiReady = false;

    private static final int PERM_REQ = 1001;
    private static final int BT_ENABLE_REQ = 1002;
    private static final int LOC_ENABLE_REQ = 1003;
    private static final String WIFI_NETWORKS_FILE = "wifi_networks.json";
    private static final String LOCATION_HISTORY_FILE = "location_history.json";
    private static final String OUI_CACHE_FILE = "oui_cache.txt";
    private static final String BT_DEVICES_FILE = "bluetooth_devices.json";
    private static final long BT_CONNECTION_DEBOUNCE_MS = 5 * 60 * 1000L;
    private static final String OUI_URL = "https://raw.githubusercontent.com/Ringmast4r/OUI-Master-Database/master/LISTS/kismet_manuf.txt";
    private static final int LOCATION_HISTORY_MAX = 500;
    private static final int LIVE_SCAN_INTERVAL_MS = 35000;
    private static final long CONNECTION_DEBOUNCE_MS = 5 * 60 * 1000L;

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
                        || url.startsWith("about:") || url.startsWith("data:")) return false;
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    return true;
                } catch (Exception e) { return false; }
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
            @Override
            public void onPageFinished(WebView v, String url) {
                // JS is now ready — push all available data
                pushPermissionStatus();
                pushWifiInfo();
                pushBondedDevices();
                pushLocation();
                // And again shortly after in case the OS needs a beat
                scanHandler.postDelayed(() -> {
                    pushPermissionStatus();
                    pushWifiInfo();
                    pushBondedDevices();
                    pushLocation();
                }, 800);
                scanHandler.postDelayed(() -> {
                    pushWifiInfo();
                    pushBondedDevices();
                }, 2500);
            }
        });
        webView.addJavascriptInterface(new AndroidBridge(), "Android");
        webView.loadUrl("file:///android_asset/index.html");
        setContentView(webView);

        ensureOuiDatabase();
        registerStateReceiver();
        registerWifiConnectionReceiver();
        requestRuntimePermissions();
    }

    /* ================= OUI database ================= */

    private File ouiCacheFile() { return new File(getFilesDir(), OUI_CACHE_FILE); }

    private void ensureOuiDatabase() {
        File f = ouiCacheFile();
        if (f.exists() && f.length() > 50000) {
            new Thread(() -> loadOuiCache(f), "oui-loader").start();
            return;
        }
        new Thread(() -> {
            try {
                URL url = new URL(OUI_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                conn.setRequestProperty("User-Agent", "DataProbe/1.0");
                conn.connect();
                if (conn.getResponseCode() == 200) {
                    StringBuilder sb = new StringBuilder();
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        // kismet format: AABBCC<TAB>Manufacturer
                        int tab = line.indexOf('\t');
                        if (tab <= 0) continue;
                        String prefix = line.substring(0, tab).replace(":", "").toUpperCase();
                        String name = line.substring(tab + 1).trim();
                        if (prefix.length() >= 6 && !name.isEmpty()) {
                            sb.append(prefix).append('\t').append(name).append('\n');
                        }
                    }
                    reader.close();
                    Files.write(f.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
                    loadOuiCache(f);
                }
            } catch (Exception e) {
                // Fall back silently to built-in table
            }
        }, "oui-downloader").start();
    }

    private void loadOuiCache(File f) {
        try {
            List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                int tab = line.indexOf('\t');
                if (tab > 0) {
                    ouiMap.put(line.substring(0, tab), line.substring(tab + 1));
                }
            }
            ouiReady = true;
        } catch (Exception ignored) {}
    }

    private String lookupManufacturer(String bssid) {
        if (bssid == null || bssid.length() < 8) return "Unknown";
        String clean = bssid.replace(":", "").toUpperCase();
        // Try 9-char (MA-S), 7-char (MA-M), 6-char (MA-L)
        for (int len : new int[]{9, 7, 6}) {
            if (clean.length() >= len) {
                String m = ouiMap.get(clean.substring(0, len));
                if (m != null && !m.isEmpty()) return m;
            }
        }
        return "Unknown (" + bssid.substring(0, Math.min(8, bssid.length())) + ")";
    }

    /* ================= state receivers ================= */

    private void registerStateReceiver() {
        stateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
                scanHandler.postDelayed(() -> {
                    pushPermissionStatus();
                    if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) pushBondedDevices();
                    if (LocationManager.PROVIDERS_CHANGED_ACTION.equals(action)) pushLocation();
                }, 500);

                if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
                    int state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1);
                    pushWifiInfo();
                    if (state == WifiManager.WIFI_STATE_ENABLED
                            || state == WifiManager.WIFI_STATE_ENABLING) {
                        // WiFi is on but not connected yet — retry a few times
                        for (long d : new long[]{1500, 3000, 6000, 10000, 15000}) {
                            scanHandler.postDelayed(() -> pushWifiInfo(), d);
                        }
                    }
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        f.addAction(LocationManager.PROVIDERS_CHANGED_ACTION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(stateReceiver, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(stateReceiver, f);
    }

    private void registerWifiConnectionReceiver() {
        wifiConnectionReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                try {
                    NetworkInfo ni = i.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                    if (ni != null && ni.isConnected()) {
                        WifiInfo wi = wifiManager.getConnectionInfo();
                        if (wi != null) {
                            recordConnection(wi);
                            // Push fresh info to the UI immediately
                            pushWifiInfo();
                            // And again shortly after — the SSID sometimes arrives late
                            scanHandler.postDelayed(() -> pushWifiInfo(), 1500);
                            scanHandler.postDelayed(() -> pushWifiInfo(), 4000);
                        }
                    }
                } catch (Exception ignored) {}
            }
        };
        IntentFilter f = new IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(wifiConnectionReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(wifiConnectionReceiver, f);

        try {
            WifiInfo wi = wifiManager.getConnectionInfo();
            if (wi != null && wifiManager.isWifiEnabled()) recordConnection(wi);
        } catch (Exception ignored) {}
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
        scanHandler.postDelayed(() -> {
            pushWifiInfo(); pushBondedDevices(); pushLocation(); pushPermissionStatus();
        }, 500);
        scanHandler.postDelayed(() -> {
            pushWifiInfo(); pushBondedDevices(); pushLocation(); pushPermissionStatus();
        }, 1500);
    }

    @Override
    protected void onResume() {
        super.onResume();
        pushPermissionStatus();
        pushWifiInfo();
        pushBondedDevices();
        pushLocation();
        new Thread(this::recordBondedDevices, "bt-bonded").start();
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
            pushWifiInfo(); pushBondedDevices(); pushLocation();
        }, 800);
    }

    /* ================= location history ================= */

    private File locationHistoryFile() { return new File(getFilesDir(), LOCATION_HISTORY_FILE); }

    private JSONArray readLocationHistory() {
        try {
            File f = locationHistoryFile();
            if (!f.exists()) return new JSONArray();
            return new JSONArray(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) { return new JSONArray(); }
    }

    private void writeLocationHistory(JSONArray arr) {
        try { Files.write(locationHistoryFile().toPath(), arr.toString().getBytes(StandardCharsets.UTF_8)); }
        catch (Exception ignored) {}
    }

    private void appendLocationHistory(Location loc) {
        try {
            JSONArray arr = readLocationHistory();
            JSONObject o = new JSONObject();
            o.put("lat", loc.getLatitude());
            o.put("lon", loc.getLongitude());
            o.put("acc", loc.getAccuracy());
            o.put("t", loc.getTime());
            arr.put(o);
            while (arr.length() > LOCATION_HISTORY_MAX) arr.remove(0);
            writeLocationHistory(arr);
        } catch (Exception ignored) {}
    }

    private Location getLastLocationQuick() {
        try {
            Location loc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (loc == null) loc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            return loc;
        } catch (Exception e) { return null; }
    }

    /* ================= WiFi networks index ================= */

    private File wifiNetworksFile() { return new File(getFilesDir(), WIFI_NETWORKS_FILE); }

    private JSONObject readWifiIndex() {
        try {
            File f = wifiNetworksFile();
            if (!f.exists()) return new JSONObject();
            return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) { return new JSONObject(); }
    }

    private void writeWifiIndex(JSONObject index) {
        try { Files.write(wifiNetworksFile().toPath(), index.toString().getBytes(StandardCharsets.UTF_8)); }
        catch (Exception ignored) {}
    }

    private void recordSightings(JSONArray networks) {
        try {
            if (networks == null || networks.length() == 0) return;
            JSONObject index = readWifiIndex();
            long now = System.currentTimeMillis();
            Location loc = getLastLocationQuick();

            for (int i = 0; i < networks.length(); i++) {
                JSONObject n = networks.getJSONObject(i);
                String bssid = n.optString("BSSID", null);
                if (bssid == null || bssid.isEmpty() || "02:00:00:00:00:00".equals(bssid)) continue;
                String ssid = n.optString("SSID", "");
                int level = n.optInt("Level", 0);
                int freq = n.optInt("Frequency", 0);

                JSONObject entry = index.optJSONObject(bssid);
                if (entry == null) {
                    entry = new JSONObject();
                    entry.put("bssid", bssid);
                    entry.put("ssid", ssid.isEmpty() ? "(hidden)" : ssid);
                    entry.put("firstSeen", now);
                    entry.put("manufacturer", lookupManufacturer(bssid));
                    entry.put("sightings", new JSONArray());
                    entry.put("connections", new JSONArray());
                }
                if (!ssid.isEmpty()) entry.put("ssid", ssid);
                entry.put("lastSeen", now);
                String caps = n.optString("Capabilities", "");
                if (!caps.isEmpty()) entry.put("capabilities", caps);
                String gen = n.optString("WiFiGeneration", "");
                if (!gen.isEmpty()) entry.put("wifiGeneration", gen);

                JSONArray sightings = entry.optJSONArray("sightings");
                if (sightings == null) sightings = new JSONArray();
                JSONArray pt = new JSONArray();
                pt.put(now);
                if (loc != null) {
                    pt.put(loc.getLatitude()); pt.put(loc.getLongitude()); pt.put(loc.getAccuracy());
                } else { pt.put(JSONObject.NULL); pt.put(JSONObject.NULL); pt.put(JSONObject.NULL); }
                pt.put(level);
                pt.put(freq);
                sightings.put(pt);
                entry.put("sightings", sightings);
                index.put(bssid, entry);
            }
            writeWifiIndex(index);
        } catch (Exception ignored) {}
    }

    private void recordConnection(WifiInfo wi) {
        try {
            String bssid = wi.getBSSID();
            if (bssid == null || bssid.isEmpty() || "02:00:00:00:00:00".equals(bssid)) return;
            String ssid = wi.getSSID();
            if (ssid != null) ssid = ssid.replace("\"", "");

            JSONObject index = readWifiIndex();
            long now = System.currentTimeMillis();
            JSONObject entry = index.optJSONObject(bssid);
            if (entry == null) {
                entry = new JSONObject();
                entry.put("bssid", bssid);
                entry.put("ssid", ssid != null && !ssid.isEmpty() ? ssid : "(hidden)");
                entry.put("firstSeen", now);
                entry.put("manufacturer", lookupManufacturer(bssid));
                entry.put("sightings", new JSONArray());
                entry.put("connections", new JSONArray());
            }
            if (ssid != null && !ssid.isEmpty()) entry.put("ssid", ssid);
            entry.put("lastSeen", now);

            JSONArray conns = entry.optJSONArray("connections");
            if (conns == null) conns = new JSONArray();

            boolean add = true;
            if (conns.length() > 0) {
                JSONArray last = conns.optJSONArray(conns.length() - 1);
                if (last != null && now - last.optLong(0) < CONNECTION_DEBOUNCE_MS) add = false;
            }
            if (add) {
                JSONArray pt = new JSONArray();
                pt.put(now);
                Location loc = getLastLocationQuick();
                if (loc != null) { pt.put(loc.getLatitude()); pt.put(loc.getLongitude()); }
                else { pt.put(JSONObject.NULL); pt.put(JSONObject.NULL); }
                conns.put(pt);
                entry.put("connections", conns);
                index.put(bssid, entry);
                writeWifiIndex(index);
            }
        } catch (Exception ignored) {}
    }

    /* ================= network interface enumeration ================= */

    private String getRealMacAddress() {
        try {
            WifiInfo wi = wifiManager.getConnectionInfo();
            String mac = wi.getMacAddress();
            if (mac != null && !mac.equals("02:00:00:00:00:00")) return mac;
        } catch (Exception ignored) {}
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (!ni.getName().startsWith("wlan")) continue;
                byte[] hw = ni.getHardwareAddress();
                if (hw != null && hw.length == 6) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < hw.length; i++) {
                        if (i > 0) sb.append(":");
                        sb.append(String.format("%02x", hw[i]));
                    }
                    String m = sb.toString();
                    if (!m.equals("02:00:00:00:00:00")) return m;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private JSONObject getNetworkInterfaceInfo() {
        JSONObject out = new JSONObject();
        try {
            JSONArray ipv4s = new JSONArray();
            JSONArray ipv6s = new JSONArray();
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (!ni.isUp()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    String hostAddr = a.getHostAddress();
                    int pct = hostAddr.indexOf('%');
                    if (pct > 0) hostAddr = hostAddr.substring(0, pct);
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        JSONObject o = new JSONObject();
                        o.put("iface", ni.getName());
                        o.put("addr", hostAddr);
                        ipv4s.put(o);
                    } else if (a instanceof Inet6Address) {
                        if (a.isLoopbackAddress()) continue;
                        JSONObject o = new JSONObject();
                        o.put("iface", ni.getName());
                        o.put("addr", hostAddr);
                        o.put("scope", a.isLinkLocalAddress() ? "link-local" :
                                a.isSiteLocalAddress() ? "unique-local" : "global");
                        ipv6s.put(o);
                    }
                }
            }
            out.put("ipv4", ipv4s);
            out.put("ipv6", ipv6s);
        } catch (Exception ignored) {}
        return out;
    }



    /* ================= WiFi standard ================= */

    private String wifiStandardName(int std, int freq) {
        switch (std) {
            case 1:  return "Wi-Fi 1-3 (Legacy 802.11a/b/g)";
            case 4:  return "Wi-Fi 4 (802.11n)";
            case 5:  return "Wi-Fi 5 (802.11ac)";
            case 6:  return freq >= 5925 ? "Wi-Fi 6E (802.11ax 6 GHz)"
                                          : "Wi-Fi 6 (802.11ax)";
            case 7:  return "WiGig (802.11ad)";
            case 8:  return "Wi-Fi 7 (802.11be)";
            default: return "Unknown";
        }
    }

    private int safeWifiStandard(WifiInfo info) {
        try {
            if (Build.VERSION.SDK_INT >= 30) return info.getWifiStandard();
        } catch (Exception ignored) {}
        return 0;
    }

    private int safeWifiStandard(android.net.wifi.ScanResult r) {
        try {
            if (Build.VERSION.SDK_INT >= 30) return r.getWifiStandard();
        } catch (Exception ignored) {}
        return 0;
    }

    /* ================= Bluetooth device categorization ================= */

    private String btDeviceCategory(BluetoothDevice device) {
        try {
            BluetoothClass cls = device.getBluetoothClass();
            if (cls != null) {
                int major = cls.getMajorDeviceClass();
                int minor = cls.getDeviceClass();

                switch (major) {
                    case BluetoothClass.Device.Major.COMPUTER:
                        switch (minor) {
                            case BluetoothClass.Device.COMPUTER_LAPTOP: return "laptop";
                            case BluetoothClass.Device.COMPUTER_DESKTOP: return "desktop";
                            case BluetoothClass.Device.COMPUTER_SERVER: return "server";
                            case BluetoothClass.Device.COMPUTER_HANDHELD_PC_PDA: return "tablet";
                            case BluetoothClass.Device.COMPUTER_PALM_SIZE_PC_PDA: return "tablet";
                            case BluetoothClass.Device.COMPUTER_WEARABLE: return "watch";
                        }
                        return "computer";

                    case BluetoothClass.Device.Major.PHONE:
                        if (minor == BluetoothClass.Device.PHONE_SMART) return "smartphone";
                        return "phone";

                    case BluetoothClass.Device.Major.AUDIO_VIDEO:
                        switch (minor) {
                            case BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET:
                            case BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES:
                                return "headphones";
                            case BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER:
                            case BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO:
                            case BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO:
                                return "speaker";
                            case BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO:
                                return "car";
                            case BluetoothClass.Device.AUDIO_VIDEO_MICROPHONE:
                                return "microphone";
                            case BluetoothClass.Device.AUDIO_VIDEO_SET_TOP_BOX:
                                return "stb";
                            case BluetoothClass.Device.AUDIO_VIDEO_VIDEO_MONITOR:
                            case BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER:
                                return "tv";
                            case BluetoothClass.Device.AUDIO_VIDEO_VIDEO_CAMERA:
                            case BluetoothClass.Device.AUDIO_VIDEO_CAMCORDER:
                                return "camera";
                            case BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE:
                                return "headphones";
                            case BluetoothClass.Device.AUDIO_VIDEO_VIDEO_CONFERENCING:
                                return "video";
                        }
                        return "audio";

                    case BluetoothClass.Device.Major.WEARABLE:
                        if (minor == BluetoothClass.Device.WEARABLE_WRIST_WATCH) return "watch";
                        if (minor == BluetoothClass.Device.WEARABLE_GLASSES) return "glasses";
                        if (minor == BluetoothClass.Device.WEARABLE_HELMET) return "helmet";
                        if (minor == BluetoothClass.Device.WEARABLE_JACKET) return "jacket";
                        return "wearable";

                    case BluetoothClass.Device.Major.HEALTH:
                        return "health";

                    case BluetoothClass.Device.Major.PERIPHERAL:
                        // Raw hex values from Bluetooth CoD spec — many
                        // PERIPHERAL_* constants are not in the public SDK
                        if (minor == 0x0540) return "keyboard";        // keyboard
                        if (minor == 0x05C0) return "keyboard";        // keyboard + pointing combo
                        if (minor == 0x0580) return "mouse";           // pointing device
                        if (minor == 0x0504) return "gamepad";         // joystick
                        if (minor == 0x0508) return "gamepad";         // gamepad
                        if (minor == 0x050C) return "remote";          // remote control
                        if (minor == 0x0510) return "peripheral";      // sensing device
                        if (minor == 0x0514) return "tablet";          // digitizer tablet
                        if (minor == 0x0518) return "peripheral";      // card reader
                        if (minor == 0x0520) return "peripheral";      // digital pen
                        if (minor == 0x0524) return "scanner";         // handheld scanner
                        if (minor == 0x0528) return "peripheral";      // gestural input
                        return "peripheral";

                    case BluetoothClass.Device.Major.IMAGING:
                        // Raw hex values — some IMAGING_* constants aren't public
                        if (minor == 0x0604) return "printer";
                        if (minor == 0x0620) return "scanner";
                        if (minor == 0x0640) return "camera";
                        if (minor == 0x0680) return "tv";
                        return "imaging";

                    case BluetoothClass.Device.Major.NETWORKING:
                        return "network";

                    case BluetoothClass.Device.Major.TOY:
                        return "gamepad";

                    case BluetoothClass.Device.Major.UNCATEGORIZED:
                        // fall through to name matching
                        break;
                }
            }
        } catch (Exception ignored) {}

        // Name-based fallback (crucial for BLE devices which have no CoD)
        String name = null;
        try { name = device.getName(); } catch (SecurityException ignored) {}
        if (name != null && !name.isEmpty()) {
            String n = name.toLowerCase();
            if (n.contains("airpod") || n.contains("buds") || n.contains("headphone")
                    || n.contains("headset") || n.contains("beats") || n.contains("freebuds")
                    || n.contains("wh-") || n.contains("wf-") || n.contains("earbud")
                    || n.contains("earphone") || n.contains("soundcore") || n.contains("edifier"))
                return "headphones";
            if (n.contains("watch") || n.contains("mi band") || n.contains("fitbit")
                    || n.contains("amazfit") || n.contains("gear s") || n.contains("huawei watch"))
                return "watch";
            if (n.contains("speaker") || n.contains("sound") || n.contains("jbl")
                    || n.contains("bose") || n.contains("sonos") || n.contains("boom")
                    || n.contains("flip") || n.contains("charge") || n.contains("marshall"))
                return "speaker";
            if (n.contains("tv") || n.contains("bravia") || n.contains("fire tv")
                    || n.contains("roku") || n.contains("chromecast") || n.contains("apple tv")
                    || n.contains("shield"))
                return "tv";
            if (n.contains("car") || n.contains("audi") || n.contains("bmw")
                    || n.contains("toyota") || n.contains("renault") || n.contains("kia")
                    || n.contains("hyundai") || n.contains("peugeot") || n.contains("nissan")
                    || n.contains("ford") || n.contains("mercedes") || n.contains("vw")
                    || n.contains("volkswagen") || n.contains("seat") || n.contains("skoda")
                    || n.contains("citroen") || n.contains("fiat") || n.contains("honda")
                    || n.contains("mazda") || n.contains("mitsubishi") || n.contains("suzuki")
                    || n.contains("dacia") || n.contains("opel") || n.contains("chevrolet"))
                return "car";
            if (n.contains("iphone") || n.contains("pixel") || n.contains("galaxy")
                    || n.contains("phone") || n.contains("xiaomi") || n.contains("redmi")
                    || n.contains("oneplus") || n.contains("oppo") || n.contains("vivo")
                    || n.contains("huawei") || n.contains("realme") || n.contains("infinix"))
                return "smartphone";
            if (n.contains("macbook") || n.contains("laptop") || n.contains("notebook")
                    || n.contains("thinkpad") || n.contains("ideapad") || n.contains("pavilion"))
                return "laptop";
            if (n.contains("ipad") || n.contains("tablet") || n.contains("tab "))
                return "tablet";
            if (n.contains("mouse")) return "mouse";
            if (n.contains("keyboard") || n.contains("kbd")) return "keyboard";
            if (n.contains("printer") || n.contains("hp ") || n.contains("epson")
                    || n.contains("canon ") || n.contains("brother"))
                return "printer";
            if (n.contains("hearing") || n.contains("oticon") || n.contains("phonak")
                    || n.contains("starkey") || n.contains("widex") || n.contains("signia")
                    || n.contains("resound"))
                return "hearing-aid";
            if (n.contains("controller") || n.contains("gamepad") || n.contains("dualshock")
                    || n.contains("dualsense") || n.contains("xbox"))
                return "gamepad";
            if (n.contains("stb") || n.contains("set-top") || n.contains("settop")
                    || n.contains("decoder") || n.contains("receiver"))
                return "stb";
        }

        try {
            int type = device.getType();
            if (type == BluetoothDevice.DEVICE_TYPE_LE
                    || type == BluetoothDevice.DEVICE_TYPE_DUAL) return "ble";
        } catch (Exception ignored) {}

        return "bluetooth";
    }

    /* Returns a short string showing raw CoD values for debugging */
    private String btClassDebug(BluetoothDevice device) {
        try {
            BluetoothClass cls = device.getBluetoothClass();
            if (cls == null) return "no CoD";
            return "major=0x" + Integer.toHexString(cls.getMajorDeviceClass()) +
                   " minor=0x" + Integer.toHexString(cls.getDeviceClass());
        } catch (Exception e) {
            return "error";
        }
    }



    /* ================= Bluetooth history storage ================= */

    private File btDevicesFile() { return new File(getFilesDir(), BT_DEVICES_FILE); }

    private JSONObject readBtIndex() {
        try {
            File f = btDevicesFile();
            if (!f.exists()) return new JSONObject();
            return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) { return new JSONObject(); }
    }

    private void writeBtIndex(JSONObject index) {
        try { Files.write(btDevicesFile().toPath(),
                index.toString().getBytes(StandardCharsets.UTF_8)); }
        catch (Exception ignored) {}
    }

    private void recordBluetoothSighting(BluetoothDevice device, int rssi, String source, boolean bonded) {
        try {
            String address = null;
            try { address = device.getAddress(); } catch (SecurityException ignored) {}
            if (address == null || address.isEmpty()) return;

            JSONObject index = readBtIndex();
            long now = System.currentTimeMillis();
            Location loc = getLastLocationQuick();

            JSONObject entry = index.optJSONObject(address);
            if (entry == null) {
                entry = new JSONObject();
                entry.put("address", address);
                String name = null;
                try { name = device.getName(); } catch (SecurityException ignored) {}
                entry.put("name", name != null ? name : "(unnamed)");
                entry.put("firstSeen", now);
                entry.put("category", btDeviceCategory(device));
                entry.put("classDebug", btClassDebug(device));
                entry.put("sightings", new JSONArray());
                entry.put("connections", new JSONArray());
                try { entry.put("deviceType", device.getType()); } catch (Exception ignored) {}
            }
            String nm = null;
            try { nm = device.getName(); } catch (SecurityException ignored) {}
            if (nm != null && !nm.isEmpty()) entry.put("name", nm);
            entry.put("lastSeen", now);
            entry.put("category", btDeviceCategory(device));
            entry.put("classDebug", btClassDebug(device));

            JSONArray sightings = entry.optJSONArray("sightings");
            if (sightings == null) sightings = new JSONArray();

            // Skip duplicate sights within 30s of the last one (same MAC)
            boolean skip = false;
            if (sightings.length() > 0) {
                JSONArray last = sightings.optJSONArray(sightings.length() - 1);
                if (last != null && now - last.optLong(0) < 30000) skip = true;
            }
            if (!skip) {
                JSONArray pt = new JSONArray();
                pt.put(now);
                if (loc != null) {
                    pt.put(loc.getLatitude()); pt.put(loc.getLongitude()); pt.put(loc.getAccuracy());
                } else {
                    pt.put(JSONObject.NULL); pt.put(JSONObject.NULL); pt.put(JSONObject.NULL);
                }
                pt.put(rssi);
                pt.put(source);
                sightings.put(pt);
                entry.put("sightings", sightings);
            }

            // Track bonded observation as a "connection"
            if (bonded) {
                JSONArray conns = entry.optJSONArray("connections");
                if (conns == null) conns = new JSONArray();
                boolean add = true;
                if (conns.length() > 0) {
                    JSONArray last = conns.optJSONArray(conns.length() - 1);
                    if (last != null && now - last.optLong(0) < BT_CONNECTION_DEBOUNCE_MS) add = false;
                }
                if (add) {
                    JSONArray pt = new JSONArray();
                    pt.put(now);
                    if (loc != null) { pt.put(loc.getLatitude()); pt.put(loc.getLongitude()); }
                    else { pt.put(JSONObject.NULL); pt.put(JSONObject.NULL); }
                    conns.put(pt);
                    entry.put("connections", conns);
                }
            }

            index.put(address, entry);
            writeBtIndex(index);
        } catch (Exception ignored) {}
    }

    private void recordBondedDevices() {
        try {
            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return;
            Set<BluetoothDevice> bonded = bluetoothAdapter.getBondedDevices();
            for (BluetoothDevice d : bonded) {
                recordBluetoothSighting(d, 0, "bonded", true);
            }
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
        public boolean isOuiReady() { return ouiReady; }

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

        @JavascriptInterface public void requestPermissionsFromUi() { runOnUiThread(MainActivity.this::requestRuntimePermissions); }
        @JavascriptInterface public void enableLocationFromUi() { runOnUiThread(MainActivity.this::promptEnableLocation); }
        @JavascriptInterface public void enableBluetoothFromUi() {
            runOnUiThread(() -> {
                try { startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), BT_ENABLE_REQ); }
                catch (Exception ignored) {}
            });
        }
        @JavascriptInterface public void openWifiSettings() {
            runOnUiThread(() -> { try { startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)); } catch (Exception ignored) {} });
        }

        /* ---- WiFi current (enhanced) ---- */
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
                int std = safeWifiStandard(info);
                o.put("WiFiGeneration", wifiStandardName(std, info.getFrequency()));
                o.put("IPAddress", intToIp(info.getIpAddress()));

                String mac = getRealMacAddress();
                if (mac != null) {
                    o.put("DeviceMAC", mac);
                    o.put("DeviceMACReal", true);
                } else {
                    o.put("DeviceMAC", "Masked by Android (privacy)");
                    o.put("DeviceMACReal", false);
                }

                String bssid = info.getBSSID();
                if (bssid != null) {
                    o.put("RouterManufacturer", lookupManufacturer(bssid));
                    // Attach stats from history if available
                    try {
                        JSONObject idx = readWifiIndex();
                        JSONObject e = idx.optJSONObject(bssid);
                        if (e != null) {
                            JSONArray s = e.optJSONArray("sightings");
                            JSONArray c = e.optJSONArray("connections");
                            if (s != null) {
                                o.put("TimesFound", s.length());
                                int best = -200, latest = 0, latestFreq = 0;
                                for (int i = 0; i < s.length(); i++) {
                                    JSONArray pt = s.optJSONArray(i);
                                    if (pt == null) continue;
                                    int lvl = pt.optInt(4, -200);
                                    if (lvl > best) best = lvl;
                                    if (i == s.length() - 1) {
                                        latest = pt.optInt(4, 0);
                                        latestFreq = pt.optInt(5, 0);
                                    }
                                }
                                o.put("BestSignal", best > -200 ? best : "—");
                                o.put("LatestSignal", latest);
                                o.put("LatestFrequency", latestFreq);
                            }
                            if (c != null) o.put("TimesConnected", c.length());
                            o.put("FirstSeen", e.optLong("firstSeen", 0));
                            o.put("LastSeen", e.optLong("lastSeen", 0));
                        }
                    } catch (Exception ignored) {}
                }

                JSONObject ifs = getNetworkInterfaceInfo();
                o.put("IPv4List", ifs.optJSONArray("ipv4"));
                o.put("IPv6List", ifs.optJSONArray("ipv6"));
            } catch (Exception e) {
                try { o.put("error", e.getMessage()); } catch (Exception ignored) {}
            }
            return o.toString();
        }

        /* ---- WiFi scan ---- */
        @JavascriptInterface public void startWifiScan() { runOnUiThread(() -> startWifiScanInternal(true, false)); }
        @JavascriptInterface public void startLiveWifiScan() {
            runOnUiThread(() -> {
                if (liveWifiScanning) return;
                liveWifiScanning = true;
                startWifiScanInternal(true, true);
            });
        }
        @JavascriptInterface public void stopLiveWifiScan() { runOnUiThread(() -> liveWifiScanning = false); }

        /* ---- WiFi networks ---- */
        @JavascriptInterface
        public String getWifiNetworkList() {
            JSONArray list = new JSONArray();
            try {
                JSONObject index = readWifiIndex();
                Iterator<String> keys = index.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    JSONObject e = index.optJSONObject(k);
                    if (e == null) continue;
                    JSONObject item = new JSONObject();
                    item.put("bssid", k);
                    item.put("ssid", e.optString("ssid", "(unknown)"));
                    item.put("firstSeen", e.optLong("firstSeen", 0));
                    item.put("lastSeen", e.optLong("lastSeen", 0));
                    item.put("manufacturer", e.optString("manufacturer", lookupManufacturer(k)));
                    item.put("wifiGeneration", e.optString("wifiGeneration", ""));
                    JSONArray s = e.optJSONArray("sightings");
                    JSONArray c = e.optJSONArray("connections");
                    item.put("sightings", s != null ? s.length() : 0);
                    item.put("connections", c != null ? c.length() : 0);
                    // Latest known location — first try sightings
                    boolean gotLoc = false;
                    if (s != null) {
                        for (int i = s.length() - 1; i >= 0; i--) {
                            JSONArray pt = s.optJSONArray(i);
                            if (pt != null && pt.length() >= 3 && !pt.isNull(1) && !pt.isNull(2)) {
                                item.put("lastLat", pt.optDouble(1));
                                item.put("lastLon", pt.optDouble(2));
                                item.put("lastLevel", pt.optInt(4, 0));
                                item.put("lastSeenAtLoc", pt.optLong(0));
                                gotLoc = true;
                                break;
                            }
                        }
                    }
                    // If no sighting had a location, fall back to last connection
                    if (!gotLoc && c != null) {
                        for (int i = c.length() - 1; i >= 0; i--) {
                            JSONArray pt = c.optJSONArray(i);
                            if (pt != null && pt.length() >= 3 && !pt.isNull(1) && !pt.isNull(2)) {
                                item.put("lastLat", pt.optDouble(1));
                                item.put("lastLon", pt.optDouble(2));
                                item.put("lastSeenAtLoc", pt.optLong(0));
                                break;
                            }
                        }
                    }
                    list.put(item);
                }
            } catch (Exception ignored) {}
            return list.toString();
        }

        @JavascriptInterface
        public String getWifiNetworkDetail(String bssid) {
            try {
                JSONObject index = readWifiIndex();
                JSONObject e = index.optJSONObject(bssid);
                if (e == null) return "null";
                if (!e.has("manufacturer"))
                    e.put("manufacturer", lookupManufacturer(bssid));
                return e.toString();
            } catch (Exception e) { return "null"; }
        }

        @JavascriptInterface public void clearWifiNetworks() {
            runOnUiThread(() -> { try { wifiNetworksFile().delete(); } catch (Exception ignored) {} });
        }

        /* ---- Bluetooth ---- */
        @JavascriptInterface
        public String getBluetoothAdapterName() {
            try {
                if (bluetoothAdapter == null) return "";
                String n = bluetoothAdapter.getName();
                return n != null ? n : "";
            } catch (Exception e) { return ""; }
        }

        @JavascriptInterface
        public String getBluetoothDeviceList() {
            JSONArray list = new JSONArray();
            try {
                JSONObject index = readBtIndex();
                Iterator<String> keys = index.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    JSONObject e = index.optJSONObject(k);
                    if (e == null) continue;
                    JSONObject item = new JSONObject();
                    item.put("address", k);
                    item.put("name", e.optString("name", "(unnamed)"));
                    item.put("category", e.optString("category", "bluetooth"));
                    item.put("classDebug", e.optString("classDebug", ""));
                    item.put("firstSeen", e.optLong("firstSeen", 0));
                    item.put("lastSeen", e.optLong("lastSeen", 0));
                    JSONArray sArr = e.optJSONArray("sightings");
                    JSONArray cArr = e.optJSONArray("connections");
                    item.put("sightings", sArr != null ? sArr.length() : 0);
                    item.put("connections", cArr != null ? cArr.length() : 0);
                    // Latest location
                    boolean gotLoc = false;
                    if (sArr != null) {
                        for (int i = sArr.length() - 1; i >= 0; i--) {
                            JSONArray pt = sArr.optJSONArray(i);
                            if (pt != null && pt.length() >= 3 && !pt.isNull(1) && !pt.isNull(2)) {
                                item.put("lastLat", pt.optDouble(1));
                                item.put("lastLon", pt.optDouble(2));
                                item.put("lastRssi", pt.optInt(4, 0));
                                gotLoc = true;
                                break;
                            }
                        }
                    }
                    if (!gotLoc && cArr != null) {
                        for (int i = cArr.length() - 1; i >= 0; i--) {
                            JSONArray pt = cArr.optJSONArray(i);
                            if (pt != null && pt.length() >= 3 && !pt.isNull(1) && !pt.isNull(2)) {
                                item.put("lastLat", pt.optDouble(1));
                                item.put("lastLon", pt.optDouble(2));
                                break;
                            }
                        }
                    }
                    list.put(item);
                }
            } catch (Exception ignored) {}
            return list.toString();
        }

        @JavascriptInterface
        public String getBluetoothDeviceDetail(String mac) {
            try {
                JSONObject index = readBtIndex();
                JSONObject e = index.optJSONObject(mac);
                return e != null ? e.toString() : "null";
            } catch (Exception ex) { return "null"; }
        }

        @JavascriptInterface
        public void clearBluetoothHistory() {
            runOnUiThread(() -> { try { btDevicesFile().delete(); } catch (Exception ignored) {} });
        }

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
                    o.put("Category", btDeviceCategory(d));
                    o.put("ClassDebug", btClassDebug(d));
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
                        "window.onBluetoothError('Bluetooth not supported.')", null); return;
                }
                if (!bluetoothAdapter.isEnabled()) {
                    webView.evaluateJavascript(
                        "window.onBluetoothError('Bluetooth is off. Turn it on in the top banner.')", null); return;
                }
                if (isBluetoothScanning) return;
                isBluetoothScanning = true;
                btSeen.clear();

                bluetoothReceiver = new BroadcastReceiver() {
                    @Override public void onReceive(Context ctx, Intent intent) {
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
                IntentFilter f = new IntentFilter();
                f.addAction(BluetoothDevice.ACTION_FOUND);
                f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
                if (Build.VERSION.SDK_INT >= 33) registerReceiver(bluetoothReceiver, f, Context.RECEIVER_EXPORTED);
                else registerReceiver(bluetoothReceiver, f);
                try { bluetoothAdapter.startDiscovery(); } catch (SecurityException ignored) {}

                if (bleScanner != null) {
                    bleCallback = new ScanCallback() {
                        @Override public void onScanResult(int t, ScanResult r) { emitBluetoothDevice(r.getDevice(), r.getRssi(), "ble"); }
                        @Override public void onBatchScanResults(List<ScanResult> rs) {
                            for (ScanResult r : rs) emitBluetoothDevice(r.getDevice(), r.getRssi(), "ble");
                        }
                    };
                    ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
                    try { bleScanner.startScan(null, settings, bleCallback); } catch (SecurityException ignored) {}
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
                try { o.put("Type", device.getType()); } catch (Exception ignored) {}
                boolean bonded = false;
                try { bonded = device.getBondState() == BluetoothDevice.BOND_BONDED; } catch (Exception ignored) {}
                o.put("Bonded", bonded);
                o.put("Source", source);
                o.put("Category", btDeviceCategory(device));
                btSeen.put(address, o);
                recordBluetoothSighting(device, rssi, source, bonded);
                final String js = "window.onBluetoothDeviceFound(" + o + ")";
                webView.post(() -> webView.evaluateJavascript(js, null));
            } catch (Exception ignored) {}
        }

        @JavascriptInterface public void stopBluetoothDiscovery() { runOnUiThread(this::stopBluetoothDiscoveryInternal); }

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
            new Thread(MainActivity.this::recordBondedDevices, "bt-bonded").start();
            webView.post(() -> webView.evaluateJavascript("window.onBluetoothScanComplete()", null));
            if (bluetoothReceiver != null) {
                try { unregisterReceiver(bluetoothReceiver); } catch (Exception ignored) {}
                bluetoothReceiver = null;
            }
        }

        /* ---- Location ---- */
        @JavascriptInterface
        public String getLocation() {
            JSONObject o = new JSONObject();
            try {
                if (!isLocationEnabled()) { o.put("error", "Location is off."); return o.toString(); }
                Location loc = getLastLocationQuick();
                if (loc != null) {
                    o.put("Latitude", loc.getLatitude());
                    o.put("Longitude", loc.getLongitude());
                    o.put("Accuracy", loc.getAccuracy());
                    o.put("Altitude", loc.getAltitude());
                    o.put("Speed", loc.getSpeed());
                    o.put("MapsUrl", "https://www.google.com/maps?q=" + loc.getLatitude() + "," + loc.getLongitude());
                } else {
                    o.put("error", "No location fix yet. Move to an open area.");
                }
            } catch (Exception e) { try { o.put("error", e.getMessage()); } catch (Exception ignored) {} }
            return o.toString();
        }

        @JavascriptInterface
        public void startLiveTracking() {
            runOnUiThread(() -> {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
                    webView.evaluateJavascript(
                        "window.onLiveLocationError('Location permission not granted')", null); return;
                }
                if (locationCallback != null) return;
                if (!isLocationEnabled()) {
                    webView.evaluateJavascript(
                        "window.onLiveLocationError('Location is off. Turn it on first.')", null); return;
                }
                LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000)
                    .setMinUpdateIntervalMillis(1000).build();
                locationCallback = new LocationCallback() {
                    @Override public void onLocationResult(@NonNull LocationResult result) {
                        Location loc = result.getLastLocation();
                        if (loc == null) return;
                        appendLocationHistory(loc);
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

        @JavascriptInterface public String getLocationHistory() { return readLocationHistory().toString(); }
        @JavascriptInterface public void clearLocationHistory() {
            runOnUiThread(() -> { try { locationHistoryFile().delete(); } catch (Exception ignored) {} });
        }

        /* ---- Installed apps ---- */
        @JavascriptInterface
        public void loadAppsAsync(final String filter) {
            new Thread(() -> {
                final String result = getInstalledAppsInternal(filter);
                writeAppsCache(result);
                webView.post(() -> webView.evaluateJavascript("window.onAppsLoaded(" + result + ")", null));
            }, "apps-loader").start();
        }

        @JavascriptInterface public String getCachedApps() { return readAppsCache(); }

        private File appsCacheFile() { return new File(getFilesDir(), "apps_cache.json"); }

        private void writeAppsCache(String json) {
            try { Files.write(appsCacheFile().toPath(), json.getBytes(StandardCharsets.UTF_8)); } catch (Exception ignored) {}
        }

        private String readAppsCache() {
            try {
                File f = appsCacheFile();
                if (!f.exists()) return "[]";
                return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            } catch (Exception e) { return "[]"; }
        }

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
                            o.put("Icon", "data:image/png;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP));
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
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(wifiScanReceiver, filter, Context.RECEIVER_EXPORTED);
        else registerReceiver(wifiScanReceiver, filter);
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
                    int std = safeWifiStandard(r);
                    o.put("WiFiGeneration", wifiStandardName(std, r.frequency));
                    arr.put(o);
                }
            }
            if (saveToHistory && arr.length() > 0) recordSightings(arr);
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

    @Override
    public void onBackPressed() {
        if (webView == null) { super.onBackPressed(); return; }
        webView.evaluateJavascript(
            "(window.__backHandler && window.__backHandler()) ? 'handled' : 'pass'",
            value -> {
                if (!"\"handled\"".equals(value)) {
                    runOnUiThread(() -> MainActivity.super.onBackPressed());
                }
            });
    }

    @Override
    protected void onDestroy() {
        try { if (wifiScanReceiver != null) unregisterReceiver(wifiScanReceiver); } catch (Exception ignored) {}
        try { if (bluetoothReceiver != null) unregisterReceiver(bluetoothReceiver); } catch (Exception ignored) {}
        try { if (stateReceiver != null) unregisterReceiver(stateReceiver); } catch (Exception ignored) {}
        try { if (wifiConnectionReceiver != null) unregisterReceiver(wifiConnectionReceiver); } catch (Exception ignored) {}
        try { if (bleScanner != null && bleCallback != null) bleScanner.stopScan(bleCallback); } catch (Exception ignored) {}
        try {
            if (fusedClient != null && locationCallback != null)
                fusedClient.removeLocationUpdates(locationCallback);
        } catch (Exception ignored) {}
        super.onDestroy();
    }
}
