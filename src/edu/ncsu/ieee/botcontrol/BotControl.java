package edu.ncsu.ieee.botcontrol;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;

/** Touch-based bot control activity. */
public class BotControl extends Activity {
	private static final String TAG = "BotControl";

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.main, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch(item.getItemId()) {
		case R.id.action_zmqtest:
			Intent intent = new Intent(this, ZMQTest.class);
			startActivity(intent);
			return true;
		
		case R.id.action_settings:
			Log.w(TAG, "onOptionsItemSelected(): Settings not implemented");
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

}
