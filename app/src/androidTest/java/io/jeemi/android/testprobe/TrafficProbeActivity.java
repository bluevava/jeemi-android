package io.jeemi.android.testprobe;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.Proxy;
import java.io.ByteArrayOutputStream;

/** Test-APK activity deliberately runs under a different UID from Jeemi. */
public class TrafficProbeActivity extends Activity {
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        TextView result = new TextView(this);
        result.setText("VPN traffic probe");
        setContentView(result);
        String target = getIntent().getStringExtra("url");
        new Thread(() -> {
            String status;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(target).openConnection(Proxy.NO_PROXY);
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(12000);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                java.io.InputStream input = connection.getInputStream();
                byte[] buffer = new byte[1024];
                int count;
                while ((count = input.read(buffer)) >= 0 && bytes.size() < 4096) bytes.write(buffer, 0, count);
                input.close();
                status = bytes.toString("UTF-8");
            } catch (Exception error) { status = "PROBE_FAILED_" + error.getClass().getSimpleName() + ": " + error.getMessage(); }
            finally { if (connection != null) connection.disconnect(); }
            final String value = status;
            runOnUiThread(() -> result.setText(value));
        }, "separate-uid-probe").start();
    }
}
