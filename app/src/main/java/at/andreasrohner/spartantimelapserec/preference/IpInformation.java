package at.andreasrohner.spartantimelapserec.preference;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.os.Bundle;
import android.preference.DialogPreference;
import android.preference.PreferenceManager;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.net.InetAddress;
import java.util.List;

import at.andreasrohner.spartantimelapserec.ForegroundService;
import at.andreasrohner.spartantimelapserec.R;
import at.andreasrohner.spartantimelapserec.rest.RestService;

/**
 * Show the current IP and Port for connection, plus an optional
 * live camera preview while the dialog is visible.
 *
 * The camera is opened only while the dialog is visible, only when
 * the preview toggle is on, and never while a recording is running
 * (the recorder owns the camera then). Every failure degrades to a
 * short message row, never a crash. The camera is released on all
 * dismiss paths and when the preview surface goes away.
 */
public class IpInformation extends DialogPreference implements SurfaceHolder.Callback {

	/**
	 * Persisted toggle: run the live preview inside this dialog.
	 * Default on. Shared with the CheckBoxPreference in preferences.xml.
	 */
	public static final String PREF_PREVIEW_ENABLED = "pref_preview_enabled";

	private Camera mCamera;
	private boolean mDialogVisible;
	private boolean mSurfaceReady;
	private boolean mStarting;
	private SurfaceHolder mHolder;
	private View mDialogView;
	private AlertDialog mDialog;
	private final Object mLock = new Object();

	public IpInformation(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
		super(context, attrs, defStyleAttr, defStyleRes);

		updateData();
	}

	public IpInformation(Context context, AttributeSet attrs, int defStyleAttr) {
		this(context, attrs, defStyleAttr, 0);
	}

	public IpInformation(Context context, AttributeSet attrs) {
		this(context, attrs, 0, 0);
	}

	public IpInformation(Context context) {
		this(context, null, 0);
	}

	@Override
	protected void showDialog(Bundle state) {
		updateData();
		showPreviewDialog();
	}

	/**
	 * Update Data
	 */
	public void updateData() {
		Context ctx = getContext();
		CharSequence info = connectionInfoText(ctx);
		if (info == null || info.length() == 0) {
			setSummary(ctx.getString(R.string.error_no_ip_refresh));
		} else {
			setSummary(info);
		}
	}

	private static CharSequence connectionInfoText(Context ctx) {
		try {
			InetAddress addr = RestService.getLocalInetAddress(ctx);
			if (addr == null) {
				return ctx.getString(R.string.error_no_ip_refresh);
			}
			return "http://" + addr.getHostAddress() + ":" + RestService.getPort(ctx);
		} catch (Throwable t) {
			return "";
		}
	}

	private boolean shouldPreview() {
		try {
			SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
			return prefs.getBoolean(PREF_PREVIEW_ENABLED, true);
		} catch (Throwable t) {
			return true;
		}
	}

