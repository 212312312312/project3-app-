package com.taxiapp.client

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.taxiapp.client.network.dto.ChatMessageDto

class ChatAdapter(private val messages: MutableList<ChatMessageDto>) :
    RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    class ChatViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val layoutIn: LinearLayout = view.findViewById(R.id.layout_msg_in)
        val tvTextIn: TextView = view.findViewById(R.id.tv_msg_in_text)

        val layoutOut: LinearLayout = view.findViewById(R.id.layout_msg_out)
        val tvTextOut: TextView = view.findViewById(R.id.tv_msg_out_text)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_message, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val msg = messages[position]

        if (msg.senderRole == "CLIENT") {
            holder.layoutOut.visibility = View.VISIBLE
            holder.layoutIn.visibility = View.GONE
            holder.tvTextOut.text = msg.content
        } else {
            holder.layoutIn.visibility = View.VISIBLE
            holder.layoutOut.visibility = View.GONE
            holder.tvTextIn.text = msg.content
        }
    }

    override fun getItemCount() = messages.size

    /**
     * Додає повідомлення з захистом від дублікатів (якщо воно одночасно прийшло по REST та WebSocket).
     */
    fun addMessage(msg: ChatMessageDto): Boolean {
        if (msg.id != null && messages.any { it.id == msg.id }) {
            return false
        }
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
        return true
    }

    fun updateMessages(newMessages: List<ChatMessageDto>): Boolean {
        val currentLastId = messages.lastOrNull()?.id
        val newLastId = newMessages.lastOrNull()?.id

        if (messages.size != newMessages.size || currentLastId != newLastId) {
            val oldList = ArrayList(messages)
            val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = oldList.size
                override fun getNewListSize(): Int = newMessages.size

                override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                    val oldItem = oldList[oldPos]
                    val newItem = newMessages[newPos]
                    return if (oldItem.id != null && newItem.id != null) {
                        oldItem.id == newItem.id
                    } else {
                        oldItem.createdAt == newItem.createdAt && oldItem.content == newItem.content
                    }
                }

                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                    return oldList[oldPos] == newMessages[newPos]
                }
            })

            messages.clear()
            messages.addAll(newMessages)
            diffResult.dispatchUpdatesTo(this)
            return true
        }
        return false
    }
}