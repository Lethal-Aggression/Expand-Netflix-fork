package com.d4cx.streamflix

object CartoonCatalog {
 fun extra():List<CatalogItem> = listOf(
  "tt1140100" to "Chowder (تشاودر)","tt0760437" to "Ben 10 (بن تن)",
  "tt3061050" to "Clarence (كلارنس)","tt3042608" to "Uncle Grandpa (العم جدو)",
  "tt4839610" to "We Bare Bears (الدببة الثلاثة)","tt3061046" to "Steven Universe (ستيفن يونيفرس)",
  "tt0115157" to "Dexter's Laboratory (مختبر دكستر)","tt0175058" to "The Powerpuff Girls (فتيات القوة)",
  "tt0278238" to "Samurai Jack (ساموراي جاك)"
 ).map{(id,name)->CatalogItem(id,name,"series","","https://images.metahub.space/poster/small/$id/img",description="Story episodes and Arabic broadcast search. Availability and ordering depend on the provider.",genres=listOf("Animation"))}
}
