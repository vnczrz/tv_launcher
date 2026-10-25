package tv.voxclean.launcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

/** Spike Home: two buttons that open YouTube and Netflix with the leanback launch intent. */
public class MainActivity extends Activity {
    private static final String TAG = "VoxSpike";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.addView(button("YouTube", "com.google.android.youtube.tv"));
        row.addView(button("Netflix", "com.netflix.ninja"));
        setContentView(row);
        Log.i(TAG, "Home onCreate");
    }

    private Button button(String label, String pkg) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(v -> open(pkg));
        return b;
    }

    private void open(String pkg) {
        Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (launch == null) {
            Toast.makeText(this, pkg + " not found", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(launch);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Log.i(TAG, "Home onNewIntent");
    }
}
