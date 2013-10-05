package edu.ncsu.ieee.botcontrol;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.zeromq.ZMQ;
import org.zeromq.ZMQException;

import zmq.ZError;
import android.util.Log;

/** Simple ZMQ request-reply client that uses an internal request queue. */
public class ZMQClientThread extends ZMQThread {
	private static final String TAG = "ZMQClientThread";
	
	public static final String SERVER_HOST = "127.0.0.1"; // default host address client connects to
	public static final int MAX_REQUESTS = 10; // no. of requests client can keep in queue
	
	private String serverAddress = null;
	
	/** Simple data structure to encapsulate a request and corresponding reply string with a serviced flag. */
	public class RequestReplyBundle {
		public String request = null;
		public String reply = null;
		public boolean serviced = false;
		
		public RequestReplyBundle(String req) {
			request = req;
		}
	}
	
	private BlockingQueue<RequestReplyBundle> requestQueue = new LinkedBlockingQueue<RequestReplyBundle>(MAX_REQUESTS);
	
	public ZMQClientThread() {
		this(ZMQServerThread.SERVER_PROTOCOL, SERVER_HOST, ZMQServerThread.SERVER_PORT);
	}
	
	public ZMQClientThread(String serverProtocol, String serverHost, int serverPort) {
		this(serverProtocol + "://" + serverHost + ":" + serverPort);
	}
	
		public ZMQClientThread(String serverAddress) {
		super(ZMQ.REQ);
		this.serverAddress = serverAddress;
	}
	
	/** Services a request string and returns a reply when done. */
	public String serviceRequestSync(String request) {
		RequestReplyBundle requestReplyBundle = new RequestReplyBundle(request);
		requestQueue.add(requestReplyBundle);
		while (!requestReplyBundle.serviced)
			yield();
		return requestReplyBundle.reply;
	}

	/** Services a request string and returns a RequestReplyBundle object immediately. Client is expected to test the serviced flag. */
	public RequestReplyBundle serviceRequestAsync(String request) {
		RequestReplyBundle requestReplyBundle = new RequestReplyBundle(request);
		requestQueue.add(requestReplyBundle);
		return requestReplyBundle;
	}

	/** Services a request string and returns a reply, only one request at a time. Returns null if any previous requests are being serviced. */
	public String serviceRequestSingleSync(String request) {
		if (requestQueue.isEmpty()) {
			RequestReplyBundle requestReplyBundle = new RequestReplyBundle(request);
			requestQueue.add(requestReplyBundle);
			while (!requestReplyBundle.serviced)
				yield();
			return requestReplyBundle.reply;
		}
		Log.w(TAG, "serviceRequestSingleSync(): Dropped request: " + request);
		return null;
	}

	@Override
	public void run() {
		// Connect socket to server address
		socket.connect(serverAddress);
		Log.i(TAG, "run(): Connected to " + serverAddress);
		
		// Service requests from queue till interrupted
		while(!isInterrupted()) {
			try {
				RequestReplyBundle requestReply = requestQueue.take();
				Log.d(TAG, "run(): Sending: " + requestReply.request);
				socket.send(requestReply.request);
				
				requestReply.reply = new String(socket.recv());
				requestReply.serviced = true;
				Log.d(TAG, "run(): Received: " + requestReply.reply);
			} catch (InterruptedException e) {
				Log.d(TAG, "Interrupted!");
				break;
			} catch(ZMQException e) {
				Log.d(TAG, "run(): ZMQException (expected - ZMQ context terminated): " + e);
				if(e.getErrorCode () == ZMQ.Error.ETERM.getCode()) {
					break;
				}
			} catch (ZError.IOException e) {
				Log.w(TAG, "Closed by interrupt (still waiting to send)? Exception: " + e);
				return; // skip trying to close socket - it'll cause another exception
			}
		}
		
		// Close socket
		Log.d(TAG, "run(): Closing socket...");
		socket.close();
		Log.d(TAG, "run(): Done.");
	}
	
	@Override
	public void term() {
		interrupt(); // interrupt self to break out of queue servicing loop
		super.term();
	}
}