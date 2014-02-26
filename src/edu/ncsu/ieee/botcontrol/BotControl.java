package edu.ncsu.ieee.botcontrol;

import java.util.regex.Pattern;

import org.json.JSONException;
import org.json.JSONObject;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
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
import android.widget.Toast;
import android.widget.ToggleButton;

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

	private JSONObject driveCmdObj = new JSONObject(); // cached JSON objects for frequent use

	// Turret variables and ranges (TODO Check pitch and yaw ranges)
	private static final ControlRange pitchRange = new ControlRange(60.f, 90.f, 90.f, 90.f, 120.f);
	private float pitch = pitchRange.zero;

	private static final ControlRange yawRange = new ControlRange(10.f, 90.f, 90.f, 90.f, 170.f);
	private float yaw = yawRange.zero;

	private float lastPitch = pitch;
	private float lastYaw = yaw;

	private JSONObject turretCmdObj;

	// Gun variables
	private int laser = 0;
	private int lastLaser = laser;
	private int spin = 0;
	private int lastSpin = spin;

	private JSONObject laserCmdObj;
	private JSONObject spinCmdObj;
	private JSONObject fireCmdObj;

	// Other control variables
	private boolean pingOkay = false;
	private JSONObject pingCmdObj;
	private JSONObject exitCmdObj;
	
	// Misc. variables
	int unknownColor = Color.rgb(80, 80, 80);
	int okayColor = Color.rgb(80, 120, 80);
	int errorColor = Color.rgb(120, 80, 80);

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
	private Button btnPing = null;
	private ToggleButton btnLaser = null;
	private ToggleButton btnSpin = null;
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
		btnPing = (Button) findViewById(R.id.btnPing);
		btnLaser = (ToggleButton) findViewById(R.id.btnLaser);
		btnSpin = (ToggleButton) findViewById(R.id.btnSpin);
		btnFire = (Button) findViewById(R.id.btnFire);

		// Initialize variables
		lastForward = forward = forwardRange.zero;
		lastStrafe = strafe = strafeRange.zero;
		lastTurn = turn = turnRange.zero;
		lastPitch = pitch = pitchRange.zero;
		lastYaw = yaw = yawRange.zero;
		lastLaser = laser = 0;
		lastSpin = spin = 0;

		// Initialize JSON objects that will be used frequently to make call requests
		driveCmdObj = makeCallReq("driver", "move_forward_strafe", new String[] { "forward", "strafe" }, new Object[] { forward, strafe });
		turretCmdObj = makeCallReq("gunner", "aim_turret", new String[] { "yaw", "pitch" }, new Object[] { yaw, pitch });
		pingCmdObj = makePingReq();
		laserCmdObj = makeCallReq("gun", "set_laser", new String[] { "state" }, new Object[] { laser });
		spinCmdObj = makeCallReq("gun", "set_spin", new String[] { "state" }, new Object[] { spin });
		fireCmdObj = makeCallReq("gun", "fire", null, null);
		exitCmdObj = makeExitReq();

		// Configure view elements
		driveJoystick.setJoystickListener(this);
		turretJoystick.setJoystickListener(this);
		driveJoystick.updateKnob(strafeRange.toNormalizedInput(strafe), -forwardRange.toNormalizedInput(forward)); // NOTE Y-flip
		turretJoystick.updateKnob(yawRange.toNormalizedInput(yaw), pitchRange.toNormalizedInput(pitch));
		updateDriveViews();
		updateTurretViews();
		btnPing.setBackgroundColor(unknownColor);
		btnPing.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				pingOkay = false; // assume not okay, unless it is
				btnPing.setBackgroundColor(errorColor);
				doPing(true); // block till reply (on reply, show visual indication using button color)
			}
		});
		btnLaser.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				laser = (btnLaser.isChecked() ? 1 : 0);
				doLaser(false); // ok to drop (on reply, show visual indication of success/failure with toggle button state)
			}
		});
		btnSpin.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				spin = (btnSpin.isChecked() ? 1 : 0);
				doSpin(false); // ok to drop (on reply, show visual indication of success/failure with toggle button state)
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
				pitch = pitchRange.fromNormalizedInput(turretJoystick.knobYNorm);
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

	private void doPing(final boolean block) {
		// Ping the control server, get response time in milliseconds
		final long startTime = System.currentTimeMillis();
		sendCommand(
			pingCmdObj,
			block,
			new CommandReplyCallback() {
				@Override
				public void onReply(final String reply) {
					// Parse JSON reply and update if valid response (TODO and result contained in reply?)
					final long responseTime = (System.currentTimeMillis() - startTime);
					if (reply == null)
						return;
					
					try {
						JSONObject replyObj = new JSONObject(reply);
						if (replyObj == null || !replyObj.has("type") || !replyObj.getString("type").equals("ping_reply"))
							return;
						
						// Update UI to show ping success and responseTime
						runOnUiThread(new Runnable() {
							public void run() {
								// Update ping state
								pingOkay = true;
								btnPing.setBackgroundColor(okayColor);
								Toast.makeText(BotControl.this, "Ping: " + responseTime + " ms", Toast.LENGTH_SHORT).show();
							}
						});
					} catch (JSONException e) {
						Log.e(TAG, "Error parsing JSON ping reply: " + e);
					}
				}
			}
		);
	}
	
	private void doDrive(final boolean block) {
		// Generate and send drive command, if different from last
		if (forward != lastForward || strafe != lastStrafe || turn != lastTurn) {
			setCallReqParam(driveCmdObj, "forward", forward);
			setCallReqParam(driveCmdObj, "strafe", strafe);
			//setCallReqParam(driveCmdObj, "turn", turn); // TODO turn currently not used

			sendCommand(
				driveCmdObj,
				block,
				new CommandReplyCallback() {
					@Override
					public void onReply(final String reply) {
						// Parse JSON reply and update if valid response (TODO and result contained in reply?)
						if (reply != null && parseCallReply(reply) != null) {
							// Update drive state and views
							lastForward = forward;
							lastStrafe = strafe;
							lastTurn = turn;
							runOnUiThread(new Runnable() {
								public void run() {
									updateDriveViews();
								}
							});
						}
						else {
							// Restore last drive state
							forward = lastForward;
							strafe = lastStrafe;
							turn = lastTurn;
						}
					}
				}
			);
		}
	}

	private void doTurret(final boolean block) {
		// Generate and send turret command, if different from last
		if ( yaw != lastYaw || pitch != lastPitch) {
			setCallReqParam(turretCmdObj, "yaw", yaw);
			setCallReqParam(turretCmdObj, "pitch", pitch);

			sendCommand(
				turretCmdObj,
				block,
				new CommandReplyCallback() {
					@Override
					public void onReply(final String reply) {
						// Parse JSON reply and update if valid response (TODO and result contained in reply?)
						if (reply != null && parseCallReply(reply) != null) {
							// Update turret state and views
							lastPitch = pitch;
							lastYaw = yaw;
							runOnUiThread(new Runnable() {
								public void run() {
									updateTurretViews();
								}
							});
						}
						else {
							// Restore last turret state
							pitch = lastPitch;
							yaw = lastYaw;
						}
					}
				}
			);
		}
	}

	private void doLaser(final boolean block) {
		// Generate and send laser ON/OFF command
		setCallReqParam(laserCmdObj, "state", laser);
		sendCommand(
			laserCmdObj,
			block,
			new CommandReplyCallback() {
				@Override
				public void onReply(final String reply) {
					// Parse JSON reply and update if valid response (TODO and result contained in reply?)
					if (reply != null && parseCallReply(reply) != null) {
						// Update laser state
						lastLaser = laser;
					}
					else {
						runOnUiThread(new Runnable() {
							public void run() {
								// Restore last laser state and toggle button
								laser = lastLaser;
								btnLaser.setChecked(laser == 1);
							}
						});
					}
				}
			}
		);
	}

	private void doSpin(final boolean block) {
		// Generate and send gun motor spin ON/OFF command
		setCallReqParam(spinCmdObj, "state", spin);
		sendCommand(
			spinCmdObj,
			block,
			new CommandReplyCallback() {
				@Override
				public void onReply(final String reply) {
					// Parse JSON reply and update if valid response (TODO and result contained in reply?)
					if (reply != null && parseCallReply(reply) != null) {
						// Update spin state
						lastSpin = spin;
					}
					else {
						runOnUiThread(new Runnable() {
							public void run() {
								// Restore last spin state and toggle button
								spin = lastSpin;
								btnSpin.setChecked(spin == 1);
							}
						});
					}
				}
			}
		);
	}

	private void doFire(final boolean block) {
		// Generate and send fire command
		sendCommand(fireCmdObj, block, null);
	}

	private void doKillServer(final boolean block) {
		// Generate and send kill command to stop server
		sendCommand(exitCmdObj, block, null);
	}

	public interface CommandReplyCallback {
		public void onReply(final String reply);
	}

	private void sendCommand(final String cmdStr, final boolean block, final CommandReplyCallback callback) {
		//Log.d(TAG, "sendCommand(): forward = " + forward + ", strafe = " + strafe + ", turn = " + turn);
		// Send this command (JSON string) to the control server,
		//   wait for ACK, deal with concurrency issues
		if (clientThread != null && clientThread.isAlive()) {
			// Start a new thread to avoid blocking the main (UI) thread
			(new Thread() {
				public void run() {
					//Log.d(TAG, "Sending : " + cmdStr);
					String reply = clientThread.serviceRequestSync(cmdStr, block);
					//Log.d(TAG, "Received: " + reply);
					if (callback != null) {
						callback.onReply(reply);
					}
				}
			}).start();
		}
	}

	private void sendCommand(final JSONObject cmdObj, final boolean block, final CommandReplyCallback callback) {
		try {
			sendCommand(cmdObj.toString(), block, callback);
		}
		catch(NullPointerException e) {
			Log.e(TAG, "Invalid command map: " + e);
		}
	}

	private JSONObject makeCallReq(String obj_name, String method, String[] params, Object[] values) {
		// NOTE It is easier to specify params and values in separate arrays than as a list of <key, value> pairs
		try {
			JSONObject cmdObj = (new JSONObject())
					.put("type", "call_req")
					.put("obj_name", obj_name)
					.put("method", method)
					.put("params", new JSONObject());
			if (params != null && params.length > 0 && values != null && values.length == params.length) {
				JSONObject paramsObj = cmdObj.getJSONObject("params");
				for (int i = 0; i < params.length; i++) {
					paramsObj.put(params[i], values[i]);
				}
			}
			return cmdObj;
		} catch (JSONException e) {
			Log.e(TAG, "Error making JSON call_req object: " + e);
			return null;
		}
	}

	private JSONObject parseCallReply(String reply) {
		try {
			JSONObject replyObj = new JSONObject(reply);
			if (replyObj.getString("type").equals("call_reply"))
				return replyObj;
			else {
				Log.w(TAG, "Call reply type not favorable: " + replyObj.getString("type"));
				return null;
			}
		} catch (JSONException e) {
			Log.e(TAG, "Error parsing JSON reply: " + e);
			return null;
		} catch (NullPointerException e) {
			Log.e(TAG, "Error parsing JSON reply: " + e);
			return null;
		}
	}

	private JSONObject makePingReq() {
		try {
			JSONObject cmdObj = (new JSONObject()).put("type", "ping_req");
			return cmdObj;
		} catch (JSONException e) {
			Log.e(TAG, "Error making JSON ping_req object: " + e);
			return null;
		}
	}

	private JSONObject makeExitReq() {
		try {
			JSONObject cmdObj = (new JSONObject()).put("type", "exit_req");
			return cmdObj;
		} catch (JSONException e) {
			Log.e(TAG, "Error making JSON exit_req object: " + e);
			return null;
		}
	}

	private void setCallReqParam(JSONObject cmdObj, String param, Object value) {
		try {
			JSONObject paramsObj = cmdObj.getJSONObject("params");
			paramsObj.put(param, value);
		} catch (JSONException e) {
			Log.e(TAG, "Error setting call_req param (silent error): " + e);
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
			// Handle IR array updates
			if (topic.startsWith("ir")) {
				Log.d(TAG, "onMessage(): IR update:- topic: " + topic + ", data: " + data);
				// TODO parse IR array values from message and update a representative view
				/*
				JsonReader myReader = new JsonReader(new StringReader(data));
				runOnUiThread(new Runnable() {
					public void run() {
						updateIRViews();
					};
				});
				 */
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
