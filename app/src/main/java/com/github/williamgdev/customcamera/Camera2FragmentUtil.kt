package com.github.williamgdev.customcamera

import android.Manifest
import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.support.v7.app.AlertDialog
import android.util.Log
import android.util.Size
import android.view.Surface
import java.util.*

internal fun Camera2Fragment.requestCameraPermission() {
    if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
        Log.d(Camera2Fragment.TAG, "Requesting Camera permission...")
        AlertDialog.Builder(requireActivity())
                .setMessage("Request Permission")
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    requestPermissions(arrayOf(Manifest.permission.CAMERA),
                            Camera2Fragment.REQUEST_CAMERA_PERMISSION)
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    requireActivity().finish()
                }
                .create()
                .show()

    } else {
        requestPermissions(arrayOf(Manifest.permission.CAMERA), Camera2Fragment.REQUEST_CAMERA_PERMISSION)
    }
}


internal fun Camera2Fragment.setUpCameraOutputs(cameraView: AutoFitTextureView, width: Int, height: Int) {
    val manager = requireActivity().getSystemService(Context.CAMERA_SERVICE) as CameraManager
    try {
        if (manager.cameraIdList == null) {
            return
        }
        for (cameraId in manager.cameraIdList) {
            if (cameraId == null) {
                return
            }
            val characteristics = manager.getCameraCharacteristics(cameraId)

            // We don't use a front facing camera in this sample.
            val cameraDirection = characteristics?.get(CameraCharacteristics.LENS_FACING)
            if (cameraDirection != null &&
                    cameraDirection == CameraCharacteristics.LENS_FACING_FRONT) {
                continue
            }

            val map = characteristics?.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue

            // For still image captures, we use the largest available size.
            val largest = Collections.max(
                    Arrays.asList(*map.getOutputSizes(ImageFormat.JPEG)),
                    CompareSizesByArea())
            imageReader = ImageReader.newInstance(largest.width, largest.height,
                    ImageFormat.JPEG, /*maxImages*/ 2).apply {
                setOnImageAvailableListener({ saveImage(acquireNextImage()) }, Handler(Looper.getMainLooper()))
            }

            // Find out if we need to swap dimension to get the preview size relative to sensor
            // coordinate.
            val displayRotation = requireActivity().windowManager.defaultDisplay.rotation

            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
            val swappedDimensions = areDimensionsSwapped(displayRotation)

            val displaySize = Point()
            requireActivity().windowManager.defaultDisplay.getSize(displaySize)
            val rotatedPreviewWidth = if (swappedDimensions) height else width
            val rotatedPreviewHeight = if (swappedDimensions) width else height
            var maxPreviewWidth = if (swappedDimensions) displaySize.y else displaySize.x
            var maxPreviewHeight = if (swappedDimensions) displaySize.x else displaySize.y

            if (maxPreviewWidth > Camera2Fragment.MAX_PREVIEW_WIDTH) maxPreviewWidth = Camera2Fragment.MAX_PREVIEW_WIDTH
            if (maxPreviewHeight > Camera2Fragment.MAX_PREVIEW_HEIGHT) maxPreviewHeight = Camera2Fragment.MAX_PREVIEW_HEIGHT

            // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
            // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
            // garbage capture data.
            previewSize = Camera2Fragment.chooseOptimalSize(map.getOutputSizes(SurfaceTexture::class.java),
                    rotatedPreviewWidth, rotatedPreviewHeight,
                    maxPreviewWidth, maxPreviewHeight,
                    largest)

            // We fit the aspect ratio of TextureView to the size of preview we picked.
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                cameraView.setAspectRatio(previewSize.width, previewSize.height)
            } else {
                cameraView.setAspectRatio(previewSize.height, previewSize.width)
            }

            // Check if the flash is supported.
            flashSupported =
                    characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

            this.cameraId = cameraId

            // We've found a viable camera and finished setting up member variables,
            // so we don't need to iterate through other available cameras.
            return
        }
    } catch (e: CameraAccessException) {
        Log.e(Camera2Fragment.TAG, e.toString())
    } catch (e: NullPointerException) {
        // Currently an NPE is thrown when the Camera2API is used but not supported on the
        // device this code runs.
        Log.d(Camera2Fragment.TAG, "Error setting up the camera - NullPointerException")
    }

}

