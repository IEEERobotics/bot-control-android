package edu.ncsu.ieee.botcontrol;

import org.zeromq.ZMQ;
import org.zeromq.ZMQException;

import android.util.Log;

/** ZMQ request-reply server with default echo behavior that can be customized by attaching a request listener. */
public class ZMQServerThread extends ZMQThread {
	private static final String TAG = "ZMQServerThread";
	
	public static final String SERVER_PROTOCOL = "tcp";
	public static final int SERVER_PORT = 61000;
	
	private static final String SERVER_BIND_HOST = "0.0.0.0"; // host address server listens on
	private static final int SERVER_RESPONSE_DELAY = 500; // ms; can be used to throttle requests from blocking clients
	
	public interface OnRequestListener {
		public String onRequest(String request);
	}
	
	private OnRequestListener onRequestListener = null;
	
	public ZMQServerThread() {
		super(ZMQ.REP);
	}
	
	public void setOnRequestListener(OnRequestListener listener) {
		onRequestListener = listener;
	}
	
	@Override
	public void run() {
		// Bind socket to an address to start listening
		String serverBindAddress = SERVER_PROTOCOL + "://" + SERVER_BIND_HOST + ":" + SERVER_PORT;
		socket.bind(serverBindAddress);
		Log.i(TAG, "run(): Listening at " + serverBindAddress);
		
		// Loop to service requests
		while(!isInterrupted()) {
			try {
				byte[] request = socket.recv(); // block for request
				String requestStr = new String(request);
				Log.d(TAG, "run(): Received: " + requestStr);
				String reply = (onRequestListener != null ? onRequestListener.onRequest(requestStr) : requestStr); // echo if no listener is set
				Thread.sleep(SERVER_RESPONSE_DELAY); // delay response
				Log.d(TAG, "run(): Sending: " + reply);
				socket.send(reply);
			} catch(InterruptedException e) {
				Log.d(TAG, "run(): Interrupted!");
				break;
			} catch(ZMQException e) {
				Log.d(TAG, "run(): ZMQException (expected - ZMQ context terminated): " + e);
				if(e.getErrorCode () == ZMQ.Error.ETERM.getCode()) {
					break;
				}
			}
		}
		
		// Close socket
		Log.d(TAG, "run(): Closing socket...");
		socket.close();
		Log.d(TAG, "run(): Done.");
	}
}