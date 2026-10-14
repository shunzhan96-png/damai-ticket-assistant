package com.example.damaiticketassistant

import android.Manifest
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

data class Concert(val id:Int, val title:String, val artist:String, val city:String, val saleTime:Long, val url:String, val price:String)
class MainActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { ConcertApp() } } }
    }
}

@Composable
fun ConcertApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("damai_assistant", Context.MODE_PRIVATE) }
    var concerts by remember { mutableStateOf(loadConcerts(prefs)) }
    var showAdd by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var saleText by remember { mutableStateOf("2026-12-01 19:00") }
    var log by remember { mutableStateOf(prefs.getString("log_last", "暂无操作记录") ?: "暂无操作记录") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }
    fun persist(list: List<Concert>, logText: String) {
        concerts = list
        prefs.edit().putString("events", JSONArray().apply { list.forEach { put(JSONObject().put("id",it.id).put("title",it.title).put("artist",it.artist).put("city",it.city).put("saleTime",it.saleTime).put("url",it.url).put("price",it.price)) } }.toString()).putString("log_last",logText).apply()
        log = logText
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("大麦购票助手", style=MaterialTheme.typography.headlineMedium)
        Text("管理演出 · 开售提醒 · 官方购票入口", style=MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(onClick={ showAdd=true }) { Text("添加演出") }
            OutlinedButton(onClick={
                if (Build.VERSION.SDK_INT >= 31) {
                    val am=context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                    if (!am.canScheduleExactAlarms()) context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:"+context.packageName)))
                }
            }) { Text("提醒权限") }
        }
        Spacer(Modifier.height(8.dp))
        Text("即将开售", style=MaterialTheme.typography.titleLarge)
        if (concerts.isEmpty()) Text("还没有演出，点击“添加演出”开始记录。", Modifier.padding(vertical=16.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement=Arrangement.spacedBy(10.dp)) {
            items(concerts, key={it.id}) { c ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(c.title, style=MaterialTheme.typography.titleMedium)
                        Text(listOf(c.artist,c.city,c.price.takeIf{it.isNotBlank()}?.let{"预算 ¥$it"}).filterNotNull().filter{it.isNotBlank()}.joinToString(" · "))
                        val remain=c.saleTime-now
                        Text(if(remain<=0) "已到开售时间" else "倒计时："+formatDuration(remain), style=MaterialTheme.typography.bodyLarge)
                        Text("开售："+SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.getDefault()).format(Date(c.saleTime)))
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(onClick={
                                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(c.url))); persist(concerts,"已打开官方页面："+c.title) }
                                catch (_:Exception) { persist(concerts,"打开失败：请检查购票链接") }
                            }, enabled=c.url.startsWith("https://")) { Text("官方购票") }
                            OutlinedButton(onClick={
                                val trigger=c.saleTime-10*60*1000L
                                if(trigger>System.currentTimeMillis()) {
                                    AlarmScheduler.schedule(context,c.id,c.title,trigger)
                                    persist(concerts,"已安排提醒："+c.title)
                                } else persist(concerts,"提醒时间已过："+c.title)
                            }) { Text("开售前10分钟提醒") }
                            TextButton(onClick={
                                AlarmScheduler.cancel(context,c.id)
                                persist(concerts, "已删除演出："+c.title)
                                persist(concerts, "已删除演出："+c.title)
                            }) { Text("删除") }
                        }
                    }
                }
            }
        }
        Divider()
        Text("最近日志", style=MaterialTheme.typography.titleMedium)
        Text(log, style=MaterialTheme.typography.bodySmall)
    }
    if(showAdd) AlertDialog(
        onDismissRequest={showAdd=false},
        title={Text("添加演出")},
        text={
            Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(title,{title=it},label={Text("演出名称")},singleLine=true)
                OutlinedTextField(artist,{artist=it},label={Text("艺人")},singleLine=true)
                OutlinedTextField(city,{city=it},label={Text("城市 / 场馆")},singleLine=true)
                OutlinedTextField(price,{price=it},label={Text("票价预算（选填）")},singleLine=true)
                OutlinedTextField(saleText,{saleText=it},label={Text("开售时间 yyyy-MM-dd HH:mm")},singleLine=true)
                OutlinedTextField(url,{url=it},label={Text("官方购票链接 https://…")},singleLine=true)
                Text("时间按手机当前时区解释。")
            }
        },
        confirmButton={ TextButton(onClick={
            val parsed=try { SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.getDefault()).apply{isLenient=false}.parse(saleText)?.time ?: 0L } catch(_:Exception){0L}
            if(title.isNotBlank() && parsed>0L) {
                val id=(concerts.maxOfOrNull{it.id} ?: 0)+1
                val item=Concert(id,title.trim(),artist.trim(),city.trim(),parsed,url.trim(),price.trim())
                persist(concerts+item,"已添加演出："+item.title)
                title="";artist="";city="";price="";url="";showAdd=false
            }
        }) { Text("保存") }},
        dismissButton={TextButton(onClick={showAdd=false}){Text("取消")}}
    )
}
fun loadConcerts(prefs: android.content.SharedPreferences): List<Concert> = try {
    val a=JSONArray(prefs.getString("events","[]") ?: "[]")
    (0 until a.length()).map { val o=a.getJSONObject(it); Concert(o.optInt("id"),o.optString("title"),o.optString("artist"),o.optString("city"),o.optLong("saleTime"),o.optString("url"),o.optString("price")) }
} catch(_:Exception){ emptyList() }
fun formatDuration(ms:Long):String {
    val s=ms/1000; val d=s/86400; val h=(s%86400)/3600; val m=(s%3600)/60; val sec=s%60
    return if(d>0) "${d}天 ${h}小时 ${m}分 ${sec}秒" else "${h}小时 ${m}分 ${sec}秒"
}