internal fun Camera2Fragment.configureTransform(viewWidth: Int, viewHeight: Int): Matrix? {
    activity ?: return null
    val rotation = requireActivity().windowManager.defaultDisplay.rotation
    val matrix = Matrix()
    val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
    val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
    val centerX = viewRect.centerX()
    val centerY = viewRect.centerY()

    if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
        val scale = Math.max(
                viewHeight.toFloat() / previewSize.height,
                viewWidth.toFloat() / previewSize.width)
        with(matrix) {
            setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            postScale(scale, scale, centerX, centerY)
            postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
        }
    } else if (Surface.ROTATION_180 == rotation) {
        matrix.postRotate(180f, centerX, centerY)
    }
    return matrix
}

internal fun Camera2Fragment.createCameraPreviewSession(texture: SurfaceTexture) {
    try {
        // We configure the size of default buffer to be the size of camera preview we want.
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)

        // This is the output Surface we need to start preview.
        val surface = Surface(texture)

        // We set up a CaptureRequest.Builder with the output Surface.
        previewRequestBuilder = cameraDevice!!.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW
        )
        previewRequestBuilder.addTarget(surface)

        // Here, we create a CameraCaptureSession for camera preview.
        cameraDevice?.createCaptureSession(Arrays.asList(surface, imageReader?.surface),
                object : CameraCaptureSession.StateCallback() {

                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        // The camera is already closed
                        if (cameraDevice == null) return

                        // When the session is ready, we start displaying the preview.
                        captureSession = cameraCaptureSession
                        try {
                            // Auto focus should be continuous for camera preview.
                            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            // Flash is automatically enabled when necessary.
                            setAutoFlash(previewRequestBuilder)

                            // Finally, we start displaying the camera preview.
                            previewRequest = previewRequestBuilder.build()
                            captureSession?.setRepeatingRequest(previewRequest,
                                    captureCallback, Handler(Looper.getMainLooper()))
                        } catch (e: CameraAccessException) {
                            Log.e(Camera2Fragment.TAG, e.toString())
                        }

                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.d(Camera2Fragment.TAG, "createCameraPreviewSession Failed")
                    }
                }, null)
    } catch (e: CameraAccessException) {
        Log.e(Camera2Fragment.TAG, e.toString())
    }

}

internal fun Camera2Fragment.runPrecaptureSequence() {
    try {
        // This is how to tell the camera to trigger.
        previewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
        // Tell #captureCallback to wait for the precapture sequence to be set.
        state = Camera2Fragment.STATE_WAITING_PRECAPTURE
        captureSession?.capture(previewRequestBuilder.build(), captureCallback,
                Handler(Looper.getMainLooper()))
    } catch (e: CameraAccessException) {
        Log.e(Camera2Fragment.TAG, e.toString())
    }
}

internal fun Camera2Fragment.captureStillPicture() {
    try {
        if (activity == null || cameraDevice == null) return
        val rotation = requireActivity().windowManager.defaultDisplay.rotation

        // This is the CaptureRequest.Builder that we use to take a picture.
        val captureBuilder = cameraDevice?.createCaptureRequest(
                CameraDevice.TEMPLATE_STILL_CAPTURE)?.apply {
            addTarget(imageReader?.surface)

            // Sensor orientation is 90 for most devices, or 270 for some devices (eg. Nexus 5X)
            // We have to take that into account and rotate JPEG properly.
            // For devices with orientation of 90, we return our mapping from ORIENTATIONS.
            // For devices with orientation of 270, we need to rotate the JPEG 180 degrees.
            set(CaptureRequest.JPEG_ORIENTATION,
                    (ORIENTATIONS.get(rotation) + sensorOrientation + 270) % 360)

            // Use the same AE and AF modes as the preview.
            set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }?.also { setAutoFlash(it) }

        val captureCallback = object : CameraCaptureSession.CaptureCallback() {

            override fun onCaptureCompleted(session: CameraCaptureSession,
                                            request: CaptureRequest,
                                            result: TotalCaptureResult) {
                Log.d(Camera2Fragment.TAG, "Saved")
                unlockFocus()
            }
        }

        captureSession?.apply {
            stopRepeating()
            abortCaptures()
            capture(captureBuilder?.build(), captureCallback, null)
        }
    } catch (e: CameraAccessException) {
        Log.e(Camera2Fragment.TAG, e.toString())
    }
}

