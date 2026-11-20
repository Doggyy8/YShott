package nl.plantscan.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Candidate is a model suggestion, not a validated botanical determination. */
data class SpeciesGuess(val name: String, val probability: Float)

data class PlantObservation(
    val id: String = UUID.randomUUID().toString(),
    var scientific: String,
    var dutch: String = "",
    var confidence: Float,
    var alternatives: List<SpeciesGuess> = emptyList(),
    var firstMs: Long,
    var lastMs: Long,
    var samples: MutableList<Long> = mutableListOf(),
    var rect: RectF = RectF(0f, 0f, 1f, 1f),
    var photo: String = "",
    var count: Int = 1,
    var group: Boolean = false,
    var confirmed: Boolean = false,
    var notes: String = "",
    var manual: Boolean = false,
    var sourceType: String = "video"
) {
    fun reviewNeeded(): Boolean = !confirmed && (scientific=="Onbekend" || group || confidence < 0.85f ||
            alternatives.size > 1 && confidence - alternatives[1].probability < 0.25f)
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("scientific", scientific); put("dutch", dutch)
        put("confidence", confidence)
        put("alternatives", JSONArray().apply { alternatives.forEach {
            put(JSONObject().put("name", it.name).put("probability", it.probability))
        } })
        put("firstMs",firstMs);put("lastMs",lastMs)
        put("samples", JSONArray().apply {samples.forEach { put(it) }})
        put("rect", JSONArray().put(rect.left).put(rect.top).put(rect.right).put(rect.bottom))
        put("photo",photo);put("count",count);put("group",group)
        put("confirmed",confirmed);put("notes",notes);put("manual",manual);put("sourceType",sourceType)
    }
    companion object {
        fun fromJson(o:JSONObject):PlantObservation {
            val a=o.optJSONArray("alternatives")?:JSONArray()
            val guesses=(0 until a.length()).map { a.getJSONObject(it) }.map {
                SpeciesGuess(it.optString("name"),it.optDouble("probability").toFloat())
            }
            val ts=o.optJSONArray("samples")?:JSONArray()
            val r=o.optJSONArray("rect")?:JSONArray().put(0).put(0).put(1).put(1)
            return PlantObservation(
                id=o.getString("id"),scientific=o.optString("scientific","Onbekend"),
                dutch=o.optString("dutch"),confidence=o.optDouble("confidence").toFloat(),
                alternatives=guesses,firstMs=o.optLong("firstMs"),lastMs=o.optLong("lastMs"),
                samples=(0 until ts.length()).map{ts.optLong(it)}.toMutableList(),
                rect=RectF(r.optDouble(0).toFloat(),r.optDouble(1).toFloat(),r.optDouble(2).toFloat(),r.optDouble(3).toFloat()),
                photo=o.optString("photo"),count=o.optInt("count",1),group=o.optBoolean("group"),
                confirmed=o.optBoolean("confirmed"),notes=o.optString("notes"),manual=o.optBoolean("manual"),
                sourceType=o.optString("sourceType","video")
            )
        }
    }
}

class PlantStore(private val context: Context) {
    private val file = File(context.filesDir,"last_project.json")
    val snapshots = File(context.filesDir,"snapshots").also {it.mkdirs()}
    var projectName: String = "Mijn tuin"
    var videoUri: String = ""
    val records: MutableList<PlantObservation> = mutableListOf()
    fun save() {
        val j=JSONObject().put("projectName",projectName).put("videoUri",videoUri)
            .put("records",JSONArray().apply {records.forEach{put(it.toJson())}})
        file.writeText(j.toString())
    }
    fun load() {
        if (!file.exists()) return
        try {
            val j=JSONObject(file.readText()); projectName=j.optString("projectName","Mijn tuin")
            videoUri=j.optString("videoUri")
            records.clear();val a=j.optJSONArray("records")?:JSONArray()
            for(i in 0 until a.length()) records.add(PlantObservation.fromJson(a.getJSONObject(i)))
        } catch (_:Exception) { /* corrupted file doesn't crash app */ }
    }
    fun writePhoto(bitmap: Bitmap):String {
        val path=File(snapshots, UUID.randomUUID().toString()+".jpg")
        path.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,82,it) }
        return path.absolutePath
    }
    fun clearForNewVideo(uri:String) {
        videoUri=uri;records.clear();save()
    }
}

/** Iris 9: https://huggingface.co/brunopaiva15/iris (CC BY 4.0). */
class IrisClassifier(context:Context):Closeable {
    private val interpreter:Interpreter
    private val labels: List<String>
    private val names:JSONObject
    private val contextMask:String
    private val data=ByteBuffer.allocateDirect(320*320*3*4).order(ByteOrder.nativeOrder())
    init {
        // Not memory mapped: asset works even if compressed by a custom build.
        val bytes=context.assets.open("plants.tflite").use {it.readBytes()}
        val model=ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        model.put(bytes).rewind()
        interpreter=Interpreter(model,Interpreter.Options().setNumThreads(2))
        labels=context.assets.open("labels.txt").bufferedReader().use {it.readLines().filter(String::isNotBlank)}
        contextMask=context.assets.open("model.json").bufferedReader().use{it.readText()}
        names=JSONObject(contextMask).getJSONObject("species")
        require(labels.size==1569) {"Ongeldige Iris model-labels"}
    }
    @Synchronized fun identify(source:Bitmap):List<SpeciesGuess> = identifyMany(listOf(source))

