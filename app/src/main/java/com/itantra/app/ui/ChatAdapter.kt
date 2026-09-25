package com.itantra.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.itantra.app.R

/**
 * RecyclerView adapter for chat-style conversation bubbles.
 * Two view types: outgoing (right, teal) and incoming (left, amber).
 */
class ChatAdapter(
    private val onReplayClick: (ChatMessage) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_OUTGOING = 0
        private const val TYPE_INCOMING = 1
    }

    private val messages = mutableListOf<ChatMessage>()

    fun addMessage(message: ChatMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun getMessages(): List<ChatMessage> = messages.toList()

    override fun getItemViewType(position: Int): Int {
        return when (messages[position].senderType) {
            ChatMessage.SenderType.YOU -> TYPE_OUTGOING
            ChatMessage.SenderType.PEER -> TYPE_INCOMING
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_OUTGOING -> {
                val view = inflater.inflate(R.layout.item_message_outgoing, parent, false)
                OutgoingViewHolder(view)
            }
            else -> {
                val view = inflater.inflate(R.layout.item_message_incoming, parent, false)
                IncomingViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]
        when (holder) {
            is OutgoingViewHolder -> holder.bind(message)
            is IncomingViewHolder -> holder.bind(message, onReplayClick)
        }
    }

    override fun getItemCount(): Int = messages.size

    class OutgoingViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvSender: TextView = view.findViewById(R.id.tvSenderLabel)
        private val tvText: TextView = view.findViewById(R.id.tvMessageText)
        private val tvTranslation: TextView = view.findViewById(R.id.tvTranslationText)
        private val ivCheck: ImageView = view.findViewById(R.id.ivSentCheck)

        fun bind(message: ChatMessage) {
            tvSender.text = itemView.context.getString(R.string.chat_you)
            tvText.text = message.text
            ivCheck.visibility = View.VISIBLE

            if (!message.translatedText.isNullOrBlank()) {
                val target = message.targetLanguage?.uppercase() ?: "TRANSLATED"
                tvTranslation.text = "↳ [$target]: ${message.translatedText}"
                tvTranslation.visibility = View.VISIBLE
            } else {
                tvTranslation.visibility = View.GONE
            }
        }
    }

    class IncomingViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvSender: TextView = view.findViewById(R.id.tvSenderLabel)
        private val tvText: TextView = view.findViewById(R.id.tvMessageText)
        private val tvTranslation: TextView = view.findViewById(R.id.tvTranslationText)
        private val btnReplay: ImageButton = view.findViewById(R.id.btnReplay)

        fun bind(message: ChatMessage, onReplayClick: (ChatMessage) -> Unit) {
            tvSender.text = message.senderName.ifBlank { "Peer" }
            tvText.text = message.text

            if (!message.translatedText.isNullOrBlank()) {
                val target = message.targetLanguage?.uppercase() ?: "TRANSLATED"
                tvTranslation.text = "↳ [$target]: ${message.translatedText}"
                tvTranslation.visibility = View.VISIBLE
            } else {
                tvTranslation.visibility = View.GONE
            }

            btnReplay.setOnClickListener { onReplayClick(message) }
        }
    }
}
