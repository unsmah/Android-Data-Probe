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
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
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

    private static final int PERM_REQ = 1001;
    private static final int BT_ENABLE_REQ = 1002;
    private static final int LOC_ENABLE_REQ = 1003;
    private static final String WIFI_NETWORKS_FILE = "wifi_networks.json";
    private static final String LOCATION_HISTORY_FILE = "location_history.json";
    private static final int LOCATION_HISTORY_MAX = 500;
    private static final int LIVE_SCAN_INTERVAL_MS = 35000;
    private static final long CONNECTION_DEBOUNCE_MS = 5 * 60 * 1000L;

    /* ------- OUI table: BSSID prefix → manufacturer ------- */
    private static final Map<String, String> OUI = new HashMap<>();
    static {
        // TP-Link
        String[] tplink = { "14CC20","30B5C2","50C7BF","60E327","98DAC4","A42BB0",
            "AC84C6","B04E26","B0BE76","C025E9","C46E1F","C4E984","D807B6","E894F6",
            "EC086B","EC888F","F42853","F8D111","FCECDA","10FEED","3460F9","480EEC",
            "5C63BF","68FF7B","909A4A","9CA615","A0F3C1","B09575","BC4699","D46E0E",
            "DC9FDB","E4C32A","F09FC2" };
        for (String p : tplink) OUI.put(p, "TP-Link");
        // Netgear
        String[] netgear = { "204E7F","28C68E","2C3033","30469A","3C3786","4494FC",
            "4C60DE","6CB0CE","841B5E","9C3DCF","A040A0","B03956","B07FB9","C03F0E",
            "C43DC7","CC40D0","D43D7E","DCEF09","E0469A","E4F4C6","E8FCAF","F87394" };
        for (String p : netgear) OUI.put(p, "Netgear");
        // D-Link
        String[] dlink = { "001B11","001E58","001F1F","002191","0022B0","002401",
            "00265A","14D64D","1C7EE5","28107B","340804","3C1E04","409BCD","5CD998",
            "74DA88","78542E","84C9B2","9094E4","9CD643","ACF1DF","B8A386","C412F5",
            "C8BE19","CCB255","D8FEE3","E01CFC","EC2280","F07D68","F48E38","FC7516" };
        for (String p : dlink) OUI.put(p, "D-Link");
        // Asus
        String[] asus = { "000C6E","000EA6","00112F","0013D4","0015F2","001731",
            "04D4C4","08606E","10BF48","10C37B","14DAE9","14DDA9","1C872C","20CF30",
            "2C56DC","2CFDA1","305A3A","38D547","3C970E","40167E","4439C4","485B39",
            "4CEDFB","50465D","5404A6","54A050","6045CB","60A44C","6C7220","704D7B",
            "74D02B","7824AF","7C10C9","88D7F6","9C5C8E","A45E60","AC220B","AC9E17",
            "B06EBF","B8AEED","BCAEC5","BCEE7B","C86000","CC2DE0","D017C2","D4CA6D",
            "D850E6","DC56E7","E03F49","E0CB4E","E470B8","E89C25","F46D04","F832E4" };
        for (String p : asus) OUI.put(p, "ASUS");
        // Huawei
        String[] huawei = { "001882","001E10","0022A1","002568","0034FE","00464B",
            "005A13","00664B","00E0FC","0425C5","043389","047503","049645","04BD70",
            "04C06F","04F938","08 19A6".replace(" ",""),"086361","087A4C","0C37DC","0C45BA",
            "0C96BF","104780","105172","10AF78","143004","145F94","14B968","18C58A",
            "1C151F","1C1D67","1C8E5C","200BC7","202BC1","20F3A3","240995","246968",
            "24DBAC","283CE4","285FDB","286ED4","28B448","2C55D3","2CAB00","308730",
            "30D17E","3400A3","3429EA","346BD4","34A84E","38F889","3CCD57","3CDFBD",
            "404D8E","40CBA8","446A2E","44C346","480031","483C0C","48437C","486276",
            "4C1FCC","4C5499","4C8BEF","4CB16C","5001D9","509F27","5425EA","5439DF",
            "548998","54A51B","581F28","582AF7","5C4CA9","5C7D5E","5CB395","60DE44",
            "643E8C","64A651","688F84","68A0F6","6C92CF","7054F5","70723C","707BE8",
            "74882A","781DBA","786A89","7C11CB","7C6097","7CA177","80B686","80FB06",
            "845B12","84A8E4","8828B3","883FD3","8853D4","88E3AB","8C0D76","8C34FD",
            "8CE117","9017AC","904E2B","90671C","94049C","940E6B","98E7F5","9C28EF",
            "9C741A","9CA2F4","A08CF8","A47174","A4C64F","A8C83A","AC4E91","AC853D",
            "ACE215","B05B67","B08991","B41513","B4CD27","B808D7","BC25E0","BC7670",
            "C07009","C40528","C4072F","C80CC8","C894BB","C8D15E","CC53B5","CCA223",
            "D02DB3","D07AB5","D46AA8","D494E8","D8490B","DCD2FC","E0247F","E09796",
            "E468A3","E4C2D1","E8088B","E8BDD1","EC233D","EC388F","ECCB30","F04347",
            "F4559C","F48E92","F49FF3","F80113","F83DFF","F84ABF","F8E811","FC48EF" };
        for (String p : huawei) OUI.put(p, "Huawei");
        // ZTE
        String[] zte = { "0015EB","0019C6","001E73","00219E","002293","002512",
            "0026ED","045A95","04C1B9","08181A","0C1262","0C3796","0C8910","105CBF",
            "1460CB","14A364","18C501","1C6423","206BE7","20C6EB","244C07","2C26C5",
            "2C9D1E","3059B7","30F31D","344B50","34E0CF","3822F4","382B78","3891FB",
            "3C1CBE","3C26E4","3CCD5D","404D8E","407C7D","442C05","4452DB","446A2E",
            "48282F","485702","4C09B4","4C16F1","4C8120","508F4C","50C8E5","5422F8",
            "54BEF7","584BBC","5C93A2","5CB395","5CC307","608A10","60C798","64136C",
            "64317E","681AB2","6C8B2F","702E22","703ACB","742F68","74888A","781DBA",
            "7831C1","788B2A","7C2F80","7CBFB1","80EA96","84742A","849DC5","88329B",
            "88E3AB","8C6878","8CDCD4","901B0E","90C7D8","9439E5","982CBE","986CF5",
            "9C6F52","A0EC80","A47B9D","A84E3F","AC6462","ACDBDA","B075D5","B40F3B",
            "B44CC4","B4B362","B84D43","BC1485","BC4486","C0028D","C44F33","C83A6B",
            "C87B5B","CC1AFA","CC7B35","D0154A","D016B4","D05BA8","D404CD","D46A91",
            "D855A3","D8C7C8","DC028E","DC7144","E0C3F3","E45D75","E81324","E892A4",
            "EC1D7F","EC8AC7","ECED04","F05A09","F46A92","F4B8A7","F88E85","FC2D5E",
            "FCC897" };
        for (String p : zte) OUI.put(p, "ZTE");
        // Tenda
        String[] tenda = { "00B00C","08107 8".replace(" ",""),"08BEAC","0C8063","10327E",
            "10FEED","14EBB6","18A6F7","1C1B0D","207693","2469A5","2887BA","2C16BD",
            "2C3AFD","340AFF","349672","38B725","3C46D8","40169F","4432C8","487B6B",
            "4C09B4","502B73","50642B","54AF97","58D9D5","5CF938","6032B1","646E97",
            "68DDD9","6C5940","6C7220","703ACB","746A89","78A5DD","7C8BCA","80EA07",
            "8416F9","882593","88571D","8C882B","909A4A","94698F","98038C","9C50EE",
            "A09D22","A42BB0","A811FC","AC5F3E","B0487A","B40F3B","B83A5A","BC325F",
            "C03D03","C4E984","C83A35","CC2D1B","D076E7","D46E0E","D83214","DC028E",
            "E01C41","E46F13","E865D4","EC172F","F09FC2","F42853","F81A67","FC7C02" };
        for (String p : tenda) OUI.put(p, "Tenda");
        // Linksys
        String[] linksys = { "000393","00045A","000C41","000F66","001217","001310",
            "0014BF","001839","0018F8","001A70","001C10","001D7E","001EE5","001F33",
            "002129","00226B","002369","0024B2","00259C","00265A","20AA4B","288F5D",
            "2C600C","308CFB","48F8B3","4C5E0C","586D8F","6038E0","687F74","7C69F6",
            "841B5E","8C5A0C","94103E","98FC11","A4DB30","AC220B","B4750E","C05627",
            "C4411E","C8BE19","C8D719","D4A02E","E42892","E89F80","EC1A59","F81EDF",
            "FCECDA" };
        for (String p : linksys) OUI.put(p, "Linksys");
        // Cisco
        String[] cisco = { "00000C","000142","000143","000163","000164","000196",
            "000197","0001C7","0001C9","000216","000217","00023D","00024A","00024B",
            "00027D","00027E","0002B9","0002BA","0002FC","0002FD","000331","000332",
            "00036B","00036C","00039F","0003A0","0003E3","0003E4","0003FD","0003FE",
            "000427","000428","00046D","00046E","00049A","00049B","0004C0","0004C1",
            "0004DD","0004DE","000500","000501","000531","000532","00055E","00055F",
            "000573","000574","00059A","00059B","0005DC","0005DD","000628","00062A",
            "000652","000653","00067C","0006C1","0006D6","0006D7","0006F6","00070D",
            "00070E","000731","000732","00077D","00077E","000784","000785","0007B3",
            "0007B4","0007EB","0007EC","000820","000821","00082F","000830","00087B",
            "00087C","0008A3","0008A4","0008E2","0008E3","000911","000912","000943",
            "000944","00095B","00095C","00097B","00097C","0009B6","0009B7","0009E8",
            "0009E9","000A41","000A42","000A8A","000A8B","000AB7","000AB8","000AF3",
            "000AF4","000B45","000B46","000B5F","000B60","000B85","000BBE","000BBF",
            "000BFC","000BFD","000C30","000C31","000C41","000C85","000C86","000CCE",
            "000CCF","000D28","000D29","000D65","000D66","000DBC","000DBD","000DEC",
            "000DED","000E38","000E39","000E83","000E84","000ED6","000ED7","000F23",
            "000F24","000F34","000F35","000F8F","000F90","000FF7","000FF8" };
        for (String p : cisco) OUI.put(p, "Cisco");
        // Xiaomi
        String[] xiaomi = { "0C1DAF","102AB3","185936","2082C0","28E31F","34CE00",
            "3C47 11".replace(" ",""),"44650D","4CFB45","50EC50","584498","640980",
            "64B473","68DFDD","742344","7802F8","7C1DD9","8CBEBE","9C99A0","A4C138",
            "AC C1EE".replace(" ",""),"B0E235","C46AB7","D4970B","F0B429","F48B32",
            "F8A45F","FC64BA","78 11DC".replace(" ",""),"74 23 44".replace(" ","") };
        for (String p : xiaomi) OUI.put(p, "Xiaomi");
        // Belkin
        String[] belkin = { "001CDF","001E C3".replace(" ",""),"002275","0024 07".replace(" ",""),
            "08006F","08863B","14 91 82".replace(" ",""),"1C 1A C0".replace(" ",""),
            "24 F5 AA".replace(" ",""),"30 23 03".replace(" ",""),"44 E9 DD".replace(" ",""),
            "58 EF 68".replace(" ",""),"60 38 E0".replace(" ",""),"64 51 06".replace(" ",""),
            "74 1E 93".replace(" ",""),"84 1B 5E".replace(" ",""),"94 10 3E".replace(" ",""),
            "98 FC 11".replace(" ",""),"B4 75 0E".replace(" ",""),"C0 56 27".replace(" ",""),
            "EC 1A 59".replace(" ",""),"F8 1E DF".replace(" ","") };
        for (String p : belkin) OUI.put(p, "Belkin");
        // Arris
        String[] arris = { "00 15 96".replace(" ",""),"00 1A 66".replace(" ",""),
            "00 1A C4".replace(" ",""),"00 1B 52".replace(" ",""),
            "00 1D CF".replace(" ",""),"00 1E 46".replace(" ",""),
            "00 22 3A".replace(" ",""),"00 24 36".replace(" ",""),
            "00 26 36".replace(" ",""),"10 05 CA".replace(" ",""),
            "20 3D 66".replace(" ",""),"3C 7A 8A".replace(" ",""),
            "44 E9 DD".replace(" ",""),"5C 57 1A".replace(" ",""),
            "6C 5A B0".replace(" ",""),"78 71 D4".replace(" ",""),
            "84 61 A0".replace(" ",""),"94 87 7C".replace(" ",""),
            "A0 11 5B".replace(" ",""),"AC 87 A3".replace(" ",""),
            "B0 7F B9".replace(" ",""),"C0 05 C2".replace(" ",""),
            "C4 48 38".replace(" ",""),"D4 05 98".replace(" ",""),
            "E8 3E FC".replace(" ",""),"F8 ED A5".replace(" ","") };
        for (String p : arris) OUI.put(p, "ARRIS");
    }

    private String lookupManufacturer(String bssid) {
        if (bssid == null || bssid.length() < 8) return "Unknown";
        String prefix = bssid.substring(0, 8).replace(":", "").toUpperCase();
        String m = OUI.get(prefix);
        return m != null ? m : "Unknown (" + bssid.substring(0, 8) + ")";
    }

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
        });
        webView.addJavascriptInterface(new AndroidBridge(), "Android");
        webView.loadUrl("file:///android_asset/index.html");
        setContentView(webView);

        registerStateReceiver();
        registerWifiConnectionReceiver();
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

    private void registerWifiConnectionReceiver() {
        wifiConnectionReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                try {
                    NetworkInfo ni = i.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                    if (ni != null && ni.isConnected()) {
                        WifiInfo wi = wifiManager.getConnectionInfo();
                        if (wi != null) recordConnection(wi);
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

                JSONArray sightings = entry.optJSONArray("sightings");
                if (sightings == null) sightings = new JSONArray();
                JSONArray pt = new JSONArray();
                pt.put(now);
                if (loc != null) {
                    pt.put(loc.getLatitude());
                    pt.put(loc.getLongitude());
                    pt.put(loc.getAccuracy());
                } else {
                    pt.put(JSONObject.NULL); pt.put(JSONObject.NULL); pt.put(JSONObject.NULL);
                }
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
        // First try WifiInfo
        try {
            WifiInfo wi = wifiManager.getConnectionInfo();
            String mac = wi.getMacAddress();
            if (mac != null && !mac.equals("02:00:00:00:00:00")) return mac;
        } catch (Exception ignored) {}

        // Then try NetworkInterface enumeration (wlan0)
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                String name = ni.getName();
                if (!name.startsWith("wlan")) continue;
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

        return null;  // genuinely masked by Android
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
                    // strip %scope from IPv6 link-local
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
                if (bssid != null) o.put("RouterManufacturer", lookupManufacturer(bssid));

                // IPv6 & other interface info
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
                    JSONArray s = e.optJSONArray("sightings");
                    JSONArray c = e.optJSONArray("connections");
                    item.put("sightings", s != null ? s.length() : 0);
                    item.put("connections", c != null ? c.length() : 0);
                    // Include latest known location for map pinning
                    if (s != null) {
                        for (int i = s.length() - 1; i >= 0; i--) {
                            JSONArray pt = s.optJSONArray(i);
                            if (pt != null && pt.length() >= 3
                                    && !pt.isNull(1) && !pt.isNull(2)) {
                                item.put("lastLat", pt.optDouble(1));
                                item.put("lastLon", pt.optDouble(2));
                                item.put("lastLevel", pt.optInt(4, 0));
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
                // Ensure manufacturer is present
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
                o.put("Type", device.getType());
                try { o.put("Bonded", device.getBondState() == BluetoothDevice.BOND_BONDED); } catch (Exception ignored) {}
                o.put("Source", source);
                btSeen.put(address, o);
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