    /**
     * Average the full 1,569-species distribution across two independently
     * selected views of the SAME plant before ranking. This is more meaningful
     * than voting over truncated top-5 results.
     */
    @Synchronized fun identifyMany(sources:List<Bitmap>):List<SpeciesGuess> {
        require(sources.isNotEmpty())
        val combined=FloatArray(labels.size)
        for(source in sources.take(3)) {
            val scores=score(source)
            for(i in combined.indices)combined[i]+=scores[i]
        }
        val count=sources.size.coerceAtMost(3).toFloat()
        for(i in combined.indices)combined[i]/=count
        // The user films outdoors. Demote clearly indoor-only labels, but
        // never remove a valid garden cultivar completely.
        val outside=JSONObject(contextMask)
        val allowed=outside.optJSONObject("masks")?.optJSONArray("outdoor")
        if(allowed!=null) {
            val namesAllowed=(0 until allowed.length()).map{allowed.optString(it)}.toSet()
            for(i in combined.indices) if(labels[i] !in namesAllowed)combined[i]*=0.25f
        }
        val sum=combined.sum().coerceAtLeast(0.000001f)
        for(i in combined.indices)combined[i]/=sum
        return combined.indices.sortedByDescending{combined[it]}.take(5).map{
            SpeciesGuess(names.optString(labels[it],labels[it]),combined[it])
        }
    }

    private fun score(source:Bitmap):FloatArray {
        // Approximate the original area-average 448 -> 366 -> center 320 recipe.
        val side=min(source.width,source.height)
        val square=Bitmap.createBitmap(source,(source.width-side)/2,(source.height-side)/2,side,side)
        val downsized=if(side>448) Bitmap.createScaledBitmap(square,448,448,true) else square
        val resized=Bitmap.createScaledBitmap(downsized,366,366,true)
        val crop=Bitmap.createBitmap(resized,23,23,320,320)
        val pixels=IntArray(320*320)
        crop.getPixels(pixels,0,320,0,0,320,320)
        data.rewind()
        pixels.forEach {px->
            data.putFloat(Color.red(px).toFloat())
            data.putFloat(Color.green(px).toFloat())
            data.putFloat(Color.blue(px).toFloat())
        }
        data.rewind()
        val result=Array(1) {FloatArray(labels.size)}
        interpreter.run(data,result)
        // TFLite output is softmax; no calibrated real-world certainty.

        if(square!==source) square.recycle()
        if(downsized!==square) downsized.recycle()
        resized.recycle(); crop.recycle()
        return result[0]
    }
    override fun close(){interpreter.close()}
}

object PlantRegions {
    /** Cheap green-vegetation proposals. These are NOT instance-segmentation. */
    fun candidates(bitmap:Bitmap):List<RectF> {
        val w=bitmap.width;val h=bitmap.height
        val gx=20; val gy=20
        val cols=(w+gx-1)/gx; val rows=(h+gy-1)/gy
        val green=BooleanArray(cols*rows)
        val px=IntArray(w*h)
        bitmap.getPixels(px,0,w,0,0,w,h)
        for(y in 0 until rows) for(x in 0 until cols) {
            var seen=0;var total=0
            for(py in y*gy until min(h,(y+1)*gy) step 3)
                for(qx in x*gx until min(w,(x+1)*gx) step 3) {
                    val c=px[py*w+qx]; val r=Color.red(c);val g=Color.green(c);val b=Color.blue(c)
                    if(g>55 && g>r*1.08f && g>b*1.07f && g-max(r,b)>9) seen++
                    total++
                }
            green[y*cols+x]= total>0 && seen>=total*0.17f
        }
        val visited=BooleanArray(green.size)
        val regions=mutableListOf<Pair<Int,RectF>>()
        for(start in green.indices) {
            if(!green[start]||visited[start])continue
            val queue=ArrayDeque<Int>();queue.add(start);visited[start]=true
            var ct=0;var left=cols;var top=rows;var right=0;var bottom=0
            while(queue.isNotEmpty()) {
                val i=queue.removeFirst();val x=i%cols;val y=i/cols
                ct++;left=min(left,x);right=max(right,x);top=min(top,y);bottom=max(bottom,y)
                for((xx,yy) in arrayOf(x-1 to y,x+1 to y,x to y-1,x to y+1)) {
                    if(xx !in 0 until cols||yy !in 0 until rows)continue
                    val j=yy*cols+xx
                    if(green[j]&&!visited[j]){visited[j]=true;queue.add(j)}
                }
            }
            if(ct>=5) {
                val pad=0.05f
                regions.add(ct to RectF(
                    max(0f,left*gx.toFloat()/w-pad),max(0f,top*gy.toFloat()/h-pad),
                    min(1f,(right+1)*gx.toFloat()/w+pad),min(1f,(bottom+1)*gy.toFloat()/h+pad)
                ))
            }
        }
        // A connected hedge or lawn isn't one plant; mark a region provisional.
        val sorted=regions.sortedByDescending {it.first}.take(3).map {it.second}
        return sorted // no full-frame guess when vegetation was not detected
    }
    fun crop(bitmap:Bitmap,r:RectF):Bitmap {
        val x=(r.left*bitmap.width).toInt().coerceIn(0,bitmap.width-1)
        val y=(r.top*bitmap.height).toInt().coerceIn(0,bitmap.height-1)
        val width=((r.right-r.left)*bitmap.width).toInt().coerceAtLeast(1).coerceAtMost(bitmap.width-x)
        val height=((r.bottom-r.top)*bitmap.height).toInt().coerceAtLeast(1).coerceAtMost(bitmap.height-y)
        return Bitmap.createBitmap(bitmap,x,y,width,height)
    }
    fun overlap(a:RectF,b:RectF):Float {
        val l=max(a.left,b.left);val t=max(a.top,b.top)
        val r=min(a.right,b.right);val bot=min(a.bottom,b.bottom)
        if(r<=l||bot<=t)return 0f
        val intersection=(r-l)*(bot-t)
        val union=a.width()*a.height()+b.width()*b.height()-intersection
        return if(union>0)intersection/union else 0f
    }
}

