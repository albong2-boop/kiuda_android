package com.kiuda.app.ui.ask

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.kiuda.app.R
import com.kiuda.app.databinding.ActivityDiagnosisResultBinding
import com.kiuda.app.ui.ncpms.EncyclopediaListActivity

class DiagnosisResultActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NAME = "diagnosisName"
        const val EXTRA_CONFIDENCE = "confidence"
        const val EXTRA_REASON = "reason"
        const val EXTRA_METHODS = "methods"
        const val EXTRA_GREETING = "greeting"
        const val EXTRA_CLOSING = "closing"
    }

    private lateinit var binding: ActivityDiagnosisResultBinding
    private var finishing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosisResultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "온새미 진단"
        binding.toolbar.setTitleTextColor(ContextCompat.getColor(this, R.color.neo_text_dark))
        binding.toolbar.setNavigationOnClickListener { finishOnce() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finishOnce()
            }
        })

        val name = intent.getStringExtra(EXTRA_NAME) ?: "진단명 없음"
        val confidence = intent.getDoubleExtra(EXTRA_CONFIDENCE, 0.0)
        val reason = intent.getStringExtra(EXTRA_REASON).orEmpty()
        val methods = intent.getStringArrayListExtra(EXTRA_METHODS) ?: arrayListOf()
        val greeting = intent.getStringExtra(EXTRA_GREETING)
            ?: "안녕하세요, 식물 질병 분석을 돕는 온새미예요"
        val closing = intent.getStringExtra(EXTRA_CLOSING)
            ?: "확진은 전문가 확인을 권해요. 궁금하면 다시 물어봐 주세요"

        binding.tvGreeting.text = sanitizeDisplay(greeting)
        binding.tvDiagnosisName.text = sanitizeDisplay(name)

        val percent = when {
            confidence <= 0.0 -> 0
            confidence <= 1.0 -> (confidence * 100).toInt()
            else -> confidence.toInt().coerceIn(0, 100)
        }
        binding.tvConfidence.text = "확신도  ${percent}%"
        binding.progressConfidence.progress = percent

        val cleanReason = sanitizeDisplay(reason)
        binding.tvReason.text = cleanReason.ifBlank {
            "자세한 분석 내용이 없어요. 사진을 다시 찍어 볼까요?"
        }
        binding.tvClosing.text = sanitizeDisplay(closing)

        binding.containerMethods.removeAllViews()
        val textColor = ContextCompat.getColor(this, R.color.neo_text)
        val cleanMethods = methods.map { sanitizeDisplay(it) }.filter { it.isNotBlank() }
        if (cleanMethods.isEmpty()) {
            binding.containerMethods.addView(TextView(this).apply {
                text = "·  통풍과 물 주기부터 천천히 점검해 보세요."
                textSize = 15f
                setTextColor(textColor)
                setLineSpacing(6f, 1f)
            })
        } else {
            cleanMethods.forEachIndexed { index, method ->
                binding.containerMethods.addView(TextView(this).apply {
                    text = "${index + 1}.  $method"
                    textSize = 15f
                    setTextColor(textColor)
                    setPadding(0, 8, 0, 8)
                    setLineSpacing(4f, 1f)
                })
            }
        }

        binding.btnEncyclopedia.setOnClickListener {
            startActivity(Intent(this, EncyclopediaListActivity::class.java).apply {
                putExtra(EncyclopediaListActivity.EXTRA_QUERY, name)
            })
        }
        binding.btnClose.setOnClickListener { finishOnce() }
    }

    private fun sanitizeDisplay(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var s = raw.trim()
        val looksJson = s.contains("\"diagnosisName\"") || s.contains("```") ||
            (s.startsWith("{") && s.contains("\"reason\""))
        if (looksJson) {
            Regex("\"reason\"\\s*:\\s*\"([\\s\\S]*?)\"").find(s)?.groupValues?.getOrNull(1)?.let {
                return it.replace("\\n", "\n").replace("\\\"", "\"")
            }
            s = s.replace(Regex("```[a-zA-Z]*"), "")
                .replace("```", "")
                .replace(Regex("[{}\\[\\]\"]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
        return s
    }

    private fun finishOnce() {
        if (finishing) return
        finishing = true
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        finishOnce()
        return true
    }
}
