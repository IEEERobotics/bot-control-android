package edu.ncsu.ieee.botcontrol;

import android.app.Activity;
import android.os.Bundle;

/** Sample Android activity that runs a ZMQ request-reply client/server test routine using appropriate views in a layout. */
public class ZMQTest extends Activity {
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_zmqtest);
	}
}
