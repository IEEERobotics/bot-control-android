package edu.ncsu.ieee.botcontrol;

import org.zeromq.ZMQ;

import android.os.Looper;
import android.util.Log;

/**
 * Base class for any thread that uses standard ZMQ patterns.
 * 
 * Derived classes should:
 * 1) Call through to ZMQThread's constructor, specifying the type of ZMQ socket to open (ZMQ.REQ, ZMQ.REP etc.).
 * 2) Override run(), use the protected socket object, and close() it when done.
 * 3) Exit from run() if (isInterrupted() == true), or on catching an InterruptedException or ZMQException. Remember to close() socket.
 * 
 * NOTE: The cleanup method term() must be called on the thread instance explicitly; this is what triggers the exceptions in run().
 */
public class ZMQThread extends Thread {
	private final String TAG = "ZMQThread";
	
	protected ZMQ.Context context = null; ///< Context object managed by ZMQThread; properly terminated by term()
	protected ZMQ.Socket socket = null;   ///< Socket object to be used and closed by derived classes' run() methods
	
	/** Create ZMQ context and socket of given type. */
	public ZMQThread(int socketType) {
		context = ZMQ.context(1);
		socket = context.socket(socketType);
	}
	
	/** Terminate ZMQ context. NOTE: This must be called for a clean exit. */
	public void term() {
		// Check if context is already null
		if (context == null) {
			Log.w(TAG, "term(): ZMQ context already terminated.");
			return;
		}
		
		// Create a runnable to terminate ZMQ context
		Runnable zmqTerminator = new Runnable() {
			@Override
			public void run() {
				// Interrupt thread
				// NOTE: This may cause a ClosedByInterruptException on socket operations,
				//   which is hard to catch because ZMQ.Socket.recv() doesn't declare that it throws this exception!
				/*
				if(ZMQThread.this.isAlive()) {
					Log.d(TAG, "term(): Interrupting thread execution...");
					ZMQThread.this.interrupt(); // try to interrupt thread execution - may or may not succeed
					yield(); // let run() respond to (isInterrupted() == true) or InterruptedException (?)
				}
				*/
				
				// Terminate ZMQ context
				Log.d(TAG, "term(): Terminating ZMQ context...");
				context.term();
				//yield(); // let run() respond to ZMQException (?)
				
				// If not currently running on this ZMQThread, wait till it finishes
				if (Thread.currentThread() != ZMQThread.this) {
					try {
						ZMQThread.this.join(); // wait till this ZMQThread actually finishes
					} catch (InterruptedException e) {
						Log.e(TAG, "term(): Interrupted while waiting for thread to finish!");
					}
				}
				
				// Set context variable to null and call it a day
				context = null;
				Log.d(TAG, "term(): Done.");
			}
		};
		
		// Check if we are on the Android main (UI) thread
		if (Looper.myLooper() == Looper.getMainLooper()) {
			// Start a new thread for terminator to prevent NetworkOnMainThread exception
			(new Thread(zmqTerminator)).start();
		}
		else {
			// Okay to run terminator directly on non-main thread
			zmqTerminator.run();
		}
	}
	
	/** Ensure ZMQ context has been terminated. NOTE: Call term() explicitly instead of relying on this. */
	@Override
	protected void finalize() throws Throwable {
		if (context != null) {
			Log.e(TAG, "finalize(): Cleanup method term() not called yet!");
			term();
		}
		super.finalize();
	}
}