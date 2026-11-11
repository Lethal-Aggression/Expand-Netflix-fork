package com.d4cx.streamflix
import android.content.Context
import org.json.JSONObject

/** Bounded source reliability history. Never stores credential-bearing URLs. */
object SourceHealth {
 private fun key(s:StreamItem):String {
  val bytes=java.security.MessageDigest.getInstance("SHA-256").digest((s.infoHash?:s.url?:s.name).toByteArray())
  return bytes.take(12).joinToString(""){"%02x".format(it)}
 }
 @Synchronized fun record(c:Context,s:StreamItem,success:Boolean){
  val p=c.applicationContext.getSharedPreferences("source_health",Context.MODE_PRIVATE)
  val rows=try{JSONObject(p.getString("rows","{}")?:"{}")}catch(_:Exception){JSONObject()}
  val k=key(s);val row=rows.optJSONObject(k)?:JSONObject()
  row.put("score",(row.optInt("score")+if(success)1 else -3).coerceIn(-12,6));row.put("time",System.currentTimeMillis());rows.put(k,row)
  if(rows.length()>200){val oldest=rows.keys().asSequence().minByOrNull{rows.optJSONObject(it)?.optLong("time")?:0};if(oldest!=null)rows.remove(oldest)}
  p.edit().putString("rows",rows.toString()).apply()
 }
 fun score(c:Context?,s:StreamItem):Int {if(c==null)return 0;return try{val p=c.applicationContext.getSharedPreferences("source_health",Context.MODE_PRIVATE);val r=JSONObject(p.getString("rows","{}")?:"{}").optJSONObject(key(s))?:return 0;if(System.currentTimeMillis()-r.optLong("time")>7*86400000L)0 else r.optInt("score")*2500}catch(_:Exception){0}}
}
