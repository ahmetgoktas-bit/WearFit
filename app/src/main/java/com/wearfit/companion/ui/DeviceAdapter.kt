package com.wearfit.companion.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wearfit.companion.databinding.ItemDeviceBinding
import com.wearfit.companion.model.BleDeviceItem

class DeviceAdapter(
    private val onConnectClick: (BleDeviceItem) -> Unit
) : ListAdapter<BleDeviceItem, DeviceAdapter.ViewHolder>(DiffCallback) {

    inner class ViewHolder(private val binding: ItemDeviceBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: BleDeviceItem) {
            binding.tvDeviceName.text = item.name
            binding.tvDeviceAddress.text = item.address
            binding.tvRssi.text = "${item.rssi} dBm"

            binding.btnConnect.setOnClickListener {
                onConnectClick(item)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDeviceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    object DiffCallback : DiffUtil.ItemCallback<BleDeviceItem>() {
        override fun areItemsTheSame(oldItem: BleDeviceItem, newItem: BleDeviceItem): Boolean {
            return oldItem.address == newItem.address
        }

        override fun areContentsTheSame(oldItem: BleDeviceItem, newItem: BleDeviceItem): Boolean {
            return oldItem == newItem
        }
    }
}
