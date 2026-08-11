package com.amr3d.preview.pro

import android.app.Dialog
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

private data class CustomUnit(
    var length: Double = 600.0,
    var width: Double = 400.0,
    var quantity: Int = 1,
    var horizontal: Boolean = true
)
private data class UiStep(val title: String, val body: LinearLayout, val next: Button)

class NestingFragment : Fragment() {
    private lateinit var rootLayout: LinearLayout
    private lateinit var preview: NestingPreviewView
    private lateinit var progress: ProgressBar
    private lateinit var progressPercent: TextView
    private lateinit var progressPanel: LinearLayout
    private lateinit var cancelButton: Button
    private lateinit var resultText: TextView
    private lateinit var sourceText: TextView
    private lateinit var boardWEdit: EditText
    private lateinit var boardHEdit: EditText
    private lateinit var copiesEdit: EditText
    private lateinit var clearanceEdit: EditText
    private lateinit var rotationEdit: EditText
    private lateinit var toolEdit: EditText
    private lateinit var processSpinner: Spinner
    private lateinit var boardPresetSpinner: Spinner
    private lateinit var runButton: Button
    private lateinit var unitsContainer: LinearLayout
    private lateinit var typeSpinner: Spinner
    private lateinit var grainSpinner: Spinner
    private lateinit var reviewText: TextView
    private lateinit var chooseButton: Button
    private lateinit var progressStage: TextView
    // إصلاح: صفوف خاصة بخانات "DXF فقط" (خطوة دوران DXF / اتجاه الخامة / عدد النسخ)
    // لازم تختفي تلقائياً لو المستخدم اختار "خزائن / وحدات مخصصة" فقط، لأن اتجاه
    // كل وحدة بيتحدد مسبقاً في خطوة 03 (أفقي/رأسي لكل وحدة على حدة).
    private lateinit var rotationRow: View
    private lateinit var grainRow: View
    private lateinit var copiesRow: View

