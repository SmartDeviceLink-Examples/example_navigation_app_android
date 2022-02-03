package com.livio.mobilenav;

import android.content.Context;

import com.smartdevicelink.managers.SdlManager;
import com.smartdevicelink.managers.SdlManagerListener;
import com.smartdevicelink.managers.file.filetypes.SdlArtwork;
import com.smartdevicelink.proxy.rpc.enums.AppHMIType;
import com.smartdevicelink.proxy.rpc.enums.Language;
import com.smartdevicelink.transport.BaseTransportConfig;
import com.smartdevicelink.transport.MultiplexTransportConfig;

import java.util.Vector;

public class SdlManagerFactory {

    public static SdlManager createSdlManager(Context context,
                                              String appID,
                                              String appName,
                                              SdlManagerListener listener,
                                              Vector<AppHMIType> appTypes,
                                              SdlArtwork appIcon,
                                              BaseTransportConfig transportConfig,
                                              Language language,
                                              String resumeHash) {

        SdlManager.Builder builder = new SdlManager.Builder(context, appID, appName, listener);
        builder.setAppTypes(appTypes)
                .setTransportType(new MultiplexTransportConfig(context, appID))
                .setAppIcon(appIcon)
                .setTransportType(transportConfig)
                .setLanguage(language)
                .setResumeHash(resumeHash);

        return builder.build();
    }
}

