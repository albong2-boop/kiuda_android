package com.kiuda.app.ui.ask

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.kiuda.app.R
import com.kiuda.app.databinding.ActivityAskBinding
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AskActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAskBinding
    private val viewModel: AskViewModel by viewModels()
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var capturedFile: File? = null
    private var cameraStarted = false

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(this, "카메라 권한이 필요합니다. 설정에서 허용해주세요.", Toast.LENGTH_LONG).show()
            binding.tvStatus.text = "카메라 권한이 없어 촬영할 수 없습니다."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAskBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "묻다 · AI 진단"
        binding.toolbar.setTitleTextColor(ContextCompat.getColor(this, R.color.neo_text_dark))
        binding.toolbar.setNavigationOnClickListener { finish() }

        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.btnCapture.setOnClickListener { takePhoto() }
        binding.btnDiagnose.setOnClickListener {
            val file = capturedFile
            if (file == null || !file.exists() || file.length() == 0L) {
                Toast.makeText(this, "먼저 사진을 촬영해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.diagnose(file)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermission.launch(Manifest.permission.CAMERA)
        }

        viewModel.loadSymptoms()

        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                when (state) {
                    is AskUiState.Idle -> {
                        binding.progress.visibility = View.GONE
                        binding.btnDiagnose.isEnabled = true
                    }
                    is AskUiState.LoadingSymptoms -> {
                        binding.progress.visibility = View.VISIBLE
                    }
                    is AskUiState.SymptomsLoaded -> {
                        binding.progress.visibility = View.GONE
                        renderSymptoms(state.tags)
                    }
                    is AskUiState.Uploading -> {
                        binding.progress.visibility = View.VISIBLE
                        binding.btnDiagnose.isEnabled = false
                        binding.tvStatus.text = "사진 업로드 중..."
                    }
                    is AskUiState.Diagnosing -> {
                        binding.progress.visibility = View.VISIBLE
                        binding.btnDiagnose.isEnabled = false
                        binding.tvStatus.text = "AI 분석 중..."
                    }
                    is AskUiState.Success -> {
                        binding.progress.visibility = View.GONE
                        binding.btnDiagnose.isEnabled = true
                        binding.tvStatus.text = "진단 완료"
                        val intent = Intent(this@AskActivity, DiagnosisResultActivity::class.java).apply {
                            putExtra(DiagnosisResultActivity.EXTRA_NAME, state.result.diagnosisName)
                            putExtra(DiagnosisResultActivity.EXTRA_CONFIDENCE, state.result.confidence ?: 0.0)
                            putExtra(DiagnosisResultActivity.EXTRA_REASON, state.result.reason)
                            putStringArrayListExtra(
                                DiagnosisResultActivity.EXTRA_METHODS,
                                ArrayList(state.result.managementMethods ?: emptyList())
                            )
                        }
                        startActivity(intent)
                    }
                    is AskUiState.Error -> {
                        binding.progress.visibility = View.GONE
                        binding.btnDiagnose.isEnabled = true
                        binding.tvStatus.text = state.message
                        Toast.makeText(this@AskActivity, state.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun renderSymptoms(tags: List<com.kiuda.app.data.model.SymptomTag>) {
        binding.containerSymptoms.removeAllViews()
        val textColor = ContextCompat.getColor(this, R.color.neo_text)
        tags.forEach { tag ->
            val cb = CheckBox(this).apply {
                text = tag.name ?: ""
                setTextColor(textColor)
                setOnCheckedChangeListener { _, isChecked ->
                    tag.id?.let { viewModel.toggleSymptom(it, isChecked) }
                }
            }
            binding.containerSymptoms.addView(cb)
        }
    }

    private fun startCamera() {
        if (cameraStarted) return
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(binding.previewView.surfaceProvider)

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
                cameraStarted = true
                binding.tvStatus.text = "작물 잎을 화면 중앙에 맞춰 촬영하세요."
            } catch (e: Exception) {
                Toast.makeText(this, "카메라 시작 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                binding.tvStatus.text = "카메라를 사용할 수 없습니다."
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture
        if (imageCapture == null) {
            Toast.makeText(this, "카메라가 아직 준비되지 않았습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val photoFile = File(
            cacheDir,
            "kiuda_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA)
                .format(System.currentTimeMillis()) + ".jpg"
        )
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    capturedFile = photoFile
                    binding.tvStatus.text = "사진 촬영 완료 ✓  (${photoFile.length() / 1024} KB)"
                    Toast.makeText(this@AskActivity, "사진이 저장되었습니다.", Toast.LENGTH_SHORT).show()
                }

                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(
                        this@AskActivity,
                        "촬영 실패: ${exc.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
        }
    }
}
