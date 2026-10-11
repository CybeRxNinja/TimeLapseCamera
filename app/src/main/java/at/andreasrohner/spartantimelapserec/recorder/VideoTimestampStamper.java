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

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.preference.PreferenceManager;
import android.util.Log;

import at.andreasrohner.spartantimelapserec.data.RecSettings;

/**
 * Burns the CCTV date/time stamp into recorded MP4 videos (VIDEO and
 * VIDEO_TIME_LAPSE modes) as a post-process step.
 *
 * <p>Direct {@code MediaRecorder} video modes feed the camera straight to the
 * encoder with no frame callback, so the stamp cannot be drawn while
 * recording. Instead, after the recorder is released, this class transcodes
 * the finished file frame-by-frame: decode (ByteBuffer mode) to YUV, convert
 * to a {@link Bitmap}, draw the running timestamp with
 * {@link TimestampOverlay#applyOverlay(Bitmap, RecSettings, int, long)},
 * convert back to YUV and re-encode to H.264 via {@link MediaMuxer}. An
 * audio track, when present (VIDEO mode records mic audio), is copied
 * through untouched; VIDEO_TIME_LAPSE files have no audio track and are
 * handled the same way.</p>
 *
 * <p>YUV / Bitmap conversion choice: manual BT.601 integer math in pure
 * Java, dependency-free (no OpenGL, no RenderScript, no Surface/ImageReader
 * paths). This works on every API level back to the {@code minSdk} (23)
 * with zero extra dependencies; the cost is CPU time on long/high-res
 * clips (see README known limits). Decoder output is assumed to be
 * NV12-interleaved (semi-planar, U first) unless the decoder reports planar
 * I420/YV12 via {@code KEY_COLOR_FORMAT} — true for all standard AOSP
 * decoders in ByteBuffer mode. Row stride / slice height padding reported
 * by the decoder output format is honored so padded frames convert
 * correctly.</p>
 *
 * <p>Rotation ({@code rotation-degrees}) is passed to the muxer via
 * {@link MediaMuxer#setOrientationHint(int)} and frames are encoded at
 * their stored dimensions, so the output displays with the same
 * orientation as the input (never sideways).</p>
 *
 * <p>Streaming: frames are processed one at a time; no full-clip frame
 * list is ever held in memory. The original file is never touched: output
 * goes to a sibling temp file, returned on success; on any failure the
 * temp file is deleted and {@code null} is returned so the caller keeps
 * the original.</p>
 */
public final class VideoTimestampStamper {

	private static final String TAG = "VideoTimestampStamper";

	private static final long CODEC_TIMEOUT_US = 10000;

	private static final String MIME_AVC = "video/avc";

	private VideoTimestampStamper() {
	}

	/**
	 * Stamps {@code inputMp4} with the running recording time.
	 *
	 * @param context            used only to load overlay appearance settings
	 * @param inputMp4           finished recording (must exist, non-empty)
	 * @param startWallClockMs   {@code System.currentTimeMillis()} captured
	 *                           when recording started; a frame's stamp is
	 *                           this plus its {@code presentationTimeUs}
	 * @return the temp stamped file (sibling of the input) on success,
	 *         {@code null} on any failure (original is left untouched)
	 */
	public static File stampVideo(Context context, File inputMp4,
			long startWallClockMs) {
		if (context == null || inputMp4 == null || !inputMp4.isFile()
				|| inputMp4.length() == 0 || startWallClockMs <= 0)
			return null;
		RecSettings settings = new RecSettings();
		try {
			Context appCtx = context.getApplicationContext() != null
					? context.getApplicationContext() : context;
			settings.load(context,
					PreferenceManager.getDefaultSharedPreferences(appCtx));
		} catch (Throwable t) {
			Log.w(TAG, "cannot load overlay settings", t);
			return null;
		}
		File tmp = new File(inputMp4.getParent(),
				inputMp4.getName() + ".stamped.tmp.mp4");
		try {
			transcode(inputMp4, tmp, settings, startWallClockMs);
		} catch (Throwable t) {
			Log.w(TAG, "stamping failed for " + inputMp4, t);
			try {
				if (tmp.isFile() && !tmp.delete())
					Log.w(TAG, "cannot delete temp file " + tmp);
			} catch (Throwable ignored) {
			}
			return null;
		}
		if (!tmp.isFile() || tmp.length() == 0) {
			try {
				if (tmp.isFile() && !tmp.delete())
					Log.w(TAG, "cannot delete empty temp file " + tmp);
			} catch (Throwable ignored) {
			}
			return null;
		}
		return tmp;
	}

