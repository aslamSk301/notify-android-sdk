package com.notifymvp.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.notifymvp.sdk.NotifyMVP
import com.notifymvp.sdk.NotifyResult
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val notifList = mutableListOf<NotifEvent>()
    private lateinit var adapter: NotifAdapter

    // Android 13+ notification permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Toast.makeText(this, "Notification permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Permission denied — notifications won't appear", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Notification tap routing — reads data.url from FCM Intent extras
        NotifyMVP.handleIntent(intent)

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Setup RecyclerView
        adapter = NotifAdapter(notifList)
        findViewById<RecyclerView>(R.id.recyclerView).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        // Status display
        updateStatusUI()

        // Re-register button
        findViewById<MaterialButton>(R.id.btnReRegister).setOnClickListener {
            lifecycleScope.launch {
                val result = NotifyMVP.register()
                updateStatusUI()
                val msg = when (result) {
                    is NotifyResult.Success -> "Re-registered ✓"
                    is NotifyResult.Failure -> "Failed: ${result.error}"
                }
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }

        // Observe foreground messages
        lifecycleScope.launch {
            NotifyEventBus.events.collect { event ->
                notifList.add(0, event)
                adapter.notifyItemInserted(0)
                Toast.makeText(
                    this@MainActivity,
                    "🔔 ${event.title}: ${event.body}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        NotifyMVP.handleIntent(intent)
    }

    private fun updateStatusUI() {
        val token = NotifyMVP.fcmToken
        val statusText = if (NotifyMVP.isInitialized) "Registered ✓" else "Not registered"

        findViewById<MaterialTextView>(R.id.tvStatus).text = statusText
        findViewById<MaterialTextView>(R.id.tvToken).text =
            if (token != null) "${token.take(16)}…${token.takeLast(8)}" else "—"
        findViewById<MaterialTextView>(R.id.tvAppId).text =
            NotifyMVP.activeConfig?.appId ?: "—"
    }
}

// ── Simple RecyclerView adapter ───────────────────────────────────────────────
class NotifAdapter(private val items: List<NotifEvent>) :
    RecyclerView.Adapter<NotifAdapter.VH>() {

    private val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    inner class VH(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        val tvTitle: MaterialTextView = itemView.findViewById(R.id.tvTitle)
        val tvBody:  MaterialTextView = itemView.findViewById(R.id.tvBody)
        val tvTime:  MaterialTextView = itemView.findViewById(R.id.tvTime)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val v = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_notification, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.tvTitle.text = item.title
        holder.tvBody.text  = item.body
        holder.tvTime.text  = fmt.format(Date(item.time))
    }

    override fun getItemCount() = items.size
}
