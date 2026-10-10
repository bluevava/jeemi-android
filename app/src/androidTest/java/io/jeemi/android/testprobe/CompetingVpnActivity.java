package io.jeemi.android.testprobe;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

public class CompetingVpnActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private final Runnable update = new Runnable() {
        @Override public void run() {
            status.setText(CompetingVpnService.active ? "TEST_VPN_ACTIVE" : "TEST_VPN_STOPPED");
            handler.postDelayed(this, 100);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (getIntent().getBooleanExtra("stop", false)) {
            // A started VPN can also be system-bound: close its TUN before stopSelf.
            startService(new Intent(this, CompetingVpnService.class).setAction("stop"));
            finish();
            return;
        }
        status = new TextView(this);
        setContentView(status);
        handler.post(update);
        // Inspecting this already-running test VPN must not reclaim it from Jeemi.
        if (!getIntent().getBooleanExtra("inspect", false)) {
            Intent permission = VpnService.prepare(this);
            if (permission == null) startVpn(); else startActivityForResult(permission, 7);
        }
    }
    private void startVpn() { startForegroundService(new Intent(this, CompetingVpnService.class)); }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 7 && result == RESULT_OK) startVpn();
    }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy(); }
}