internal fun Camera2Fragment.unlockFocus() {
    try {
        // Reset the auto-focus trigger
        previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
        setAutoFlash(previewRequestBuilder)
        captureSession?.capture(previewRequestBuilder.build(), captureCallback,
                Handler(Looper.getMainLooper()))
        // After this, the camera will go back to the normal state of preview.
        state = Camera2Fragment.STATE_PREVIEW
        captureSession?.setRepeatingRequest(previewRequest, captureCallback,
                Handler(Looper.getMainLooper()))
    } catch (e: CameraAccessException) {
        Log.e(Camera2Fragment.TAG, e.toString())
    }

}

internal  fun Camera2Fragment.lockFocus() {
    try {
        // This is how to tell the camera to lock focus.
        previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                CameraMetadata.CONTROL_AF_TRIGGER_START)
        // Tell #captureCallback to wait for the lock.
        state = Camera2Fragment.STATE_WAITING_LOCK
        captureSession?.capture(previewRequestBuilder.build(), captureCallback,
                Handler(Looper.getMainLooper()))
    } catch (e: CameraAccessException) {
        Log.e(Camera2Fragment.TAG, e.toString())
    }

}

internal fun Camera2Fragment.setAutoFlash(requestBuilder: CaptureRequest.Builder) {
    if (flashSupported) {
        requestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
    }
}
/**
 * Determines if the dimensions are swapped given the phone's current rotation.
 *
 * @param displayRotation The current rotation of the display
 *
 * @return true if the dimensions are swapped, false otherwise.
 */
internal fun Camera2Fragment.areDimensionsSwapped(displayRotation: Int): Boolean {
    var swappedDimensions = false
    when (displayRotation) {
        Surface.ROTATION_0, Surface.ROTATION_180 -> {
            if (sensorOrientation == 90 || sensorOrientation == 270) {
                swappedDimensions = true
            }
        }
        Surface.ROTATION_90, Surface.ROTATION_270 -> {
            if (sensorOrientation == 0 || sensorOrientation == 180) {
                swappedDimensions = true
            }
        }
        else -> {
            Log.e(Camera2Fragment.TAG, "Display rotation is invalid: $displayRotation")
        }
    }
    return swappedDimensions
}
internal fun Camera2Fragment.processCaptureResult(result: CaptureResult) {
    when (state) {
        Camera2Fragment.STATE_PREVIEW -> Unit // Do nothing when the camera preview is working normally.
        Camera2Fragment.STATE_WAITING_LOCK -> capturePicture(result)
        Camera2Fragment.STATE_WAITING_PRECAPTURE -> {
            // CONTROL_AE_STATE can be null on some devices
            val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
            if (aeState == null ||
                    aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                    aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
                state = Camera2Fragment.STATE_WAITING_NON_PRECAPTURE
            }
        }
        Camera2Fragment.STATE_WAITING_NON_PRECAPTURE -> {
            // CONTROL_AE_STATE can be null on some devices
            val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
            if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                state = Camera2Fragment.STATE_PICTURE_TAKEN
                captureStillPicture()
            }
        }
    }
}

internal fun Camera2Fragment.capturePicture(result: CaptureResult) {
    val afState = result.get(CaptureResult.CONTROL_AF_STATE)
    if (afState == null) {
        captureStillPicture()
    } else if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
            || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
        // CONTROL_AE_STATE can be null on some devices
        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
        if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
            state = Camera2Fragment.STATE_PICTURE_TAKEN
            captureStillPicture()
        } else {
            runPrecaptureSequence()
        }
    }
}

internal class CompareSizesByArea : Comparator<Size> {

    // We cast here to ensure the multiplications won't overflow
    override fun compare(lhs: Size, rhs: Size) =
            java.lang.Long.signum(lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height)

}

