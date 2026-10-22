package com.linghit.gdmusic

import android.app.DatePickerDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Calendar
import java.util.UUID

/**
 * 提醒页：列表 + 分类 tab + 新建/编辑/删除 + 节日一键添加 + 高铁票派生提醒。
 * 弹窗配色注意：AlertDialog 继承 Activity 浅色主题，深色底上必须用 0xDE000000 字。
 */
class ReminderActivity : AppCompatActivity() {

    private data class Tab(val key: String, val label: String)

    private val tabs = listOf(
        Tab("all", "全部"),
        Tab(ReminderStore.TYPE_BIRTHDAY, "🎂 生日"),
        Tab(ReminderStore.TYPE_TRAIN, "🚄 车票"),
        Tab(ReminderStore.TYPE_FESTIVAL, "🧨 节日"),
        Tab(ReminderStore.TYPE_CUSTOM, "⏰ 自定义")
    )
    private var curTab = "all"
    private lateinit var tabsContainer: LinearLayout
    private lateinit var adapter: RAdapter
    private lateinit var emptyText: TextView
    private val rows = mutableListOf<ReminderStore.Item>()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        ThemePrefs.applyTheme(this)
        setContentView(R.layout.activity_reminder)

        tabsContainer = findViewById(R.id.reminderTabs)
        emptyText = findViewById(R.id.emptyText)
        adapter = RAdapter(
            onClick = { showEditDialog(it) },
            onToggle = { item, on -> item.enabled = on; ReminderStore.upsert(this, item); ReminderScheduler.onChanged(this) },
            onLongClick = { confirmDelete(it) }
        )
        findViewById<RecyclerView>(R.id.reminderList).apply {
            layoutManager = LinearLayoutManager(this@ReminderActivity)
            adapter = this@ReminderActivity.adapter
        }
        findViewById<TextView>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<TextView>(R.id.addBtn).setOnClickListener { showEditDialog(null) }
        findViewById<TextView>(R.id.addFestivalBtn).setOnClickListener { showFestivalDialog() }
        findViewById<TextView>(R.id.addTrainBtn).setOnClickListener { showTrainDialog() }
        renderTabs()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---------- 列表 ----------

    private fun renderTabs() {
        tabsContainer.removeAllViews()
        tabs.forEach { t ->
            val tv = TextView(this).apply {
                text = t.label
                textSize = 14f
                gravity = Gravity.CENTER
                val padH = (14 * resources.displayMetrics.density).toInt()
                val padV = (7 * resources.displayMetrics.density).toInt()
                setPadding(padH, padV, padH, padV)
                val sel = t.key == curTab
                setTextColor(if (sel) 0xDE000000.toInt() else 0x8A000000.toInt())
                setBackgroundColor(if (sel) 0x2E000000.toInt() else 0x14000000)
                isAllCaps = false
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = (8 * resources.displayMetrics.density).toInt()
                layoutParams = lp
                setOnClickListener { curTab = t.key; renderTabs(); refresh() }
            }
            tabsContainer.addView(tv)
        }
    }

