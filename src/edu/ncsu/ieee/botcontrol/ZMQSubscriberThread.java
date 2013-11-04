package edu.ncsu.ieee.botcontrol;

import org.zeromq.ZMQ;
import org.zeromq.ZMQException;

import zmq.ZError;
import android.util.Log;

public class ZMQSubscriberThread extends ZMQThread {
	private static final String TAG = "ZMQSubscriberThread";
	private static final long loop_delay = 100; // ms; time to wait between requests for updates (we don't want to hog the channel)
	
	private String serverAddress = null;
	
	public interface OnMessageListener {
		public void onMessage(String message);
	}
	private OnMessageListener listener = null;
	private String topics[] = null;
	
	public ZMQSubscriberThread(String serverProtocol, String serverHost, int serverPort) {
		this(serverProtocol + "://" + serverHost + ":" + serverPort);
	}
	
	public ZMQSubscriberThread(String serverAddress) {
		super(ZMQ.SUB);
		this.serverAddress = serverAddress;
	}
	
	@Override
	public void run() {
		// Connect socket to server address
		socket.connect(serverAddress);
		Log.i(TAG, "run(): Connected to " + serverAddress);
		
		// Subscribe to set topics
		if (this.topics == null) {
			socket.subscribe("".getBytes());
			Log.d(TAG, "run(): Subscribed to all topics");
		}
		else {
			for (String topic : this.topics) {
				socket.subscribe(topic.getBytes());
				Log.d(TAG, "run(): Subscribed to topic: " + topic);
			}
		}
		
		// Listen for topic messages till interrupted
		while(!isInterrupted()) {
			try {
				String message = socket.recvStr();
				Log.d(TAG, "run(): Received: " + message);
				if (listener != null) {
					listener.onMessage(message);
				}
				sleep(loop_delay);
			} catch (InterruptedException e) {
				Log.d(TAG, "run(): Interrupted!");
				break;
			} catch(ZMQException e) {
				Log.d(TAG, "run(): ZMQException (expected - ZMQ context terminated): " + e);
				if(e.getErrorCode () == ZMQ.Error.ETERM.getCode()) {
					break;
				}
			} catch (ZError.IOException e) {
				Log.w(TAG, "run(): Closed by interrupt? Exception: " + e);
				return; // skip trying to close socket - it'll cause another exception
			}
		}
		
		// Close socket
		Log.d(TAG, "run(): Closing socket...");
		socket.close();
		Log.d(TAG, "run(): Done.");
	}
	
	/*
	@Override
	public void term() {
		interrupt(); // interrupt self to break out of message listening loop
		super.term();
	}
	*/
	
	public void setTopics(String[] topics) {
		if (isAlive()) {
			Log.w(TAG, "setTopics(): Trying to set topics after thread has started; ignoring...");
			return;
		}
		this.topics = topics;
	}
	
	public void setListener(OnMessageListener listener) {
		this.listener = listener;
	}
}
