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
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

/** Touch-based bot control activity. */
public class BotControl extends Activity implements TouchJoystick.JoystickListener, ZMQSubscriberThread.OnMessageListener {
	private static final String TAG = "BotControl";

	/** Convenience class for specifying control ranges. */
	public static class ControlRange {
		public float min, zero_min, zero, zero_max, max;
		public float half_range, offset;
		
		/** Initialize control range members, compute some useful derived values. */
		ControlRange(float min, float zero_min, float zero, float zero_max, float max) {
			// NOTE min < zero_min <= zero <= zero_max < max
			this.min = min;
			this.zero_min = zero_min;
			this.zero = zero;
			this.zero_max = zero_max;
			this.max = max;
			
			this.half_range = (this.max - this.min) / 2;
			this.offset = (this.min + this.max) / 2;
		}
		
		/** Convert a normalized input value in [-1, 1] to a range-limited control value. */
		public float fromNormalizedInput(float value) {
			return applyLimits(offset + half_range * value);
		}
		
		/** Convert a range-limited control value to a normalized input value in [-1, 1]. */
		public float toNormalizedInput(float value) {
			return (value - offset) / half_range;
		}
		
		/** Apply range limits to control value. */
		public float applyLimits(float value) {
			return
				(value < min
					? min
					: (value < zero_min
						? value
						: (value <= zero_max
							? zero
							: (value <= max
								? value
								: max
							)
						)
					)
				);
		}
	}
	
	// Drive variables and ranges (TODO Check turn range)
	private static final ControlRange forwardRange = new ControlRange(-100.f, -25.f, 0.f, 25.f, 100.f);
	private float forward = forwardRange.zero;
	
	private static final ControlRange strafeRange = new ControlRange(-100.f, -25.f, 0.f, 25.f, 100.f);
	private float strafe = strafeRange.zero;
	
	private static final ControlRange turnRange = new ControlRange(-100.f, -25.f, 0.f, 25.f, 100.f);
	private float turn = turnRange.zero;
	// TODO Turning not implemented yet; turn is always 0

	private float lastForward = forward;
	private float lastStrafe = strafe;
	private float lastTurn = turn;
	
	// Turret variables and ranges (TODO Check pitch and yaw ranges)
	private static final ControlRange pitchRange = new ControlRange(75.f, 90.f, 90.f, 90.f, 135.f);
	private float pitch = pitchRange.zero;
	
	private static final ControlRange yawRange = new ControlRange(30.f, 90.f, 90.f, 90.f, 150.f);
	private float yaw = yawRange.zero;
	
	private float lastPitch = pitch;
	private float lastYaw = yaw;
	
	// Topics of streaming messages to subscribe to
	// TODO Enable topics: "drive" (forward, strafe, turn), "turret" (pitch, yaw), "ir" (front, back, left, right)
	private String[] subscriptionTopics = { "turret_pitch", "turret_yaw", "ir" };

	// Communication
	private String serverProtocol = "tcp";
	private String serverHost = "10.0.2.2"; // NOTE When running on an emulator, 10.0.2.2 refers to the host computer
	private int serverPort = 60000;
	private int pubServerPort = 60001;
	private ZMQClientThread clientThread = null;
	private ZMQSubscriberThread subscriberThread = null;

	// View elements
	private TouchJoystick driveJoystick = null;
	private TouchJoystick turretJoystick = null;
	private TextView txtForward = null;
	private TextView txtStrafe = null;
	private TextView txtPitch = null;
	private TextView txtYaw = null;
	private Button btnReload = null;
	private Button btnFire = null;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		
		// Setup view and obtain references to view elements
		setContentView(R.layout.activity_main);
		driveJoystick = (TouchJoystick) findViewById(R.id.driveJoystick);
		turretJoystick = (TouchJoystick) findViewById(R.id.turretJoystick);
		txtForward = (TextView) findViewById(R.id.txtForward);
		txtStrafe = (TextView) findViewById(R.id.txtStrafe);
		txtPitch = (TextView) findViewById(R.id.txtPitch);
		txtYaw = (TextView) findViewById(R.id.txtYaw);
		btnReload = (Button) findViewById(R.id.btnReload);
		btnFire = (Button) findViewById(R.id.btnFire);
		
		// Initialize control variables
		lastForward = forward = forwardRange.zero;
		lastStrafe = strafe = strafeRange.zero;
		lastTurn = turn = turnRange.zero;
		lastPitch = pitch = pitchRange.zero;
		lastYaw = yaw = yawRange.zero;
		
