package com.example.mainactivity.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.mainactivity.R
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PhoneRecord(
    val phone: String,
    val verdict: String,      // "보이스피싱 의심" or "안심번호"
    val confidence: Int,      // 0~100
    val time: Long            // epoch millis
)

class HistoryFragment : Fragment() {

    private val adapter = HistoryAdapter()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_history, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val rv = view.findViewById<RecyclerView>(R.id.rvHistory)
        val tvEmpty = view.findViewById<TextView>(R.id.tvEmpty)
        val fab = view.findViewById<FloatingActionButton>(R.id.fabAddDummy)

        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        // 빈 화면 처리
        fun updateEmpty(isEmpty: Boolean) {
            tvEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
            rv.visibility = if (isEmpty) View.GONE else View.VISIBLE
        }

        // 초기 상태
        adapter.submitList(emptyList())
        updateEmpty(true)

        // 데모용: 더미 데이터 추가 (나중에 Analyze 결과 저장 로직으로 교체)
        fab.setOnClickListener {
            val now = System.currentTimeMillis()
            val sample = listOf(
                PhoneRecord("010-1234-5678", "보이스피싱 의심", 87, now),
                PhoneRecord("02-987-6543", "안심번호", 96, now - 60_000),
            ) + (adapter.currentList)
            adapter.submitList(sample)
            updateEmpty(sample.isEmpty())
        }
    }
}

/* ---------- RecyclerView Adapter ---------- */

private object Diff : DiffUtil.ItemCallback<PhoneRecord>() {
    override fun areItemsTheSame(old: PhoneRecord, new: PhoneRecord) =
        old.phone == new.phone && old.time == new.time
    override fun areContentsTheSame(old: PhoneRecord, new: PhoneRecord) = old == new
}

private class HistoryAdapter :
    ListAdapter<PhoneRecord, HistoryVH>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryVH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false)
        return HistoryVH(v)
    }

    override fun onBindViewHolder(holder: HistoryVH, position: Int) {
        holder.bind(getItem(position))
    }
}

private class HistoryVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
    private val tvPhone = itemView.findViewById<TextView>(R.id.tvPhone)
    private val tvVerdict = itemView.findViewById<TextView>(R.id.tvVerdict)
    private val tvMeta = itemView.findViewById<TextView>(R.id.tvMeta)

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA)

    fun bind(item: PhoneRecord) {
        tvPhone.text = item.phone
        tvVerdict.text = item.verdict
        val ts = fmt.format(Date(item.time))
        tvMeta.text = "신뢰도 ${item.confidence}% · $ts"
        // 색 강조 (선택)
        tvVerdict.setTextColor(
            if (item.verdict.contains("의심")) 0xFFE53935.toInt() else 0xFF1E88E5.toInt()
        )
    }
}
