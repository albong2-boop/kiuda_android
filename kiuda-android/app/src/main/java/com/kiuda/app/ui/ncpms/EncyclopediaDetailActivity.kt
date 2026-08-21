package com.kiuda.app.ui.ncpms

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.kiuda.app.R
import com.kiuda.app.data.repository.KiudaRepository
import com.kiuda.app.databinding.ActivityEncyclopediaDetailBinding
import kotlinx.coroutines.launch

class EncyclopediaDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "id"
    }

    private lateinit var binding: ActivityEncyclopediaDetailBinding
    private val repo = KiudaRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEncyclopediaDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setTitleTextColor(ContextCompat.getColor(this, R.color.neo_text_dark))

        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        if (id.isBlank()) {
            Toast.makeText(this, "잘못된 항목입니다.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        lifecycleScope.launch {
            val result = repo.ncpmsEncyclopediaDetail(id)
            result.fold(
                onSuccess = { item ->
                    binding.tvMeta.text = "${item.crop ?: ""} · ${item.category ?: ""}"
                    binding.tvName.text = item.name ?: "-"
                    binding.tvScientific.text = item.scientificName ?: ""
                    binding.tvSymptoms.text = item.symptoms ?: "-"
                    binding.tvEnv.text = item.environment ?: "-"
                    binding.tvPrevention.text = item.prevention ?: "-"
                    binding.containerControl.removeAllViews()
                    val color = ContextCompat.getColor(this@EncyclopediaDetailActivity, R.color.neo_text)
                    (item.control ?: emptyList()).forEachIndexed { i, line ->
                        binding.containerControl.addView(TextView(this@EncyclopediaDetailActivity).apply {
                            text = "${i + 1}.  $line"
                            textSize = 14f
                            setTextColor(color)
                            setPadding(0, 6, 0, 6)
                            setLineSpacing(3f, 1f)
                        })
                    }
                    supportActionBar?.title = item.name
                },
                onFailure = {
                    Toast.makeText(this@EncyclopediaDetailActivity, it.message, Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
