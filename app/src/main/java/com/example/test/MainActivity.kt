package com.example.test

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues.TAG
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import com.google.mlkit.common.model.LocalModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.custom.CustomImageLabelerOptions
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import com.google.android.material.snackbar.Snackbar;
import com.google.mlkit.vision.face.FaceDetection
import android.media.AudioManager

import android.content.Context.AUDIO_SERVICE
import androidx.core.content.ContextCompat.getSystemService


typealias LumaListener = (luma: Double) -> Unit

class MainActivity : AppCompatActivity() {
    private var imageCapture: ImageCapture? = null

    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }


        // Set up the listener for take photo button
        //camera_capture_button.setOnClickListener { takePhoto() }

        outputDirectory = getOutputDirectory()

        cameraExecutor = Executors.newSingleThreadExecutor()
    }


    private fun takePhoto() {
        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return

        // Create time-stamped output file to hold the image
        val photoFile = File(
            outputDirectory,
            SimpleDateFormat(FILENAME_FORMAT, Locale.US
            ).format(System.currentTimeMillis()) + ".jpg")

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        // Set up image capture listener, which is triggered after photo has
        // been taken
        imageCapture.takePicture(
            outputOptions, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = Uri.fromFile(photoFile)
                    val msg = "Photo capture succeeded: $savedUri"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, msg)
                }
            })
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val audioFlags = AudioManager.FLAG_SHOW_UI or AudioManager.FLAG_PLAY_SOUND
        val alarmVol = audioManager.getStreamVolume(AudioManager.STREAM_RING).toFloat()
        // それぞれの設定音量
        val setVolume = 8

        cameraProviderFuture.addListener(Runnable {
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()


            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewFinder.createSurfaceProvider())
                }
            imageCapture = ImageCapture.Builder()
                .build()

            val imageAnalyzer = ImageAnalysis.Builder()
                .build()
                .also {
                    var numbercount = 15
                    var times = 8
                    var timescout =0
                    var ans = 0
                    it.setAnalyzer(cameraExecutor,MyLabelAnalyzer{ labels ->
                        Log.d(TAG, "Face detected: $labels")
                        var alarmVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        //camera_capture_button.setEnabled(labels == 1)
                        if(times-1 == timescout % times ) {
                            if (labels == 1) {
                                alarmVol--
                                // 音楽
                                audioManager.setStreamVolume(
                                    AudioManager.STREAM_MUSIC,
                                    alarmVol,
                                    audioFlags
                                )
                            }
                        }
                        ans = timescout % times
                        Log.d(TAG,"time = $ans")
                        timescout++
                    })
                }

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {

                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }


    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() }
        }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else filesDir
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraXBasic"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    private class FaceAnalyzer(private var listener: (Int) -> Unit) : ImageAnalysis.Analyzer {
        private val detector = FaceDetection.getClient()

        @ExperimentalGetImage
        override fun analyze(imageProxy: ImageProxy) {

            val mediaImage = imageProxy.image ?: return
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            detector.process(image)
                .addOnSuccessListener { faces ->
                    listener(faces.size)
                }
                .addOnFailureListener { e ->
                    Log.e("FaceAnalyzer", "Face detection failure.", e)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        }
    }
    // My Classifier Image Labeling Model
    private class MyLabelAnalyzer(private var listener: (Int) -> Unit) : ImageAnalysis.Analyzer {

        val localModel = LocalModel.Builder()
            //.setAssetFilePath("lite-model_aiy_vision_classifier_food_V1_1.tflite")
            .setAssetFilePath("mov4metadata.tflite")
            //.setAssetFilePath("soudnAI.tflite")
            .build()

        val customImageLabelerOptions =
            CustomImageLabelerOptions.Builder(localModel)
                .setConfidenceThreshold(0.1f)
                .setMaxResultCount(5)
                .build()

        @SuppressLint("UnsafeOptInUsageError")
        override fun analyze(imageProxy: ImageProxy) {

            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image =
                    InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                // Pass image to food classifier model
                val imageLabeler = ImageLabeling.getClient(customImageLabelerOptions)
                imageLabeler.process(image)
                    .addOnSuccessListener { labels ->
                        // Task completed successfully
                        val ary = floatArrayOf(1.0f, 2.0f,3.0f)
                        var most = 0
                        var count = 1
                        for (label in labels) {
                            val text = label.text
                            val confidence = label.confidence
                            val index = label.index
                            //Log.d(TAG, "most confidence ans: $text, $confidence, $index")

                            if (label.text == "エラー") {
                                ary[0] = label.confidence
                            }

                            if (label.text == "不快") {
                                ary[1] = label.confidence
                            }

                            if (label.text == "普通") {
                                ary[2] = label.confidence
                            }
                            Log.d(TAG, "My Label: $text, $confidence, $index")
                            if(count == 1 && label.index == 1){
                                Log.d(TAG,"ary = $ary" )
                                most = 1
                            }
                            count++
                        }
                        listener(most)
                        Log.d(TAG, "end loop")
                    }

                    .addOnFailureListener { e ->
                        // Task failed with an exception
                        Log.e(TAG, "My Label: $e")
                    }
                    .addOnCompleteListener { results -> imageProxy.close() }
            }
        }

    }
}
