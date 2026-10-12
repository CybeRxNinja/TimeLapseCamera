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
 * camera straight to the encoder with no frame callback, so they are
 * stamped after the fact instead: {@link VideoTimestampStamper} transcodes
 * the finished MP4 frame-by-frame and reuses
 * {@link #applyOverlay(Bitmap, RecSettings, int, long, int)} for every frame
 * (the rotation-aware overload, so the stamp lays out in display space).</p>
 */
public final class TimestampOverlay {

	/** Preference value marking the format as user supplied. */
	public static final String FORMAT_CUSTOM = "custom";

	/** Fallback pattern whenever a pattern is invalid. */
	public static final String FALLBACK_PATTERN = "yyyy-MM-dd HH:mm:ss";

	/** Maximum stamp lines drawn; extra lines from a multiline pattern are dropped. */
	public static final int MAX_LINES = 3;

	/**
	 * Splits a formatted stamp on newlines for stacked drawing.
	 * Never returns an empty array; lines beyond {@link #MAX_LINES} are dropped.
	 */
	public static String[] splitLines(String text) {
		if (text == null || text.isEmpty())
			return new String[] { "" };
		String[] parts = text.split("\r?\n", -1);
		if (parts.length <= MAX_LINES)
			return parts;
		String[] capped = new String[MAX_LINES];
		System.arraycopy(parts, 0, capped, 0, MAX_LINES);
		return capped;
	}

	/** Width of the widest line, used to size the background pill. */
	public static float maxLineWidth(Paint textPaint, String[] lines) {
		float width = 0f;
		for (String line : lines)
			width = Math.max(width, textPaint.measureText(line));
		return width;
	}

	private TimestampOverlay() {
	}

	/**
	 * Draws the configured timestamp onto {@code src} and returns a mutable
	 * bitmap holding the result (the source itself when already mutable).
	 * Equivalent to the 5-arg overload with {@code rotationDeg = 0}.
	 */
	public static Bitmap applyOverlay(Bitmap src, RecSettings settings,
			int frameNumber, long captureTimeMillis) {
		return applyOverlay(src, settings, frameNumber, captureTimeMillis, 0);
	}

	/**
	 * Rotation-aware variant: {@code rotationDeg} is the clockwise rotation
	 * the muxer's {@code setOrientationHint} will apply at playback (0, 90,
	 * 180 or 270). The stamp is laid out against the DISPLAY dimensions
	 * (swapped when the rotation turns the frame) and drawn through the
	 * inverse transform, so once the player rotates the picture the text
	 * reads upright in the chosen corner — not sideways or upside down.
	 *
	 * @param rotationDeg clockwise display rotation of this frame, degrees
	 */
	public static Bitmap applyOverlay(Bitmap src, RecSettings settings,
			int frameNumber, long captureTimeMillis, int rotationDeg) {
		String text = buildText(settings, frameNumber, captureTimeMillis);
		if (text == null || text.isEmpty())
			return src;

		Bitmap bitmap = src.isMutable() ? src : src.copy(Bitmap.Config.ARGB_8888, true);
		if (bitmap == null)
			return src;

		// Stored (pre-rotation) frame size...
		int imgW = bitmap.getWidth();
		int imgH = bitmap.getHeight();

		// ...but everything is sized and positioned in display space: a
		// 90/270 rotation swaps the visible width and height.
		int rot = ((rotationDeg % 360) + 360) % 360;
		boolean swap = (rot % 180) != 0;
		int dispW = swap ? imgH : imgW;
		int dispH = swap ? imgW : imgH;

		float textSize = textSizeFor(settings.getOverlaySize(), dispW, dispH);

		Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		textPaint.setColor(resolveTextColor(settings.getOverlayTextColor()));
		textPaint.setTextSize(textSize);

		// Canvas.drawText does not render '\n', so split the stamp into lines
		// (capped at MAX_LINES) and stack them; the pill grows to fit all lines.
		String[] lines = splitLines(text);

		// Shrink the text until the pill fits even tiny frames.
		float margin = Math.max(4f, textSize * 0.5f);
		float padX = textSize * 0.6f;
		float padY = textSize * 0.4f;
		float textWidth = maxLineWidth(textPaint, lines);
		while (textWidth + 2 * padX > dispW - 2 * margin && textSize > 8f) {
			textSize *= 0.9f;
			textPaint.setTextSize(textSize);
			margin = Math.max(4f, textSize * 0.5f);
			padX = textSize * 0.6f;
			padY = textSize * 0.4f;
			textWidth = maxLineWidth(textPaint, lines);
		}

		Paint.FontMetrics fm = textPaint.getFontMetrics();
		float lineHeight = fm.descent - fm.ascent;
		float lineGap = textSize * 0.15f;
		float boxW = textWidth + 2 * padX;
		float boxH = lines.length * lineHeight + (lines.length - 1) * lineGap + 2 * padY;

		float[] pos = boxPosition(settings.getOverlayPosition(), dispW, dispH,
				boxW, boxH, margin);
		float left = pos[0];
		float top = pos[1];

		Canvas canvas = new Canvas(bitmap);

		// Draw in display coordinates, mapped onto the stored frame by the
		// inverse of the muxer rotation (display -> stored):
		//   90  -> translate(0, imgH);  rotate(-90)
		//   180 -> translate(imgW, imgH); rotate(180)
		//   270 -> translate(imgW, 0);  rotate(90)
		// All four display corners land exactly on stored corners, so the
		// box is neither clipped nor skewed once the player applies the
		// clockwise rotation hint.
		canvas.save();
		if (rot == 90) {
			canvas.translate(0, imgH);
			canvas.rotate(-90);
		} else if (rot == 180) {
			canvas.translate(imgW, imgH);
			canvas.rotate(180);
		} else if (rot == 270) {
			canvas.translate(imgW, 0);
			canvas.rotate(90);
		}

		int alpha = backgroundAlpha(settings.getOverlayBackground());
		if (alpha > 0) {
			Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
			bg.setColor(Color.BLACK);
			bg.setAlpha(alpha);
			canvas.drawRoundRect(new RectF(left, top, left + boxW, top + boxH),
					textSize * 0.3f, textSize * 0.3f, bg);
		}

		for (int i = 0; i < lines.length; i++) {
			float baseline = top + padY - fm.ascent + i * (lineHeight + lineGap);
			canvas.drawText(lines[i], left + padX, baseline, textPaint);
		}
		canvas.restore();
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
