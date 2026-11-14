/*
 * App Name: Camera
 * Package: com.example.camera
 *
 * Minimalist Android Camera App featuring a full-screen viewfinder and a single
 * red shutter button to capture and save photos to the device gallery.
 *
 * ============================================================================
 * 1. app/src/main/AndroidManifest.xml
 * ============================================================================
 * <?xml version="1.0" encoding="utf-8"?>
 * <manifest xmlns:android="http://schemas.android.com/apk/res/android"
 *     package="com.example.camera">
 *
 *     <uses-feature android:name="android.hardware.camera.any" />
 *     <uses-permission android:name="android.permission.CAMERA" />
 *     <uses-permission
 *         android:name="android.permission.WRITE_EXTERNAL_STORAGE"
 *         android:maxSdkVersion="28" />
 *
 *     <application
 *         android:allowBackup="true"
 *         android:label="Camera"
 *         android:supportsRtl="true"
 *         android:theme="@style/Theme.AppCompat.NoActionBar">
 *         <activity
 *             android:name=".MainActivity"
 *             android:exported="true"
 *             android:screenOrientation="portrait">
 *             <intent-filter>
 *                 <action android:name="android.intent.action.MAIN" />
 *                 <category android:name="android.intent.category.LAUNCHER" />
 *             </intent-filter>
 *         </activity>
 *     </application>
 * </manifest>
 *
 * ============================================================================
 * 2. app/build.gradle.kts (Dependencies)
 * ============================================================================
 * dependencies {
 *     val cameraVersion = "1.3.4"
 *     implementation("androidx.camera:camera-core:$cameraVersion")
 *     implementation("androidx.camera:camera-camera2:$cameraVersion")
 *     implementation("androidx.camera:camera-lifecycle:$cameraVersion")
 *     implementation("androidx.camera:camera-view:$cameraVersion")
 *     implementation("androidx.appcompat:appcompat:1.7.0")
 *     implementation("androidx.core:core-ktx:1.13.1")
 * }
 */

package com.example.camera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var redShutterButton: View
    private lateinit var flashOverlay: View
    private var imageCapture: ImageCapture? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = REQUIRED_PERMISSIONS.all { perm ->
            permissions[perm] == true || ContextCompat.checkSelfPermission(
                this, perm
            ) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            startCamera()
        } else {
            Toast.makeText(
                this,
                "Camera permission is required to take pictures.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()
        setupSingleButtonCameraUi()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            permissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    /**
     * Builds the minimalist UI programmatically:
     * - Full-screen live camera viewfinder
     * - One red circular shutter button at the bottom center to take pictures
     */
    private fun setupSingleButtonCameraUi() {
        val rootLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }

        // Full-screen CameraX PreviewView
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        rootLayout.addView(previewView)

        // Subtle shutter flash overlay for visual capture feedback
        flashOverlay = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.WHITE)
            alpha = 0f
            isClickable = false
        }
        rootLayout.addView(flashOverlay)

        // Single Red Shutter Button (only interactive control in the app)
        val buttonSizePx = dpToPx(84f)
        val bottomMarginPx = dpToPx(44f)

        redShutterButton = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(buttonSizePx, buttonSizePx).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = bottomMarginPx
            }
            background = createRedShutterDrawable()
            contentDescription = "Take Picture"
            isClickable = true
            isFocusable = true

            // Press scale feedback
            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start()
                    }
                }
                false
            }

            setOnClickListener {
                takePicture()
            }
        }
        rootLayout.addView(redShutterButton)

        setContentView(rootLayout)
    }

    /**
     * Creates a circular red shutter button drawable with a clean white outer ring.
     */
    private fun createRedShutterDrawable(): LayerDrawable {
        val outerRing = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(dpToPx(4f), Color.WHITE)
        }

        val innerRedCircle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#E53935")) // Vibrant camera shutter red
        }

        val insetPx = dpToPx(7f)
        return LayerDrawable(arrayOf(outerRing, innerRedCircle)).apply {
            setLayerInset(1, insetPx, insetPx, insetPx, insetPx)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageCapture
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Failed to bind camera use cases", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePicture() {
        val imageCapture = imageCapture ?: return

        // Trigger brief shutter flash animation
        flashOverlay.animate()
            .alpha(0.75f)
            .setDuration(50)
            .withEndAction {
                flashOverlay.animate().alpha(0f).setDuration(150).start()
            }
            .start()

        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_$name")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Camera")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    Toast.makeText(
                        baseContext,
                        "Failed to capture photo.",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Toast.makeText(
                        baseContext,
                        "Picture saved to Gallery",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    private fun allPermissionsGranted(): Boolean = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            resources.displayMetrics
        ).toInt()
    }

    companion object {
        private const val TAG = "CameraApp"
        private const val FILENAME_FORMAT = "yyyyMMdd_HHmmss_SSS"
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA
        ).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }
}
