package com.amr3d.preview.pro

import kotlin.math.*

/**
 * Mixed nesting layer: DXF true-shape pieces + user-defined cabinet/board rectangles.
 * It deliberately keeps the existing NestingEngine untouched for DXF-only jobs.
 */
object MixedNestingEngine {
    data class InputPiece(
        val polygon: NestingPolygon,
        val rotationMode: RotationMode = RotationMode.FREE,
        val label: String = "Piece"
    )

    private data class WorkPiece(val source: InputPiece, val ordinal: Int)
    private data class Placed(val polygon: List<NestingPoint>, val piece: NestingPiece)

    fun nest(
        pieces: List<InputPiece>,
        boardWidth: Double,
        boardHeight: Double,
        margin: Double,
        rotationStepDeg: Double,
        onProgress: (NestingProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
        boardColor: Int = 0xFF0D0F14.toInt()
    ): NestingResult {
        val start = System.currentTimeMillis()
        if (pieces.isEmpty() || boardWidth <= 0 || boardHeight <= 0) {
            return NestingResult(emptyList(), pieces.size, 0, 0.0, 0.0, 0.0, 0)
        }
        val usableW = boardWidth - margin * 2.0
        val usableH = boardHeight - margin * 2.0
        if (usableW <= 0 || usableH <= 0) return NestingResult(emptyList(), pieces.size, 0, 0.0, 0.0, 0.0, 0)

        val work = pieces.mapIndexed { i, p -> WorkPiece(p, i) }.sortedByDescending { area(it.source.polygon.outer).let(::abs) }
        val boards = mutableListOf<NestingBoard>()
        var current = mutableListOf<Placed>()
        var boardIndex = 1
        var globalPlaced = 0
        var nextIndex = 1

        fun finishBoard() {
            if (current.isEmpty()) return
            boards += NestingBoard(boardIndex++, boardWidth, boardHeight, current.map { it.piece }, boardColor)
            current = mutableListOf()
        }

        for ((wi, workPiece) in work.withIndex()) {
            if (isCancelled()) break
            val rotations = rotations(workPiece.source.rotationMode, rotationStepDeg)
            var best: Placed? = null
            var bestScore = Double.POSITIVE_INFINITY

            fun tryOnCurrent(): Boolean {
                for (angle in rotations) {
                    if (isCancelled()) return false
                    val rotated = rotate(workPiece.source.polygon.outer, angle)
                    val b = bounds(rotated)
                    val anchorX = -b.minX
                    val anchorY = -b.minY
                    // إصلاح: الترتيب الصحيح لمعاملات الدالة هو (current, w, h, pw, ph, m)
                    // — كان الاستدعاء القديم يمرر margin مكان عرض القطعة وارتفاع القطعة
                    // مكان الهامش، فيفسد حساب حدود اللوح وخطوة المسح بالكامل.
                    val candidates = candidatePositions(current, usableW, usableH, b.w, b.h, margin)
                    for ((cx, cy) in candidates) {
                        val x = margin + cx + anchorX
                        val y = margin + cy + anchorY
                        val placedPoly = translate(rotated, x, y)
                        if (!inside(placedPoly, boardWidth, boardHeight, margin)) continue
                        if (current.any { tooClose(placedPoly, it.polygon, margin) }) continue
                        val score = score(placedPoly, boardWidth, boardHeight)
                        if (score < bestScore) {
                            bestScore = score
                            // إصلاح: نخزّن الشكل الأصلي (غير المُدوَّر وغير المُنقَّل) في
                            // piece.polygon، بنفس عقد NestingEngine بالظبط — لأن
                            // NestingPreviewView.drawPiece بيطبّق rotation/translation
                            // بنفسه من piece.rotationDeg و piece.x/y. تخزين placedPoly
                            // (المُحوَّل بالفعل) هنا كان يسبب رسم القطعة بتحويل مزدوج
                            // (دوران × 2 وإزاحة × 2) فتظهر مشوّهة وفي مكان خاطئ.
                            best = Placed(
                                placedPoly,
                                NestingPiece(workPiece.ordinal + 1, workPiece.source.polygon, x, y, Math.toDegrees(angle), b.w, b.h)
                            )
                        }
                    }
                }
                return best != null
            }

            if (!tryOnCurrent()) {
                finishBoard()
                best = null
                bestScore = Double.POSITIVE_INFINITY
                if (!tryOnCurrent()) {
                    // إصلاح: القطعة دي فعلاً مستحيل تترص حتى على لوح فاضٍ بالكامل
                    // (أكبر من المساحة المتاحة). كانت الحلقة القديمة بتعمل break هنا
                    // فتوقف رص كل القطع الباقية في القائمة كلها من غير أي داعٍ —
                    // الصح إننا نتخطى القطعة دي بس ونكمّل الباقي.
                    continue
                }
            }

            current += best!!
            globalPlaced++
            nextIndex++
            onProgress(NestingProgress(globalPlaced, work.size, boardIndex, (globalPlaced * 100 / work.size).coerceIn(0, 100), NestingPhase.PLACING))
            if (wi % 2 == 0) Thread.yield()
        }
        finishBoard()

        // إصلاح: sourceArea هنا هي مجموع مساحات كل القطع المطلوبة (مش قطعة
        // واحدة)، فاستخدام الصيغة الافتراضية (sourceArea × totalPlaced) في
        // NestingResult كان يضاعف المساحة بشكل فلكي. نمرر placedArea الحقيقية
        // = مجموع مساحات القطع المرصوصة فعلياً فقط (بحساب مباشر من مضلعاتها
        // الأصلية المخزَّنة في كل NestingPiece، وهي ثابتة القيمة بغض النظر عن
        // الدوران/الإزاحة لأن المساحة لا تتأثر بأي منهما).
        val sourceArea = pieces.sumOf { abs(area(it.polygon.outer)) }
        val placedArea = boards.flatMap { it.pieces }.sumOf { abs(area(it.polygon.outer)) }
        return NestingResult(
            boards = boards,
            totalRequested = pieces.size,
            totalPlaced = globalPlaced,
            sourceWidth = pieces.maxOfOrNull { bounds(it.polygon.outer).w } ?: 0.0,
            sourceHeight = pieces.maxOfOrNull { bounds(it.polygon.outer).h } ?: 0.0,
            sourceArea = sourceArea,
            elapsedMs = System.currentTimeMillis() - start,
            placedArea = placedArea
        )
    }

    private fun rotations(mode: RotationMode, stepDeg: Double): List<Double> {
        val step = stepDeg.coerceIn(1.0, 90.0)
        return when (mode) {
            RotationMode.HORIZONTAL -> listOf(0.0)
            RotationMode.VERTICAL -> listOf(Math.PI / 2.0)
            RotationMode.FREE -> (0..floor(360.0 / step).toInt()).map { Math.toRadians(it * step) }.distinct()
        }
    }

    private data class B(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
        val w get() = maxX - minX
        val h get() = maxY - minY
    }
    private fun bounds(p: List<NestingPoint>): B = B(p.minOf { it.x }, p.minOf { it.y }, p.maxOf { it.x }, p.maxOf { it.y })
    private fun area(p: List<NestingPoint>): Double = p.indices.sumOf { i -> val a=p[i]; val b=p[(i+1)%p.size]; a.x*b.y-b.x*a.y } / 2.0
    private fun rotate(p: List<NestingPoint>, a: Double): List<NestingPoint> { val c=cos(a); val s=sin(a); return p.map { NestingPoint(it.x*c-it.y*s, it.x*s+it.y*c) } }
    private fun translate(p: List<NestingPoint>, x: Double, y: Double) = p.map { NestingPoint(it.x+x,it.y+y) }
    private fun cross(a:NestingPoint,b:NestingPoint,c:NestingPoint)= (b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x)
    private fun onSegment(a:NestingPoint,b:NestingPoint,p:NestingPoint):Boolean = abs(cross(a,b,p))<1e-7 && p.x>=min(a.x,b.x)-1e-7 && p.x<=max(a.x,b.x)+1e-7 && p.y>=min(a.y,b.y)-1e-7 && p.y<=max(a.y,b.y)+1e-7
    private fun segmentsIntersect(a:NestingPoint,b:NestingPoint,c:NestingPoint,d:NestingPoint):Boolean {
        val c1=cross(a,b,c); val c2=cross(a,b,d); val c3=cross(c,d,a); val c4=cross(c,d,b)
        if (((c1>1e-7 && c2<-1e-7)||(c1<-1e-7&&c2>1e-7)) && ((c3>1e-7&&c4<-1e-7)||(c3<-1e-7&&c4>1e-7))) return true
        return (abs(c1)<1e-7&&onSegment(a,b,c))||(abs(c2)<1e-7&&onSegment(a,b,d))||(abs(c3)<1e-7&&onSegment(c,d,a))||(abs(c4)<1e-7&&onSegment(c,d,b))
    }
    private fun pointInPolygon(p:NestingPoint,poly:List<NestingPoint>):Boolean { var inside=false; for(i in poly.indices){ val a=poly[i]; val b=poly[(i+1)%poly.size]; if(onSegment(a,b,p)) return true; val hit=(a.y>p.y)!=(b.y>p.y) && p.x < (b.x-a.x)*(p.y-a.y)/(b.y-a.y)+a.x; if(hit) inside=!inside }; return inside }
    private fun polygonsOverlap(a:List<NestingPoint>,b:List<NestingPoint>):Boolean {
        val ab=bounds(a); val bb=bounds(b); if(ab.maxX<=bb.minX+1e-7||bb.maxX<=ab.minX+1e-7||ab.maxY<=bb.minY+1e-7||bb.maxY<=ab.minY+1e-7) return false
        for(i in a.indices){val a1=a[i];val a2=a[(i+1)%a.size];for(j in b.indices){val b1=b[j];val b2=b[(j+1)%b.size];if(segmentsIntersect(a1,a2,b1,b2))return true}}
        return pointInPolygon(a[0],b)||pointInPolygon(b[0],a)
    }
    // إصلاح: فحص المسافة الفعلية بين قطعتين — تقاطع حقيقي أولاً، وبعدين فحص
    // تقريبي سريع عبر الصناديق المحيطة الموسّعة بمقدار الفجوة المطلوبة. كافٍ
    // ودقيق للمستطيلات (الاستخدام الأساسي لهذا المحرك: خزائن/وحدات مخصصة)،
    // ويمنع رص أي قطعة قريبة جداً من قطعة أخرى مش بس القطعة اللي اتولّد
    // المرشح بناءً عليها.
    private fun tooClose(a: List<NestingPoint>, b: List<NestingPoint>, gap: Double): Boolean {
        if (polygonsOverlap(a, b)) return true
        if (gap <= 1e-9) return false
        val ba = bounds(a); val bb = bounds(b)
        return !(ba.maxX + gap <= bb.minX + 1e-7 || bb.maxX + gap <= ba.minX + 1e-7 ||
                 ba.maxY + gap <= bb.minY + 1e-7 || bb.maxY + gap <= ba.minY + 1e-7)
    }
    private fun inside(p:List<NestingPoint>,w:Double,h:Double,m:Double):Boolean { val b=bounds(p); return b.minX>=m-1e-7&&b.minY>=m-1e-7&&b.maxX<=w-m+1e-7&&b.maxY<=h-m+1e-7 }
    private fun candidatePositions(current: List<Placed>, w: Double, h: Double, pw: Double, ph: Double, gap: Double): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>(128)
        out += 0.0 to 0.0
        for (q in current) {
            val b = bounds(q.polygon)
            // إصلاح: مفيش أي طرح/إلغاء للـ gap هنا — نولّد المرشحات عند حافة
            // القطعة المجاورة + فجوة (gap) كاملة، عشان تفضل مسافة حقيقية بين
            // كل قطعتين متجاورتين بدل ما تترص ملتصقة تماماً.
            out += (b.maxX + gap) to b.minY
            out += b.minX to (b.maxY + gap)
            out += (b.maxX + gap) to (b.maxY + gap)
        }
        val step = max(10.0, min(50.0, min(pw, ph) / 6.0))
        var x = 0.0; var count = 0
        while (x <= w - pw && count < 200) { out += x to 0.0; x += step; count++ }
        x = 0.0; count = 0
        while (x <= w - pw && count < 200) { out += x to max(0.0, h - ph); x += step; count++ }
        return out.distinct()
    }
    private fun score(p:List<NestingPoint>,w:Double,h:Double):Double { val b=bounds(p); return b.maxY*10000.0+b.maxX+abs(area(p))*1e-6 }
}
