package com.gohj99.galaxywatchhelper.utils.vpn;

import android.content.Context;
import android.net.LinkProperties;
import android.net.NetworkAgent;
import android.net.NetworkAgentConfig;
import android.net.NetworkCapabilities;
import android.net.NetworkProvider;
import android.net.NetworkScore;
import android.os.Looper;

/** NetworkAgent is abstract even though its callback methods have default implementations. */
final class GwhNetworkAgent extends NetworkAgent {
    GwhNetworkAgent(Context context, Looper looper, String tag, NetworkCapabilities capabilities,
            LinkProperties properties, NetworkScore score, NetworkAgentConfig config, NetworkProvider provider) {
        super(context, looper, tag, capabilities, properties, score, config, provider);
    }
}
