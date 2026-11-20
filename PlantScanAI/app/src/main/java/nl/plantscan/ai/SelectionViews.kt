package nl.plantscan.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** The user explicitly crops a plant from a paused video, useful for mixed beds. */
class CropSelectionView(context: Context,private val bitmap:Bitmap):View(context) {
    private val pen=Paint(Paint.ANTI_ALIAS_FLAG)
    private val area=RectF()
    var selection=RectF(0.2f,0.2f,0.8f,0.8f)
        private set
    private var downX=0f;private var downY=0f
    override fun onDraw(c:Canvas) {
        super.onDraw(c)
        val scale=min(width.toFloat()/bitmap.width,height.toFloat()/bitmap.height)
        val w=bitmap.width*scale;val h=bitmap.height*scale
        area.set((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2)
        c.drawColor(Color.rgb(23,31,27))
        pen.color=Color.WHITE;pen.style=Paint.Style.FILL
        c.drawBitmap(bitmap,null,area,pen)
        pen.color=Color.rgb(178,225,87);pen.strokeWidth=5f;pen.style=Paint.Style.STROKE
        c.drawRoundRect(RectF(area.left+selection.left*area.width(),area.top+selection.top*area.height(),
            area.left+selection.right*area.width(),area.top+selection.bottom*area.height()),10f,10f,pen)
        pen.style=Paint.Style.FILL
    }
    override fun onTouchEvent(e:MotionEvent):Boolean {
        val x=((e.x-area.left)/max(1f,area.width())).coerceIn(0f,1f)
        val y=((e.y-area.top)/max(1f,area.height())).coerceIn(0f,1f)
        when(e.action) {
            MotionEvent.ACTION_DOWN -> {downX=x;downY=y;return true}
            MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP -> {
                selection=RectF(min(downX,x),min(downY,y),max(downX,x),max(downY,y))
                invalidate();return true
            }
        }
        return true
    }
    fun validSelection():Boolean = selection.width()>0.03f && selection.height()>0.03f
}

/** Frame overlay renders detections at their recorded times; rectangles are suggestions. */
class VideoMarksView(context:Context,private val onTap:(PlantObservation)->Unit):View(context) {
    var plants:List<PlantObservation> = emptyList()
    var videoTime:Long=0L
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c:Canvas) {
        super.onDraw(c)
        visible().forEach {o->
            paint.style=Paint.Style.STROKE
            paint.strokeWidth=4f
            paint.color=if(o.confirmed)Color.rgb(129,222,93) else Color.rgb(255,202,85)
            val r=RectF(o.rect.left*width,o.rect.top*height,o.rect.right*width,o.rect.bottom*height)
            c.drawRoundRect(r,7f,7f,paint)
        }
        paint.style=Paint.Style.FILL
    }
    private fun visible():List<PlantObservation> = plants.filter{
        it.samples.any{ms->kotlin.math.abs(ms-videoTime)<5000L}
    }
    override fun onTouchEvent(e:MotionEvent):Boolean {
        if(e.action==MotionEvent.ACTION_UP) {
            val x=e.x/width;val y=e.y/height
            visible().reversed().firstOrNull{it.rect.contains(x,y)}?.let{onTap(it)}
            performClick();return true
        }
        return true
    }
    override fun performClick():Boolean {super.performClick();return true}
}
