package com.example.tendacontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String a=intent==null?null:intent.getAction();
        if ((Intent.ACTION_BOOT_COMPLETED.equals(a)||Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) && Prefs.bgRunning(context) && Prefs.backgroundEnabled(context)) BackgroundModeService.resume(context);
    }
}
