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

import android.content.Context;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.os.PowerManager.WakeLock;
import android.text.format.DateFormat;
import android.util.Log;
import at.andreasrohner.spartantimelapserec.data.RecSettings;
import at.andreasrohner.spartantimelapserec.sensor.MuteShutter;
import at.andreasrohner.spartantimelapserec.sensor.OrientationSensor;

public abstract class Recorder {
	protected Context mContext;
	protected RecSettings mSettings;
	protected Camera mCamera;
	protected boolean mCanDisableShutterSound;
	protected Handler mHandler;
	protected int mInitDelay;
	private OrientationSensor mOrientation;
	protected MuteShutter mMute;
	private File mOutputDir;
	private int mFileIndex;

	public static Recorder getInstance(RecSettings settings,
			 Context context, Handler handler,
			WakeLock wakeLock) {
		Recorder recorder;

		switch (settings.getRecMode()) {
		case VIDEO_TIME_LAPSE:
			recorder = new VideoTimeLapseRecorder(settings,
					context, handler);
			break;
		case IMAGE_TIME_LAPSE:
			if (settings.shouldUsePowerSaveMode()) {
				recorder = new PowerSavingImageRecorder(settings,
						 context, handler, wakeLock);
			} else {
				recorder = new ImageRecorder(settings,  context,
						handler);
			}
			break;
		default:
			recorder = new VideoRecorder(settings,  context,
					handler);
			break;
		}

		return recorder;
	}

	public Recorder(RecSettings settings, 			Context context, Handler handler) {
		mContext = context;

		mOrientation = new OrientationSensor(context);
		mOrientation.enable();

		mSettings = settings;
		mHandler = handler;

		CameraInfo info = new CameraInfo();
		Camera.getCameraInfo(mSettings.getCameraId(), info);
		mCanDisableShutterSound = info.canDisableShutterSound;

		mMute = new MuteShutter(context);
		mOutputDir = new File(settings.getProjectPath() + "/"
				+ settings.getProjectName() + "/"
				+ DateFormat.format("yyyy-MM-dd", System.currentTimeMillis())
				+ "/");

		if (!mOutputDir.exists() && !mOutputDir.mkdirs()) {
			Log.e("TimeLapseCamera", "Failed to make directory: " + mOutputDir.toString());
			return;
		}

		// Sweep stamp temps orphaned by a killed/crashed run: without this
		// a *.stamped.tmp.mp4 could linger forever next to its recording.
		sweepStaleStampTemps(mOutputDir);

		mInitDelay = settings.getInitDelay();
	}

	/**
	 * Age after which a leftover stamp temp file is considered stale.
	 * Active temps keep getting written by the muxer (mtime stays fresh),
	 * so this also protects a stamp thread still running from a previous
	 * recorder instance in the same process.
	 */
	private static final long STALE_STAMP_TMP_AGE_MS = 10 * 60 * 1000L;

	/**
	 * Deletes leftover {@code VideoTimestampStamper} outputs
	 * ({@code *.stamped.tmp.mp4}) untouched for at least
	 * {@link #STALE_STAMP_TMP_AGE_MS}. The stamper removes its own temp on
	 * success (rename) and on failure (delete); only a process kill can
	 * leave one behind.
	 */
	protected static void sweepStaleStampTemps(File dir) {
		if (dir == null || !dir.isDirectory())
			return;
		try {
			File[] files = dir.listFiles();
			if (files == null)
				return;
			long now = System.currentTimeMillis();
			for (File file : files) {
				if (!file.isFile())
					continue;
				if (!file.getName().endsWith(
						VideoTimestampStamper.STAMP_TMP_SUFFIX))
					continue;
				if (now - file.lastModified() < STALE_STAMP_TMP_AGE_MS)
					continue;
				if (!file.delete())
					Log.w("TimeLapseCamera",
							"Cannot delete stale stamp temp " + file);
			}
		} catch (Exception e) {
			Log.w("TimeLapseCamera", "stamp temp sweep failed for " + dir, e);
		}
	}

	protected void releaseCamera() {
		if (mCamera == null)
			return;

		try {
			mCamera.reconnect();
			mCamera.release();
		} catch (Exception e) {
			e.printStackTrace();
		}
		mCamera = null;
	}

	protected void handleError(String tag, String msg) {
		if (mHandler != null) {
			Message m = new Message();
			Bundle b = new Bundle();
			b.putString("status", "error");
			b.putString("tag", tag);
			b.putString("msg", msg);
			m.setData(b);
			m.setTarget(mHandler);
			mHandler.sendMessage(m);
			mHandler = null;
		}
	}

	protected void success() {
		if (mHandler != null) {
			Message m = new Message();
			Bundle b = new Bundle();
			b.putString("status", "success");
			m.setData(b);
			m.setTarget(mHandler);
			mHandler.sendMessage(m);
		}
	}

