/*
 * Spartan Time Lapse Recorder - Minimalistic android time lapse recording app
 * Copyright (C) 2014  Andreas Rohner
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package at.andreasrohner.spartantimelapserec.recorder;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.media.MediaRecorder.OnErrorListener;
import android.media.MediaRecorder.OnInfoListener;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import at.andreasrohner.spartantimelapserec.R;
import at.andreasrohner.spartantimelapserec.data.RecSettings;

public class VideoRecorder extends Recorder implements OnInfoListener,
		OnErrorListener {
	protected MediaRecorder mMediaRecorder;
	protected int mRate;
	protected File mLastOutputFile;
	/** Wall clock (System.currentTimeMillis) when recording started. */
	protected long mRecordStartWallMs;

	/**
	 * Resolve a usable CamcorderProfile: never let CamcorderProfile.get throw
	 * for a missing profile, fall back to a guaranteed quality instead.
	 */
	protected CamcorderProfile resolveProfile() {
		int cameraId = mSettings.getCameraId();
		int quality = mSettings.getRecProfile();
		int[] candidates = new int[] { quality,
				CamcorderProfile.QUALITY_HIGH, CamcorderProfile.QUALITY_720P,
				CamcorderProfile.QUALITY_480P, CamcorderProfile.QUALITY_LOW };
		for (int q : candidates) {
			try {
				if (CamcorderProfile.hasProfile(cameraId, q))
					return CamcorderProfile.get(cameraId, q);
			} catch (Exception ignored) {
			}
		}
		return CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_LOW);
	}

	public VideoRecorder(RecSettings settings,
			Context context, Handler handler) {
		super(settings, context, handler);
	}

	protected int getFrameRate() {
		Camera.Parameters params = mCamera.getParameters();

		List<int[]> ranges = params.getSupportedPreviewFpsRange();
		if (ranges == null)
			return -1;

		int fps = mSettings.getFrameRate() * 1000;
		int selFps = ranges.get(ranges.size() - 1)[1];

		for (int i = 0; i < ranges.size(); ++i) {
			int[] range = ranges.get(i);

			if (fps >= range[0] && fps <= range[1]) {
				selFps = fps / 1000;
				break;
			}
		}

		return selFps;
	}

	private void releaseMediaRecorder() {
		if (mMediaRecorder == null)
			return;

		try {
			mMediaRecorder.stop();
			mMediaRecorder.reset();
			mMediaRecorder.release();
		} catch (Exception e) {
			e.printStackTrace();
		}
		mMediaRecorder = null;
	}

	@Override
	public void stop() {
		muteShutter();
		releaseMediaRecorder();

		final File output = mLastOutputFile;
		final Context appCtx = mContext != null
				&& mContext.getApplicationContext() != null
				? mContext.getApplicationContext() : mContext;
		final RecSettings settings = mSettings;
		final long startWallMs = mRecordStartWallMs;

		if (output != null && output.isFile()
				&& output.length() > 0)
			scanSavedFile(output, "video/mp4");

		super.stop();

		// VideoTimeLapseRecorder inherits this stop() without
		// overriding it, so this hook covers both video modes exactly
		// once. Never let stamping break the stop path.
		try {
			maybeStampVideoAsync(appCtx, settings, output, startWallMs);
		} catch (Throwable t) {
			Log.w(getClass().getSimpleName(),
					"video stamping skipped", t);
		}
	}

	/**
	 * Burns the timestamp into the finished MP4 on a background thread
	 * when both the master overlay switch and "stamp videos too" are on.
	 * Never blocks the caller; on success the original is replaced by an
	 * atomic rename and re-scanned, on failure the original is kept.
	 */
	private void maybeStampVideoAsync(final Context appCtx,
			final RecSettings settings, final File output,
			final long startWallMs) {
		if (appCtx == null || settings == null || output == null)
			return;
		if (!settings.isOverlayEnabled() || !settings.isOverlayVideo())
			return;
		if (!output.isFile() || output.length() == 0 || startWallMs <= 0)
			return;
		final Handler main = new Handler(Looper.getMainLooper());
		main.post(new Runnable() {
			@Override
			public void run() {
				try {
					Toast.makeText(appCtx,
							R.string.video_stamp_started,
							Toast.LENGTH_SHORT).show();
				} catch (Throwable ignored) {
				}
			}
		});
		final String logTag = getClass().getSimpleName();
		new Thread(new Runnable() {
			@Override
			public void run() {
				File stamped = null;
				try {
					stamped = VideoTimestampStamper.stampVideo(appCtx,
							output, startWallMs);
				} catch (Throwable t) {
					Log.w(logTag,
							"stamping failed, keeping original", t);
					stamped = null;
				}
				boolean replaced = false;
				if (stamped != null && stamped.isFile()
						&& stamped.length() > 0) {
					try {
						if (stamped.renameTo(output)) {
							replaced = true;
							scanSavedFile(appCtx, output, "video/mp4");
						} else {
							Log.w(logTag,
									"cannot replace " + output
									+ ", keeping original");
							try {
								stamped.delete();
							} catch (Throwable ignored) {
							}
						}
					} catch (Throwable t) {
						Log.w(logTag,
								"replace failed, keeping original", t);
						try {
							stamped.delete();
						} catch (Throwable ignored) {
						}
					}
				}
				final int msgId = replaced ? R.string.video_stamp_done
						: R.string.video_stamp_failed;
				main.post(new Runnable() {
					@Override
					public void run() {
						try {
							Toast.makeText(appCtx, msgId,
									Toast.LENGTH_SHORT).show();
						} catch (Throwable ignored) {
						}
					}
				});
			}
		}).start();
	}

	protected void setCameraParams() throws IOException {
		Camera.Parameters params = mCamera.getParameters();
		double ratio = (double) mSettings.getFrameWidth()
				/ mSettings.getFrameHeight();
		/*
		 * hack necessary for samsung phones to enable 16:9 recordings otherwise
		 * the camera will record 4:3 image and stretch it to 16:9
		 */
		if (Math.abs(ratio - (16D / 9D)) < 0.01)
			params.set("cam_mode", 1);

		List<String> suppList = params.getSupportedWhiteBalance();
		if (suppList != null) {
			Set<String> suppModes = new HashSet<String>();
			suppModes.addAll(suppList);

			if (suppModes.contains(Camera.Parameters.WHITE_BALANCE_AUTO)) {
				params.setWhiteBalance(Camera.Parameters.WHITE_BALANCE_AUTO);
			}
		}

		suppList = params.getSupportedFocusModes();
		if (suppList != null) {
			Set<String> suppModes = new HashSet<String>();
			suppModes.addAll(suppList);

			if (suppModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
				params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
			}
		}
		params.setExposureCompensation(clampExposure(params,
				mSettings.getExposureCompensation()));
		setClampedZoom(params, mSettings.getZoom());
		mCamera.setParameters(params);
	}

	private static int clampExposure(Camera.Parameters params, int exposure) {
		try {
			int minExposure = params.getMinExposureCompensation();
			int maxExposure = params.getMaxExposureCompensation();
			if (exposure < minExposure)
				return minExposure;
			if (exposure > maxExposure)
				return maxExposure;
			return exposure;
		} catch (Exception e) {
			return 0;
		}
	}

	private static void setClampedZoom(Camera.Parameters params, int zoom) {
		try {
			if (!params.isZoomSupported())
				return;
			int maxZoom = params.getMaxZoom();
			if (zoom < 0)
				zoom = 0;
			else if (zoom > maxZoom)
				zoom = maxZoom;
			params.setZoom(zoom);
		} catch (Exception ignored) {
		}
	}

	@Override
	protected void prepareRecord() throws IOException {
		releaseMediaRecorder();

		releaseCamera();

		mCamera = Camera.open(mSettings.getCameraId());

		setCameraParams();

		mRate = getFrameRate();

		SurfaceTexture surfaceTexture = new SurfaceTexture(10);
		mCamera.setPreviewTexture(surfaceTexture);
		//mCamera.setPreviewDisplay(mSurfaceHolder);

		muteShutter();

		mCamera.unlock();

		mMediaRecorder = new MediaRecorder();
		mMediaRecorder.setCamera(mCamera);
	}

	@Override
	protected void doRecord() throws IllegalStateException, IOException {
		mMediaRecorder.setOrientationHint(getCameraRotation(mSettings.getCameraId()));
		// no need for more sensor data
		disableOrientationSensor();

		mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
		mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);

		CamcorderProfile p = resolveProfile();
		p.videoFrameWidth = mSettings.getFrameWidth();
		p.videoFrameHeight = mSettings.getFrameHeight();
		mMediaRecorder.setProfile(p);

		if (mRate != -1)
			mMediaRecorder.setVideoFrameRate(mRate);
		mLastOutputFile = getOutputFile("mp4");
		mMediaRecorder.setOutputFile(mLastOutputFile.getAbsolutePath());

		if (mSettings.getStopRecAfter() > 0) {
			mMediaRecorder.setMaxDuration(mSettings.getStopRecAfter());
			mMediaRecorder.setOnInfoListener(this);
		}

		Log.i(getClass().getSimpleName(), "Starting video recording");
		mMediaRecorder.setOnErrorListener(this);
		if (mSettings.getVideoEncodingBitRate()>0) mMediaRecorder.setVideoEncodingBitRate(mSettings.getVideoEncodingBitRate());
		mMediaRecorder.prepare();

		mRecordStartWallMs = System.currentTimeMillis();
		mMediaRecorder.start();
	}

	@Override
	protected void muteShutter() {
		// mCamera.enableShutterSound(false); does not work for MediaRecorder
		// (on Samsung Galaxy S3)
		if (mSettings != null && mSettings.isMuteShutter() && mMute != null)
			mMute.muteShutter();
	}

	@Override
	protected void unmuteShutter() {
		// mCamera.enableShutterSound(true); does not work for MediaRecorder (on
		// Samsung Galaxy S3)
		if (mSettings != null && mSettings.isMuteShutter() && mMute != null)
			mMute.unmuteShutter();
	}

	@Override
	public void onInfo(MediaRecorder mr, int what, int extra) {
		switch (what) {
		case MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED:
			success();  //tell service to stop
			break;
		}
	}

	@Override
	public void onError(MediaRecorder mr, int what, int extra) {
		switch (what) {
		case MediaRecorder.MEDIA_ERROR_SERVER_DIED:
			handleError(getClass().getSimpleName(), "Mediaserver died");
			break;
		default:
			handleError(getClass().getSimpleName(),
					"Unkown error occured while recording");
			break;
		}
	}
}
