package at.andreasrohner.spartantimelapserec.preference;

import android.content.Context;
import android.content.res.TypedArray;
import android.preference.DialogPreference;
import android.util.AttributeSet;
import android.view.View;
import android.widget.NumberPicker;

import at.andreasrohner.spartantimelapserec.R;

public class IntervalPickerPreference extends DialogPreference {
	private static final int DEFAULT_VALUE = 0;

	private int mValue = DEFAULT_VALUE;
	private NumberPicker mMilliPicker;
	private NumberPicker mSecondsPicker;
	private NumberPicker mMinutesPicker;

	public IntervalPickerPreference(Context context, AttributeSet attrs) {

		super(context, attrs);

		setDialogLayoutResource(R.layout.dialog_intervalpicker_preference);
	}



	@Override
	protected void onBindDialogView(View v) {
		super.onBindDialogView(v);

		mValue = getPersistedInt(DEFAULT_VALUE);
		if (mValue < 0)
			mValue = DEFAULT_VALUE;

		mMilliPicker = (NumberPicker) v.findViewById(R.id.dialog_seekbar_preference_milli);
		if (mMilliPicker != null) {
			String[] nums = new String[100];
			for(int i=0; i<nums.length; i++)
				nums[i] = Integer.toString(i*10);
			mMilliPicker.setMinValue(0);
			mMilliPicker.setMaxValue(99);
			mMilliPicker.setDisplayedValues(nums);
			mMilliPicker.setValue(clamp((mValue%1000)/10, 0, 99));
		}

		mSecondsPicker = (NumberPicker) v.findViewById(R.id.dialog_seekbar_preference_seconds);
		if (mSecondsPicker != null) {
			mSecondsPicker.setMinValue(0);
			mSecondsPicker.setMaxValue(59);
			mSecondsPicker.setValue(clamp((mValue-mValue%1000)/1000%60, 0, 59));
		}

		mMinutesPicker = (NumberPicker) v.findViewById(R.id.dialog_seekbar_preference_minutes);
		if (mMinutesPicker != null) {
			mMinutesPicker.setMinValue(0);
			mMinutesPicker.setMaxValue(10);
			int minutes = 0;
			try {
				minutes = (mValue
						- (mSecondsPicker != null ? mSecondsPicker.getValue() : 0) * 1000
						- (mMilliPicker != null ? mMilliPicker.getValue() : 0) * 10) / 60000;
			} catch (Exception ignored) {
			}
			mMinutesPicker.setValue(clamp(minutes, 0, 10));
		}

	}

	private static int clamp(int value, int min, int max) {
		if (value < min)
			return min;
		if (value > max)
			return max;
		return value;
	}

	@Override
	protected void onDialogClosed(boolean positiveResult) {
		if (positiveResult) {
			if (mMinutesPicker == null || mSecondsPicker == null
					|| mMilliPicker == null)
				return;
			mValue=mMinutesPicker.getValue()*60000+mSecondsPicker.getValue()*1000+mMilliPicker.getValue()*10;
			persistInt(mValue);
		}
	}

	@Override
	protected void onSetInitialValue(boolean restorePersistedValue,
			Object defaultValue) {
		if (restorePersistedValue) {
			// Restore existing state
			mValue = getPersistedInt(DEFAULT_VALUE);
		} else {
			// Set default state from the XML attribute
			mValue = (Integer) defaultValue;
			persistInt(mValue);
		}
	}

	@Override
	protected Object onGetDefaultValue(TypedArray a, int index) {
		return a.getInteger(index, DEFAULT_VALUE);
	}

	public int getmValue(){return mValue;}

}
