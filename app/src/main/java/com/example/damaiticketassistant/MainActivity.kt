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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

data class Concert(val id:Int, val title:String, val artist:String, val city:String, val saleTime:Long, val url:String, val price:String)
data class PageInfo(val title:String?, val saleTime:Long?)

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
    val scope = rememberCoroutineScope()
    var concerts by remember { mutableStateOf(loadConcerts(prefs)) }
    var showAdd by remember { mutableStateOf(false) }
    var quickPrepConcert by remember { mutableStateOf<Concert?>(null) }
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var saleTime by remember { mutableLongStateOf(System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000) }
    var recognizing by remember { mutableStateOf(false) }
    var recognitionMessage by remember { mutableStateOf("可粘贴大麦活动链接尝试识别名称；开售时间请核对后手动选择。") }
    var log by remember { mutableStateOf(prefs.getString("log_last", "暂无操作记录") ?: "暂无操作记录") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }

    fun persist(list: List<Concert>, logText: String) {
        concerts = list
        prefs.edit().putString("events", JSONArray().apply {
            list.forEach {
                put(JSONObject().put("id",it.id).put("title",it.title).put("artist",it.artist)
                    .put("city",it.city).put("saleTime",it.saleTime).put("url",it.url).put("price",it.price))
            }
        }.toString()).putString("log_last",logText).apply()
        log = logText
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("大麦购票助手", style=MaterialTheme.typography.headlineMedium)
        Text("记录开售时间 · 自动提醒 · 打开官方购票入口", style=MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(onClick={
                title = ""
                url = ""
                saleTime = System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000
                recognitionMessage = "可粘贴大麦活动链接尝试识别名称；开售时间请核对后手动选择。"
                showAdd = true
            }) { Text("添加演出") }
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
                        val details = listOf(c.artist, c.city, c.price.takeIf { it.isNotBlank() }?.let { "预算 ¥$it" })
                            .filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
                        if (details.isNotBlank()) Text(details)
                        val remain=c.saleTime-now
                        Text(if(remain<=0) "已到开售时间" else "倒计时："+formatDuration(remain), style=MaterialTheme.typography.bodyLarge)
                        Text("开售："+SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.getDefault()).format(Date(c.saleTime)))
                        OutlinedButton(onClick={ quickPrepConcert = c }, modifier=Modifier.fillMaxWidth()) { Text("购票准备清单") }
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(onClick={
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(c.url)))
                                    persist(concerts,"已打开官方页面："+c.title)
                                } catch (_:Exception) {
                                    persist(concerts,"打开失败：请检查购票链接")
                                }
                            }, enabled=c.url.startsWith("https://") || c.url.startsWith("http://")) { Text("官方购票") }
                            OutlinedButton(onClick={
                                val trigger=c.saleTime-10*60*1000L
                                if(trigger>System.currentTimeMillis()) {
                                    AlarmScheduler.schedule(context,c.id,c.title,trigger)
                                }
                                if(c.saleTime>System.currentTimeMillis()) {
                                    AlarmScheduler.schedule(context,c.id,c.title,c.saleTime,true)
                                    persist(concerts,if(trigger>System.currentTimeMillis()) "已安排提前10分钟及开售时提醒："+c.title else "已安排开售时提醒："+c.title)
                                } else persist(concerts,"开售时间已过，未安排提醒："+c.title)
                            }) { Text("重设提醒") }
                            TextButton(onClick={
                                AlarmScheduler.cancel(context,c.id)
                                persist(concerts.filterNot { it.id == c.id }, "已删除演出："+c.title)
                            }) { Text("删除") }
                        }
                    }
                }
            }
        }
        Divider()
        Text("最近操作", style=MaterialTheme.typography.titleMedium)
        Text(log, style=MaterialTheme.typography.bodySmall)
    }

    if(showAdd) AlertDialog(
        onDismissRequest={ if (!recognizing) showAdd=false },
        title={Text("添加演出")},
        text={
            Column(
                modifier=Modifier.fillMaxWidth().heightIn(max=520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement=Arrangement.spacedBy(10.dp)
            ) {
                Text("只需填写演出名称和开售时间。链接可选，保存后自动安排提前10分钟提醒。", style=MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value=title, onValueChange={title=it}, label={Text("演出名称（必填）")},
                    singleLine=true, modifier=Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value=url, onValueChange={url=it}, label={Text("大麦活动链接（选填）")},
                    singleLine=true, modifier=Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    enabled=!recognizing,
                    onClick={
                        if (url.isBlank()) {
                            recognitionMessage = "先粘贴活动链接；如果识别失败，可以直接填写演出名称。"
                        } else {
                            recognizing = true
                            recognitionMessage = "正在尝试读取活动页面标题……"
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { fetchPageInfo(url.trim()) }
                                if (result != null && (!result.title.isNullOrBlank() || result.saleTime != null)) {
                                    val foundTitle = result.title?.takeIf { it.isNotBlank() }
                                    if (title.isBlank() && foundTitle != null) title = foundTitle
                                    if (result.saleTime != null) saleTime = result.saleTime
                                    val titleNote = if (foundTitle != null) "已读取页面标题" else "未读取到标题"
                                    val timeNote = if (result.saleTime != null) "并找到疑似开售时间，请务必核对" else "未找到可确认的开售时间，请手动选择"
                                    recognitionMessage = "$titleNote；$timeNote。页面数据不保证完整或最新。"
                                } else {
                                    recognitionMessage = "暂时无法读取活动信息（页面可能需要登录、动态加载或限制访问）。请手动填写名称和开售时间；链接仍可保存。"
                                }
                                recognizing = false
                            }
                        }
                    },
                    modifier=Modifier.fillMaxWidth()
                ) { Text(if (recognizing) "正在识别…" else "尝试识别活动名称") }
                Text(recognitionMessage, style=MaterialTheme.typography.bodySmall)
                Text("开售时间（必填）", style=MaterialTheme.typography.labelLarge)
                Text(SimpleDateFormat("yyyy年MM月dd日  HH:mm",Locale.getDefault()).format(Date(saleTime)), style=MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={
                        val cal=Calendar.getInstance().apply { timeInMillis=saleTime }
                        DatePickerDialog(context,{_,y,m,d ->
                            val updated=Calendar.getInstance().apply {
                                timeInMillis=saleTime
                                set(Calendar.YEAR,y); set(Calendar.MONTH,m); set(Calendar.DAY_OF_MONTH,d)
                            }
                            saleTime=updated.timeInMillis
                        },cal.get(Calendar.YEAR),cal.get(Calendar.MONTH),cal.get(Calendar.DAY_OF_MONTH)).show()
                    }, modifier=Modifier.weight(1f)) { Text("选择日期") }
                    OutlinedButton(onClick={
                        val cal=Calendar.getInstance().apply { timeInMillis=saleTime }
                        TimePickerDialog(context,{_,h,m ->
                            val updated=Calendar.getInstance().apply {
                                timeInMillis=saleTime
                                set(Calendar.HOUR_OF_DAY,h); set(Calendar.MINUTE,m); set(Calendar.SECOND,0)
                            }
                            saleTime=updated.timeInMillis
                        },cal.get(Calendar.HOUR_OF_DAY),cal.get(Calendar.MINUTE),true).show()
                    }, modifier=Modifier.weight(1f)) { Text("选择时间") }
                }
                Text("注意：自动识别目前只尝试读取公开页面标题，不会读取大麦账户、票档或实时库存。请自行核对开售时间。", style=MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton={ TextButton(enabled=!recognizing,onClick={
            if(title.isNotBlank() && saleTime>0L) {
                val id=(concerts.maxOfOrNull{it.id} ?: 0)+1
                val item=Concert(id,title.trim(),"","",saleTime,url.trim(),"")
                val updated=concerts+item
                persist(updated,"已添加演出："+item.title)
                val trigger=saleTime-10*60*1000L
                if(trigger>System.currentTimeMillis()) {
                    AlarmScheduler.schedule(context,id,item.title,trigger)
                }
                if(item.saleTime>System.currentTimeMillis()) {
                    AlarmScheduler.schedule(context,id,item.title,item.saleTime,true)
                    persist(updated,if(trigger>System.currentTimeMillis()) "已添加并安排提前10分钟及开售时提醒："+item.title else "已添加并安排开售时提醒："+item.title)
                } else {
                    persist(updated,"已添加演出；开售时间已过，未安排提醒："+item.title)
                }
                title="";url="";showAdd=false
            } else {
                recognitionMessage="请填写演出名称，并选择有效的开售时间。"
            }
        }) { Text("保存并设提醒") }},
        dismissButton={TextButton(onClick={showAdd=false}){Text("取消")}}
    )

    if (quickPrepConcert != null) {
        val target = quickPrepConcert!!
        var checked by remember(target.id) { mutableStateOf(setOf<Int>()) }
        val checklist = listOf(
            "已确认演出名称和官方链接",
            "已在大麦本人账号登录",
            "已核对场次、票档、数量和预算",
            "已确认实名观演人",
            "已准备好本人支付方式"
        )
        AlertDialog(
            onDismissRequest = { quickPrepConcert = null },
            title = { Text("开售前快速准备") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(target.title, style = MaterialTheme.typography.titleMedium)
                    Text("开售时间：" + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(target.saleTime)))
                    checklist.forEachIndexed { index, item ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Checkbox(checked = index in checked, onCheckedChange = { yes ->
                                checked = if (yes) checked + index else checked - index
                            })
                            Text(item, modifier = Modifier.padding(top = 12.dp))
                        }
                    }
                    Text("助手负责准备、提醒和打开官方入口。登录验证、选购确认及支付由你本人完成。", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (target.url.startsWith("https://") || target.url.startsWith("http://")) {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target.url)))
                            persist(concerts, "已从购票准备清单打开官方入口：" + target.title)
                        } catch (_: Exception) {
                            persist(concerts, "官方入口打开失败，请检查链接：" + target.title)
                        }
                    } else {
                        persist(concerts, "该演出尚未填写有效购票链接：" + target.title)
                    }
                    quickPrepConcert = null
                }) { Text("打开官方购票入口") }
            },
            dismissButton = { TextButton(onClick = { quickPrepConcert = null }) { Text("稍后再说") } }
        )
    }
}

