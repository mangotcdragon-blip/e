package com.customautocorrect.keyboard

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class RulesAdapter(
    private val onRemove: (Rule) -> Unit
) : RecyclerView.Adapter<RulesAdapter.ViewHolder>() {

    private val items = mutableListOf<Rule>()

    fun submitList(newItems: List<Rule>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val fromText: TextView = view.findViewById(R.id.fromText)
        val toText: TextView = view.findViewById(R.id.toText)
        val removeBtn: ImageButton = view.findViewById(R.id.removeBtn)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_rule, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val rule = items[position]
        holder.fromText.text = rule.from
        holder.toText.text = rule.to
        holder.removeBtn.setOnClickListener { onRemove(rule) }
    }

    override fun getItemCount() = items.size
}
