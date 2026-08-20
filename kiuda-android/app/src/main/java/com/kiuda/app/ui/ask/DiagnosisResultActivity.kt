package com.kiuda.app.ui.ask

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.kiuda.app.R
import com.kiuda.app.databinding.ActivityDiagnosisResultBinding

class DiagnosisResultActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NAME = "diagnosisName"
        const val EXTRA_CONFIDENCE = "confidence"
        const val EXTRA_REASON = "reason"
        const val EXTRA_METHODS = "methods"
    }

    private lateinit var binding: ActivityDiagnosisResultBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosisResultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "진단 결과"
        binding.toolbar.setTitleTextColor(ContextCompat.getColor(this, R.color.neo_text_dark))
        binding.toolbar.setNavigationOnClickListener { finish() }

        val name = intent.getStringExtra(EXTRA_NAME) ?: "진단명 없음"
        val confidence = intent.getDoubleExtra(EXTRA_CONFIDENCE, 0.0)
        val reason = intent.getStringExtra(EXTRA_REASON) ?: ""
        val methods = intent.getStringArrayListExtra(EXTRA_METHODS) ?: arrayListOf()

        binding.tvDiagnosisName.text = name
        val percent = when {
            confidence <= 0.0 -> 0
            confidence <= 1.0 -> (confidence * 100).toInt()
            else -> confidence.toInt().coerceIn(0, 100)
        }
        binding.tvConfidence.text = "확신도 ${percent}%"
        binding.progressConfidence.progress = percent
        binding.tvReason.text = reason.ifBlank { "상세 사유가 제공되지 않았습니다." }

        binding.containerMethods.removeAllViews()
        val textColor = ContextCompat.getColor(this, R.color.neo_text)
        if (methods.isEmpty()) {
            val tv = TextView(this).apply {
                text = "관리 방법이 없습니다."
                textSize = 15f
                setTextColor(textColor)
            }
            binding.containerMethods.addView(tv)
        } else {
            methods.forEachIndexed { index, method ->
                val tv = TextView(this).apply {
                    text = "${index + 1}.  $method"
                    textSize = 15f
                    setTextColor(textColor)
                    setPadding(0, 8, 0, 8)
                }
                binding.containerMethods.addView(tv)
            }
        }

        binding.btnClose.setOnClickListener { finish() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