suspend fun fetchPageInfo(link: String): PageInfo? = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
        val uri = Uri.parse(link)
        val host = uri.host?.lowercase(Locale.ROOT) ?: return@withContext null
        val allowed = host == "damai.cn" || host.endsWith(".damai.cn") ||
            host == "damai.com" || host.endsWith(".damai.com")
        if (uri.scheme != "https" || !allowed) return@withContext null

        connection = (URL(link).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 6000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36")
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
            instanceFollowRedirects = true
        }
        if (connection.responseCode !in 200..299) return@withContext null
        val finalHost = connection.url.host.lowercase(Locale.ROOT)
        if (!(finalHost == "damai.cn" || finalHost.endsWith(".damai.cn") ||
                    finalHost == "damai.com" || finalHost.endsWith(".damai.com"))) return@withContext null

        val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().take(1_000_000) }
        fun decodeEntities(value: String): String = value
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
            .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value }

        val meta = Regex("""(?is)<meta[^>]+(?:property|name)=["']og:title["'][^>]+content=["']([^"']+)""").find(html)?.groupValues?.getOrNull(1)
            ?: Regex("""(?is)<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']og:title["']""").find(html)?.groupValues?.getOrNull(1)
        val rawTitle = meta ?: Regex("""(?is)<title[^>]*>(.*?)</title>""").find(html)?.groupValues?.getOrNull(1)
        val title = rawTitle?.let { decodeEntities(it.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ")).trim() }
            ?.takeIf { it.isNotBlank() }

        val plain = decodeEntities(
            html.replace(Regex("""(?is)<script[^>]*>.*?</script>"""), " ")
                .replace(Regex("""(?is)<style[^>]*>.*?</style>"""), " ")
                .replace(Regex("""(?is)<[^>]+>"""), " ")
        ).replace(Regex("\\s+"), " ")
        val dateRegex = Regex("""(?<!\d)(20\d{2})[年./-](\d{1,2})[月./-](\d{1,2})日?\s*(?:[日号]\s*)?(\d{1,2}):(\d{2})""")
        var foundTime: Long? = null
        for (match in dateRegex.findAll(plain)) {
            val from = (match.range.first - 90).coerceAtLeast(0)
            val to = (match.range.last + 50).coerceAtMost(plain.length - 1)
            val nearby = plain.substring(from, to + 1)
            if (!Regex("""开售|预售|开票|售票|销售时间""").containsMatchIn(nearby)) continue
            val values = match.groupValues.drop(1).mapNotNull { it.toIntOrNull() }
            if (values.size != 5) continue
            val cal = Calendar.getInstance().apply {
                isLenient = false
                set(Calendar.YEAR, values[0]); set(Calendar.MONTH, values[1] - 1)
                set(Calendar.DAY_OF_MONTH, values[2]); set(Calendar.HOUR_OF_DAY, values[3])
                set(Calendar.MINUTE, values[4]); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            try {
                foundTime = cal.timeInMillis
                break
            } catch (_: Exception) {
                // Ignore malformed date candidates and keep looking.
            }
        }
        if (title == null && foundTime == null) null else PageInfo(title, foundTime)
    } catch (_: Exception) {
        null
    } finally {
        connection?.disconnect()
    }
}

fun loadConcerts(prefs: android.content.SharedPreferences): List<Concert> = try {
    val a=JSONArray(prefs.getString("events","[]") ?: "[]")
    (0 until a.length()).map {
        val o=a.getJSONObject(it)
        Concert(o.optInt("id"),o.optString("title"),o.optString("artist"),o.optString("city"),o.optLong("saleTime"),o.optString("url"),o.optString("price"))
    }
} catch(_:Exception){ emptyList() }

fun formatDuration(ms:Long):String {
    val s=ms/1000; val d=s/86400; val h=(s%86400)/3600; val m=(s%3600)/60; val sec=s%60
    return if(d>0) "${d}天 ${h}小时 ${m}分 ${sec}秒" else "${h}小时 ${m}分 ${sec}秒"
}
