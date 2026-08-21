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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
    private var cameraStarted = false
    private var lastRefreshToken = -1L

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else {
            Toast.makeText(this, "카메라 권한이 필요합니다.", Toast.LENGTH_LONG).show()
            binding.tvStatus.text = "카메라 권한이 없어 촬영할 수 없어요."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAskBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "묻다 · 온새미"
        binding.toolbar.setTitleTextColor(ContextCompat.getColor(this, R.color.neo_text_dark))
        binding.toolbar.setNavigationOnClickListener { finish() }

        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.btnCapture.setOnClickListener { takePhoto() }
        binding.btnDiagnose.setOnClickListener {
            val custom = binding.etCustomQuestion.text?.toString()
            viewModel.diagnose(custom)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera()
        else requestPermission.launch(Manifest.permission.CAMERA)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is AskUiState.Idle -> {
                            binding.progress.visibility = View.GONE
                            binding.btnDiagnose.isEnabled = false
                            binding.btnCapture.isEnabled = true
                            clearPredictUi()
                            binding.tvStatus.text = "작물 잎을 화면 중앙에 맞춰 촬영해 주세요."
                        }
                        is AskUiState.AnalyzingPhoto -> {
                            binding.progress.visibility = View.VISIBLE
                            binding.btnDiagnose.isEnabled = false
                            binding.btnCapture.isEnabled = false
                            clearPredictUi()
                            binding.tvStatus.text = "온새미가 사진을 살펴보는 중이에요..."
                        }
                        is AskUiState.Uploading -> {
                            binding.progress.visibility = View.VISIBLE
                            binding.btnDiagnose.isEnabled = false
                            binding.btnCapture.isEnabled = false
                            clearPredictUi()
                            binding.tvStatus.text = "사진을 올리는 중이에요..."
                        }
                        is AskUiState.Predicting -> {
                            binding.progress.visibility = View.VISIBLE
                            binding.btnDiagnose.isEnabled = false
                            binding.btnCapture.isEnabled = false
                            clearPredictUi()
                            binding.tvStatus.text = "온새미가 예상 질문을 정리하고 있어요..."
                        }
                        is AskUiState.Ready -> {
                            binding.progress.visibility = View.GONE
                            binding.btnDiagnose.isEnabled = true
                            binding.btnCapture.isEnabled = true
                            binding.tvStatus.text = state.summary
                                ?: "해당되는 질문을 고르거나 직접 적어 주세요."
                            // 새 사진 분석 결과일 때만 목록 강제 리프레시
                            if (state.refreshToken != lastRefreshToken) {
                                lastRefreshToken = state.refreshToken
                                binding.etCustomQuestion.setText("")
                                showPredictUi(state.questions)
                            }
                        }
                        is AskUiState.Diagnosing -> {
                            binding.progress.visibility = View.VISIBLE
                            binding.btnDiagnose.isEnabled = false
                            binding.btnCapture.isEnabled = false
                            binding.tvStatus.text = "온새미가 진단 중이에요. 잠시만요 🌿"
                        }
                        is AskUiState.Success -> {
                            binding.progress.visibility = View.GONE
                            binding.btnDiagnose.isEnabled = true
                            binding.btnCapture.isEnabled = true
                            val i = Intent(this@AskActivity, DiagnosisResultActivity::class.java).apply {
                                putExtra(DiagnosisResultActivity.EXTRA_NAME, state.result.diagnosisName)
                                putExtra(DiagnosisResultActivity.EXTRA_CONFIDENCE, state.result.confidence ?: 0.0)
                                putExtra(DiagnosisResultActivity.EXTRA_REASON, state.result.reason)
                                putExtra(DiagnosisResultActivity.EXTRA_GREETING, state.result.greeting)
                                putExtra(DiagnosisResultActivity.EXTRA_CLOSING, state.result.closing)
                                putStringArrayListExtra(
                                    DiagnosisResultActivity.EXTRA_METHODS,
                                    ArrayList(state.result.managementMethods ?: emptyList())
                                )
                                // 같은 결과가 스택에 중복되지 않도록
                                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            }
                            startActivity(i)
                            // 핵심: Success 상태 소비 → 뒤로가기 시 결과 화면 재실행 방지
                            viewModel.consumeSuccess()
                        }
                        is AskUiState.Error -> {
                            binding.progress.visibility = View.GONE
                            binding.btnDiagnose.isEnabled = false
                            binding.btnCapture.isEnabled = true
                            binding.tvStatus.text = state.message
                            Toast.makeText(this@AskActivity, state.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    private fun clearPredictUi() {
        binding.tvPredictTitle.visibility = View.GONE
        binding.tvPredictHint.visibility = View.GONE
        binding.containerSymptoms.visibility = View.GONE
        binding.tvCustomLabel.visibility = View.GONE
        binding.tilCustomQuestion.visibility = View.GONE
        binding.containerSymptoms.removeAllViews()
    }

    private fun showPredictUi(questions: List<com.kiuda.app.data.model.PredictedQuestion>) {
        binding.tvPredictTitle.visibility = View.VISIBLE
        binding.tvPredictHint.visibility = View.VISIBLE
        binding.containerSymptoms.visibility = View.VISIBLE
        binding.tvCustomLabel.visibility = View.VISIBLE
        binding.tilCustomQuestion.visibility = View.VISIBLE
        binding.containerSymptoms.removeAllViews()
        val color = ContextCompat.getColor(this, R.color.neo_text)
        questions.forEach { q ->
            val text = q.text ?: return@forEach
            val cb = CheckBox(this).apply {
                this.text = text
                textSize = 14f
                setTextColor(color)
                setPadding(8, 10, 8, 10)
                setOnCheckedChangeListener { _, checked ->
                    viewModel.toggleQuestion(text, checked)
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
                binding.tvStatus.text = "작물 잎을 화면 중앙에 맞춰 촬영해 주세요."
            } catch (e: Exception) {
                Toast.makeText(this, "카메라 시작 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                binding.tvStatus.text = "카메라를 사용할 수 없어요."
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture
        if (imageCapture == null) {
            Toast.makeText(this, "카메라가 아직 준비되지 않았어요.", Toast.LENGTH_SHORT).show()
            return
        }
        // 촬영 즉시 이전 질문 비우기
        clearPredictUi()
        binding.btnDiagnose.isEnabled = false
        binding.tvStatus.text = "촬영 중..."

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
                    binding.tvStatus.text = "촬영 완료 · 온새미가 분석해요"
                    viewModel.onPhotoCaptured(photoFile)
                }
                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(this@AskActivity, "촬영 실패: ${exc.message}", Toast.LENGTH_SHORT).show()
                    binding.tvStatus.text = "다시 촬영해 주세요."
                    binding.btnCapture.isEnabled = true
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
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
    }
}
