package dev.wind.agent;

import android.app.Service;

/**
 * Base service providing API 34 and API 35 timeout callbacks.
 * Java allows overriding methods without mandatory @Override annotations,
 * guaranteeing seamless compilation on both API 34 (local) and API 35 (CI).
 */
public abstract class BaseAgentService extends Service {
    public void onTimeout(int startId) {
        handleFgsTimeout(startId);
    }

    public void onTimeout(int startId, int fgsType) {
        handleFgsTimeout(startId);
    }

    protected abstract void handleFgsTimeout(int startId);
}