    private val units = mutableListOf<CustomUnit>()
    private val steps = mutableListOf<UiStep>()
    private var boardColor = 0xFF0D0F14.toInt()
    private var currentShape: NestingPolygon? = null
    private var lastResult: NestingResult? = null
    private var engineJob: Job? = null
    private val cancelled = AtomicBoolean(false)
    private var activeStep = 0

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { loadSelectedDxf(it) }
    }

    override fun onCreateView(inflater: android.view.LayoutInflater, container: android.view.ViewGroup?, savedInstanceState: Bundle?): View {
        rootLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.background_dark))
        }
        rootLayout.addView(toolbar())
        rootLayout.addView(previewSection(), LinearLayout.LayoutParams(-1, dp(300)))
        rootLayout.addView(settingsSection(), LinearLayout.LayoutParams(-1, 0, 1f))
        loadSessionIfAvailable()
        return rootLayout
    }

    private fun toolbar(): View = LinearLayout(requireContext()).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setBackgroundColor(color(R.color.surface_dark))
        addView(TextView(context).apply {
            text = "‹"; textSize = 34f; setTextColor(color(R.color.accent_orange))
            setOnClickListener { (activity as? MainActivity)?.closeNesting() }
        }, LinearLayout.LayoutParams(dp(42), dp(48)))
        addView(TextView(context).apply {
            text = "Nesting Pro"; textSize = 20f; setTextColor(color(R.color.text_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(context).apply {
            text = "⛶"; textSize = 26f; gravity = Gravity.CENTER; setTextColor(color(R.color.accent_orange))
            setOnClickListener { openFullscreenPreview() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
    }

    private fun previewSection(): View {
        val frame = FrameLayout(requireContext()).apply { setBackgroundColor(color(R.color.background_dark)) }
        preview = NestingPreviewView(requireContext())
        frame.addView(preview, FrameLayout.LayoutParams(-1, -1))
        val hint = TextView(requireContext()).apply {
            text = "مرّر على البنود بالترتيب ثم راجع المعاينة قبل تنفيذ الرص"
            textSize = 12f; gravity = Gravity.CENTER; setTextColor(color(R.color.text_secondary)); setPadding(dp(10))
        }
        frame.addView(hint, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        progressPanel = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(18))
            setBackgroundColor(0xD90A0C11.toInt()); visibility = View.GONE
        }
        progressPercent = TextView(requireContext()).apply {
            text = "0%"; textSize = 34f; gravity = Gravity.CENTER; setTextColor(color(R.color.text_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        progressPanel.addView(progressPercent, LinearLayout.LayoutParams(-1, dp(52)))
        progressStage = TextView(requireContext()).apply {
            text = "جاري الرص"; textSize = 14f; gravity = Gravity.CENTER; setTextColor(color(R.color.text_secondary))
        }
        progressPanel.addView(progressStage, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(6)) })
        progress = ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressTintList = android.content.res.ColorStateList.valueOf(color(R.color.accent_orange))
        }
        progressPanel.addView(progress, LinearLayout.LayoutParams(-1, dp(16)).apply { setMargins(0, dp(6), 0, dp(12)) })
        cancelButton = button("إلغاء") { cancelEngine() }
        progressPanel.addView(cancelButton)
        frame.addView(progressPanel, FrameLayout.LayoutParams(dp(330), -2, Gravity.CENTER))
        return frame
    }

    private fun settingsSection(): View {
        val scroll = ScrollView(requireContext())
        val box = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12)) }

        addStep(box, "01  نوع الإدخال") { body, next ->
            typeSpinner = Spinner(requireContext()).apply {
                adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item,
                    listOf("DXF / تصميم", "خزائن / وحدات مخصصة", "DXF + خزائن / وحدات مخصصة"))
            }
            body.addView(labeled("مصدر القطع", typeSpinner))
            sourceText = label("لم يتم اختيار ملف DXF")
            body.addView(sourceText)
            chooseButton = button("اختيار ملف DXF") {
                if (currentShape != null) {
                    // إصلاح: بدل ما يفتح منتقي الملفات فوراً ويستبدل الملف الحالي
                    // بلا تحذير، نسأل تأكيد الأول لأن في ملف محدد فعلاً.
                    androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setTitle("إعادة تعيين الملف؟")
                        .setMessage("سيتم حذف ملف الـ DXF المحدد حالياً (${NestingSession.sourceName.ifBlank { "ملف" }}). هل تريد المتابعة؟")
                        .setPositiveButton("نعم") { d, _ -> d.dismiss(); resetSelectedDxf() }
                        .setNegativeButton("لا") { d, _ -> d.dismiss() }
                        .show()
                } else {
                    filePicker.launch(arrayOf("application/dxf", "application/octet-stream", "*/*"))
                }
            }
            body.addView(chooseButton)
            typeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    updateSourceUi()
                    applyTypeVisibility()
                }
            }
            next.setOnClickListener {
                val needsDxf = typeSpinner.selectedItemPosition != 1
                if (!needsDxf || currentShape != null) openStep(1) else toast("اختَر ملف DXF أولاً")
            }
        }

        addStep(box, "02  مقاس اللوح") { body, next ->
            boardPresetSpinner = Spinner(requireContext()).apply {
                adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item,
                    listOf("1220 × 2440", "1220 × 3050", "1830 × 3660", "مخصص"))
            }
            boardWEdit = numberField("1220")
            boardHEdit = numberField("2440")
            boardPresetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    when (position) {
                        0 -> { boardWEdit.setText("1220"); boardHEdit.setText("2440") }
                        1 -> { boardWEdit.setText("1220"); boardHEdit.setText("3050") }
                        2 -> { boardWEdit.setText("1830"); boardHEdit.setText("3660") }
                    }
                    val custom = position == 3
                    boardWEdit.isEnabled = custom; boardHEdit.isEnabled = custom
                }
            }
            body.addView(labeled("مقاس اللوح", boardPresetSpinner))
            body.addView(labeled("الطول / العرض (mm)", boardWEdit))
            body.addView(labeled("العرض / الارتفاع (mm)", boardHEdit))
            next.setOnClickListener { if (boardWEdit.value() > 0 && boardHEdit.value() > 0) openStep(2) else toast("أدخل مقاس اللوح بشكل صحيح") }
        }

        addStep(box, "03  الوحدات / الخزائن") { body, next ->
            unitsContainer = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            body.addView(unitsContainer)
            addUnitRow()
            body.addView(button("＋ إضافة وحدة أخرى") { addUnitRow() })
            body.addView(label("لكل وحدة: طول + عرض + كمية + اتجاه أفقي/رأسي. يمكن إضافة أي عدد من الوحدات.").apply { setPadding(0, dp(8), 0, 0) })
            next.setOnClickListener {
                syncAllUnits()
                val needsUnits = typeSpinner.selectedItemPosition != 0
                if (!needsUnits || units.all { it.length > 0 && it.width > 0 && it.quantity > 0 }) openStep(3)
                else toast("راجع مقاسات الوحدات والكميات")
            }
        }

        addStep(box, "04  ماكينة + هامش الرص") { body, next ->
            processSpinner = Spinner(requireContext()).apply {
                adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, listOf("CNC", "Laser"))
            }
            toolEdit = numberField("6")
            clearanceEdit = numberField("6")
            body.addView(labeled("نوع الماكينة", processSpinner))
            body.addView(labeled("قطر الأداة CNC (mm)", toolEdit))
            body.addView(labeled("الهامش الموحد (mm)", clearanceEdit))
            body.addView(label("الهامش خانة واحدة فقط وتُطبَّق تلقائياً على الجهات الأربع في CNC والليزر."))
            processSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    toolEdit.visibility = if (position == 0) View.VISIBLE else View.GONE
                }
            }
            next.setOnClickListener { if (clearanceEdit.value() >= 0) openStep(4) else toast("أدخل الهامش") }
        }

        addStep(box, "05  اتجاه وطريقة الرص") { body, next ->
            rotationEdit = numberField("15")
            grainSpinner = Spinner(requireContext()).apply {
                adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, listOf("حر", "أفقي", "رأسي"))
            }
            copiesEdit = numberField("10")
            copiesRow = labeled("عدد نسخ DXF", copiesEdit)
            grainRow = labeled("اتجاه الخامة / Grain", grainSpinner)
            rotationRow = labeled("خطوة دوران DXF (درجة)", rotationEdit)
            body.addView(copiesRow)
            body.addView(grainRow)
            body.addView(rotationRow)
            body.addView(label("الخزائن لا تدور بحرية: اتجاه كل وحدة يحدد من خانة أفقي / رأسي الخاصة بها."))
            next.setOnClickListener { openStep(5) }
            applyTypeVisibility()
        }

        addStep(box, "06  مراجعة نهائية") { body, next ->
            reviewText = label("").apply { textSize = 14f; setTextColor(color(R.color.text_primary)); setPadding(dp(8)) }
            body.addView(reviewText)
            next.text = "✓ تأكيد الإعدادات"
            next.setOnClickListener { updateReview(); openStep(6) }
        }

        addStep(box, "07  تنفيذ الرص") { body, next ->
            body.addView(label("كل البنود السابقة مكتملة. اضغط لبدء محرك الرص."))
            runButton = button("ابدأ Nesting") { startEngine() }
            body.addView(runButton)
            resultText = label("لم يتم تنفيذ الرص بعد")
            body.addView(resultText)
            next.visibility = View.GONE
        }

        scroll.addView(box)
        openStep(0)
        return scroll
    }

    private fun addStep(parent: LinearLayout, title: String, build: (LinearLayout, Button) -> Unit) {
        val body = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(dp(4), 0, dp(4), dp(8))
        }
        val next = button("التالي  →") {}
        val header = TextView(requireContext()).apply {
            text = title; textSize = 15f; setTextColor(color(R.color.accent_orange))
            setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, dp(14), 0, dp(10)); isClickable = true
        }
        header.setOnClickListener {
            val idx = steps.indexOfFirst { it.body === body }
            if (idx in 0..activeStep) openStep(idx)
        }
        parent.addView(header)
        parent.addView(body)
        parent.addView(next, LinearLayout.LayoutParams(-1, dp(44)))
        val step = UiStep(title, body, next)
        steps.add(step)
        build(body, next)
    }

    private fun openStep(index: Int) {
        activeStep = maxOf(activeStep, index)
        steps.forEachIndexed { i, s ->
            s.body.visibility = if (i == index) View.VISIBLE else View.GONE
            s.next.visibility = if (i == index && i < steps.lastIndex) View.VISIBLE else View.GONE
        }
        if (index == 5) updateReview()
    }

    /**
     * إصلاح: خانات "خطوة دوران DXF" و"اتجاه الخامة/Grain" و"عدد نسخ DXF" (خطوة 05)
     * كانت دايماً ظاهرة حتى لو المستخدم اختار "خزائن / وحدات مخصصة" فقط — رغم إن
     * النص التوضيحي جنبها بيقول صراحةً إن اتجاه كل خزانة بيتحدد مسبقاً في خطوة 03.
     * الدالة دي بتُخفي الخانات دي تلقائياً في وضع الخزائن فقط، وتُظهرها في وضع
     * DXF أو الوضع المختلط (لأنها فعلاً مؤثرة هناك).
     *
     * وكمان: لو فيه ملف DXF محمّل بالفعل (من العارض أو باختيار يدوي) والمستخدم
     * لسه واقف على "خزائن / وحدات مخصصة فقط" — نرجّعه تلقائياً لوضع DXF، لأن
     * وجود ملف جاهز يعني إن نية المستخدم غالباً رص الملف ده، مش تجاهله بالكامل.
     * القرار قابل للتراجع: المستخدم لسه يقدر يختار "خزائن فقط" يدوياً بعد كده.
     */
    private fun applyTypeVisibility() {
        if (::typeSpinner.isInitialized && currentShape != null && typeSpinner.selectedItemPosition == 1) {
            typeSpinner.setSelection(0)
            return // onItemSelected هينادي applyTypeVisibility تاني بالقيمة الجديدة
        }
        if (!::rotationRow.isInitialized || !::grainRow.isInitialized || !::copiesRow.isInitialized) return
        val cabinetsOnly = typeSpinner.selectedItemPosition == 1
        val vis = if (cabinetsOnly) View.GONE else View.VISIBLE
        rotationRow.visibility = vis
        grainRow.visibility = vis
        copiesRow.visibility = vis
    }

    /** إصلاح: تحديث نص/ظهور زر اختيار الملف ونص الحالة سوياً من مكان واحد،
     * بدل تكرار المنطق في كل نقطة بتتغيّر فيها الحالة (كان زر "اختيار ملف"
     * بيفضل بنفس النص القديم حتى بعد اختيار ملف فعلاً من العارض). */
    private fun updateSourceUi() {
        val cabinetsOnly = ::typeSpinner.isInitialized && typeSpinner.selectedItemPosition == 1
        chooseButton.visibility = if (cabinetsOnly) View.GONE else View.VISIBLE
        sourceText.visibility = if (cabinetsOnly) View.GONE else View.VISIBLE
        if (cabinetsOnly) {
            sourceText.text = "سيتم إنشاء القطع من المقاسات المخصصة"
            return
        }
        if (currentShape != null) {
            sourceText.text = "✓ DXF جاهز: ${NestingSession.sourceName.ifBlank { "ملف" }}"
            chooseButton.text = "تم اختيار: ${NestingSession.sourceName.ifBlank { "ملف" }} — إعادة تعيين"
        } else {
            sourceText.text = "لم يتم اختيار ملف DXF"
            chooseButton.text = "اختيار ملف DXF"
        }
    }

    /** إصلاح: مسح الملف المختار حالياً (بعد تأكيد المستخدم) وإرجاع الحالة
     * لوضعها الافتراضي، بما فيها تصفير NestingSession العالمية. */
    private fun resetSelectedDxf() {
        currentShape = null
        NestingSession.clear()
        updateSourceUi()
        applyTypeVisibility()
    }

    private fun addUnitRow() {
        val unit = CustomUnit()
        units += unit
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(8)); setBackgroundResource(R.drawable.bg_settings_row)
        }
        val title = label("وحدة ${units.size}").apply { setTextColor(color(R.color.accent_orange)); setTypeface(typeface, android.graphics.Typeface.BOLD) }
        val len = numberField("600")
        val wid = numberField("400")
        val qty = numberField("1")
        val orient = Spinner(requireContext()).apply {
            adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, listOf("أفقي", "رأسي"))
        }
        fun sync() {
            unit.length = len.value()
            unit.width = wid.value()
            unit.quantity = qty.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1
            unit.horizontal = orient.selectedItemPosition == 0
        }
        listOf(len, wid, qty).forEach { e -> e.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) sync() } }
        orient.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = sync()
        }
        card.addView(title)
        card.addView(labeled("الطول (mm)", len))
        card.addView(labeled("العرض (mm)", wid))
        card.addView(labeled("الكمية", qty))
        card.addView(labeled("الاتجاه", orient))
        card.addView(button("حذف الوحدة") { units.remove(unit); unitsContainer.removeView(card); refreshUnitTitles() })
        unitsContainer.addView(card)
    }

    private fun syncAllUnits() {
        for (i in units.indices) {
            val card = unitsContainer.getChildAt(i) as? LinearLayout ?: continue
            val len = (card.getChildAt(1) as? LinearLayout)?.getChildAt(1) as? EditText
            val wid = (card.getChildAt(2) as? LinearLayout)?.getChildAt(1) as? EditText
            val qty = (card.getChildAt(3) as? LinearLayout)?.getChildAt(1) as? EditText
            val orient = (card.getChildAt(4) as? LinearLayout)?.getChildAt(1) as? Spinner
            units[i].length = len?.value() ?: units[i].length
            units[i].width = wid?.value() ?: units[i].width
            units[i].quantity = qty?.text?.toString()?.toIntOrNull()?.coerceAtLeast(1) ?: units[i].quantity
            units[i].horizontal = orient?.selectedItemPosition == 0
        }
    }

    private fun refreshUnitTitles() {
        for (i in 0 until unitsContainer.childCount) {
            val c = unitsContainer.getChildAt(i) as? LinearLayout ?: continue
            (c.getChildAt(0) as? TextView)?.text = "وحدة ${i + 1}"
        }
    }

    private fun updateReview() {
        syncAllUnits()
        val process = if (processSpinner.selectedItemPosition == 0) "CNC" else "Laser"
        val type = when (typeSpinner.selectedItemPosition) {
            0 -> "DXF / تصميم"
            1 -> "خزائن / وحدات مخصصة"
            else -> "DXF + خزائن / وحدات مخصصة"
        }
        val sb = StringBuilder()
        sb.append("✓ نوع الإدخال: $type\n")
        if (typeSpinner.selectedItemPosition != 1) sb.append("✓ ملف DXF: ${NestingSession.sourceName.ifBlank { "جاهز" }}\n")
        sb.append("✓ مقاس اللوح: ${boardWEdit.value().fmt()} × ${boardHEdit.value().fmt()} mm\n")
        sb.append("✓ الماكينة: $process\n")
        sb.append("✓ الهامش الموحد: ${clearanceEdit.value().fmt()} mm\n")
        if (process == "CNC") sb.append("✓ قطر الأداة: ${toolEdit.value().fmt()} mm\n")
        if (typeSpinner.selectedItemPosition != 0) {
            sb.append("✓ عدد الوحدات: ${units.size}\n")
            units.forEachIndexed { idx, u -> sb.append("  • ${idx + 1}: ${u.length.fmt()} × ${u.width.fmt()} — ${u.quantity} — ${if (u.horizontal) "أفقي" else "رأسي"}\n") }
        }
        if (typeSpinner.selectedItemPosition != 1) sb.append("✓ نسخ DXF: ${copiesEdit.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1}\n")
        reviewText.text = sb.toString()
    }

    private fun loadSessionIfAvailable() {
        val model = NestingSession.model ?: return
        sourceText.text = "جاري تجهيز DXF من العارض…"
        lifecycleScope.launch(Dispatchers.Default) {
            val shape = NestingShapeBuilder.fromModel(model)
            withContext(Dispatchers.Main) {
                currentShape = shape
                updateSourceUi()
                applyTypeVisibility()
            }
        }
    }

    private fun loadSelectedDxf(uri: Uri) {
        sourceText.text = "جاري تحميل DXF…"
        lifecycleScope.launch {
            try {
                val model = withContext(Dispatchers.IO) { DXFParser.parse(requireContext(), uri) }
                NestingSession.model = model
                NestingSession.sourceUri = uri
                NestingSession.sourceName = uri.lastPathSegment ?: "DXF"
                val shape = withContext(Dispatchers.Default) { NestingShapeBuilder.fromModel(model) }
                currentShape = shape
                if (shape == null) sourceText.text = "خطأ: تعذر استخراج شكل مغلق من DXF"
                updateSourceUi()
                applyTypeVisibility()
            } catch (e: Exception) {
                sourceText.text = "خطأ: ${e.message}"
            }
        }
    }

    private fun buildMixedPieces(): List<MixedNestingEngine.InputPiece> {
        syncAllUnits()
        val result = mutableListOf<MixedNestingEngine.InputPiece>()
        val type = typeSpinner.selectedItemPosition
        if (type != 1) {
            val shape = currentShape ?: return emptyList()
            val copies = copiesEdit.text.toString().toIntOrNull()?.coerceIn(1, 10000) ?: 1
            repeat(copies) { result += MixedNestingEngine.InputPiece(shape, RotationMode.FREE, "DXF") }
        }
        if (type != 0) {
            for (u in units) {
                repeat(u.quantity) {
                    val w = if (u.horizontal) u.length else u.width
                    val h = if (u.horizontal) u.width else u.length
                    val rect = NestingPolygon(listOf(
                        NestingPoint(0.0, 0.0), NestingPoint(w, 0.0),
                        NestingPoint(w, h), NestingPoint(0.0, h)
                    ))
                    result += MixedNestingEngine.InputPiece(rect, if (u.horizontal) RotationMode.HORIZONTAL else RotationMode.VERTICAL, "Cabinet")
                }
            }
        }
        return result
    }

    private fun stageLabel(phase: NestingPhase): String = when (phase) {
        NestingPhase.PLACING -> "جاري الرص…"
        NestingPhase.OPTIMIZING -> "جاري الحصول على أفضل توفير…"
        NestingPhase.ABSORBING -> "جاري تقليل عدد الألواح…"
        NestingPhase.PREPARING_PREVIEW -> "جاري تجهيز المعاينة…"
    }

    private fun onEngineProgress(p: NestingProgress) {
        lifecycleScope.launch(Dispatchers.Main) {
            progress.progress = p.percent
            progressPercent.text = "${p.percent}%"
            progressStage.text = stageLabel(p.phase)
        }
    }

    private fun startEngine() {
        if (engineJob?.isActive == true) return
        syncAllUnits()
        val bw = boardWEdit.value().coerceAtLeast(1.0)
        val bh = boardHEdit.value().coerceAtLeast(1.0)
        val margin = clearanceEdit.value().coerceAtLeast(0.0)
        val type = typeSpinner.selectedItemPosition
        val mixed = type == 2

        cancelled.set(false)
        progress.progress = 0
        progressPercent.text = "0%"
        progressStage.text = stageLabel(NestingPhase.PLACING)
        progressPanel.visibility = View.VISIBLE
        runButton.isEnabled = false
        resultText.text = "جاري تنفيذ الرص…"

        engineJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                if (mixed) {
                    MixedNestingEngine.nest(
                        buildMixedPieces(), bw, bh, margin,
                        rotationEdit.value().coerceIn(1.0, 90.0),
                        onProgress = { p -> onEngineProgress(p) },
                        isCancelled = { cancelled.get() }, boardColor = boardColor
                    )
                } else if (type == 1) {
                    MixedNestingEngine.nest(
                        buildMixedPieces(), bw, bh, margin,
                        90.0,
                        onProgress = { p -> onEngineProgress(p) },
                        isCancelled = { cancelled.get() }, boardColor = boardColor
                    )
                } else {
                    val shape = currentShape ?: return@withContext NestingResult(emptyList(), 1, 0, 0.0, 0.0, 0.0, 0)
                    NestingEngine.nest(
                        shape,
                        NestingConfig(
                            boardWidth = bw, boardHeight = bh,
                            copies = copiesEdit.text.toString().toIntOrNull()?.coerceIn(1, 10000) ?: 1,
                            rotationStepDeg = rotationEdit.value().coerceIn(1.0, 90.0),
                            rotationMode = RotationMode.FREE,
                            grainAxis = when (grainSpinner.selectedItemPosition) { 1 -> GrainAxis.HORIZONTAL; 2 -> GrainAxis.VERTICAL; else -> GrainAxis.FREE },
                            clearanceMm = margin, boardColor = boardColor,
                            edgeTopMm = margin, edgeBottomMm = margin,
                            edgeLeftMm = margin, edgeRightMm = margin
                        ),
                        onProgress = { p -> onEngineProgress(p) },
                        isCancelled = { cancelled.get() }
                    )
                }
            }
            lastResult = result
            // إصلاح: مرحلة "تجهيز المعاينة" — تعيين النتيجة لـ NestingPreviewView
            // بيستدعي رسم كل القطع على الـ Canvas، وده فعلاً بياخد وقت ملحوظ مع
            // عدد ألواح/قطع كبير، فبنعرض وصف حقيقي للمستخدم بدل ما الشاشة تفضل
            // واقفة على "100%" بلا تفسير.
            progressStage.text = stageLabel(NestingPhase.PREPARING_PREVIEW)
            preview.result = result
            progress.progress = 100
            progressPercent.text = "100%"
            progressPanel.visibility = View.GONE
            runButton.isEnabled = true
            resultText.text = "تم الرص: ${result.totalPlaced}/${result.totalRequested} قطعة | ألواح: ${result.boards.size} | استغلال: ${"%.1f".format(result.utilization)}%"
        }
    }

    private fun cancelEngine() {
        cancelled.set(true); engineJob?.cancel(); engineJob = null
        progressPanel.visibility = View.GONE
        if (::runButton.isInitialized) runButton.isEnabled = true
    }

    private fun openFullscreenPreview() {
        val r = lastResult ?: preview.result ?: return
        val d = Dialog(requireContext())
        val v = NestingPreviewView(requireContext()).apply { result = r; showAllBoards = true }
        d.setContentView(v); d.show(); d.window?.setLayout(-1, -1)
    }

    private fun numberField(value: String) = EditText(requireContext()).apply {
        setText(value); textSize = 14f; setTextColor(color(R.color.text_primary)); setSingleLine(true)
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        setBackgroundResource(R.drawable.bg_toggle_button); setPadding(dp(10))
    }
    private fun EditText.value() = text.toString().replace(",", ".").toDoubleOrNull() ?: 0.0
    private fun Double.fmt() = if (abs(this - roundToInt()) < 0.001) roundToInt().toString() else "%.2f".format(this)
    private fun label(text: String) = TextView(requireContext()).apply { this.text = text; textSize = 13f; setTextColor(color(R.color.text_secondary)) }
    private fun labeled(text: String, v: View) = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(4))
        addView(label(text).apply { setTextColor(color(R.color.accent_orange)); setTypeface(typeface, android.graphics.Typeface.BOLD) })
        addView(v, LinearLayout.LayoutParams(-1, dp(44)))
    }
    private fun button(text: String, click: () -> Unit) = Button(requireContext()).apply {
        this.text = text; setTextColor(Color.BLACK)
        backgroundTintList = android.content.res.ColorStateList.valueOf(color(R.color.accent_orange))
        setOnClickListener { click() }; setPadding(dp(4))
    }
    private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private fun color(id: Int) = requireContext().getColor(id)

    override fun onDestroyView() { cancelled.set(true); engineJob?.cancel(); super.onDestroyView() }
    companion object { fun newInstance() = NestingFragment() }
}
