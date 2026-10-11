/*
 * Spartan Time Lapse Recorder - Minimalistic android time lapse recording app
 * Copyright (C) 2014 Andreas Rohner
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package at.andreasrohner.spartantimelapserec.recorder;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import at.andreasrohner.spartantimelapserec.data.RecSettings;

/**
 * Draws a CCTV-style date/time stamp onto a captured frame.
 *
 * <p>Dependency-free: only {@link Canvas}/{@link Paint} plus
 * {@link SimpleDateFormat}. All pure logic (pattern resolution, text building,
 * text size, box placement, background alpha) lives in static helpers without
 * Android types so it stays easy to reason about.</p>
 *
 * <p>Used by {@link ImageRecorder#onPictureTaken(byte[], android.hardware.Camera)}
 * so every still image (IMAGE_TIME_LAPSE mode, including the power-saving
 * variant which inherits that method) carries the stamp. Direct MediaRecorder
 * video modes ({@code VideoRecorder}, {@code VideoTimeLapseRecorder}) feed the
 * camera straight to the encoder with no frame callback, so they cannot carry
 * a burned-in stamp without a Camera2/MediaCodec rewrite.</p>
 */
public final class TimestampOverlay {

	/** Preference value marking the format as user supplied. */
	public static final String FORMAT_CUSTOM = "custom";

	/** Fallback pattern whenever a pattern is invalid. */
	public static final String FALLBACK_PATTERN = "yyyy-MM-dd HH:mm:ss";

	private TimestampOverlay() {
	}

	/**
	 * Draws the configured timestamp onto {@code src} and returns a mutable
	 * bitmap holding the result (the source itself when already mutable).
	 */
	public static Bitmap applyOverlay(Bitmap src, RecSettings settings,
			int frameNumber, long captureTimeMillis) {
		String text = buildText(settings, frameNumber, captureTimeMillis);
		if (text == null || text.isEmpty())
			return src;

		Bitmap bitmap = src.isMutable() ? src : src.copy(Bitmap.Config.ARGB_8888, true);
		if (bitmap == null)
			return src;

		int imgW = bitmap.getWidth();
		int imgH = bitmap.getHeight();

		float textSize = textSizeFor(settings.getOverlaySize(), imgW, imgH);

		Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		textPaint.setColor(resolveTextColor(settings.getOverlayTextColor()));
		textPaint.setTextSize(textSize);

		// Shrink the text until the pill fits even tiny frames.
		float margin = Math.max(4f, textSize * 0.5f);
		float padX = textSize * 0.6f;
		float padY = textSize * 0.4f;
		float textWidth = textPaint.measureText(text);
		while (textWidth + 2 * padX > imgW - 2 * margin && textSize > 8f) {
			textSize *= 0.9f;
			textPaint.setTextSize(textSize);
			margin = Math.max(4f, textSize * 0.5f);
			padX = textSize * 0.6f;
			padY = textSize * 0.4f;
			textWidth = textPaint.measureText(text);
		}

		Paint.FontMetrics fm = textPaint.getFontMetrics();
		float boxW = textWidth + 2 * padX;
		float boxH = (fm.descent - fm.ascent) + 2 * padY;

		float[] pos = boxPosition(settings.getOverlayPosition(), imgW, imgH,
				boxW, boxH, margin);
		float left = pos[0];
		float top = pos[1];

		Canvas canvas = new Canvas(bitmap);

		int alpha = backgroundAlpha(settings.getOverlayBackground());
		if (alpha > 0) {
			Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
			bg.setColor(Color.BLACK);
			bg.setAlpha(alpha);
			canvas.drawRoundRect(new RectF(left, top, left + boxW, top + boxH),
					textSize * 0.3f, textSize * 0.3f, bg);
		}

		canvas.drawText(text, left + padX, top + padY - fm.ascent, textPaint);
		return bitmap;
	}

	/**
	 * Builds the stamp text. {@code date}/{@code time} content uses fixed
	 * patterns; {@code datetime}/{@code datetime_frame} use the configured
	 * format preset (or custom pattern), with the frame counter appended.
	 */
	public static String buildText(RecSettings settings, int frameNumber, long now) {
		String content = settings.getOverlayContent();
		if ("date".equals(content))
			return formatSafe("yyyy-MM-dd", now);
		if ("time".equals(content))
			return formatSafe("HH:mm:ss", now);
		String pattern = resolvePattern(settings.getOverlayFormat(),
				settings.getOverlayCustomFormat());
		String text = formatSafe(pattern, now);
		if ("datetime_frame".equals(content))
			text += " #" + frameNumber;
		return text;
	}

	/** Resolves the effective {@link SimpleDateFormat} pattern. */
	public static String resolvePattern(String formatValue, String customPattern) {
		if (FORMAT_CUSTOM.equals(formatValue)) {
			if (customPattern != null && !customPattern.trim().isEmpty())
				return customPattern.trim();
			return FALLBACK_PATTERN;
		}
		if (formatValue == null || formatValue.isEmpty())
			return FALLBACK_PATTERN;
		return formatValue;
	}

	/** Formats {@code now} with {@code pattern}, falling back on bad patterns. */
	public static String formatSafe(String pattern, long now) {
		try {
			return new SimpleDateFormat(pattern, Locale.US).format(new Date(now));
		} catch (IllegalArgumentException e) {
			return new SimpleDateFormat(FALLBACK_PATTERN, Locale.US).format(new Date(now));
		}
	}

	/** Resolves "#RRGGBB" (or "#AARRGGBB") to a color int, defaulting to white. */
	public static int resolveTextColor(String colorValue) {
		if (colorValue != null) {
			try {
				return Color.parseColor(colorValue);
			} catch (IllegalArgumentException e) {
				// fall through to default
			}
		}
		return Color.WHITE;
	}

	/** Text size as a fraction of the smaller image dimension. */
	public static float textSizeFor(String sizeValue, int width, int height) {
		float min = Math.min(width, height);
		if ("small".equals(sizeValue))
			return min * 0.028f;
		if ("large".equals(sizeValue))
			return min * 0.06f;
		return min * 0.042f; // medium (default)
	}

	/**
	 * Top-left corner of the stamp box for the given position value.
	 * Unknown values fall back to bottom-right. The box is clamped inside
	 * the image so it is never drawn partially off-screen.
	 */
	public static float[] boxPosition(String position, int imgW, int imgH,
			float boxW, float boxH, float margin) {
		float left;
		float top;
		if ("top_left".equals(position)) {
			left = margin;
			top = margin;
		} else if ("top_right".equals(position)) {
			left = imgW - boxW - margin;
			top = margin;
		} else if ("bottom_left".equals(position)) {
			left = margin;
			top = imgH - boxH - margin;
		} else if ("center".equals(position)) {
			left = (imgW - boxW) / 2f;
			top = (imgH - boxH) / 2f;
		} else { // bottom_right (default)
			left = imgW - boxW - margin;
			top = imgH - boxH - margin;
		}
		left = Math.max(margin, Math.min(left, imgW - boxW - margin));
		top = Math.max(margin, Math.min(top, imgH - boxH - margin));
		return new float[] { left, top };
	}

	/** Converts an opacity percent string ("0"-"100") to a 0-255 alpha. */
	public static int backgroundAlpha(String opacityPercent) {
		int percent = 60;
		if (opacityPercent != null) {
			try {
				percent = Integer.parseInt(opacityPercent.trim());
			} catch (NumberFormatException e) {
				percent = 60;
			}
		}
		if (percent < 0)
			percent = 0;
		if (percent > 100)
			percent = 100;
		return percent * 255 / 100;
	}
}
