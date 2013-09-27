package edu.ncsu.ieee.botcontrol;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

/**
 * A touch-based joystick that can capture 2-DoF input.
 */
public class TouchJoystick extends View {
	private static String TAG = "TouchJoystick";    ///< Tag to be used with log messages
	private static double minKnobRangeRatio = 0.2; ///< Lower bound for knob's valid input range, expressed as a fraction of available range
	private static double maxKnobRangeRatio = 0.85;  ///< Upper bound for knob's valid input range, expressed as a fraction of available range
	
	// View-related attributes (read from XML)
	private Drawable backgroundDrawable = null; ///< Background image or other drawable; default: null (blank)
	// TODO Add deadband (zero-region) drawable/radius, and knob drawable/radius
	private String messageText = null;          ///< Message text to be displayed in the middle; default: null (blank)
	private int messageColor = Color.GRAY;      ///< Color to use for message text; default: gray
	private float messageFontSize = 16;         ///< Font size to use for message text; default: 16 (pixels?)
	
	// Control-related attributes
	private float centerX = 0.f; ///< Center/neutral X position
	private float centerY = 0.f; ///< Center/neutral Y position
	private float knobX = 0.f;   ///< Knob X position (relative to center)
	private float knobY = 0.f;   ///< Knob Y position (relative to center)
	
	private double knobR = 0.0;      ///< Knob distance from center (i.e. radius, in polar coordinates)
	private double minKnobR = 30.0;  ///< Minimum knob distance from center to be counted as non-zero; will be updated if view size changes
	private double maxKnobR = 100.0; ///< Maximum knob distance from center to be counted as valid; will be updated if view size changes
	
	private float forward = 0.f;
	private float strafe = 0.f;
	//private float turn = 0.f;
	// TODO Turning not implemented yet

	private float lastForward = 0.f;
	private float lastStrafe = 0.f;
	//private float lastTurn = 0.f;

	// Display parameters
	private float knobSize = 25.f; ///< Radius of circle drawn to denote knob position
	private Paint knobPaint;
	private TextPaint messagePaint;
	private float messageTextWidth;
	private float messageTextHeight;
	
	public TouchJoystick(Context context) {
		super(context);
		init(null, 0);
	}

	public TouchJoystick(Context context, AttributeSet attrs) {
		super(context, attrs);
		init(attrs, 0);
	}

	public TouchJoystick(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
		init(attrs, defStyle);
	}

	private void init(AttributeSet attrs, int defStyle) {
		// Load attributes and copy values into member variables
		final TypedArray a = getContext().obtainStyledAttributes(attrs,
				R.styleable.TouchJoystick, defStyle, 0);
		
		backgroundDrawable = a.getDrawable(R.styleable.TouchJoystick_backgroundDrawable);
		//backgroundDrawable.setCallback(this); // for animated drawables
		messageText = a.getString(R.styleable.TouchJoystick_messageText);
		messageColor = a.getColor(R.styleable.TouchJoystick_messageColor, messageColor);
		messageFontSize = a.getDimension(R.styleable.TouchJoystick_messageFontSize, messageFontSize);
		// NOTE Use getDimensionPixelSize() or getDimensionPixelOffset() when dealing
		//   with values that should fall on pixel boundaries (what does this mean?)
		a.recycle();

		// Initialize knob position and other control attributes
		// NOTE Actual view width and height will only be available in onSizeChanged() [use addOnLayoutChangeListener() instead for API 11+]
		knobX = 0.f;
		knobY = 0.f;
		knobR = 0.f;
		
		lastForward = forward = 0.f;
		lastStrafe = strafe = 0.f;
		//lastTurn = turn = 0.f;
		
		// Initialize display parameters
		knobPaint = new Paint();
		knobPaint.setFlags(Paint.ANTI_ALIAS_FLAG);
		knobPaint.setStyle(Style.FILL);
		knobPaint.setColor(Color.argb(200, 200, 200, 255));
		// NOTE Can also use system colors such as: getContext().getResources().getColor(android.R.color.primary_text_dark)
		
		messagePaint = new TextPaint();
		messagePaint.setFlags(Paint.ANTI_ALIAS_FLAG);
		messagePaint.setTextAlign(Paint.Align.LEFT);

		// Update TextPaint and text measurement parameters from attributes
		updateMessageDisplayParams();
	}
	
	// NOTE It's better to use addOnLayoutChangeListener() instead for API 11+
	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		super.onSizeChanged(w, h, oldw, oldh);
		
		if (w == 0 || h == 0)
			return; // invalid width/height, nothing to do
		
		centerX = w / 2.f;
		centerY = h / 2.f;
		
		minKnobR = minKnobRangeRatio * (Math.min(w, h) / 2.f);
		maxKnobR = maxKnobRangeRatio * (Math.min(w, h) / 2.f);
		
