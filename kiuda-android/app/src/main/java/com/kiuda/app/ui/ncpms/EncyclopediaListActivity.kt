package com.kiuda.app.ui.ncpms

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.kiuda.app.R
import com.kiuda.app.data.model.NcpmsEncyclopediaItem
import com.kiuda.app.data.repository.KiudaRepository
import com.kiuda.app.databinding.ActivityEncyclopediaListBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class EncyclopediaListActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_QUERY = "query"
    }

    private lateinit var binding: ActivityEncyclopediaListBinding
    private val repo = KiudaRepository()
    private val adapter = EncAdapter { item ->
        val id = (item.sickKey ?: item.id)?.takeIf { it.isNotBlank() } ?: return@EncAdapter
        startActivity(Intent(this, EncyclopediaDetailActivity::class.java).apply {
            putExtra(EncyclopediaDetailActivity.EXTRA_ID, id)
        })
    }
    private var selectedCrop: String? = null
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEncyclopediaListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setTitleTextColor(ContextCompat.getColor(this, R.color.neo_text_dark))

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        setupCropChips()
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                load()
                true
            } else false
        }
        // simple debounce on text? load on IME only + chips

        intent.getStringExtra(EXTRA_QUERY)?.let {
            binding.etSearch.setText(it)
        }
        load()
    }

    private fun setupCropChips() {
        val crops = listOf("전체", "고추", "토마토", "배추", "오이", "사과")
        binding.chipCrops.removeAllViews()
        crops.forEach { crop ->
            val chip = Chip(this).apply {
                text = crop
                isCheckable = true
                isChecked = crop == "전체"
                setOnClickListener {
                    selectedCrop = if (crop == "전체") null else crop
                    load()
                }
            }
            binding.chipCrops.addView(chip)
        }
    }

    private fun load() {
        val q = binding.etSearch.text?.toString()?.trim().orEmpty()
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = repo.ncpmsEncyclopedia(q.ifBlank { null }, selectedCrop)
            binding.progress.visibility = View.GONE
            result.fold(
                onSuccess = { adapter.submit(it.items) },
                onFailure = {
                    Toast.makeText(this@EncyclopediaListActivity, it.message, Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private class EncAdapter(
        val onClick: (NcpmsEncyclopediaItem) -> Unit
    ) : RecyclerView.Adapter<EncAdapter.VH>() {
        private val items = mutableListOf<NcpmsEncyclopediaItem>()
        fun submit(list: List<NcpmsEncyclopediaItem>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_encyclopedia, parent, false)
            return VH(v)
        }
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val tvCategory = v.findViewById<TextView>(R.id.tvCategory)
            private val tvCrop = v.findViewById<TextView>(R.id.tvCrop)
            private val tvName = v.findViewById<TextView>(R.id.tvName)
            private val tvSummary = v.findViewById<TextView>(R.id.tvSummary)
            fun bind(item: NcpmsEncyclopediaItem) {
                tvCategory.text = item.category ?: "-"
                tvCrop.text = item.crop ?: ""
                tvName.text = item.name ?: "-"
                tvSummary.text = item.summary ?: item.symptoms ?: ""
                itemView.setOnClickListener { onClick(item) }
            }
        }
    }
}
