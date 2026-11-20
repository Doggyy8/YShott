package nl.plantscan.ai

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Generates a real, searchable Android PdfDocument, not an image of a page. */
class ReportWriter(private val context:Context) {
    private val green=Color.rgb(25,79,64)
    private val muted=Color.rgb(91,108,100)
    private val line=Color.rgb(216,228,218)
    private val ink=Color.rgb(35,48,42)
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val width=595
    private val height=842
    private var doc=PdfDocument()
    private lateinit var canvas:Canvas
    private var pageNumber=0
    private var y=0f

    fun write(uri:Uri,store:PlantStore) {
        doc=PdfDocument();pageNumber=0
        try {
            page()
            text("PLANTSCAN AI",42f,32f,green,true)
            y=120f
            text("Planteninventarisatie",42f,24f,ink,true)
            y+=36f
            text(store.projectName,42f,17f,green,true)
            y+=28f
            text("Datum: "+LocalDate.now().format(DateTimeFormatter.ofPattern("dd-MM-yyyy")),42f,12f,muted)
            y+=38f
            rule()
            y+=25f
            val species=store.records.map{it.scientific}.distinct().size
            text("$species voorgestelde soorten",42f,20f,ink,true)
            y+=36f
            val count=store.records.filter{!it.group}.sumOf{it.count}
            text("$count voorlopig geregistreerde exemplaren",42f,14f,ink)
            y+=28f
            text("${store.records.count{it.reviewNeeded()}} herkenningen vragen controle",42f,14f,ink)
            y+=28f
            text("${store.records.count{it.group}} plantengroepen met onbekend aantal",42f,14f,ink)
            y+=44f
            paragraph("Dit rapport bevat AI-voorstellen en door de gebruiker ingevoerde correcties. " +
                "Een herkenningsscore is geen zekerheid. Het automatisch volgen en tellen van afzonderlijke planten uit bewegende video is experimenteel; aantallen moeten in het veld worden gecontroleerd.",42f,500f,12f,18f)
            y+=18f
            text("Soortenoverzicht",42f,16f,green,true)
            y+=24f
            val grouped=store.records.groupBy{it.scientific}
            grouped.toSortedMap().forEach{(name,obs)->
                if(y>750f)page()
                val number=obs.filter{!it.group}.sumOf{it.count}
                val suffix=if(obs.any{it.group}) " + groep(en)" else ""
                paragraph("• $name — $number exemplaren$suffix",48f,500f,11f,16f)
            }
            page()
            text("Waarnemingen en controle",42f,21f,green,true)
            y+=22f
            store.records.forEachIndexed {index, item->
                if(y>470f)page()
                paint.color=line;paint.strokeWidth=1f
                canvas.drawLine(42f,y,553f,y,paint)
                y+=22f
                text("${index+1}. ${item.dutch.ifBlank{item.scientific}}",42f,15f,ink,true)
                y+=22f
                if(item.dutch.isNotBlank()) {
                    text(item.scientific,42f,11f,muted)
                    y+=18f
                }
                val bitmap=try{ BitmapFactory.decodeFile(item.photo)}catch(_:Exception){null}
                if(bitmap!=null) {
                    val ratio=bitmap.width.toFloat()/bitmap.height.toFloat()
                    val dw=if(ratio>=1f)140f else 140f*ratio
                    val dh=if(ratio>=1f)140f/ratio else 140f
                    canvas.drawBitmap(bitmap,null,RectF(42f,y,42f+dw,y+dh),paint)
                    y+=dh+14f
                    bitmap.recycle()
                }
                text("Aantal: "+if(item.group) "plantengroep (aantal onbekend)" else item.count.toString(),42f,11f,ink)
                y+=17f
                text("Videotijd: "+item.samples.distinct().take(8).joinToString(", "){formatTime(it)},42f,11f,ink)
                y+=17f
                text("Herkenning: "+if(item.confirmed) "handmatig bevestigd" else "AI-voorstel (niet bevestigd)",42f,11f,ink)
                y+=17f
                text("Modelscore: "+"%.0f%%".format(item.confidence*100f),42f,11f,muted)
                y+=17f
                if(item.notes.isNotBlank()) {
                    paragraph("Opmerking: "+item.notes,42f,500f,11f,15f)
                }
                y+=26f
            }
            if(y>625f)page()
            rule();y+=22f
            paragraph("Techniek: Iris 9 (brunopaiva15, CC BY 4.0) voor offline soortvoorstellen. " +
                "Vegetatiegebieden worden met een eenvoudige kleurheuristiek voorgesteld; dit is geen gevalideerde individuele plantendetector. " +
                "Online diensten worden niet vereist voor deze PDF.",42f,500f,10f,15f)
            finish()
            context.contentResolver.openOutputStream(uri)?.use {doc.writeTo(it)}
                ?:error("Kan het gekozen PDF-bestand niet openen")
        } finally{doc.close()}
    }
    private fun page() {
        if(pageNumber>0)finish()
        pageNumber++
        currentPage=doc.startPage(PdfDocument.PageInfo.Builder(width,height,pageNumber).create())
        canvas=currentPage.canvas
        canvas.drawColor(Color.WHITE)
        y=58f
        text("PlantScan AI · Conceptinventarisatie",42f,10f,muted)
        y+=30f
    }
    private fun finish() {
        text("PlantScan AI  •  pagina $pageNumber",42f,9f,muted,baseline=814f)
        doc.finishPage(docPage())
    }
    private fun docPage():PdfDocument.Page = currentPage
    private lateinit var currentPage:PdfDocument.Page
    // Set via [page] to ensure the correct page is finished.
    private fun text(value:String,x:Float,size:Float,color:Int,bold:Boolean=false,baseline:Float=y) {
        paint.reset();paint.isAntiAlias=true;paint.color=color;paint.textSize=size
        paint.typeface=if(bold)android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.BOLD)
            else android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.NORMAL)
        canvas.drawText(value.take(95),x,baseline,paint)
    }
    private fun rule() {
        paint.color=line; paint.strokeWidth=1.5f
        canvas.drawLine(42f,y,553f,y,paint)
    }
    private fun paragraph(value:String,x:Float,maxWidth:Float,size:Float,lineHeight:Float) {
        paint.textSize=size;paint.typeface=android.graphics.Typeface.DEFAULT
        val words=value.replace('\n',' ').split(' ')
        var buffer=""
        for(word in words) {
            val proposed=if(buffer.isEmpty())word else "$buffer $word"
            if(paint.measureText(proposed)>maxWidth && buffer.isNotBlank()) {
                if(y>765f)page()
                text(buffer,x,size,ink);y+=lineHeight;buffer=word
            } else buffer=proposed
        }
        if(buffer.isNotEmpty()) {if(y>765f)page();text(buffer,x,size,ink);y+=lineHeight}
    }
}