    private fun refresh() {
        val all = ReminderStore.list(this)
        rows.clear()
        rows.addAll(
            all.filter { curTab == "all" || it.type == curTab }
                .map { it to nextOcc(it) }
                .sortedBy { it.second?.second ?: Long.MAX_VALUE }
                .map { it.first }
        )
        adapter.submit(rows)
        emptyText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    /** 下一个触发点：Pair(yyyy-MM-dd, 0点毫秒)；无（已过期的仅一次提醒）返回 null */
    private fun nextOcc(item: ReminderStore.Item): Pair<String, Long>? {
        val occ = ReminderScheduler.occurrences(this, item).firstOrNull() ?: return null
        val key = occ.first
        val disp = "${key.substring(0, 4)}-${key.substring(4, 6)}-${key.substring(6, 8)}"
        return disp to occ.second
    }

    private inner class RAdapter(
        val onClick: (ReminderStore.Item) -> Unit,
        val onToggle: (ReminderStore.Item, Boolean) -> Unit,
        val onLongClick: (ReminderStore.Item) -> Unit
    ) : RecyclerView.Adapter<RH>() {
        private val data = mutableListOf<ReminderStore.Item>()
        fun submit(l: List<ReminderStore.Item>) {
            data.clear(); data.addAll(l); notifyDataSetChanged()
        }
        override fun onCreateViewHolder(p: ViewGroup, t: Int): RH {
            val row = LinearLayout(p.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val pad = (14 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setBackgroundColor(0x14000000)
            }
            return RH(row, onClick, onToggle, onLongClick)
        }
        override fun onBindViewHolder(h: RH, i: Int) = h.bind(data[i])
        override fun getItemCount() = data.size
    }

    private inner class RH(
        row: View,
        val onClick: (ReminderStore.Item) -> Unit,
        val onToggle: (ReminderStore.Item, Boolean) -> Unit,
        val onLongClick: (ReminderStore.Item) -> Unit
    ) : RecyclerView.ViewHolder(row) {
        fun bind(item: ReminderStore.Item) {
            val ctx = itemView.context
            val row = itemView as LinearLayout
            row.removeAllViews()
            val d = resources.displayMetrics.density
            val emoji = TextView(ctx).apply {
                text = item.emoji
                textSize = 26f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams((44 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val title = TextView(ctx).apply {
                text = item.title
                textSize = 15f
                setTextColor(0xDE000000.toInt())
                maxLines = 1
            }
            val sub = TextView(ctx).apply {
                textSize = 13f
                setTextColor(0x8A000000.toInt())
                val occ = nextOcc(item)
                text = if (occ == null) {
                    "已过期"
                } else {
                    val days = ((occ.second - today0()) / 86_400_000L).toInt()
                    val left = when {
                        days < 0 -> "已过期"
                        days == 0 -> "就是今天"
                        else -> "还有 $days 天"
                    }
                    "${occ.first} · $left"
                }
            }
            box.addView(title); box.addView(sub)
            if (item.note.isNotBlank()) {
                box.addView(TextView(ctx).apply {
                    text = item.note
                    textSize = 12f
                    setTextColor(0x66000000)
                    maxLines = 2
                })
            }
            val sw = android.widget.Switch(ctx).apply {
                isChecked = item.enabled
                setOnCheckedChangeListener { _, on -> onToggle(item, on) }
            }
            row.addView(emoji); row.addView(box); row.addView(sw)
            row.setOnClickListener { onClick(item) }
            row.setOnLongClickListener { onLongClick(item); true }
            val lp = row.layoutParams as RecyclerView.LayoutParams
            lp.bottomMargin = (10 * d).toInt()
            row.layoutParams = lp
        }
    }

    private fun today0(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun confirmDelete(item: ReminderStore.Item) {
        AlertDialog.Builder(this)
            .setTitle("删除提醒")
            .setMessage("确定删除「${item.title}」吗？")
            .setPositiveButton("删除") { _, _ ->
                ReminderStore.remove(this, item.id)
                ReminderScheduler.onChanged(this)
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- 新建/编辑 ----------

    private fun showEditDialog(existing: ReminderStore.Item?) {
        val isFest = existing?.type == ReminderStore.TYPE_FESTIVAL
        val d = resources.displayMetrics.density
        val pad = (18 * d).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val titleIn = EditText(this).apply {
            setText(existing?.title ?: "")
            hint = "标题，如：妈妈生日"
            setTextColor(0xDE000000.toInt())
            setHintTextColor(0x66000000)
            setSingleLine()
        }
        box.addView(label("标题")); box.addView(titleIn)

        val cal = Calendar.getInstance().apply {
            // 先把当前年月日取出来再 clear()——clear() 后字段归零，直接 get(YEAR) 会得到 0（1970）
            val nowY = get(Calendar.YEAR); val nowM = get(Calendar.MONTH); val nowD = get(Calendar.DAY_OF_MONTH)
            clear()
            if (existing != null) { set(existing.year.takeIf { it > 0 } ?: nowY, existing.month - 1, existing.day) }
            else set(nowY, nowM, nowD, 0, 0, 0)
        }
        val dateBtn = TextView(this).apply {
            textSize = 14f
            setTextColor(0xDE000000.toInt())
            setBackgroundColor(0x14000000)
            val p = (10 * d).toInt(); setPadding(p, p, p, p)
            gravity = Gravity.CENTER_VERTICAL
            text = "📅 ${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH) + 1}-${cal.get(Calendar.DAY_OF_MONTH)}（点此修改）"
            if (!isFest) setOnClickListener {
                DatePickerDialog(this@ReminderActivity, { _, y, m, dd ->
                    cal.clear(); cal.set(y, m, dd, 0, 0, 0)
                    text = "📅 $y-${m + 1}-$dd（点此修改）"
                }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
            }
        }
        box.addView(label("日期")); box.addView(dateBtn)
        if (isFest) box.addView(TextView(this).apply {
            text = "农历节日日期由内置农历表自动计算，无需手改"
            textSize = 12f; setTextColor(0x66000000)
        })

        val advs = (existing?.advances ?: listOf(0, 1)).toMutableSet()
        val advLabels = listOf(0 to "当天", 1 to "提前1天", 7 to "提前7天", 30 to "提前30天")
        val advChips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        advLabels.forEach { (v, name) ->
            advChips.addView(chip(name, advs.contains(v)) { on ->
                if (on) advs.add(v) else advs.remove(v)
            })
        }
        box.addView(label("提前提醒（可多选）")); box.addView(advChips)

        var repeat = existing?.repeat ?: ReminderStore.REPEAT_YEARLY
        val repChips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            ReminderStore.REPEAT_NONE to "仅一次",
            ReminderStore.REPEAT_YEARLY to "每年",
            ReminderStore.REPEAT_MONTHLY to "每月"
        ).forEach { (v, name) ->
            repChips.addView(chip(name, repeat == v) { on ->
                if (on) { repeat = v; renderRepeat(repChips, repeat) }
            })
        }
        box.addView(label("重复")); box.addView(repChips)

        val hourIn = EditText(this).apply {
            setText(String.format("%02d:%02d", existing?.hour ?: 9, existing?.minute ?: 0))
            hint = "09:00"
            inputType = InputType.TYPE_CLASS_DATETIME
            setTextColor(0xDE000000.toInt()); setHintTextColor(0x66000000)
        }
        box.addView(label("提醒时间")); box.addView(hourIn)

        val noteIn = EditText(this).apply {
            setText(existing?.note ?: "")
            hint = "备注（可选）"
            setTextColor(0xDE000000.toInt()); setHintTextColor(0x66000000)
        }
        box.addView(label("备注")); box.addView(noteIn)

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "新建提醒" else "编辑提醒")
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val t = titleIn.text.toString().trim().ifBlank { "提醒" }
                val hm = hourIn.text.toString().trim().split(":")
                val h = hm.getOrNull(0)?.trim()?.toIntOrNull() ?: 9
                val m = hm.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
                val year = if (repeat == ReminderStore.REPEAT_NONE && !isFest) cal.get(Calendar.YEAR) else 0
                val item = ReminderStore.Item(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    type = if (isFest) ReminderStore.TYPE_FESTIVAL else typeOf(repeat),
                    title = t,
                    emoji = existing?.emoji ?: if (repeat == ReminderStore.REPEAT_NONE) "⏰" else "🎂",
                    month = cal.get(Calendar.MONTH) + 1,
                    day = cal.get(Calendar.DAY_OF_MONTH),
                    repeat = if (isFest) ReminderStore.REPEAT_YEARLY else repeat,
                    advances = if (advs.isEmpty()) listOf(0) else advs.sortedDescending(),
                    hour = h.coerceIn(0, 23), minute = m.coerceIn(0, 59),
                    note = noteIn.text.toString().trim(),
                    festKey = existing?.festKey ?: "",
                    year = year,
                    enabled = existing?.enabled ?: true,
                    createdAt = existing?.createdAt ?: System.currentTimeMillis()
                )
                ReminderStore.upsert(this, item)
                ReminderScheduler.onChanged(this)
                refresh()
                Toast.makeText(this, "已保存，到点会弹通知", Toast.LENGTH_SHORT).show()
            }
        if (existing != null) dialog.setNeutralButton("删除") { _, _ ->
            ReminderStore.remove(this, existing.id)
            ReminderScheduler.onChanged(this)
            refresh()
        }
        dialog.show()
    }

    private fun typeOf(repeat: String) = when (repeat) {
        ReminderStore.REPEAT_NONE -> ReminderStore.TYPE_CUSTOM
        ReminderStore.REPEAT_MONTHLY -> ReminderStore.TYPE_CUSTOM
        else -> ReminderStore.TYPE_BIRTHDAY
    }

    private fun renderRepeat(container: LinearLayout, cur: String) {
        for (i in 0 until container.childCount) {
            val c = container.getChildAt(i) as TextView
            val v = c.tag as? String ?: continue
            val sel = v == cur
            c.setTextColor(if (sel) 0xDE000000.toInt() else 0x8A000000.toInt())
            c.setBackgroundColor(if (sel) 0x2E000000.toInt() else 0x14000000)
        }
    }

    private fun label(t: String) = TextView(this).apply {
        text = t; textSize = 12f; setTextColor(0x66000000)
        val p = (4 * resources.displayMetrics.density).toInt()
        setPadding(0, p, 0, p / 2)
    }

    private fun chip(t: String, sel: Boolean, onChange: (Boolean) -> Unit): TextView {
        val tv = TextView(this).apply {
            text = t; textSize = 13f; gravity = Gravity.CENTER
            isAllCaps = false
            val p = (9 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, p / 2)
            setTextColor(if (sel) 0xDE000000.toInt() else 0x8A000000.toInt())
            setBackgroundColor(if (sel) 0x2E000000.toInt() else 0x14000000)
            tag = t
            setOnClickListener {
                val nowSel = tag == "✓ $t"
                tag = if (nowSel) t else "✓ $t"
                setTextColor(if (!nowSel) 0xDE000000.toInt() else 0x8A000000.toInt())
                setBackgroundColor(if (!nowSel) 0x2E000000.toInt() else 0x14000000)
                onChange(!nowSel)
            }
        }
        return tv
    }

    // ---------- 节日一键添加 ----------

    private fun showFestivalDialog() {
        val keys = listOf("SPRING", "CHU_XI", "YUAN_XIAO", "DUAN_WU", "QI_XI", "ZHONG_YUAN", "ZHONG_QIU", "CHONG_YANG", "LA_BA", "QING_MING")
        val existing = ReminderStore.list(this).filter { it.type == ReminderStore.TYPE_FESTIVAL }
            .map { it.festKey }.toSet()
        val names = keys.map { k ->
            val name = FestivalTable.NAMES[k] ?: k
            val emoji = FestivalTable.EMOJIS[k] ?: "🧨"
            val probe = ReminderStore.Item(
                id = "probe", type = ReminderStore.TYPE_FESTIVAL, title = name,
                emoji = emoji, month = 1, day = 1, festKey = k
            )
            val occ = ReminderScheduler.occurrences(this, probe).firstOrNull()
            val disp = occ?.first?.let { "${it.substring(0, 4)}-${it.substring(4, 6)}-${it.substring(6, 8)}" } ?: "—"
            "$emoji $name（$disp）"
        }.toTypedArray()
        val checked = BooleanArray(keys.size) { keys[it] in existing }
        AlertDialog.Builder(this)
            .setTitle("一键添加农历节日")
            .setMultiChoiceItems(names, checked) { _, which, on -> checked[which] = on }
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                keys.forEachIndexed { i, k ->
                    val has = existing.contains(k)
                    if (checked[i] && !has) {
                        val name = FestivalTable.NAMES[k] ?: k
                        val emoji = FestivalTable.EMOJIS[k] ?: "🧨"
                        val probe = ReminderStore.Item(
                            id = "p", type = ReminderStore.TYPE_FESTIVAL, title = name,
                            emoji = emoji, month = 1, day = 1, festKey = k
                        )
                        val occ = ReminderScheduler.occurrences(this, probe).firstOrNull()
                        ReminderStore.upsert(this, ReminderStore.Item(
                            id = UUID.randomUUID().toString(),
                            type = ReminderStore.TYPE_FESTIVAL,
                            title = name, emoji = emoji,
                            month = occ?.first?.substring(4, 6)?.toIntOrNull() ?: 1,
                            day = occ?.first?.substring(6, 8)?.toIntOrNull() ?: 1,
                            repeat = ReminderStore.REPEAT_YEARLY,
                            advances = listOf(1, 0),
                            hour = 9, minute = 0,
                            note = "农历节日，日期每年自动重算",
                            festKey = k
                        ))
                    } else if (!checked[i] && has) {
                        ReminderStore.remove(this, ReminderStore.list(this)
                            .first { it.type == ReminderStore.TYPE_FESTIVAL && it.festKey == k }.id)
                    }
                }
                ReminderScheduler.onChanged(this)
                refresh()
            }
            .show()
    }

    // ---------- 高铁票派生 ----------

    private fun showTrainDialog() {
        val d = resources.displayMetrics.density
        val pad = (18 * d).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 15) }
        val dateBtn = TextView(this).apply {
            textSize = 14f; setTextColor(0xDE000000.toInt())
            setBackgroundColor(0x14000000)
            val p = (10 * d).toInt(); setPadding(p, p, p, p)
            text = "📅 出发日期：${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH) + 1}-${cal.get(Calendar.DAY_OF_MONTH)}（点此修改）"
            setOnClickListener {
                DatePickerDialog(this@ReminderActivity, { _, y, m, dd ->
                    cal.clear(); cal.set(y, m, dd, 0, 0, 0)
                    text = "📅 出发日期：$y-${m + 1}-$dd（点此修改）"
                }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
            }
        }
        val fromIn = EditText(this).apply {
            hint = "出发地，如：上海"; setSingleLine()
            setTextColor(0xDE000000.toInt()); setHintTextColor(0x66000000)
        }
        val toIn = EditText(this).apply {
            hint = "到达地，如：杭州"; setSingleLine()
            setTextColor(0xDE000000.toInt()); setHintTextColor(0x66000000)
        }
        box.addView(dateBtn)
        val p2 = (6 * d).toInt()
        box.addView(fromIn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = p2 })
        box.addView(toIn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = p2 })
        box.addView(TextView(this).apply {
            text = "将自动生成 3 条提醒：放票日（提前 15 天）该抢票、出发前 1 天、出发当天早上"
            textSize = 12f; setTextColor(0x66000000)
        })
        AlertDialog.Builder(this)
            .setTitle("🚄 添加高铁票提醒")
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("生成") { _, _ ->
                val n = ReminderStore.addTrain(
                    this,
                    cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
                    fromIn.text.toString().trim(), toIn.text.toString().trim()
                )
                ReminderScheduler.onChanged(this)
                refresh()
                Toast.makeText(this, "已生成 $n 条提醒", Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