	protected void disableOrientationSensor() {
		if (mOrientation != null)
			mOrientation.disable();
	}

	protected void enableOrientationSensor() {
		if (mOrientation != null)
			mOrientation.enable();
	}

	public void stop() {
		disableOrientationSensor();
		mOrientation = null;

		releaseCamera();
		unmuteShutter();

		mHandler = null;
		mContext = null;
		mMute = null;
	}

	protected abstract void prepareRecord() throws Exception;

	protected abstract void doRecord() throws Exception;

	public void start() {
		long timeDiff = SystemClock.elapsedRealtime();
		Runnable r = new Runnable() {
			@Override
			public void run() {
				try {
					doRecord();
				} catch (Exception e) {
					e.printStackTrace();
					handleError(Recorder.class.getSimpleName(), e.getMessage());
				}
			}
		};

		if (mHandler == null  || mContext == null)
			return;

		enableOrientationSensor();

		try {
			prepareRecord();
		} catch (Exception e) {
			e.printStackTrace();
			handleError(getClass().getSimpleName(), e.getMessage());
			return;
		}

		timeDiff = SystemClock.elapsedRealtime() - timeDiff;
		timeDiff = mInitDelay - timeDiff;
		if (timeDiff <= 0)
			mHandler.post(r);
		else
			mHandler.postDelayed(r, timeDiff);

		mInitDelay = 0;
	}

	public File getOutputDir() {
		return mOutputDir;
	}

	protected File getOutputFile(String ext) throws IOException {
		File outFile;
		do {
			outFile = new File(mOutputDir, mSettings.getProjectName()
					+ mFileIndex + "." + ext);
			mFileIndex++;
		} while (outFile.isFile());

		if (!mOutputDir.isDirectory())
			throw new IOException("Could not open directory");

		return outFile;
	}

	/**
	 * Make a saved image/video visible to gallery apps. API 29+ does not index
	 * app-specific directories by file-path scanning alone, so publish a
	 * visible copy through MediaStore there; scan the file directly below that.
	 */
	protected void scanSavedFile(File file, String mimeType) {
		scanSavedFile(mContext, file, mimeType);
	}

	/**
	 * Context-explicit variant so background work started in
	 * {@link VideoRecorder#stop()} can re-scan the final file after
	 * {@code mContext} has been released.
	 */
	protected static void scanSavedFile(Context ctx, File file,
			String mimeType) {
		if (file == null || ctx == null)
			return;
		try {
			Context appCtx = ctx.getApplicationContext() != null ? ctx
					.getApplicationContext() : ctx;
			if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
				android.content.ContentValues values = new android.content.ContentValues();
				values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
						file.getName());
				values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE,
						mimeType);
				values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
						android.os.Environment.DIRECTORY_PICTURES
								+ "/TimeLapseCamera");
				android.net.Uri collection = mimeType != null
						&& mimeType.startsWith("video") ? android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
						: android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
				android.net.Uri uri = appCtx.getContentResolver().insert(
						collection, values);
				if (uri != null) {
					java.io.InputStream in = null;
					java.io.OutputStream out = null;
					try {
						in = new java.io.FileInputStream(file);
						out = appCtx.getContentResolver().openOutputStream(uri);
						byte[] buf = new byte[8192];
						int n;
						while ((n = in.read(buf)) != -1)
							out.write(buf, 0, n);
					} finally {
						if (in != null)
							try {
								in.close();
							} catch (IOException ignored) {
							}
						if (out != null)
							try {
								out.close();
							} catch (IOException ignored) {
							}
					}
					return;
				}
			}
			android.media.MediaScannerConnection.scanFile(appCtx,
					new String[] { file.getAbsolutePath() },
					new String[] { mimeType }, null);
		} catch (Exception e) {
			Log.w("TimeLapseCamera", "scanSavedFile failed for " + file, e);
		}
	}

	protected int getCameraRotation(int cameraId) {
		if (mOrientation != null)
			return mOrientation.getCameraRotation(cameraId);
		return 0;
	}


	protected void muteShutter() {
		if (mSettings != null && mSettings.isMuteShutter()) {
			if (mCanDisableShutterSound) {
				// don't merge with upper if (to prevent elseif-branch if
				// mCamera == null)
				if (mCamera != null)
					mCamera.enableShutterSound(false);
			} else if (mMute != null) {
				mMute.muteShutter();
			}
		}

	}

	protected void unmuteShutter() {
		if (mSettings != null && mSettings.isMuteShutter()) {
			if (mCanDisableShutterSound) {
				// don't merge with upper if (to prevent elseif-branch if
				// mCamera == null)
				if (mCamera != null)
					mCamera.enableShutterSound(true);
			} else if (mMute != null) {
				mMute.unmuteShutter();
			}
		}
	}
}
