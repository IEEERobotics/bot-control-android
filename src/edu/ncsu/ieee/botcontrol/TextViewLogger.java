package edu.ncsu.ieee.botcontrol;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.widget.TextView;

/** A Handler that appends messages to a given TextView, taking care to perform UI updates on main thread. */
public class TextViewLogger extends Handler {
	private TextView textView;
	
	public TextViewLogger(TextView outputTextView) {
		super(Looper.getMainLooper());
		textView = outputTextView;
	}
	
	public void log(String message) {
		Message msg = new Message();
		msg.obj = (Object) message;
		sendMessage(msg);
	}
	
	@Override
	public void handleMessage(Message msg) {
		try {
			textView.append(((String) msg.obj) + "\n");
			// TODO drop earlier messages if full
		}
		catch (Exception e) {
			super.handleMessage(msg);
		}
	}
}