		Log.d(TAG, "onSizeChanged(): Updated center: (" + centerX + ", " + centerY + "), knob range: [" + minKnobR + ", " + maxKnobR + "]");
	}
	
	@Override
	public boolean onTouchEvent(MotionEvent event) {
		switch(event.getAction()) {
		case MotionEvent.ACTION_DOWN:
			//Log.d(TAG, "onTouchEvent(): [ACTION_DOWN] @ (" + event.getX() + ", " + event.getY() + ")");
			updateKnob(event.getX() - centerX, event.getY() - centerY);
			// NOTE Don't produce movement command here, only on ACTION_MOVE, in case this is just a tap
			return true;
		
		case MotionEvent.ACTION_MOVE:
			//Log.d(TAG, "onTouchEvent(): [ACTION_MOVE] @ (" + event.getX() + ", " + event.getY() + ")");
			updateKnob(event.getX() - centerX, event.getY() - centerY);
			doMove();
			return true;
		
		case MotionEvent.ACTION_UP:
			//Log.d(TAG, "onTouchEvent(): [ACTION_UP] @ (" + event.getX() + ", " + event.getY() + ")");
			updateKnob(0.f, 0.f);
			doStop();
			return true;
		}
		return super.onTouchEvent(event);
	}

	@Override
	protected void onDraw(Canvas canvas) {
		super.onDraw(canvas);

		// TODO Consider storing these as member variables to reduce allocations per draw cycle
		int paddingLeft = getPaddingLeft();
		int paddingTop = getPaddingTop();
		int paddingRight = getPaddingRight();
		int paddingBottom = getPaddingBottom();

		int contentWidth = getWidth() - paddingLeft - paddingRight;
		int contentHeight = getHeight() - paddingTop - paddingBottom;

		// Draw background drawable(s)
		if (backgroundDrawable != null) {
			backgroundDrawable.setBounds(
					paddingLeft,
					paddingTop,
					paddingLeft + contentWidth,
					paddingTop + contentHeight);
			backgroundDrawable.draw(canvas);
		}

		// Draw message text, if any
		if (messageText != null) {
			canvas.drawText(
					messageText,
					paddingLeft + (contentWidth - messageTextWidth) / 2,
					paddingTop + (contentHeight + messageTextHeight) / 2 + (contentHeight / 4), // slightly offset below vertical middle
					messagePaint);
		}
		
		// Draw joystick knob
		canvas.drawCircle(centerX + knobX, centerY + knobY, knobSize, knobPaint);
	}
	
	private void updateKnob(final float x, final float y) {
		// Update knob position
		knobX = x;
		knobY = y;
		
		// Clamp knob position to range
		knobR = Math.hypot(knobX, knobY);
		if (knobR > maxKnobR) {
			/*
			// Method 1: Clamp radius, compute angle, then project x, y to clamped radius
			knobR = maxKnobR;
			double knobTheta = Math.atan2(knobY, knobX);
			knobX = (float) (maxKnobR * Math.cos(knobTheta));
			knobY = (float) (maxKnobR * Math.sin(knobTheta));
			*/
			
			// Method 2: Multiply x, y by ratio of max to actual radius (more efficient?)
			knobX *= (float) (maxKnobR / knobR);
			knobY *= (float) (maxKnobR / knobR); // hopefully, this is optimized by the compiler to a single division
		}
		//Log.d(TAG, "updateKnob(): knob @ (" + knobX + ", " + knobY + ")");
		
		// Invalidate view
		invalidate();
		
		// Compute control inputs
		if (knobR < minKnobR) {
			forward = 0.f;
			strafe = 0.f;
		}
		else {
			forward = -100.f * (float) (knobY / maxKnobR); // NOTE Y-flip
			strafe = 100.f * (float) (knobX / maxKnobR);
		}
		//Log.d(TAG, "updateKnob(): forward = " + forward + ", strafe = " + strafe);
	}

	private void doStop() {
		sendCommand(null); // TODO Generate and send stop command (if not already stopped?)
	}

	private void doMove() {
		sendCommand(null); // TODO Generate and send movement command (if different from last command?)
	}

	private void sendCommand(Object cmdObj) {
		if (lastForward != forward || lastStrafe != strafe) {
			// TODO add turn when implemented
			Log.d(TAG, "sendCommand(): forward = " + forward + ", strafe = " + strafe);
			// TODO Send this command to the control server, creating JSON object from cmdObj;
			//   wait for ACK, deal with concurrency issues
			lastForward = forward;
			lastStrafe = strafe;
		}
	}

	private void updateMessageDisplayParams() {
		if(messageText != null) {
			messagePaint.setTextSize(messageFontSize);
			messagePaint.setColor(messageColor);
			messageTextWidth = messagePaint.measureText(messageText);
			messageTextHeight = messagePaint.getFontMetrics().bottom;
		}
	}
	
	public String getMessageText() {
		return messageText;
	}

	/**
	 * Set message text string; update dependent message-related parameters.
	 */
	public void setMessageText(String text) {
		messageText = text;
		updateMessageDisplayParams();
	}

	public int getMessageColor() {
		return messageColor;
	}

	/**
	 * Set message text color; update dependent message-related parameters.
	 */
	public void setMessageColor(int color) {
		messageColor = color;
		updateMessageDisplayParams();
	}

	public float getMessageFontSize() {
		return messageFontSize;
	}

	/**
	 * Set message font size; update dependent message-related parameters.
	 */
	public void setMessageFontSize(float fontSize) {
		messageFontSize = fontSize;
		updateMessageDisplayParams();
	}

	public Drawable getBackgroundDrawable() {
		return backgroundDrawable;
	}

	public void setBackgroundDrawable(Drawable drawable) {
		backgroundDrawable = drawable;
	}
}
