package com.dnsperapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Control the service from shell/Tasker: am broadcast -a com.dnsperapp.START -p com.dnsperapp */
public class ControlReceiver extends BroadcastReceiver {
    private static final String TAG = "DnsPerApp.Ctl";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        if (action.equals("com.dnsperapp.START")) {
            Controller.start(context);
            Log.i(TAG, "START requested");
        } else if (action.equals("com.dnsperapp.STOP")) {
            Controller.stop(context);
            Log.i(TAG, "STOP requested");
        }
    }
}