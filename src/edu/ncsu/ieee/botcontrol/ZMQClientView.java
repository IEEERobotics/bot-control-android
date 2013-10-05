package edu.ncsu.ieee.botcontrol;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A simple view group (layout) to manage and monitor a ZMQ client.
 */
public class ZMQClientView extends LinearLayout {
	private static final String TAG = "ZMQClientView";
	
	public ImageButton btnLoadHostComputer = null;
	public TextView txtServerProtocol = null;
	public EditText txtServerHost = null;
	public TextView txtServerPort = null;
	public Button btnStartClient = null;
	public Button btnStopClient = null;
	public EditText txtMessage = null;
	public Button btnSend = null;
	public EditText txtClientConsole = null;
	
	private ZMQClientThread clientThread = null;
	
	private TextViewLogger consoleLogger = null;

	public ZMQClientView(Context context) {
		super(context);
		init(null, 0);
	}

	public ZMQClientView(Context context, AttributeSet attrs) {
		super(context, attrs);
		init(attrs, 0);
	}

	private void init(AttributeSet attrs, int defStyle) {
		// Initialize view elements from XML
		LayoutInflater  inflater = (LayoutInflater) getContext().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
		inflater.inflate(R.layout.view_zmqclient, this, true);
		
		// Obtain references to view elements
		btnLoadHostComputer = (ImageButton) findViewById(R.id.btnLoadHostComputer);
		txtServerProtocol = (TextView) findViewById(R.id.txtServerProtocol);
		txtServerHost = (EditText) findViewById(R.id.txtServerHost);
		txtServerPort = (TextView) findViewById(R.id.txtServerPort);
		btnStartClient = (Button) findViewById(R.id.btnStartClient);
		btnStopClient = (Button) findViewById(R.id.btnStopClient);
		txtMessage = (EditText) findViewById(R.id.txtMessage);
		btnSend = (Button) findViewById(R.id.btnSend);
		txtClientConsole = (EditText) findViewById(R.id.txtClientConsole);
		
		// Configure view elements
		txtServerProtocol.setText(ZMQServerThread.SERVER_PROTOCOL);
		txtServerHost.setText(ZMQClientThread.SERVER_HOST);
		txtServerPort.setText(String.valueOf(ZMQServerThread.SERVER_PORT));
		
		// Hook up actions
		btnLoadHostComputer.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				txtServerHost.setText("10.0.2.2"); // NOTE: This is a special IP address for referring to the host computer from an emulator instance
			}
		});
		
		btnStartClient.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				startClient();
			}
		});
		
		btnStopClient.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				stopClient();
			}
		});
		
		btnSend.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				if (clientThread != null && clientThread.isAlive()) {
					final String request = txtMessage.getText().toString().trim();
					if (request.length() > 0) {
						// Start a new thread to avoid blocking the main (UI) thread
						(new Thread() {
							public void run() {
								consoleLogger.log("Sending : " + request);
								String reply =  clientThread.serviceRequestSingleSync(request);
								consoleLogger.log("Received: " + reply);
							}
						}).start();
					}
				}
			}
		});
		
		// Setup logger to append messages to console view
		consoleLogger = new TextViewLogger(txtClientConsole); // NOTE We can only modify views on main (UI) thread
	}

	@Override
	protected void onDetachedFromWindow() {
		stopClient(); // view is being destroyed, stop client thread if running
		super.onDetachedFromWindow();
	}

	private void startClient() {
		stopClient(); // stop previously running client thread, if any
		Log.d(TAG, "startClient(): Starting client thread...");
		clientThread = new ZMQClientThread(txtServerProtocol.getText().toString(), txtServerHost.getText().toString(), Integer.parseInt(txtServerPort.getText().toString()));
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
