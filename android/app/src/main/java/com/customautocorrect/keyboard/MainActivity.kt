package com.customautocorrect.keyboard

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import com.customautocorrect.keyboard.databinding.ActivityMainBinding
import org.json.JSONException

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: RulesAdapter
    private var rules: List<Rule> = emptyList()

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let { writeRulesToUri(it) }
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { readRulesFromUri(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = RulesAdapter { rule -> removeRule(rule) }
        binding.ruleList.layoutManager = LinearLayoutManager(this)
        binding.ruleList.adapter = adapter

        rules = DictionaryStore.loadRules(this)
        render()

        binding.addBtn.setOnClickListener { addRuleFromInputs() }
        binding.exportBtn.setOnClickListener { exportLauncher.launch("autocorrect-dictionary.json") }
        binding.importBtn.setOnClickListener { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) }
        binding.clearBtn.setOnClickListener { clearAll() }
        binding.enableKeyboardBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        binding.switchKeyboardBtn.setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }
    }

    override fun onResume() {
        super.onResume()
        rules = DictionaryStore.loadRules(this)
        render()
    }

    private fun addRuleFromInputs() {
        val from = binding.fromInput.text.toString().trim()
        val to = binding.toInput.text.toString().trim()
        if (from.isEmpty() || to.isEmpty()) return
        rules = DictionaryStore.upsert(rules, Rule(from, to))
        persist()
        binding.fromInput.text?.clear()
        binding.toInput.text?.clear()
        binding.fromInput.requestFocus()
    }

    private fun removeRule(rule: Rule) {
        rules = rules.filterNot { it.from.equals(rule.from, ignoreCase = true) }
        persist()
    }

    private fun clearAll() {
        if (rules.isEmpty()) return
        AlertDialog.Builder(this)
            .setMessage("Remove all custom words? This cannot be undone.")
            .setPositiveButton("Remove") { _, _ ->
                rules = emptyList()
                persist()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun writeRulesToUri(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(DictionaryStore.toJson(rules).toByteArray())
            }
            Toast.makeText(this, "Exported ${rules.size} words", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun readRulesFromUri(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: throw JSONException("Could not read file")
            val imported = DictionaryStore.parseJson(text)
            if (imported.isEmpty()) throw JSONException("No valid rules found in file")
            rules = DictionaryStore.mergeAll(rules, imported)
            persist()
            Toast.makeText(this, "Imported ${imported.size} words", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun persist() {
        DictionaryStore.saveRules(this, rules)
        render()
    }

    private fun render() {
        adapter.submitList(rules)
        binding.emptyHint.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
    }
}
