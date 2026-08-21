package com.kiuda.app.ui.dashboard

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.kiuda.app.R
import com.kiuda.app.databinding.ActivityDashboardBinding
import com.kiuda.app.ui.ask.AskActivity
import com.kiuda.app.ui.ncpms.EncyclopediaListActivity
import com.kiuda.app.ui.ncpms.PestAlertsActivity
import com.kiuda.app.ui.login.LoginActivity
import kotlinx.coroutines.launch

class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding
    private val viewModel: DashboardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "키:우다"
        binding.toolbar.setTitleTextColor(ContextCompat.getColor(this, R.color.neo_text_dark))

        binding.tvGreeting.text = "안녕하세요, ${viewModel.greetingName}님"

        binding.btnAsk.setOnClickListener {
            startActivity(Intent(this, AskActivity::class.java))
        }
        binding.btnPestAlerts.setOnClickListener {
            startActivity(Intent(this, PestAlertsActivity::class.java))
        }
        binding.btnEncyclopedia.setOnClickListener {
            startActivity(Intent(this, EncyclopediaListActivity::class.java))
        }

        binding.swipeRefresh.setColorSchemeColors(
            ContextCompat.getColor(this, R.color.neo_green_primary)
        )
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.load()
        }

        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                binding.swipeRefresh.isRefreshing = state is DashboardUiState.Loading
                when (state) {
                    is DashboardUiState.Loading -> {
                        binding.progress.visibility = View.VISIBLE
                    }
                    is DashboardUiState.Success -> {
                        binding.progress.visibility = View.GONE
                        // 데이터가 전부 비어 있으면 데모 UI
                        val d = state.data
                        if (d.plants.isEmpty() && d.checklist.isEmpty() && d.weather == null && d.pestAlerts.isEmpty()) {
                            renderDemoData()
                        } else {
                            renderDashboard(d)
                        }
                    }
                    is DashboardUiState.Error -> {
                        binding.progress.visibility = View.GONE
                        // 백엔드 미연결: 토스트 짧게 + 데모 데이터
                        Toast.makeText(
                            this@DashboardActivity,
                            "서버 연결 없음 · 데모 화면 표시",
                            Toast.LENGTH_SHORT
                        ).show()
                        renderDemoData()
                    }
                }
            }
        }

        viewModel.load()
    }

    private fun renderDashboard(data: com.kiuda.app.data.model.DashboardData) {
        val textColor = ContextCompat.getColor(this, R.color.neo_text)

        binding.containerPlants.removeAllViews()
        if (data.plants.isEmpty()) {
            binding.tvNoPlants.visibility = View.VISIBLE
        } else {
            binding.tvNoPlants.visibility = View.GONE
            data.plants.forEach { plant ->
                val tv = TextView(this).apply {
                    text = "🌱  ${plant.nickname ?: plant.name ?: "작물"}"
                    textSize = 16f
                    setTextColor(textColor)
                    setPadding(16, 12, 16, 12)
                }
                binding.containerPlants.addView(tv)
            }
        }

        binding.containerChecklist.removeAllViews()
        data.checklist.forEach { item ->
            val cb = CheckBox(this).apply {
                text = item.title ?: ""
                isChecked = item.completed
                setTextColor(textColor)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked && item.id != null) {
                        viewModel.completeChecklist(item.id)
                    }
                }
            }
            binding.containerChecklist.addView(cb)
        }

        data.weather?.let { w ->
            binding.tvWeather.text =
                "☀  ${w.temperature?.toInt() ?: "-"}°C / ${w.condition ?: "정보 없음"}"
        } ?: run {
            binding.tvWeather.text = "☀  날씨 정보 없음"
        }

        binding.containerPest.removeAllViews()
        val alertColor = ContextCompat.getColor(this, R.color.neo_green_dark)
        data.pestAlerts.forEach { alert ->
            val tv = TextView(this).apply {
                text = "⚠  ${alert.title ?: "주의보"} (${alert.level ?: "-"})"
                textSize = 14f
                setTextColor(alertColor)
                setPadding(8, 8, 8, 8)
            }
            binding.containerPest.addView(tv)
        }
    }

    private fun renderDemoData() {
        val textColor = ContextCompat.getColor(this, R.color.neo_text)
        val alertColor = ContextCompat.getColor(this, R.color.neo_green_dark)

        binding.tvNoPlants.visibility = View.GONE
        binding.containerPlants.removeAllViews()
        listOf("토마토", "고추").forEach {
            val tv = TextView(this).apply {
                text = "🌱  $it"
                textSize = 16f
                setTextColor(textColor)
                setPadding(16, 12, 16, 12)
            }
            binding.containerPlants.addView(tv)
        }

        binding.containerChecklist.removeAllViews()
        listOf("오늘 물 주기", "잎 상태 확인", "비료 주기").forEachIndexed { i, title ->
            val cb = CheckBox(this).apply {
                text = title
                isChecked = i == 0
                setTextColor(textColor)
            }
            binding.containerChecklist.addView(cb)
        }

        binding.tvWeather.text = "☀  28°C / 맑음"

        binding.containerPest.removeAllViews()
        val tv = TextView(this).apply {
            text = "⚠  병해충 주의보 (MEDIUM)"
            setTextColor(alertColor)
            setPadding(8, 8, 8, 8)
        }
        binding.containerPest.addView(tv)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_dashboard, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_logout -> {
                viewModel.logout()
                startActivity(Intent(this, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
