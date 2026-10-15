package com.example.mixed.app;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import androidx.annotation.Nullable;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AppJavaCoordinator coordinator = new AppJavaCoordinator();
        AppKotlinWorkflow workflow = new AppKotlinWorkflow();
        String report = workflow.buildFullReport(coordinator, "R3-Handset-Launch");

        TextView tv = new TextView(this);
        tv.setText(report);
        setContentView(tv);
    }
}
