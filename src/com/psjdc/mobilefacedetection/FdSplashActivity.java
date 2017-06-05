package com.psjdc.mobilefacedetection;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;

import com.psjdc.mobilefacedetection.R;

public class FdSplashActivity extends Activity {

	// *******************************************************
	// Initialize
	// *******************************************************
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_splash);

		Handler handler = new Handler();
		handler.postDelayed(new Runnable() {
			public void run() {
				Intent intent = new Intent(FdSplashActivity.this,
						FdActivity.class);

				if (intent != null) {
					startActivity(intent);
					overridePendingTransition(0, 0);
					finish();
				}
			}
		}, 1000);
	}
}