	private static void transcode(File input, File output,
			RecSettings settings, long startWallMs) throws Exception {
		MediaExtractor extractor = new MediaExtractor();
		MediaCodec decoder = null;
		MediaCodec encoder = null;
		MediaMuxer muxer = null;
		try {
			extractor.setDataSource(input.getAbsolutePath());

			int videoTrack = -1;
			int audioTrack = -1;
			MediaFormat videoFormat = null;
			MediaFormat audioFormat = null;
			String videoMime = null;
			for (int i = 0; i < extractor.getTrackCount(); i++) {
				MediaFormat f = extractor.getTrackFormat(i);
				String mime;
				try {
					mime = f.getString(MediaFormat.KEY_MIME);
				} catch (Exception e) {
					continue;
				}
				if (mime == null)
					continue;
				if (videoTrack < 0 && mime.startsWith("video/")) {
					videoTrack = i;
					videoFormat = f;
					videoMime = mime;
				} else if (audioTrack < 0 && mime.startsWith("audio/")) {
					audioTrack = i;
					audioFormat = f;
				}
			}
			if (videoTrack < 0 || videoFormat == null || videoMime == null)
				throw new IOException("no video track in " + input);

			int width = videoFormat.getInteger(MediaFormat.KEY_WIDTH);
			int height = videoFormat.getInteger(MediaFormat.KEY_HEIGHT);
			int frameRate = 30;
			try {
				frameRate = videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE);
			} catch (Exception ignored) {
			}
			if (frameRate <= 0)
				frameRate = 30;
			int rotation = 0;
			try {
				rotation = videoFormat.getInteger(MediaFormat.KEY_ROTATION);
			} catch (Exception ignored) {
			}
			long bitrate = (long) width * height * frameRate / 8;
			try {
				int inBr = videoFormat.getInteger(MediaFormat.KEY_BIT_RATE);
				if (inBr > 0)
					bitrate = inBr;
			} catch (Exception ignored) {
			}
			if (bitrate < 500000)
				bitrate = 500000;
			if (bitrate > 20000000)
				bitrate = 20000000;

			extractor.selectTrack(videoTrack);

			decoder = MediaCodec.createDecoderByType(videoMime);
			decoder.configure(videoFormat, null, null, 0);
			decoder.start();
			// Stored (pre-rotation) stride/padding/color layout for the
			// YUV -> Bitmap step; refreshed on OUTPUT_FORMAT_CHANGED.
			int[] strideInfo = readStrideInfo(decoder);

			MediaCodecInfo encoderInfo = selectAvcEncoder();
			int encoderColor = selectYuv420ColorFormat(encoderInfo, MIME_AVC);
			boolean encoderSemiPlanar = encoderColor
					!= MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar;
			MediaFormat encFormat = MediaFormat.createVideoFormat(MIME_AVC,
					width, height);
			encFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, encoderColor);
			encFormat.setInteger(MediaFormat.KEY_BIT_RATE, (int) bitrate);
			encFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
			encFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
			encoder = MediaCodec.createByCodecName(encoderInfo.getName());
			encoder.configure(encFormat, null, null,
					MediaCodec.CONFIGURE_FLAG_ENCODE);
			encoder.start();

