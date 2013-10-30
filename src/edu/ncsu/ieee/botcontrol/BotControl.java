package edu.ncsu.ieee.botcontrol;

import java.util.regex.Pattern;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;

/** Touch-based bot control activity. */
public class BotControl extends Activity {
	private static final String TAG = "BotControl";

	private String serverProtocol = "tcp";
	private String serverHost = "10.0.2.2"; // NOTE When running on an emulator, 10.0.2.2 refers to the host computer
	private int serverPort = 60000;
	private String dialogInput = null;

	private ZMQClientThread clientThread = null;
	
	private TouchJoystick driveJoystick = null;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		
		driveJoystick = (TouchJoystick) findViewById(R.id.driveJoystick);
	}

	@Override
	protected void onResume() {
		super.onResume();
		startClient();
		driveJoystick.setClientThread(clientThread);
	}

	@Override
	protected void onPause() {
		driveJoystick.setClientThread(null);
		stopClient();
		super.onPause();
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
			Intent zmqTestIntent = new Intent(this, ZMQTest.class);
			startActivity(zmqTestIntent);
			return true;
		
		case R.id.action_serverparams:
			Log.d(TAG, "onOptionsItemSelected(): Getting new server params...");
			// Build a dialog
			final AlertDialog.Builder serverParamsDialog = new AlertDialog.Builder(BotControl.this);
			serverParamsDialog.setTitle("Server params");
			serverParamsDialog.setMessage("Bot server IP address:");
			
			// Set an EditText view to get user input 
			final EditText txtServerHost = new EditText(BotControl.this);
			txtServerHost.setText(serverHost);
			serverParamsDialog.setView(txtServerHost);
			
			// Set button actions
			serverParamsDialog.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int whichButton) {
					String newServerHost = txtServerHost.getText().toString();
					Log.d(TAG, "onOptionsItemSelected(): [serverParamsDialog] New server host: " + newServerHost);
					// TODO Allow hostnames as well?
					if (!newServerHost.equals(serverHost)) {
						// TODO Improve IP validation (current regex doesn't constrain the range of octet numbers)
						if(Pattern.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+", newServerHost)) {
							setServerHost(newServerHost);
						}
						else {
							Log.e(TAG, "onOptionsItemSelected(): [serverParamsDialog] Invalid server host IP: " + newServerHost);
						}
					}
				}
			});
			serverParamsDialog.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int whichButton) {
					dialog.cancel();
				}
			});
			
			serverParamsDialog.show();
			
			// TODO Create ServerParams activity instead of dialog
			//Intent serverParamsIntent = new Intent(this, ZMQTest.class);
			//startActivity(serverParamsIntent);
			
			return true;
		
		case R.id.action_settings:
			Log.w(TAG, "onOptionsItemSelected(): Settings not implemented");
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private void setServerHost(String newServerHost) {
		serverHost = newServerHost;
		Log.d(TAG, "setServerHost(): Resetting client thread...");
		driveJoystick.setClientThread(null);
		stopClient();
		startClient();
		driveJoystick.setClientThread(clientThread);
	}

	private void startClient() {
		stopClient(); // stop previously running client thread, if any
		Log.d(TAG, "startClient(): Starting client thread...");
		clientThread = new ZMQClientThread(serverProtocol, serverHost, serverPort);
		clientThread.start();
	}

	private void stopClient() {
		if (clientThread != null) {
			Log.d(TAG, "stopClient(): Stopping client thread...");
			clientThread.term();
			clientThread = null;
		}
	}
}
