package com.lperezmo.voicerelay

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Settings > About > Open-source licenses: the app's LICENSE and NOTICE, the
 * third-party notices, and the full license texts they point to. The build copies
 * those files from the repository root into assets/licenses/ (see licenseAssets in
 * app/build.gradle.kts), so the app always shows the same text as the repository.
 *
 * Without an extra it lists the documents; with [EXTRA_FILE] it shows one. Some are
 * hundreds of kilobytes, so a document is shown in a ListView of paragraph chunks
 * rather than one huge TextView.
 */
class LicensesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val file = intent.getStringExtra(EXTRA_FILE)
        if (file != null && file in documentFiles()) showDocument(file) else showIndex()
    }

    private fun documentFiles(): List<String> = assets.list(DIR)?.toList().orEmpty()

    private fun showIndex() {
        val present = documentFiles()
        val known = KNOWN.filter { it.file in present }
        val extra = present.filter { f -> KNOWN.none { it.file == f } }.sorted().map { Doc(it, it, "") }

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(32))
        }
        list.addView(title("Open-source licenses"))
        list.addView(TextView(this).apply {
            text = "Voice Relay is open source under the Apache License 2.0, and it includes the " +
                "third-party software listed in the notices. Tap a document to read it."
            textSize = 14f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(dp(24), 0, dp(24), dp(16))
        })
        for (doc in known + extra) {
            list.addView(row(doc.title, doc.subtitle) {
                startActivity(Intent(this, LicensesActivity::class.java).putExtra(EXTRA_FILE, doc.file))
            })
        }
        setContentView(android.widget.ScrollView(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(attrColor(android.R.attr.colorBackground))
            addView(list)
        })
    }

    private fun showDocument(file: String) {
        val text = try {
            assets.open("$DIR/$file").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            "Could not open $file."
        }
        val chunks = chunk(text.replace("\r\n", "\n").trimStart('﻿'))
        val heading = KNOWN.firstOrNull { it.file == file }?.title ?: file
        val list = ListView(this).apply {
            fitsSystemWindows = true
            clipToPadding = false
            divider = null
            setBackgroundColor(attrColor(android.R.attr.colorBackground))
            addHeaderView(title(heading), null, false)
            adapter = ChunkAdapter(chunks)
        }
        setContentView(list)
    }

    private inner class ChunkAdapter(private val chunks: List<String>) : BaseAdapter() {
        override fun getCount() = chunks.size
        override fun getItem(position: Int) = chunks[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun isEnabled(position: Int) = false
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = (convertView as? TextView) ?: TextView(this@LicensesActivity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 12f
                setTextColor(attrColor(android.R.attr.textColorPrimary))
                setPadding(dp(16), 0, dp(16), 0)
            }
            view.text = chunks[position]
            return view
        }
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 28f
        setPadding(dp(24), dp(32), dp(24), dp(16))
    }

    private fun row(title: String, subtitle: String, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(14), dp(24), dp(14))
        isClickable = true
        isFocusable = true
        val outValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        setBackgroundResource(outValue.resourceId)
        setOnClickListener { onClick() }
        addView(TextView(context).apply {
            text = title
            textSize = 18f
            setTextColor(attrColor(android.R.attr.textColorPrimary))
        })
        if (subtitle.isNotEmpty()) addView(TextView(context).apply {
            text = subtitle
            textSize = 14f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(0, dp(2), 0, 0)
        })
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun attrColor(attr: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, 0)
        ta.recycle()
        return color
    }

    private data class Doc(val file: String, val title: String, val subtitle: String)

    companion object {
        const val EXTRA_FILE = "file"
        private const val DIR = "licenses"

        private val KNOWN = listOf(
            Doc("THIRD_PARTY_NOTICES.md", "Third-party notices", "What the app includes, and under which licenses"),
            Doc("LICENSE", "Apache License 2.0", "Voice Relay, and most of the software it includes"),
            Doc("NOTICE", "NOTICE", "Voice Relay is based on Phone Whisper by kafkasl"),
            Doc("onnxruntime-ThirdPartyNotices.txt", "ONNX Runtime third-party notices", "Inside the wake word library"),
            Doc("MPL-2.0.txt", "Mozilla Public License 2.0", "Eigen, and the Public Suffix List in OkHttp"),
            Doc("kaldifst-LICENSE.txt", "kaldifst license", "Inside the wake word library"),
        )

        /**
         * Splits a document into list items of whole paragraphs, each about [target]
         * characters, keeping the blank lines between paragraphs.
         */
        fun chunk(text: String, target: Int = 2000): List<String> {
            val out = mutableListOf<String>()
            val current = StringBuilder()
            for (paragraph in text.split("\n\n")) {
                if (current.isNotEmpty() && current.length + paragraph.length > target) {
                    out += current.toString()
                    current.clear()
                }
                if (current.isNotEmpty()) current.append("\n\n")
                current.append(paragraph)
            }
            if (current.isNotEmpty()) out += current.toString()
            // Keep the paragraph break between list items visible.
            return out.mapIndexed { i, s -> if (i < out.lastIndex) s + "\n" else s }
        }
    }
}