	private void showPreviewDialog() {
		final Context ctx = getContext();
		final View view;
		try {
			view = LayoutInflater.from(ctx).inflate(R.layout.dialog_ip_preview, null);
		} catch (Throwable t) {
			return;
		}
		TextView info = view.findViewById(R.id.dialog_ip_preview_info);
		SurfaceView surface = view.findViewById(R.id.dialog_ip_preview_surface);
		CheckBox toggle = view.findViewById(R.id.dialog_ip_preview_toggle);
		if (info == null || surface == null || toggle == null) {
			return;
		}
		info.setText(connectionInfoText(ctx));
		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
		boolean enabled = true;
		try {
			enabled = prefs.getBoolean(PREF_PREVIEW_ENABLED, true);
		} catch (Throwable ignored) {
		}
		toggle.setChecked(enabled);

		mDialogView = view;
		mDialogVisible = true;
		mSurfaceReady = false;

		AlertDialog dialog;
		try {
			dialog = new AlertDialog.Builder(ctx)
					.setTitle(R.string.pref_restapi_connectioninfo_title)
					.setView(view)
					.setPositiveButton(android.R.string.ok, null)
					.create();
		} catch (Throwable t) {
			mDialogView = null;
			mDialogVisible = false;
			return;
		}
		mDialog = dialog;
		dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
			@Override
			public void onDismiss(DialogInterface d) {
				mDialogVisible = false;
				releasePreview();
				detachHolder();
				mDialog = null;
				mDialogView = null;
				try {
					updateData();
				} catch (Throwable ignored) {
				}
			}
		});
		dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
			@Override
			public void onCancel(DialogInterface d) {
				mDialogVisible = false;
				releasePreview();
				detachHolder();
			}
		});
		toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
			@Override
			public void onCheckedChanged(CompoundButton button, boolean checked) {
				try {
					prefs.edit().putBoolean(PREF_PREVIEW_ENABLED, checked).apply();
				} catch (Throwable ignored) {
				}
				if (checked) {
					hideStatus();
					maybeStartPreview();
				} else {
					releasePreview();
				}
			}
		});
		try {
			surface.getHolder().addCallback(this);
		} catch (Throwable ignored) {
		}
		try {
			dialog.show();
		} catch (Throwable t) {
			mDialogVisible = false;
			releasePreview();
			detachHolder();
			mDialog = null;
			mDialogView = null;
			return;
		}
		hideStatus();
		maybeStartPreview();
	}

	/**
	 * Start the live preview unless a guard applies. Guards show a
	 * short message row instead of touching the camera. The camera
	 * itself is opened on a background thread.
	 */
	private void maybeStartPreview() {
		if (!mDialogVisible || !shouldPreview()) {
			return;
		}
		try {
			if (ForegroundService.mIsRunning) {
				postStatus(R.string.info_recording_running);
				return;
			}
		} catch (Throwable t) {
			postStatus(R.string.info_recording_running);
			return;
		}
		try {
			if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.CAMERA)
					!= PackageManager.PERMISSION_GRANTED) {
				postStatus(R.string.error_missing_permission);
				return;
			}
		} catch (Throwable t) {
			postStatus(R.string.error_missing_permission);
			return;
		}
		if (!mSurfaceReady) {
			return;
		}
		synchronized (mLock) {
			if (mCamera != null || mStarting) {
				return;
			}
			mStarting = true;
		}
		final SurfaceHolder holder;
		synchronized (mLock) {
			holder = mHolder;
		}
		if (holder == null) {
			synchronized (mLock) {
				mStarting = false;
			}
			return;
		}
		Thread t = new Thread(new Runnable() {
			@Override
			public void run() {
				Camera camera = null;
				try {
					int camId = backCameraId();
					camera = Camera.open(camId);
					try {
						Camera.Parameters params = camera.getParameters();
						try {
							List<Camera.Size> sizes = params.getSupportedPreviewSizes();
							Camera.Size modest = chooseModestPreviewSize(sizes);
							if (modest != null) {
								params.setPreviewSize(modest.width, modest.height);
							}
						} catch (Throwable ignored) {
						}
						try {
							List<String> modes = params.getSupportedFocusModes();
							if (modes != null
									&& modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
								params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
							}
						} catch (Throwable ignored) {
						}
						try {
							camera.setParameters(params);
						} catch (Throwable ignored) {
						}
					} catch (Throwable ignored) {
					}
					try {
						setDisplayOrientation(getContext(), camId, camera);
					} catch (Throwable ignored) {
					}
					synchronized (mLock) {
						if (!mDialogVisible || !shouldPreview()) {
							mStarting = false;
						} else {
							mCamera = camera;
							camera = null;
							mStarting = false;
						}
					}
					if (camera != null) {
						try {
							camera.release();
						} catch (Throwable ignored) {
						}
						return;
					}
					Camera adopted;
					synchronized (mLock) {
						adopted = mCamera;
					}
					try {
						adopted.setPreviewDisplay(holder);
						adopted.startPreview();
						postHideStatus();
					} catch (Throwable th) {
						releasePreview();
						postStatus(R.string.pref_preview_unavailable);
					}
				} catch (Throwable th) {
					if (camera != null) {
						try {
							camera.release();
						} catch (Throwable ignored) {
						}
					}
					synchronized (mLock) {
						mStarting = false;
					}
					postStatus(R.string.pref_preview_unavailable);
				}
			}
		});
		t.setDaemon(true);
		t.start();
	}

	private void releasePreview() {
		Camera camera;
		synchronized (mLock) {
			camera = mCamera;
			mCamera = null;
			mStarting = false;
		}
		if (camera == null) {
			return;
		}
		try {
			camera.setPreviewCallback(null);
		} catch (Throwable ignored) {
		}
		try {
			camera.stopPreview();
		} catch (Throwable ignored) {
		}
		try {
			camera.release();
		} catch (Throwable ignored) {
		}
	}

	private void detachHolder() {
		SurfaceHolder holder;
		synchronized (mLock) {
			holder = mHolder;
			mHolder = null;
			mSurfaceReady = false;
		}
		if (holder != null) {
			try {
				holder.removeCallback(this);
			} catch (Throwable ignored) {
			}
		}
	}

	private void postStatus(final int resId) {
		final View v = mDialogView;
		if (v == null) {
			return;
		}
		try {
			v.post(new Runnable() {
				@Override
				public void run() {
					try {
						TextView status = v.findViewById(R.id.dialog_ip_preview_status);
						if (status != null) {
							status.setText(resId);
							status.setVisibility(View.VISIBLE);
						}
					} catch (Throwable ignored) {
					}
				}
			});
		} catch (Throwable ignored) {
		}
	}

	private void hideStatus() {
		final View v = mDialogView;
		if (v == null) {
			return;
		}
		try {
			TextView status = v.findViewById(R.id.dialog_ip_preview_status);
			if (status != null) {
				status.setVisibility(View.GONE);
			}
		} catch (Throwable ignored) {
		}
	}

	private void postHideStatus() {
		final View v = mDialogView;
		if (v == null) {
			return;
		}
		try {
			v.post(new Runnable() {
				@Override
				public void run() {
					hideStatus();
				}
			});
		} catch (Throwable ignored) {
		}
	}

	@Override
	public void surfaceCreated(SurfaceHolder holder) {
		synchronized (mLock) {
			mHolder = holder;
			mSurfaceReady = true;
		}
		if (mDialogVisible && shouldPreview()) {
			maybeStartPreview();
		}
	}

	@Override
	public void surfaceDestroyed(SurfaceHolder holder) {
		synchronized (mLock) {
			mSurfaceReady = false;
			if (mHolder == holder) {
				mHolder = null;
			}
		}
		releasePreview();
	}

	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
	}

	private static int backCameraId() {
		try {
			int n = Camera.getNumberOfCameras();
			Camera.CameraInfo info = new Camera.CameraInfo();
			for (int i = 0; i < n; i++) {
				Camera.getCameraInfo(i, info);
				if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
					return i;
				}
			}
		} catch (Throwable ignored) {
		}
		return 0;
	}

	private static Camera.Size chooseModestPreviewSize(List<Camera.Size> sizes) {
		if (sizes == null || sizes.isEmpty()) {
			return null;
		}
		Camera.Size best = null;
		for (Camera.Size s : sizes) {
			if (s == null || s.width > 640) {
				continue;
			}
			if (best == null || s.width * s.height > best.width * best.height) {
				best = s;
			}
		}
		if (best != null) {
			return best;
		}
		best = sizes.get(0);
		for (Camera.Size s : sizes) {
			if (s != null && s.width * s.height < best.width * best.height) {
				best = s;
			}
		}
		return best;
	}

	private static void setDisplayOrientation(Context ctx, int cameraId, Camera camera) {
		try {
			if (!(ctx instanceof Activity) || camera == null) {
				return;
			}
			Camera.CameraInfo info = new Camera.CameraInfo();
			Camera.getCameraInfo(cameraId, info);
			int rotation = ((Activity) ctx).getWindowManager().getDefaultDisplay().getRotation();
			int degrees = 0;
			switch (rotation) {
				case Surface.ROTATION_0:
					degrees = 0;
					break;
				case Surface.ROTATION_90:
					degrees = 90;
					break;
				case Surface.ROTATION_180:
					degrees = 180;
					break;
				case Surface.ROTATION_270:
					degrees = 270;
					break;
				default:
					break;
			}
			int result;
			if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
				result = (info.orientation + degrees) % 360;
				result = (360 - result) % 360;
			} else {
				result = (info.orientation - degrees + 360) % 360;
			}
			camera.setDisplayOrientation(result);
		} catch (Throwable ignored) {
		}
	}
}
