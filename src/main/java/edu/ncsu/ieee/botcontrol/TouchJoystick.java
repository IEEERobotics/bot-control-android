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
	private static final String TAG = "TouchJoystick";    ///< Tag to be used with log messages
	private static final float maxKnobRangeRatio = 0.85f;  ///< Upper bound for knob's valid input range, expressed as a fraction of available range
	
	// Shape enum constants (NOTE these need to match values defined in attrs_touch_joystick.xml)
	private static final int shape_circle = 0;
	private static final int shape_square = 1;
	
	// View-related attributes (read from XML)
	private int shape = shape_circle; ///< Shape of the joystick's interactive region (used for display as well as limits)
	private Drawable backgroundDrawable = null; ///< Background image or other drawable; default: null (blank)
	// TODO Add deadband (zero-region) drawable/radius, and knob drawable/radius
	private String messageText = null;          ///< Message text to be displayed in the middle; default: null (blank)
	private int messageColor = Color.GRAY;      ///< Color to use for message text; default: gray
	private float messageFontSize = 16;         ///< Font size to use for message text; default: 16 (pixels?)
	
	// Control-related attributes
	private float centerX = 0.f;   ///< Center X position
	private float centerY = 0.f;   ///< Center Y position
	public float knobXNorm = 0.f;  ///< Knob X position in [-1, 1] (relative to center, normalized by maxKnobX)
	public float knobYNorm = 0.f;  ///< Knob Y position in [-1, 1] (relative to center, normalized by maxKnobY)
	
	public float maxKnobR = 100.f; ///< Maximum knob distance from center; will be updated if view size changes
	public float maxKnobX = 100.f; ///< Maximum X distance from center; will be updated if view size changes
	public float maxKnobY = 100.f; ///< Maximum Y distance from center; will be updated if view size changes
	// NOTE maxKnobX = maxKnobY = maxKnobR when shape = circle
	
	// Display parameters
	private float knobSize = 20.f; ///< Radius of circle drawn to denote knob position
	private Paint knobPaint;
	private Paint axesPaint;
	private TextPaint messagePaint;
	private float messageTextWidth;
	private float messageTextHeight;

	public interface JoystickListener {
		public boolean onJoystickEvent(TouchJoystick joystick, int action, float x, float y);
	}
	private JoystickListener listener = null;

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
		
		shape = a.getInt(R.styleable.TouchJoystick_shape, shape);
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
		knobXNorm = 0.f;
		knobYNorm = 0.f;
		
		// Initialize display parameters
		knobPaint = new Paint();
		knobPaint.setFlags(Paint.ANTI_ALIAS_FLAG);
		knobPaint.setStyle(Style.FILL);
		knobPaint.setColor(Color.argb(200, 200, 200, 255));
		// NOTE Can also use system colors such as: getContext().getResources().getColor(android.R.color.primary_text_dark)
		
		axesPaint = new Paint();
		axesPaint.setFlags(Paint.ANTI_ALIAS_FLAG);
		axesPaint.setStyle(Style.STROKE);
		axesPaint.setStrokeWidth(1.5f);
		axesPaint.setColor(Color.argb(200, 200, 200, 200));
		//axesPaint.setPathEffect(new DashPathEffect(new float[] { 10, 20 }, 0)); // dotted/dashed line
		//setLayerType(LAYER_TYPE_SOFTWARE, null); // needed on Jellybean+ devices to show dotted/dashed line (requires target API 11+)
		
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
		
		// Compute knob range(s)
		maxKnobX = maxKnobY = maxKnobR = maxKnobRangeRatio * (Math.min(w, h) / 2.f);
		if (shape == shape_square) {
			maxKnobX = maxKnobRangeRatio * (w / 2.f);
			maxKnobY = maxKnobRangeRatio * (h / 2.f);
		}
		
		Log.d(TAG, "onSizeChanged(): Updated center: (" + centerX + ", " + centerY + "), knob range: [" + -maxKnobR + ", " + maxKnobR + "]");
		updateKnob(knobXNorm, knobYNorm);
	}
	
	@Override
	public boolean onTouchEvent(MotionEvent event) {
		// If a listener is set and it returns true, then return true, else return value from default implementation
		return ((listener != null
					&& listener.onJoystickEvent(this, event.getAction(), (event.getX() - centerX) / maxKnobX, (event.getY() - centerY) / maxKnobY))
				|| super.onTouchEvent(event));
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
					paddingTop + (contentHeight + messageTextHeight) / 2 - (contentHeight / 4), // slightly offset above vertical middle
					messagePaint);
		}
		
		// Draw axes lines
		float drawX = centerX + knobXNorm * maxKnobX, drawY = centerY + knobYNorm * maxKnobY;
		canvas.drawLine(drawX, 0, drawX, getHeight(), axesPaint);
		canvas.drawLine(0, drawY, getWidth(), drawY, axesPaint);
		
		// Draw joystick knob
		canvas.drawCircle(centerX + knobXNorm * maxKnobX, centerY + knobYNorm * maxKnobY, knobSize, knobPaint);
	}
	
	public void updateKnob(final float x, final float y) {
		// Update knob position
		knobXNorm = x;
		knobYNorm = y;
		
		// Clamp knob position to shape-dependent limits
		switch(shape) {
		case shape_circle:
			double knobRNorm = Math.hypot(knobXNorm, knobYNorm);
			if (knobRNorm > 1.0) {
				/*
				// Method 1: Compute angle, then project to unit radius
				double knobTheta = Math.atan2(knobYNorm, knobXNorm);
				knobXNorm = (float) Math.cos(knobTheta);
				knobYNorm = (float) Math.sin(knobTheta);
				*/
				
				// Method 2: Normalize x, y by actual radius (more efficient?)
				knobXNorm /= (float) knobRNorm;
				knobYNorm /= (float) knobRNorm;
			}
			break;
		
		case shape_square:
			// NOTE The square shape can actually be asymmetric (i.e. a rectangle) since X and Y are clamped independently
			if (Math.abs(knobXNorm) > 1.f)
				knobXNorm = Math.copySign(1.f, knobXNorm);
			if (Math.abs(knobYNorm) > 1.f)
				knobYNorm = Math.copySign(1.f, knobYNorm);
			break;
		}
		//Log.d(TAG, "updateKnob(): knob @ (" + knobXNorm + ", " + knobYNorm + ")");
		
		// Invalidate view
		invalidate();
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

	public void setJoystickListener(JoystickListener listener) {
		this.listener = listener;
	}
}