class VideoScanner(private val context:Context,private val store:PlantStore) {
    fun scan(uri:Uri,onProgress:(String)->Unit):List<PlantObservation> {
        val r=MediaMetadataRetriever()
        val classifier=IrisClassifier(context)
        try {
            r.setDataSource(context,uri)
            val duration=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:0L
            require(duration>0){"Video heeft geen leesbare duur"}
            val step=max(4000L,duration/70L)
            val items=mutableListOf<PlantObservation>()
            var t=0L;var frameNo=0
            while(t<duration && frameNo<70) {
                onProgress("Videobeeld ${frameNo+1} · ${t/1000}s / ${duration/1000}s")
                val raw=r.getFrameAtTime(t*1000L,MediaMetadataRetriever.OPTION_CLOSEST)
                if(raw!=null) {
                    val scale=min(1f,1000f/max(raw.width,raw.height))
                    val frame=Bitmap.createScaledBitmap(raw,(raw.width*scale).toInt().coerceAtLeast(1),
                        (raw.height*scale).toInt().coerceAtLeast(1),true)
                    if(frame!==raw)raw.recycle()
                    for(region in PlantRegions.candidates(frame)) {
                        // A cheap contiguous-frame overlap is a *provisional* track,
                        // not evidence of separate individuals or a reliable count.
                        val recent=items.lastOrNull { it.lastMs>=t-12000L &&
                            PlantRegions.overlap(it.rect,region)>0.58f }
                        if(recent!=null) {
                            recent.lastMs=t;recent.samples.add(t);continue
                        }
                        val patch=PlantRegions.crop(frame,region)
                        val guesses=classifier.identify(patch)
                        val group=region.width()>0.60f && region.height()>0.50f
                        val name=if(group || (guesses.firstOrNull()?.probability?:0f)<0.85f) "Onbekend" else guesses[0].name
                        val photo=store.writePhoto(patch)
                        patch.recycle()
                        items.add(PlantObservation(scientific=name,
                            confidence=guesses.firstOrNull()?.probability?:0f,alternatives=guesses,
                            firstMs=t,lastMs=t,samples= mutableListOf(t),rect=region,photo=photo,
                            group=group))
                    }
                    frame.recycle()
                }
                frameNo++;t+=step
            }
            return items.sortedBy{it.firstMs}
        } finally {r.release();classifier.close()}
    }
    fun classifyManual(bitmap:Bitmap,t:Long,rect:RectF,previousPhoto:String?=null):PlantObservation {
        IrisClassifier(context).use {classifier->
            val patch=PlantRegions.crop(bitmap,rect)
            val earlier=if(!previousPhoto.isNullOrBlank())
                android.graphics.BitmapFactory.decodeFile(previousPhoto) else null
            val g=try {
                if(earlier!=null) classifier.identifyMany(listOf(earlier,patch))
                else classifier.identify(patch)
            } finally {earlier?.recycle()}
            val photo=store.writePhoto(patch);patch.recycle()
            val first=g.firstOrNull()
            return PlantObservation(scientific=if((first?.probability?:0f)<0.85f)"Onbekend" else first!!.name,
                confidence=first?.probability?:0f,alternatives=g,firstMs=t,lastMs=t,
                samples=mutableListOf(t),rect=rect,photo=photo,manual=true)
        }
    }
}

fun formatTime(ms:Long):String {
    val s=ms/1000L
    return "%02d:%02d".format(s/60,s%60)
}
