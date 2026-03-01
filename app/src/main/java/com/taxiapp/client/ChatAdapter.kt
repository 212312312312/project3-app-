package com.taxiapp.client

import android.view.LayoutInflater
import android.view.View

import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
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
        // Заменили R.id на R.layout
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

    fun addMessage(msg: ChatMessageDto) {
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
    }

    // Заменяем старый setMessages на умный updateMessages
    fun updateMessages(newMessages: List<ChatMessageDto>): Boolean {
        val currentLastId = messages.lastOrNull()?.id
        val newLastId = newMessages.lastOrNull()?.id

        // Проверяем: если изменилось количество или ID последнего сообщения
        if (messages.size != newMessages.size || currentLastId != newLastId) {
            messages.clear()
            messages.addAll(newMessages)
            notifyDataSetChanged()
            return true // Были обновления
        }
        return false // Изменений нет, обновлять UI не нужно
    }
}