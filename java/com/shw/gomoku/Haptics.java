package com.shw.gomoku;

import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.View;

/** Centralised haptics: a light tick for search activity, a firmer pulse for a landing. */
final class Haptics {
	private Haptics() {}

	/** Light tick — used as the CPU enumerates its search. */
	static void tick(View v) {
		v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
	}

	/** Firmer confirmation — used when a stone is committed. */
	static void land(View v) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
			v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
		else
			v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
	}
}
