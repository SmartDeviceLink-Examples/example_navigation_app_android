package com.livio.mobilenav;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.smartdevicelink.transport.SdlBroadcastReceiver;
import com.smartdevicelink.transport.SdlRouterService;

public class SdlReceiver extends SdlBroadcastReceiver {

    @Override
    public void onSdlEnabled(Context context, Intent intent) {
        intent.setClass(context, SdlService.class);
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            context.startService(intent);
        }else{
            context.startForegroundService(intent);
        }
    }

    @Override
    public Class<? extends SdlRouterService> defineLocalSdlRouterClass() {
        return com.livio.mobilenav.SdlRouterService.class;
    }
}
