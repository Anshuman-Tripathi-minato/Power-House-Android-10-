package com.powerhouse.android;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private static final String TAG = "PowerHouse";
    private static final int REQ_BT = 31;
    private static final int REQ_FINE_LOCATION = 32;
    private final Handler handler = new Handler();
    private BluetoothAdapter bluetoothAdapter;
    private SharedPreferences prefs;
    private TextView batteryPct, chargingPill, healthValue, degradationValue, healthSource;
    private TextView tempValue, voltageValue, timeValue, sourceValue, btAdapterValue;
    private LinearLayout pairedList, scanList;
    private Button scanButton;
    private BatteryChart chart;
    private CircularHealthView healthCircle;
    private final ArrayList<Float> history = new ArrayList<>();
    private BroadcastReceiver batteryReceiver;
    private BroadcastReceiver btReceiver;
    private boolean scanning = false;

    private String currentHealthDisplay = "—";
    private float currentHealthAnimPct = 0f;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refreshBattery(null);
            handler.postDelayed(this, 3000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("power_house", MODE_PRIVATE);
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        buildUi();
        registerBatteryReceiver();
        registerBluetoothReceiver();
        requestBluetoothPermissionsIfNeeded();
        handler.post(tick);
        refreshBluetooth();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new AmbientBackground());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(20), dp(20), dp(32));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon = text("⚡", 28, Color.WHITE);
        GradientDrawable iconBg = rounded(0xFFFF8A2A, 18, 0x55FFFFFF);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(iconBg);
        top.addView(icon, new LinearLayout.LayoutParams(dp(54), dp(54)));

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setPadding(dp(12), 0, 0, 0);
        TextView title = text("Power House", 26, Color.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        TextView sub = text("Battery intelligence • Bluetooth • device control", 13, 0xFFB9AAA0);
        titleBox.addView(title);
        titleBox.addView(sub);
        top.addView(titleBox, new LinearLayout.LayoutParams(0, -2, 1));

        Button refresh = button("Refresh", false);
        refresh.setOnClickListener(v -> { refreshBattery(null); refreshBluetooth(); });
        top.addView(refresh, new LinearLayout.LayoutParams(dp(100), dp(46)));
        page.addView(top, marginBottom(18));

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(18), dp(18), dp(18), dp(18));
        hero.setBackground(glass(0xCC201B1E, 24, 0x4DFFFFFF));

        LinearLayout heroRow = new LinearLayout(this);
        heroRow.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView cap = text("CURRENT BATTERY", 12, 0xFFB9AAA0);
        cap.setLetterSpacing(0.14f);
        batteryPct = text("--%", 56, Color.WHITE);
        batteryPct.setTypeface(null, android.graphics.Typeface.BOLD);
        chargingPill = badge("Checking…", 0x44FFFFFF, 0xFFFFF2E6);
        left.addView(cap);
        left.addView(batteryPct);
        left.addView(chargingPill, new LinearLayout.LayoutParams(-2, dp(34)));
        heroRow.addView(left, new LinearLayout.LayoutParams(0, -2, 1));

        healthCircle = new CircularHealthView(this);
        heroRow.addView(healthCircle, new LinearLayout.LayoutParams(dp(128), dp(128)));
        hero.addView(heroRow);

        LinearLayout metrics = new LinearLayout(this);
        metrics.setOrientation(LinearLayout.HORIZONTAL);
        healthValue = metric(metrics, "Health", "...");
        degradationValue = metric(metrics, "Wear", "...");
        tempValue = metric(metrics, "Temp", "--");
        voltageValue = metric(metrics, "Volt", "--");
        hero.addView(metrics, marginTop(14));

        healthSource = text("Telemetry source: initializing...", 11, 0xFFB9AAA0);
        hero.addView(healthSource, marginTop(10));
        page.addView(hero, marginBottom(16));

        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        timeValue = metric(stats, "Cycle/Charge", "--");
        sourceValue = metric(stats, "Power source", "--");
        btAdapterValue = metric(stats, "Bluetooth", "--");
        page.addView(stats, marginBottom(16));

        LinearLayout chartCard = glassCard("Battery & power trend", "Live battery history • Android reports what the device exposes");
        chart = new BatteryChart(this);
        chartCard.addView(chart, marginTop(10));
        page.addView(chartCard, marginBottom(16));

        page.addView(sectionHeader("BLUETOOTH", "Pair, scan, rename and manage nearby devices"), marginBottom(8));
        LinearLayout btCard = new LinearLayout(this);
        btCard.setOrientation(LinearLayout.VERTICAL);
        btCard.setPadding(dp(18), dp(18), dp(18), dp(18));
        btCard.setBackground(glass(0xC5201B1E, 24, 0x4DFFFFFF));

        LinearLayout adapterRow = new LinearLayout(this);
        adapterRow.setGravity(Gravity.CENTER_VERTICAL);
        adapterRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout adapterText = new LinearLayout(this);
        adapterText.setOrientation(LinearLayout.VERTICAL);
        TextView a1 = text("This phone", 17, Color.WHITE); a1.setTypeface(null, android.graphics.Typeface.BOLD);
        TextView a2 = text("Bluetooth adapter", 12, 0xFFB9AAA0);
        adapterText.addView(a1); adapterText.addView(a2);
        adapterRow.addView(adapterText, new LinearLayout.LayoutParams(0, -2, 1));
        Button renameLocal = button("Rename", false);
        renameLocal.setOnClickListener(v -> renameAdapter());
        adapterRow.addView(renameLocal, new LinearLayout.LayoutParams(dp(96), dp(44)));
        btCard.addView(adapterRow);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        scanButton = button("Scan devices", true);
        scanButton.setOnClickListener(v -> startScan());
        Button settingsBt = button("Bluetooth settings", false);
        settingsBt.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        actions.addView(scanButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        actions.addView(settingsBt, new LinearLayout.LayoutParams(0, dp(48), 1));
        btCard.addView(actions, marginTop(12));

        TextView pairedTitle = text("Paired devices", 18, Color.WHITE); pairedTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        btCard.addView(pairedTitle, marginTop(16));
        pairedList = new LinearLayout(this); pairedList.setOrientation(LinearLayout.VERTICAL);
        btCard.addView(pairedList, marginTop(8));

        TextView scanTitle = text("Scan results", 18, Color.WHITE); scanTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        btCard.addView(scanTitle, marginTop(16));
        scanList = new LinearLayout(this); scanList.setOrientation(LinearLayout.VERTICAL);
        btCard.addView(scanList, marginTop(8));
        page.addView(btCard, marginBottom(16));

        LinearLayout limitation = glassCard("Android capability note", "Battery health degradation, cycle counts, and raw capacity are restricted by Android SELinux on many modern devices. Power House uses PowerProfile reflection and live telemetry estimation to bypass some OS limitations.");
        page.addView(limitation);

        scroll.addView(page);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        applyWindowFeatures(root);
    }

    private void applyWindowFeatures(View root) {
        Window w = getWindow();
        w.setStatusBarColor(Color.rgb(11,10,13));
        w.setNavigationBarColor(Color.rgb(11,10,13));
        if (Build.VERSION.SDK_INT >= 30) {
            root.post(() -> {
                WindowInsetsController c = root.getWindowInsetsController();
                if (c != null) {
                    c.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
                }
            });
        }
    }

    private LinearLayout sectionHeader(String title, String sub) {
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(title, 12, 0xFFFFB347); t.setTypeface(null, android.graphics.Typeface.BOLD); t.setLetterSpacing(0.16f);
        TextView s = text(sub, 13, 0xFFB9AAA0);
        l.addView(t); l.addView(s);
        return l;
    }

    private LinearLayout glassCard(String title, String subtitle) {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(dp(18), dp(18), dp(18), dp(18)); c.setBackground(glass(0xB8201B1E, 22, 0x38FFFFFF));
        TextView t = text(title, 18, Color.WHITE); t.setTypeface(null, android.graphics.Typeface.BOLD);
        TextView s = text(subtitle, 12, 0xFFB9AAA0);
        c.addView(t); c.addView(s, marginTop(3));
        return c;
    }

    private TextView metric(LinearLayout parent, String label, String value) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(6), dp(10), dp(6), dp(10)); box.setBackground(glass(0x552B252A, 17, 0x26FFFFFF));
        TextView l = text(label, 10, 0xFFB9AAA0);
        TextView v = text(value, 13, Color.WHITE); v.setTypeface(null, android.graphics.Typeface.BOLD);
        box.addView(l); box.addView(v, marginTop(4));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(78), 1); p.setMargins(dp(2), 0, dp(2), 0);
        parent.addView(box, p);
        return v;
    }

    private TextView text(String s, int size, int color) { TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color); return t; }
    private Button button(String s, boolean primary) { Button b = new Button(this); b.setText(s); b.setTextSize(13); b.setTextColor(Color.WHITE); b.setAllCaps(false); b.setPadding(dp(10),0,dp(10),0); b.setBackground(glass(primary?0xFFB96623:0x40333338, 18, primary?0x77FFB347:0x40FFFFFF)); return b; }
    private TextView badge(String s, int bg, int fg) { TextView t=text(s,12,fg); t.setGravity(Gravity.CENTER); t.setPadding(dp(13),0,dp(13),0); t.setBackground(glass(bg, 17, 0x55FFB347)); return t; }
    private GradientDrawable rounded(int fill, int radius, int stroke) { GradientDrawable d=new GradientDrawable(); d.setColor(fill); d.setCornerRadius(dp(radius)); d.setStroke(dp(1), stroke); return d; }
    private GradientDrawable glass(int fill, int radius, int stroke) { return rounded(fill,radius,stroke); }
    private LinearLayout.LayoutParams marginTop(int top) { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.topMargin=dp(top); return p; }
    private LinearLayout.LayoutParams marginBottom(int bottom) { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.bottomMargin=dp(bottom); return p; }
    private int dp(int n){ return (int)(n*getResources().getDisplayMetrics().density+0.5f); }

    private void registerBatteryReceiver(){
        batteryReceiver = new BroadcastReceiver(){ @Override public void onReceive(Context c, Intent intent){ refreshBattery(intent); } };
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_EXPORTED); else registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    private void registerBluetoothReceiver(){
        btReceiver = new BroadcastReceiver(){ @Override public void onReceive(Context c, Intent intent){ String a=intent.getAction(); if(BluetoothDevice.ACTION_FOUND.equals(a)){ BluetoothDevice d=intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE); addScanDevice(d); } else if(BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(a)){ scanning=false; if(scanButton!=null) scanButton.setText("Scan devices"); } else if(BluetoothAdapter.ACTION_STATE_CHANGED.equals(a)){ refreshBluetooth(); } } };
        IntentFilter f=new IntentFilter(); f.addAction(BluetoothDevice.ACTION_FOUND); f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED); f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED); if (Build.VERSION.SDK_INT >= 33) registerReceiver(btReceiver, f, Context.RECEIVER_EXPORTED); else registerReceiver(btReceiver, f);
    }

    private void refreshBattery(Intent batteryIntent){
        if(batteryIntent==null) batteryIntent=registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if(batteryIntent==null) return;
        int level=batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL,-1); int scale=batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE,100);
        int pct=scale>0?(int)Math.round(level*100.0/scale):-1;
        int status=batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS,-1); int plugged=batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED,0);
        boolean charging=status==BatteryManager.BATTERY_STATUS_CHARGING || status==BatteryManager.BATTERY_STATUS_FULL;
        String cs=statusText(status, plugged);
        batteryPct.setText(pct>=0?String.format(Locale.US,"%d%%",pct):"--%");
        chargingPill.setText(cs);
        tempValue.setText(String.format(Locale.US,"%.1f°C", batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,0)/10.0));
        voltageValue.setText(String.format(Locale.US,"%.2f V", batteryIntent.getIntExtra(BatteryManager.EXTRA_VOLTAGE,0)/1000.0));
        sourceValue.setText(powerSource(plugged));

        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        long cycles = -1;
        if (Build.VERSION.SDK_INT >= 34) {
            try { cycles = bm.getLongProperty(8); } catch(Exception ignored) {}
        }
        if (cycles > 0) {
            timeValue.setText(cycles + " cycles");
        } else {
            long ms = -1;
            if(charging && Build.VERSION.SDK_INT>=28){ try { ms=bm.computeChargeTimeRemaining(); } catch(Exception ignored){} }
            if(ms>0) timeValue.setText(formatTime(ms)); else timeValue.setText("—");
        }

        int health=batteryIntent.getIntExtra(BatteryManager.EXTRA_HEALTH,BatteryManager.BATTERY_HEALTH_UNKNOWN);
        String hl = healthLabel(health);
        healthValue.setText(hl);
        currentHealthDisplay = hl.toUpperCase();

        Capacity caps=readCapacity();
        double design = getDesignCapacity();
        
        Log.d(TAG, "Design Capacity: " + design);
        Log.d(TAG, "Battery level: " + pct + "%");

        if(caps.valid && caps.design>0 && caps.full>0){
            double h=Math.max(0,Math.min(100,caps.full/caps.design*100));
            currentHealthAnimPct = (float)(h/100.0);
            currentHealthDisplay = String.format(Locale.US,"%.1f%%",h);
            healthValue.setText(currentHealthDisplay);
            degradationValue.setText(String.format(Locale.US,"%.1f%%",100-h));
            healthSource.setText("Source: Raw hardware telemetry bypass");
        } else if (design > 100 && pct > 0) {
            long currentCharge_uAh = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
            if (currentCharge_uAh > 0) {
                double currentCharge_mAh = currentCharge_uAh / 1000.0;
                double estimatedFull_mAh = (currentCharge_mAh / pct) * 100.0;
                double healthPct = Math.max(0, Math.min(100, (estimatedFull_mAh / design) * 100.0));
                currentHealthAnimPct = (float)(healthPct / 100.0);
                currentHealthDisplay = String.format(Locale.US, "%.1f%%", healthPct);
                healthValue.setText(currentHealthDisplay);
                degradationValue.setText(String.format(Locale.US, "%.1f%%", 100 - healthPct));
                healthSource.setText("Source: Live capacity estimation (" + hl.toUpperCase() + ")");
            } else {
                currentHealthAnimPct = 1.0f;
                degradationValue.setText("...");
                healthSource.setText("Source: Estimating... (System reports " + hl.toUpperCase() + ")");
            }
        } else {
            currentHealthAnimPct = 1.0f;
            degradationValue.setText("Unavailable");
            healthSource.setText("Source: System reported (" + hl.toUpperCase() + ")");
        }

        if (healthCircle != null) healthCircle.invalidate();
        history.add((float)Math.max(0,Math.min(100,pct))); while(history.size()>40) history.remove(0); if(chart!=null) chart.setData(history);
    }

    private double getDesignCapacity() {
        try {
            Object profile = Class.forName("com.android.internal.os.PowerProfile").getConstructor(Context.class).newInstance(this);
            double cap = (double) Class.forName("com.android.internal.os.PowerProfile").getMethod("getBatteryCapacity").invoke(profile);
            if (cap > 100) return cap;
        } catch (Exception ignored) {}
        
        String[] keys = {"ro.transsion.battery.design_capacity", "ro.battery.capacity", "persist.sys.battery.design"};
        for (String k : keys) {
            String val = getSystemProperty(k);
            if (val != null && !val.isEmpty()) {
                try { return Double.parseDouble(val); } catch (Exception ignored) {}
            }
        }
        return 0;
    }
    
    private String getSystemProperty(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            return (String) c.getMethod("get", String.class).invoke(c, key);
        } catch (Exception e) { return null; }
    }

    private String statusText(int s,int plugged){ if(s==BatteryManager.BATTERY_STATUS_FULL) return "Full • plugged"; if(s==BatteryManager.BATTERY_STATUS_CHARGING) return "Charging now"; if(s==BatteryManager.BATTERY_STATUS_DISCHARGING) return "On battery"; if(s==BatteryManager.BATTERY_STATUS_NOT_CHARGING) return "Plugged • not charging"; return "Battery state unknown"; }
    private String powerSource(int p){ if(p==BatteryManager.BATTERY_PLUGGED_AC) return "AC"; if(p==BatteryManager.BATTERY_PLUGGED_USB) return "USB"; if(Build.VERSION.SDK_INT>=17 && p==BatteryManager.BATTERY_PLUGGED_WIRELESS) return "Wireless"; return "Battery"; }
    private String formatTime(long ms){ long min=ms/60000; return min<60?min+" min":(min/60)+"h "+(min%60)+"m"; }
    private String healthLabel(int h){ switch(h){case BatteryManager.BATTERY_HEALTH_GOOD:return "Good";case BatteryManager.BATTERY_HEALTH_OVERHEAT:return "Overheat";case BatteryManager.BATTERY_HEALTH_DEAD:return "Dead";case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE:return "Over voltage";case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE:return "Failure";case BatteryManager.BATTERY_HEALTH_COLD:return "Cold";default:return "Unknown";} }

    private static class Capacity { double design=0, full=0; boolean valid=false; }
    private Capacity readCapacity(){
        String[][] pairs={{"/sys/class/power_supply/battery/energy_full_design","/sys/class/power_supply/battery/energy_full"},{"/sys/class/power_supply/BAT0/energy_full_design","/sys/class/power_supply/BAT0/energy_full"},{"/sys/class/power_supply/battery/charge_full_design","/sys/class/power_supply/battery/charge_full"},{"/sys/class/power_supply/BAT0/charge_full_design","/sys/class/power_supply/BAT0/charge_full"}};
        for(String[] p:pairs){ try{ double d=Double.parseDouble(readText(p[0]).trim()); double f=Double.parseDouble(readText(p[1]).trim()); if(d>0&&f>0){ Capacity c=new Capacity(); c.design=d;c.full=f;c.valid=true;return c; } }catch(Exception ignored){} }
        return new Capacity();
    }
    private String readText(String path) throws Exception{ BufferedReader r=new BufferedReader(new FileReader(new File(path))); String s=r.readLine(); r.close(); if(s==null) throw new Exception(); return s; }

    private void requestBluetoothPermissionsIfNeeded(){
        if(Build.VERSION.SDK_INT>=31){ if(checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT},REQ_BT); }
        else if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){ requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},REQ_FINE_LOCATION); }
    }

    private boolean btReady(){ if(bluetoothAdapter==null){toast("This device has no Bluetooth adapter");return false;} if(Build.VERSION.SDK_INT>=31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){toast("Allow Nearby devices permission first");return false;} return bluetoothAdapter.isEnabled(); }

    private void refreshBluetooth(){
        if(bluetoothAdapter==null){btAdapterValue.setText("Unavailable");return;}
        boolean on; try{ on=bluetoothAdapter.isEnabled(); }catch(SecurityException e){btAdapterValue.setText("Permission needed");return; }
        btAdapterValue.setText(on?"Ready":"Off");
        pairedList.removeAllViews();
        if(!on){ pairedList.addView(text("Bluetooth is off. Open system settings to enable it.",13,0xFFB9AAA0)); return; }
        try{ for(BluetoothDevice d:bluetoothAdapter.getBondedDevices()) pairedList.addView(deviceRow(d,true)); }catch(SecurityException e){pairedList.addView(text("Bluetooth permission required.",13,0xFFB9AAA0));}
    }

    private void startScan(){
        if(!btReady()){ startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); return; }
        try{
            if(scanning){ bluetoothAdapter.cancelDiscovery(); scanning=false; scanButton.setText("Scan devices"); return; }
            scanList.removeAllViews(); scanRows.clear(); scanning=true; scanButton.setText("Stop scan"); bluetoothAdapter.startDiscovery();
        }catch(SecurityException e){toast("Bluetooth scan permission denied");}
    }

    private final Map<String,View> scanRows=new HashMap<>();
    private void addScanDevice(BluetoothDevice d){
        if(d==null)return; String key=d.getAddress(); if(scanRows.containsKey(key)) return; View row=deviceRow(d,false); scanRows.put(key,row); scanList.addView(row);
    }

    private View deviceRow(BluetoothDevice d, boolean paired){
        LinearLayout row=new LinearLayout(this); row.setOrientation(LinearLayout.VERTICAL); row.setPadding(dp(14),dp(12),dp(14),dp(12)); row.setBackground(glass(0x502A2429,20,0x2EFFFFFF));
        String address=d.getAddress(); String alias=prefs.getString("alias_"+address, null); String name=alias!=null?alias:safeDeviceName(d);
        LinearLayout head=new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout names=new LinearLayout(this); names.setOrientation(LinearLayout.VERTICAL);
        TextView nt=text(name,16,Color.WHITE); nt.setTypeface(null,android.graphics.Typeface.BOLD);
        TextView sub=text(address+" • "+(paired?"Paired":"Discovered")+" • battery unavailable",11,0xFFB9AAA0);
        names.addView(nt); names.addView(sub,marginTop(2)); head.addView(names,new LinearLayout.LayoutParams(0,-2,1));
        Button rename=button("Rename",false); rename.setOnClickListener(v->renameDevice(d,nt,sub)); head.addView(rename,new LinearLayout.LayoutParams(dp(92),dp(42)));
        row.addView(head);
        LinearLayout actions=new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL);
        if(!paired){ Button pair=button("Pair",true); pair.setOnClickListener(v->pair(d)); actions.addView(pair,new LinearLayout.LayoutParams(0,dp(44),1)); }
        else { Button settings=button("Device settings",false); settings.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS))); actions.addView(settings,new LinearLayout.LayoutParams(0,dp(44),1)); Button forget=button("Forget",false); forget.setOnClickListener(v->forget(d)); actions.addView(forget,new LinearLayout.LayoutParams(0,dp(44),1)); }
        row.addView(actions,marginTop(10));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(8);row.setLayoutParams(p);return row;
    }

    private String safeDeviceName(BluetoothDevice d){ try{String s=d.getName();return s==null||s.trim().isEmpty()?"Unknown device":s;}catch(SecurityException e){return "Protected device";} }
    private void pair(BluetoothDevice d){ try{boolean ok=d.createBond();toast(ok?"Pairing requested":"Pairing request could not start");}catch(SecurityException e){toast("Pairing permission denied");} }
    private void forget(BluetoothDevice d){ new AlertDialog.Builder(this).setTitle("Forget device?").setMessage(safeDeviceName(d)+" will be removed from paired devices.").setNegativeButton("Cancel",null).setPositiveButton("Forget",(di,w)->{try{java.lang.reflect.Method m = d.getClass().getMethod("removeBond"); boolean ok = (boolean) m.invoke(d); toast(ok?"Forget requested":"Could not forget device");handler.postDelayed(this::refreshBluetooth,800);}catch(Exception e){toast("Android denied the request");}}).show(); }

    private void renameAdapter(){ if(bluetoothAdapter==null)return; try { EditText input=new EditText(this); input.setSingleLine(); input.setText(bluetoothAdapter.getName()); input.setTextColor(Color.WHITE); input.setHintTextColor(0x88FFFFFF); input.setHint("New Bluetooth adapter name");
        new AlertDialog.Builder(this).setTitle("Rename Bluetooth adapter").setView(input).setNegativeButton("Cancel",null).setPositiveButton("Rename",(d,w)->{String n=input.getText().toString().trim(); if(n.isEmpty())return; try{boolean ok=bluetoothAdapter.setName(n);toast(ok?"Adapter renamed":"Android rejected the name change"); refreshBluetooth();}catch(SecurityException e){toast("Bluetooth permission required");}}).show(); } catch (SecurityException e) { toast("Bluetooth permission required"); } }

    private void renameDevice(BluetoothDevice d, TextView nameView, TextView sub){ EditText input=new EditText(this); input.setSingleLine(); input.setHint("Local device name"); input.setText(prefs.getString("alias_"+d.getAddress(),safeDeviceName(d))); input.setTextColor(Color.WHITE); input.setHintTextColor(0x88FFFFFF);
        new AlertDialog.Builder(this).setTitle("Rename device").setMessage("Power House keeps a local alias on every supported Android version. On Android 12+ it also attempts the native Bluetooth alias when the OS allows it.").setView(input).setNegativeButton("Cancel",null).setPositiveButton("Save",(di,w)->{String n=input.getText().toString().trim();if(n.isEmpty())return;prefs.edit().putString("alias_"+d.getAddress(),n).apply();if(Build.VERSION.SDK_INT>=31){try{d.setAlias(n);}catch(Exception ignored){}}nameView.setText(n);sub.setText(d.getAddress()+" • Paired • battery unavailable");toast("Name saved locally");}).show(); }

    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}

    @Override protected void onDestroy(){super.onDestroy();handler.removeCallbacks(tick);try{unregisterReceiver(batteryReceiver);}catch(Exception ignored){}try{unregisterReceiver(btReceiver);}catch(Exception ignored){}}

    private class AmbientBackground extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            int w = b.width(), h = b.height();
            if (w <= 0 || h <= 0) return;
            p.setShader(new android.graphics.LinearGradient(0, 0, w, h, new int[]{0xFF0B0A0D, 0xFF1A0F12, 0xFF10140D}, null, Shader.TileMode.CLAMP));
            c.drawRect(b, p);
            p.setShader(new android.graphics.RadialGradient(w * 0.1f, h * 0.15f, w * 0.8f, new int[]{0x55FF8A2A, 0x00000000}, null, Shader.TileMode.CLAMP));
            c.drawCircle(w * 0.1f, h * 0.15f, w * 0.8f, p);
            p.setShader(new android.graphics.RadialGradient(w * 0.9f, h * 0.8f, w * 0.75f, new int[]{0x444DFF2A, 0x00000000}, null, Shader.TileMode.CLAMP));
            c.drawCircle(w * 0.9f, h * 0.8f, w * 0.75f, p);
        }
        @Override public void setAlpha(int alpha) { p.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter colorFilter) { p.setColorFilter(colorFilter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private class CircularHealthView extends View {
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        public CircularHealthView(Context c){super(c);}
        @Override protected void onDraw(Canvas c){
            int w=getWidth(),h=getHeight();
            float r=Math.min(w,h)*0.34f, cx=w/2f, cy=h/2f;
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(10)); p.setColor(0x44333333);
            c.drawCircle(cx,cy,r,p);
            p.setColor(0xFFFF8A2A);
            c.drawArc(cx-r,cy-r,cx+r,cy+r,-90,360*currentHealthAnimPct,false,p);
            p.setStyle(Paint.Style.FILL); p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); p.setTextSize(dp(18)); p.setColor(Color.WHITE);
            c.drawText(currentHealthDisplay,cx,cy+dp(7),p);
            p.setTextSize(dp(9)); p.setTypeface(android.graphics.Typeface.DEFAULT); p.setColor(0xFFB9AAA0);
            c.drawText("HEALTH",cx,cy+dp(25),p);
        }
    }

    private class BatteryChart extends View {
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        ArrayList<Float> data=new ArrayList<>();
        public BatteryChart(Context c){super(c);setMinimumHeight(dp(120));}
        public void setData(List<Float> d){data.clear();data.addAll(d);invalidate();}
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            int w=getWidth(),h=getHeight();
            p.setStyle(Paint.Style.FILL); p.setColor(0x182B252A);
            c.drawRoundRect(0,0,w,h,dp(18),dp(18),p);
            if(data.size()<2)return;
            Path path=new Path(); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(3)); p.setColor(0xFFFF8A2A);
            for(int i=0;i<data.size();i++){
                float x=dp(12)+(w-dp(24))*(i/(float)(data.size()-1));
                float y=h-dp(18)-(h-dp(36))*(data.get(i)/100f);
                if(i==0)path.moveTo(x,y); else path.lineTo(x,y);
            }
            c.drawPath(path,p);
            p.setStyle(Paint.Style.FILL); p.setColor(0x33FF8A2A);
            Path fill=new Path(path); fill.lineTo(w-dp(12),h-dp(18)); fill.lineTo(dp(12),h-dp(18)); fill.close();
            c.drawPath(fill,p);
        }
    }
}
