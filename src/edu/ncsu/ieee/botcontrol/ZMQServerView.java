package edu.ncsu.ieee.botcontrol;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

/**
 * A simple view group to manage and monitor a ZMQ server.
 */
public class ZMQServerView extends LinearLayout implements ZMQServerThread.OnRequestListener {
	private static final String TAG = "ZMQServerView";
	
	public EditText txtServerAddress = null;
	public Button btnStartServer = null;
	public Button btnStopServer = null;
	public EditText txtServerConsole = null;
	
	private ZMQServerThread serverThread = null;
	
	private TextViewLogger consoleLogger = null;
	
	public ZMQServerView(Context context) {
		super(context);
		init(null, 0);
	}

	public ZMQServerView(Context context, AttributeSet attrs) {
		super(context, attrs);
		init(attrs, 0);
	}

	private void init(AttributeSet attrs, int defStyle) {
		// Initialize view elements from XML
		LayoutInflater  inflater = (LayoutInflater) getContext().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
		inflater.inflate(R.layout.view_zmqserver, this, true);
		
		// Obtain references to view elements
		txtServerAddress = (EditText) findViewById(R.id.txtServerBindAddress);
		btnStartServer = (Button) findViewById(R.id.btnStartServer);
		btnStopServer = (Button) findViewById(R.id.btnStopServer);
		txtServerConsole = (EditText) findViewById(R.id.txtServerConsole);
		
		// Hook up actions
		btnStartServer.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				startServer();
			}
		});
		
		btnStopServer.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				stopServer();
			}
		});
		
		// Setup logger to append messages to console view
		consoleLogger = new TextViewLogger(txtServerConsole); // NOTE We can only modify views on main (UI) thread
		
		/*
		// List named loggers registered with LogManager (anonymous ones won't show up) [debug]
		Enumeration<String> loggerNames = LogManager.getLogManager().getLoggerNames();
		String loggerNamesStr = "";
		while(loggerNames.hasMoreElements()) {
			loggerNamesStr += loggerNames.nextElement() + (loggerNames.hasMoreElements() ? ", " : "");
		}
		Log.d(TAG, "Logger names: " + loggerNamesStr);
		*/
	}

	@Override
	protected void onDetachedFromWindow() {
		stopServer(); // view is being destroyed, stop server thread if running
		super.onDetachedFromWindow();
	}
	
	@Override
	public String onRequest(String request) {
		consoleLogger.log("Received: " + request);
		consoleLogger.log("Sending : OK");
		return "OK"; // send back reply
	}

	private void startServer() {
		stopServer(); // stop previously running server thread, if any
		Log.d(TAG, "startServer(): Starting server thread...");
		serverThread = new ZMQServerThread();
		serverThread.setOnRequestListener(this);
		serverThread.start();
	}
	
	private void stopServer() {
		if (serverThread != null) {
			Log.d(TAG, "stopServer(): Stopping server thread...");
			serverThread.setOnRequestListener(null);
			serverThread.term();
			serverThread = null;
		}
	}
}
