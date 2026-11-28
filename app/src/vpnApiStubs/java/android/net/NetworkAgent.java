package android.net;

import android.content.Context;
import android.os.Looper;

/** Compile-only signature, checked against BZF4 framework-connectivity.jar. Never packaged. */
public abstract class NetworkAgent {
    public NetworkAgent(Context context, Looper looper, String tag, NetworkCapabilities capabilities,
            LinkProperties properties, NetworkScore score, NetworkAgentConfig config, NetworkProvider provider) {
        throw new UnsupportedOperationException("Compile-only platform signature");
    }
}