		// Configure view elements
		driveJoystick.setJoystickListener(this);
		turretJoystick.setJoystickListener(this);
		driveJoystick.updateKnob(strafeRange.toNormalizedInput(strafe), -forwardRange.toNormalizedInput(forward)); // NOTE Y-flip
		turretJoystick.updateKnob(yawRange.toNormalizedInput(yaw), -pitchRange.toNormalizedInput(pitch)); // NOTE Y-flip
		updateDriveViews();
		updateTurretViews();
		btnReload.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				doReload(false); // ok to drop (TODO show visual indication of success/failure, e.g. with button color?)
			}
		});
		btnFire.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				doFire(false); // ok to drop (TODO show visual indication of success/failure, e.g. with button color?)
			}
		});
	}

	@Override
	protected void onResume() {
		super.onResume();
		startClient();
		startSubscriber();
	}

	@Override
	protected void onPause() {
		stopSubscriber();
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
			serverParamsDialog.setTitle("Connect to server");
			serverParamsDialog.setMessage("IP address:");
			
			// Set an EditText view to get user input 
			final EditText txtServerHost = new EditText(BotControl.this);
			txtServerHost.setText(serverHost);
			serverParamsDialog.setView(txtServerHost);
			
			// Set button actions
			serverParamsDialog.setPositiveButton("Connect", new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int whichButton) {
					String newServerHost = txtServerHost.getText().toString();
					Log.d(TAG, "onOptionsItemSelected(): [serverParamsDialog] New server host: " + newServerHost);
					if (!newServerHost.equals(serverHost)) {
						// TODO Improve IP validation (current regex doesn't constrain the range of octet numbers); allow hostnames as well?
						if(Pattern.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+", newServerHost)) {
							setServerHost(newServerHost);
						}
						else {
							Log.w(TAG, "onOptionsItemSelected(): [serverParamsDialog] Ignoring invalid server host IP: " + newServerHost);
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
			
			// Show dialog
			serverParamsDialog.show();
			
			// TODO Create ServerParams activity instead of dialog
			//Intent serverParamsIntent = new Intent(this, ServerParams.class);
			//startActivity(serverParamsIntent);
			
			return true;
		
		case R.id.action_killserver:
			// Build a confirmation dialog
			final AlertDialog.Builder killServerDialog = new AlertDialog.Builder(BotControl.this);
			killServerDialog.setTitle("Kill server");
			killServerDialog.setMessage("Are you sure you want to kill the server instance?");
			
			// Set button actions
			killServerDialog.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int whichButton) {
					Log.d(TAG, "onOptionsItemSelected(): [killServerDialog] Killing server...");
					doKillServer(false); // ok to drop (try again later?)
				}
			});
			killServerDialog.setNegativeButton("No", new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int whichButton) {
					dialog.cancel();
				}
			});
			
			// Show dialog
			killServerDialog.show();
			return true;
		
		case R.id.action_settings:
			Log.w(TAG, "onOptionsItemSelected(): Settings not implemented");
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private void setServerHost(String serverHost) {
		Log.d(TAG, "setServerHost(): Resetting client threads...");
		stopSubscriber();
		stopClient();
		this.serverHost = serverHost;
		startClient();
		startSubscriber();
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
	
	private void startSubscriber() {
		stopSubscriber();
		Log.d(TAG, "startSubscriber(): Starting subscriber thread...");
		subscriberThread = new ZMQSubscriberThread(serverProtocol, serverHost, pubServerPort);
		subscriberThread.setTopics(subscriptionTopics);
		subscriberThread.setListener(this);
		subscriberThread.start();
	}
	
	private void stopSubscriber() {
		if (subscriberThread != null) {
			Log.d(TAG, "stopSubscriber(): Stopping subscriber thread...");
			subscriberThread.term();
			subscriberThread = null;
		}
	}

	@Override
	public boolean onJoystickEvent(TouchJoystick joystick, int action, float x, float y) {
		if (joystick == driveJoystick) {
			switch(action) {
			case MotionEvent.ACTION_DOWN:
				//Log.d(TAG, "onJoystickEvent(): [drive/ACTION_DOWN] @ (" + x + ", " + y + ")");
				driveJoystick.updateKnob(x, y);
				// NOTE Don't produce movement command here, only on ACTION_MOVE, in case this is just a tap
				return true;
			
			case MotionEvent.ACTION_MOVE:
				//Log.d(TAG, "onJoystickEvent(): [drive/ACTION_MOVE] @ (" + x + ", " + y + ")");
				driveJoystick.updateKnob(x, y);
				
				// Compute control inputs
				forward = forwardRange.fromNormalizedInput(-driveJoystick.knobYNorm); // NOTE Y-flip
				strafe  = strafeRange.fromNormalizedInput(driveJoystick.knobXNorm);
				//Log.d(TAG, "onJoystickEvent(): [drive/ACTION_MOVE] forward = " + forward + ", strafe = " + strafe + ", turn = " + turn);
				
				doDrive(false); // ok to drop
				return true;
			
			case MotionEvent.ACTION_UP:
				//Log.d(TAG, "onJoystickEvent(): [drive/ACTION_UP] @ (" + x + ", " + y + ")");
				forward = forwardRange.zero;
				strafe = strafeRange.zero;
				turn = turnRange.zero;
				driveJoystick.updateKnob(forward, strafe); // spring back to neutral
				doDrive(true); // NOT ok to drop
				return true;
			}
		}
		else if (joystick == turretJoystick) {
			switch(action) {
			case MotionEvent.ACTION_DOWN:
				turretJoystick.updateKnob(x, y);
				return true;
			
			case MotionEvent.ACTION_MOVE:
				turretJoystick.updateKnob(x, y);
				
				// Compute control inputs
				pitch = pitchRange.fromNormalizedInput(-turretJoystick.knobYNorm); // NOTE Y-flip
				yaw = yawRange.fromNormalizedInput(turretJoystick.knobXNorm);
				//Log.d(TAG, "onJoystickEvent(): [turret/ACTION_MOVE] pitch = " + pitch + ", yaw = " + yaw);
				
				doTurret(false); // ok to drop
				return true;
			
			case MotionEvent.ACTION_UP:
				// NOP
				return true;
			}
		}
		return false;
	}

	private void doDrive(final boolean block) {
		// Generate and send drive command, if different from last
		if (forward != lastForward || strafe != lastStrafe || turn != lastTurn) {
			updateDriveViews(); // TODO update upon successful reply?
			sendCommand(
				// NOTE Hopefully the string literals get compiled into one!
				String.format(
					"{" +
						"cmd: fwd_strafe_turn, " +
						"opts: {" +
							"fwd: %.2f, " +
							"strafe: %.2f, " +
							"turn: %.2f" +
						"}" +
					"}",
					forward,
					strafe,
					turn),
				block);
			
			// Store last sent values to prevent repeats (NOTE these are only values *sent*, not necessarily received by the server)
			lastForward = forward;
			lastStrafe = strafe;
			lastTurn = turn;
		}
	}

	private void doTurret(final boolean block) {
		// Generate and send turret command, if different from last
		if (pitch != lastPitch || yaw != lastYaw) {
			//updateTurretViews(); // update upon receiving a message from subscriber
			sendCommand(
				String.format(
					"{" +
						"cmd: aim, " +
						"opts: {" +
							"pitch: %.2f, " +
							"yaw: %.2f" +
						"}" +
					"}",
					pitch,
					yaw),
				block);
			
			// Store last sent values to prevent repeats (NOTE these are only values *sent*, not necessarily received by the server)
			lastPitch = pitch;
			lastYaw = yaw;
		}
	}

	private void doReload(final boolean block) {
		// Generate and send reload command
		sendCommand("{cmd: advance_dart, opts: {}}", block);
	}

	private void doFire(final boolean block) {
		// Generate and send fire command
		sendCommand("{cmd: fire, opts: {}}", block);
	}

	private void doKillServer(final boolean block) {
		// Generate and send kill command to stop server
		sendCommand("{cmd: die, opts: {}}", block);
	}

	private void sendCommand(final String cmdStr, final boolean block) {
		//Log.d(TAG, "sendCommand(): forward = " + forward + ", strafe = " + strafe + ", turn = " + turn);
		// Send this command (JSON string) to the control server,
		//   wait for ACK, deal with concurrency issues
		if (clientThread != null && clientThread.isAlive()) {
			// Start a new thread to avoid blocking the main (UI) thread
			(new Thread() {
				public void run() {
					//Log.d(TAG, "Sending : " + cmdStr);
					clientThread.serviceRequestSync(cmdStr, block);
					//Log.d(TAG, "Received: " + reply);
					// TODO Check reply to see if it was successful or not (and copy values sent back?)
				}
			}).start();
		}
	}
	
	@Override
	public void onMessage(String message) {
		String messageParts[] = message.split("\\s", 2); // split message into 2 parts on first whitespace
		if (messageParts.length < 2) {
			Log.w(TAG, "onMessage(): Ignoring invalid message: \"" + message + "\"");
			return;
		}
		
		String topic = messageParts[0].trim();
		String data = messageParts[1].trim();
		try {
			// TODO Handle drive updates (forward, strafe, turn)
			// TODO Combine turret updates (pitch, yaw) into one
			// TODO Handle IR array updates [topic.startsWith("ir")]
			if (topic.equals("turret_pitch")) {
				final float realPitch = Float.parseFloat(data);
				runOnUiThread(new Runnable() {
					public void run() {
						txtPitch.setText(String.format("%7.2f", realPitch)); //updateTurretViews();
					};
				});
			}
			else if (topic.equals("turret_yaw")) {
				final float realYaw = Float.parseFloat(data);
				runOnUiThread(new Runnable() {
					public void run() {
						txtYaw.setText(String.format("%7.2f", realYaw)); //updateTurretViews();
					};
				});
			}
		}
		catch (NumberFormatException e) {
			Log.w(TAG, "onMessage(): [topic=" + topic + "] Ignoring invalid data: \"" + data + "\"");
		}
	}
	
	private void updateDriveViews() {
		txtForward.setText(String.format("%7.2f", forward));
		txtStrafe.setText(String.format("%7.2f", strafe));
	}
	
	private void updateTurretViews() {
		txtPitch.setText(String.format("%7.2f", pitch));
		txtYaw.setText(String.format("%7.2f", yaw));
	}

}
