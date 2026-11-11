package com.d4cx.streamflix
import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.widget.TextView

/** A quiet title shimmer while resolving, then a fill from actual buffered duration. */
class TitleLoadingView @JvmOverloads constructor(c:Context,attrs:AttributeSet?=null):TextView(c,attrs){
 private var fill=-1f
 init { setTextColor(0xffeeeeee.toInt()) }
 fun setFill(value:Float){if(fill!=value){fill=value;invalidate()}}
 override fun onDraw(canvas:Canvas){
  val layer=canvas.saveLayerAlpha(0f,0f,width.toFloat(),height.toFloat(),70)
  super.onDraw(canvas);canvas.restoreToCount(layer)
  val clip=canvas.save()
  if(fill>=0)canvas.clipRect(0f,0f,width*fill,height.toFloat())
  else {val x=((android.os.SystemClock.uptimeMillis()%2400)/2400f)*(width*1.3f)-width*.3f;canvas.clipRect(x,0f,x+width*.3f,height.toFloat())}
  super.onDraw(canvas);canvas.restoreToCount(clip)
  if(isShown&&fill<0)postInvalidateDelayed(50)
 }
}
