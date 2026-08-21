package com.kiuda.app.ui.ncpms

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.kiuda.app.R
import com.kiuda.app.data.model.NcpmsAlert
import com.kiuda.app.data.repository.KiudaRepository
import com.kiuda.app.databinding.ActivityPestAlertsBinding
import kotlinx.coroutines.launch

class PestAlertsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPestAlertsBinding
    private val repo = KiudaRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPestAlertsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setTitleTextColor(ContextCompat.getColor(this, R.color.neo_text_dark))

        binding.swipe.setOnRefreshListener { load() }
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            binding.swipe.isRefreshing = true
            val result = repo.ncpmsAlerts()
            binding.swipe.isRefreshing = false
            result.fold(
                onSuccess = { res ->
                    binding.tvSource.text = "출처: ${res.source ?: "NCPMS"} · ${res.updatedAt?.take(16) ?: ""}"
                    render(res.items)
                },
                onFailure = {
                    Toast.makeText(this@PestAlertsActivity, it.message, Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    private fun render(items: List<NcpmsAlert>) {
        binding.container.removeAllViews()
        if (items.isEmpty()) {
            binding.container.addView(TextView(this).apply {
                text = "현재 표시할 주의보가 없습니다."
                setTextColor(ContextCompat.getColor(this@PestAlertsActivity, R.color.neo_muted))
                textSize = 14f
            })
            return
        }
        items.forEach { alert ->
            val card = layoutInflater.inflate(R.layout.item_encyclopedia, binding.container, false)
            // reuse fields
            card.findViewById<TextView>(R.id.tvCategory).text = levelLabel(alert.level)
            card.findViewById<TextView>(R.id.tvCrop).text = "${alert.crop ?: ""} · ${alert.region ?: ""}"
            card.findViewById<TextView>(R.id.tvName).text = alert.name ?: "-"
            card.findViewById<TextView>(R.id.tvSummary).text =
                listOfNotNull(alert.period, alert.summary).joinToString("\n")
            card.setOnClickListener {
                val key = alert.sickKey ?: alert.id
                if (!key.isNullOrBlank() && !key.startsWith("tmp-")) {
                    startActivity(Intent(this, EncyclopediaDetailActivity::class.java).apply {
                        putExtra(EncyclopediaDetailActivity.EXTRA_ID, key)
                    })
                } else {
                    startActivity(Intent(this, EncyclopediaListActivity::class.java).apply {
                        putExtra(EncyclopediaListActivity.EXTRA_QUERY, alert.name)
                    })
                }
            }
            binding.container.addView(card)
        }
    }

    private fun levelLabel(level: String?): String = when (level?.uppercase()) {
        "ALERT", "경보" -> "경보"
        "WARNING", "주의보" -> "주의보"
        "WATCH", "예보" -> "예보"
        else -> level ?: "안내"
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
