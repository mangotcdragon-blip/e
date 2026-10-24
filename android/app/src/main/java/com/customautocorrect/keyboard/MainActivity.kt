package com.customautocorrect.keyboard

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import com.customautocorrect.keyboard.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PAGE_SIZE = 200
        private const val SEARCH_DEBOUNCE_MS = 300L
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: RulesAdapter
    private val mainHandler = Handler(Looper.getMainLooper())
    private var searchDebounce: Runnable? = null

    private var currentSearch = ""
    private var loadedCount = 0

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let { runExport(it) }
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { runImport(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = RulesAdapter { rule -> removeRule(rule) }
        binding.ruleList.layoutManager = LinearLayoutManager(this)
        binding.ruleList.adapter = adapter

        binding.addBtn.setOnClickListener { addRuleFromInputs() }
        binding.exportBtn.setOnClickListener { exportLauncher.launch("autocorrect-dictionary.json") }
        binding.importBtn.setOnClickListener { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) }
        binding.clearBtn.setOnClickListener { clearAll() }
        binding.loadMoreBtn.setOnClickListener { loadMore() }
        binding.enableKeyboardBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        binding.switchKeyboardBtn.setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchDebounce?.let { mainHandler.removeCallbacks(it) }
                val r = Runnable {
                    currentSearch = s?.toString().orEmpty()
                    loadFirstPage()
                }
                searchDebounce = r
                mainHandler.postDelayed(r, SEARCH_DEBOUNCE_MS)
            }
        })

        loadFirstPage()
    }

    override fun onResume() {
        super.onResume()
        loadFirstPage()
    }

    private fun loadFirstPage() {
        val items = DictionaryStore.page(this, 0, PAGE_SIZE, currentSearch)
        loadedCount = items.size
        adapter.submitList(items)
        render()
    }

    private fun loadMore() {
        val more = DictionaryStore.page(this, loadedCount, PAGE_SIZE, currentSearch)
        loadedCount += more.size
        adapter.appendList(more)
        render()
    }

    private fun render() {
        val total = DictionaryStore.count(this, currentSearch)
        binding.emptyHint.visibility = if (total == 0) View.VISIBLE else View.GONE
        binding.countText.text = if (currentSearch.isBlank()) {
            "$total word${if (total == 1) "" else "s"} total"
        } else {
            "$total match${if (total == 1) "" else "es"} for \"$currentSearch\""
        }
        binding.loadMoreBtn.visibility = if (loadedCount < total) View.VISIBLE else View.GONE
    }

    private fun addRuleFromInputs() {
        val from = binding.fromInput.text.toString().trim()
        val to = binding.toInput.text.toString().trim()
        if (from.isEmpty() || to.isEmpty()) return
        DictionaryStore.upsert(this, from, to)
        binding.fromInput.text?.clear()
        binding.toInput.text?.clear()
        binding.fromInput.requestFocus()
        loadFirstPage()
    }

    private fun removeRule(rule: Rule) {
        DictionaryStore.remove(this, rule.from)
        loadFirstPage()
    }

    private fun clearAll() {
        if (DictionaryStore.count(this) == 0) return
        AlertDialog.Builder(this)
            .setMessage("Remove all custom words? This cannot be undone.")
            .setPositiveButton("Remove") { _, _ ->
                DictionaryStore.clear(this)
                loadFirstPage()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setBusy(busy: Boolean, message: String) {
        binding.ioStatusText.visibility = if (busy) View.VISIBLE else View.GONE
        binding.ioStatusText.text = message
        binding.exportBtn.isEnabled = !busy
        binding.importBtn.isEnabled = !busy
        binding.clearBtn.isEnabled = !busy
    }

    private fun runExport(uri: Uri) {
        setBusy(true, "Exporting…")
        Thread {
            var error: String? = null
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    DictionaryStore.exportStream(this, out)
                } ?: throw IllegalStateException("Could not open destination file")
            } catch (e: Exception) {
                error = e.message
            }
            mainHandler.post {
                setBusy(false, "")
                if (error != null) {
                    Toast.makeText(this, "Export failed: $error", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Export complete", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun runImport(uri: Uri) {
        setBusy(true, "Importing…")
        Thread {
            var imported = 0
            var error: String? = null
            try {
                val stream = contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Could not read file")
                stream.use { imported = DictionaryStore.importStream(this, it) }
            } catch (e: Exception) {
                error = e.message
            }
            mainHandler.post {
                setBusy(false, "")
                if (error != null) {
                    Toast.makeText(this, "Import failed: $error", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Imported $imported words", Toast.LENGTH_SHORT).show()
                    binding.searchInput.text?.clear()
                    currentSearch = ""
                    loadFirstPage()
                }
            }
        }.start()
    }
}