			muxer = new MediaMuxer(output.getAbsolutePath(),
					MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
			// Keep the display orientation of the input so the stamped
			// output is never sideways.
			if (rotation != 0) {
				try {
					muxer.setOrientationHint(rotation);
				} catch (Exception ignored) {
				}
			}
			// Audio track (when present) is registered up-front so both
			// tracks exist before the muxer starts; samples are copied
			// verbatim after the video pass.
			int muxAudioTrack = -1;
			if (audioTrack >= 0 && audioFormat != null) {
				try {
					muxAudioTrack = muxer.addTrack(audioFormat);
				} catch (Exception e) {
					Log.w(TAG, "cannot add audio track, video only", e);
					muxAudioTrack = -1;
					audioTrack = -1;
				}
			}

			copyVideo(extractor, decoder, strideInfo, encoder,
					encoderSemiPlanar, muxer, settings, startWallMs, width,
					height);

			// Muxer is started inside copyVideo once the encoder output
			// format is known; without it there is nothing to attach
			// audio to.
			copyAudio(extractor, videoTrack, audioTrack, muxAudioTrack,
					muxer);

			try {
				muxer.stop();
			} catch (Exception e) {
				throw new IOException("muxer stop failed", e);
			}
		} finally {
			if (muxer != null) {
				try {
					muxer.release();
				} catch (Exception ignored) {
				}
			}
			if (encoder != null) {
				try {
					encoder.stop();
				} catch (Exception ignored) {
				}
				try {
					encoder.release();
				} catch (Exception ignored) {
				}
			}
			if (decoder != null) {
				try {
					decoder.stop();
				} catch (Exception ignored) {
				}
				try {
					decoder.release();
				} catch (Exception ignored) {
				}
			}
			try {
				extractor.release();
			} catch (Exception ignored) {
			}
		}
	}

	/**
	 * Decodes every video frame, stamps it and feeds it to the encoder,
	 * writing encoded samples to the muxer. Starts the muxer on the first
	 * encoder output format change.
	 */
	private static void copyVideo(MediaExtractor extractor, MediaCodec decoder,
			int[] strideInfo, MediaCodec encoder, boolean encoderSemiPlanar,
			MediaMuxer muxer, RecSettings settings, long startWallMs,
			int width, int height) throws Exception {
		MediaCodec.BufferInfo decInfo = new MediaCodec.BufferInfo();
		MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();
		boolean extractorDone = false;
		boolean decoderDone = false;
		boolean encoderDone = false;
		boolean muxerStarted = false;
		int muxVideoTrack = -1;
		long frameIndex = 0;
		int yuvSize = width * height * 3 / 2;
		byte[] encYuv = new byte[yuvSize];
		int[] argb = new int[width * height];

		while (!encoderDone) {
			if (!extractorDone) {
				int inIdx = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US);
				if (inIdx >= 0) {
					ByteBuffer inBuf = decoder.getInputBuffer(inIdx);
					inBuf.clear();
					int size = extractor.readSampleData(inBuf, 0);
					if (size < 0) {
						decoder.queueInputBuffer(inIdx, 0, 0, 0,
								MediaCodec.BUFFER_FLAG_END_OF_STREAM);
						extractorDone = true;
					} else {
						decoder.queueInputBuffer(inIdx, 0, size,
								extractor.getSampleTime(), 0);
						extractor.advance();
					}
				}
			}

			if (!decoderDone) {
				int outIdx = decoder.dequeueOutputBuffer(decInfo,
						CODEC_TIMEOUT_US);
				if (outIdx >= 0) {
					boolean eos = (decInfo.flags
							& MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
					if (decInfo.size > 0) {
						ByteBuffer outBuf = decoder.getOutputBuffer(outIdx);
						byte[] yuv = new byte[decInfo.size];
						outBuf.position(decInfo.offset);
						outBuf.limit(decInfo.offset + decInfo.size);
						outBuf.get(yuv);
						long ptsUs = decInfo.presentationTimeUs;
						decoder.releaseOutputBuffer(outIdx, false);

						Bitmap frame = yuvToBitmap(yuv, width, height,
								strideInfo[0], strideInfo[1],
								strideInfo[2], argb);
						Bitmap work = frame;
						try {
							long wallMs = startWallMs + ptsUs / 1000;
							Bitmap stamped = TimestampOverlay.applyOverlay(
									frame, settings, (int) (frameIndex + 1),
									wallMs);
							if (stamped != null)
								work = stamped;
						} catch (Throwable t) {
							// Never let a stamp failure drop the frame.
							work = frame;
						}
						bitmapToYuv(work, width, height, encYuv,
								encoderSemiPlanar);
						if (work != frame)
							frame.recycle();
						work.recycle();

						feedEncoder(encoder, encYuv, ptsUs, 0);
						frameIndex++;
					} else {
						decoder.releaseOutputBuffer(outIdx, false);
					}
					if (eos) {
						decoderDone = true;
						feedEncoder(encoder, null, 0,
								MediaCodec.BUFFER_FLAG_END_OF_STREAM);
					}
				} else if (outIdx
						== MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
					try {
						strideInfo = readStrideInfo(decoder);
					} catch (Exception ignored) {
					}
				}
			}

			int encOut = encoder.dequeueOutputBuffer(encInfo,
					CODEC_TIMEOUT_US);
			if (encOut >= 0) {
				boolean eos = (encInfo.flags
						& MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
				boolean config = (encInfo.flags
						& MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
				if (!config && encInfo.size > 0) {
					if (!muxerStarted) {
						throw new IOException(
								"encoder data before format change");
					}
					ByteBuffer outBuf = encoder.getOutputBuffer(encOut);
					outBuf.position(encInfo.offset);
					outBuf.limit(encInfo.offset + encInfo.size);
					muxer.writeSampleData(muxVideoTrack, outBuf, encInfo);
				}
				encoder.releaseOutputBuffer(encOut, false);
				if (eos)
					encoderDone = true;
			} else if (encOut == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
				if (muxerStarted)
					throw new IOException("encoder format changed twice");
				muxVideoTrack = muxer.addTrack(encoder.getOutputFormat());
				muxer.start();
				muxerStarted = true;
			}
		}

		if (!muxerStarted)
			throw new IOException("no encoded video output produced");
	}

	private static void feedEncoder(MediaCodec encoder, byte[] yuv,
			long ptsUs, int flags) {
		// Encoder outputs are drained every pass of the caller's pump
		// loop, so an input buffer always frees up; spin until one does.
		int inIdx = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US);
		while (inIdx < 0)
			inIdx = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US);
		ByteBuffer inBuf = encoder.getInputBuffer(inIdx);
		inBuf.clear();
		if (yuv != null && yuv.length > 0) {
			inBuf.put(yuv);
			encoder.queueInputBuffer(inIdx, 0, yuv.length, ptsUs, flags);
		} else {
			encoder.queueInputBuffer(inIdx, 0, 0, ptsUs, flags);
		}
	}

	/**
	 * Copies the audio track's samples verbatim (no re-encode) into the
	 * already-started muxer. No-op when the input has no audio track
	 * (VIDEO_TIME_LAPSE) or the audio track could not be registered.
	 */
	private static void copyAudio(MediaExtractor extractor, int videoTrack,
			int audioTrack, int muxAudioTrack, MediaMuxer muxer)
			throws Exception {
		if (audioTrack < 0 || muxAudioTrack < 0)
			return;
		try {
			extractor.unselectTrack(videoTrack);
		} catch (Exception ignored) {
		}
		extractor.selectTrack(audioTrack);
		try {
			extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
		} catch (Exception ignored) {
		}
		ByteBuffer audioBuf = ByteBuffer.allocateDirect(512 * 1024);
		MediaCodec.BufferInfo aInfo = new MediaCodec.BufferInfo();
		while (true) {
			audioBuf.clear();
			int size = extractor.readSampleData(audioBuf, 0);
			if (size < 0)
				break;
			aInfo.offset = 0;
			aInfo.size = size;
			aInfo.presentationTimeUs = extractor.getSampleTime();
			try {
				aInfo.flags = extractor.getSampleFlags();
			} catch (Exception e) {
				aInfo.flags = 0;
			}
			muxer.writeSampleData(muxAudioTrack, audioBuf, aInfo);
			extractor.advance();
		}
	}

	/**
	 * Reads decoder output stride info: {@code [stride, sliceHeight,
	 * planarFlag]}. Defaults to packed width/height, non-planar (NV12).
	 */
	private static int[] readStrideInfo(MediaCodec decoder) {
		int stride = -1;
		int sliceHeight = -1;
		int color = 0;
		try {
			MediaFormat out = decoder.getOutputFormat();
			try {
				stride = out.getInteger(MediaFormat.KEY_STRIDE);
			} catch (Exception ignored) {
			}
			try {
				sliceHeight = out.getInteger(MediaFormat.KEY_SLICE_HEIGHT);
			} catch (Exception ignored) {
			}
			try {
				color = out.getInteger(MediaFormat.KEY_COLOR_FORMAT);
			} catch (Exception ignored) {
			}
		} catch (Exception ignored) {
		}
		int planar = 0; // 0 = NV12 semi-planar, 1 = I420, 2 = YV12
		if (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar)
			planar = 1;
		else if (color == 0x32315659 /* YV12: planar, V plane first */)
			planar = 2;
		return new int[] { stride, sliceHeight, planar };
	}

	private static MediaCodecInfo selectAvcEncoder() throws IOException {
		MediaCodecList list = new MediaCodecList(
				MediaCodecList.REGULAR_CODECS);
		for (MediaCodecInfo info : list.getCodecInfos()) {
			if (!info.isEncoder())
				continue;
			try {
				for (String type : info.getSupportedTypes()) {
					if (MIME_AVC.equalsIgnoreCase(type))
						return info;
				}
			} catch (Exception ignored) {
			}
		}
		throw new IOException("no AVC encoder found");
	}

	/**
	 * Picks a YUV420 color format the encoder supports for ByteBuffer
	 * input, preferring the planar family. Queried at runtime because
	 * supported formats differ per device/encoder.
	 */
	private static int selectYuv420ColorFormat(MediaCodecInfo info,
			String mime) throws IOException {
		MediaCodecInfo.CodecCapabilities caps;
		try {
			caps = info.getCapabilitiesForType(mime);
		} catch (Exception e) {
			throw new IOException("no capabilities for " + mime, e);
		}
		boolean hasPlanar = false;
		boolean hasSemiPlanar = false;
		int firstYuv420 = -1;
		for (int c : caps.colorFormats) {
			if (c == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar)
				hasPlanar = true;
			else if (c == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
				hasSemiPlanar = true;
			if (firstYuv420 < 0 && isYuv420Family(c))
				firstYuv420 = c;
		}
		if (hasPlanar)
			return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar;
		if (hasSemiPlanar)
			return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;
		if (firstYuv420 >= 0)
			return firstYuv420;
		throw new IOException("encoder has no YUV420 color format");
	}

	private static boolean isYuv420Family(int color) {
		switch (color) {
		case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
		case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar:
		case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
		case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar:
		case 0x32315659: /* YV12 */
		case 0x7F000100: /* FLEXIBLE / TI NV21 */
			return true;
		default:
			// Vendor formats (QCOM/OMX proprietary) are not in the 420
			// family we can fill from a ByteBuffer.
			return color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV411Planar
					|| color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV411PackedPlanar
					|| color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV422Planar
					|| color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV422PackedPlanar
					|| color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV422SemiPlanar
					|| color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV422PackedSemiPlanar
					|| color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV444Interleaved
					|| color == MediaCodecInfo.CodecCapabilities.COLOR_QCOM_FormatYUV420SemiPlanar
					|| color == MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar;
		}
	}

	/**
	 * Converts one decoder YUV frame to an ARGB_8888 {@link Bitmap},
	 * honoring row stride / slice-height padding. {@code planar} selects
	 * the chroma layout: 0 is NV12-interleaved (U first), 1 is I420
	 * (U plane then V plane), 2 is YV12 (V plane then U plane).
	 * {@code argb} is a caller-owned reusable pixel buffer.
	 */
	private static Bitmap yuvToBitmap(byte[] yuv, int width, int height,
			int stride, int sliceHeight, int planar, int[] argb) {
		if (stride <= 0)
			stride = width;
		if (sliceHeight <= 0)
			sliceHeight = height;
		if (stride < width)
			stride = width;
		if (sliceHeight < height)
			sliceHeight = height;
		int ySize = stride * sliceHeight;
		int uvStride = stride / 2;
		int uvHeight = sliceHeight / 2;
		int chromaSize = uvStride * uvHeight;
		for (int y = 0; y < height; y++) {
			int yRow = y * stride;
			int uvRow = (y / 2) * uvStride;
			for (int x = 0; x < width; x++) {
				int Y = yuv[yRow + x] & 0xFF;
				int u;
				int v;
				if (planar != 0) {
					int idx = uvRow + x / 2;
					if (chromaSize > 0 && ySize + idx + chromaSize < yuv.length
							&& ySize + idx < yuv.length) {
						if (planar == 2) {
							// YV12: V plane first, then U plane.
							v = yuv[ySize + idx] & 0xFF;
							u = yuv[ySize + chromaSize + idx] & 0xFF;
						} else {
							// I420: U plane then V plane.
							u = yuv[ySize + idx] & 0xFF;
							v = yuv[ySize + chromaSize + idx] & 0xFF;
						}
					} else {
						u = 128;
						v = 128;
					}
				} else {
					int idx = uvRow + x / 2;
					int base = ySize + idx * 2;
					if (base + 1 < yuv.length) {
						// NV12: U first, then V.
						u = yuv[base] & 0xFF;
						v = yuv[base + 1] & 0xFF;
					} else {
						u = 128;
						v = 128;
					}
				}
				int r = Y + ((1436 * (v - 128)) >> 10);
				int g = Y - ((352 * (u - 128) + 731 * (v - 128)) >> 10);
				int b = Y + ((1815 * (u - 128)) >> 10);
				if (r < 0)
					r = 0;
				else if (r > 255)
					r = 255;
				if (g < 0)
					g = 0;
				else if (g > 255)
					g = 255;
				if (b < 0)
					b = 0;
				else if (b > 255)
					b = 255;
				argb[y * width + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
			}
		}
		return Bitmap.createBitmap(argb, width, height,
				Bitmap.Config.ARGB_8888);
	}

	/**
	 * Converts an ARGB_8888 {@link Bitmap} to YUV420 for the encoder:
	 * NV12-interleaved when {@code semiPlanar}, I420 planar otherwise.
	 */
	private static void bitmapToYuv(Bitmap bitmap, int width, int height,
			byte[] out, boolean semiPlanar) {
		int[] argb = new int[width * height];
		bitmap.getPixels(argb, 0, width, 0, 0, width, height);
		int ySize = width * height;
		int uvWidth = width / 2;
		int uvHeight = height / 2;
		int p = 0;
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int pixel = argb[p++];
				int R = (pixel >> 16) & 0xFF;
				int G = (pixel >> 8) & 0xFF;
				int B = pixel & 0xFF;
				out[y * width + x] = (byte) ((((66 * R + 129 * G + 25 * B
						+ 128) >> 8) + 16) & 0xFF);
			}
		}
		if (semiPlanar) {
			int o = ySize;
			for (int y = 0; y < uvHeight; y++) {
				for (int x = 0; x < uvWidth; x++) {
					int pixel = argb[(y * 2) * width + x * 2];
					int R = (pixel >> 16) & 0xFF;
					int G = (pixel >> 8) & 0xFF;
					int B = pixel & 0xFF;
					out[o++] = (byte) ((((-38 * R - 74 * G + 112 * B
							+ 128) >> 8) + 128) & 0xFF);
					out[o++] = (byte) ((((112 * R - 94 * G - 18 * B
							+ 128) >> 8) + 128) & 0xFF);
				}
			}
		} else {
			int uOff = ySize;
			int vOff = ySize + uvWidth * uvHeight;
			for (int y = 0; y < uvHeight; y++) {
				for (int x = 0; x < uvWidth; x++) {
					int pixel = argb[(y * 2) * width + x * 2];
					int R = (pixel >> 16) & 0xFF;
					int G = (pixel >> 8) & 0xFF;
					int B = pixel & 0xFF;
					out[uOff++] = (byte) ((((-38 * R - 74 * G + 112 * B
							+ 128) >> 8) + 128) & 0xFF);
					out[vOff++] = (byte) ((((112 * R - 94 * G - 18 * B
							+ 128) >> 8) + 128) & 0xFF);
				}
			}
		}
	}
}
